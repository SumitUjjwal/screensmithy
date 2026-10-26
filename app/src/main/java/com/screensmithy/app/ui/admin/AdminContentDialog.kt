package com.screensmithy.app.ui.admin

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.screensmithy.app.R
import com.screensmithy.app.databinding.DialogAdminContentBinding
import com.screensmithy.app.domain.admin.AdminContentController
import com.screensmithy.app.domain.admin.CandidateValidationResult
import com.screensmithy.app.domain.admin.DeviceManagementInfoProvider
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import kotlinx.coroutines.launch

/**
 * TV-first modal dialog for authenticated administrators.
 * Provides transactional content configuration, URL validation, SAF local HTML import,
 * navigation policy selection, live previewing, and safe rollback.
 */
class AdminContentDialog : DialogFragment() {

    private var _binding: DialogAdminContentBinding? = null
    private val binding get() = _binding!!

    private var contentController: AdminContentController? = null
    private var deviceInfoProvider: DeviceManagementInfoProvider? = null

    private var onPickHtmlRequested: (() -> Unit)? = null
    private var onWifiTransferRequested: (() -> Unit)? = null
    private var onPreviewRequested: ((ContentSource, NavigationPolicy) -> Unit)? = null
    private var onReloadActiveRequested: (() -> Unit)? = null
    private var onDismissed: (() -> Unit)? = null

    private var selectedTab: SourceTab = SourceTab.REMOTE_URL
    private var stagedNavigationMode: NavigationMode = NavigationMode.CONFIGURED_HOST_ONLY

    enum class SourceTab {
        REMOTE_URL,
        LOCAL_HTML,
        WIFI_TRANSFER
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAdminContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply authoritative display margins to dialog root
        val margins = (activity as? com.screensmithy.app.MainActivity)?.currentDisplayMargins ?: com.screensmithy.app.domain.model.DisplayMargins.ZERO
        com.screensmithy.app.ui.display.DisplayMarginApplicator.applyMargins(binding.dialogAdminRoot, margins, basePaddingDp = 24)

        setupDeviceInfo()
        setupTabs()
        setupUrlHelpers()
        setupPolicyButtons()
        setupActions()
        refreshFromController()

        // Default focus to URL edit field or first interactive element for TV remote
        binding.editRemoteUrl.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun setupDeviceInfo() {
        val provider = deviceInfoProvider ?: return
        val info = provider.getInfo()
        binding.badgeDeviceMode.text = info.modeDescription
    }

    private fun setupTabs() {
        binding.tabRemoteUrl.setOnClickListener {
            selectTab(SourceTab.REMOTE_URL)
        }

        binding.tabLocalHtml.setOnClickListener {
            selectTab(SourceTab.LOCAL_HTML)
        }

        binding.tabWifiTransfer.setOnClickListener {
            selectTab(SourceTab.WIFI_TRANSFER)
        }
    }

    private fun selectTab(tab: SourceTab) {
        selectedTab = tab
        when (tab) {
            SourceTab.REMOTE_URL -> {
                binding.tabRemoteUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                binding.tabLocalHtml.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                binding.tabWifiTransfer.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                binding.panelRemoteUrl.visibility = View.VISIBLE
                binding.panelLocalHtml.visibility = View.GONE
                binding.panelWifiTransfer.visibility = View.GONE
                binding.editRemoteUrl.requestFocus()
            }
            SourceTab.LOCAL_HTML -> {
                binding.tabLocalHtml.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                binding.tabRemoteUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                binding.tabWifiTransfer.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                binding.panelLocalHtml.visibility = View.VISIBLE
                binding.panelRemoteUrl.visibility = View.GONE
                binding.panelWifiTransfer.visibility = View.GONE
                binding.btnPickHtmlFile.requestFocus()
            }
            SourceTab.WIFI_TRANSFER -> {
                binding.tabWifiTransfer.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                binding.tabRemoteUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                binding.tabLocalHtml.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                binding.panelWifiTransfer.visibility = View.VISIBLE
                binding.panelRemoteUrl.visibility = View.GONE
                binding.panelLocalHtml.visibility = View.GONE
                binding.btnStartWifiTransfer.requestFocus()
            }
        }
    }

    private fun setupUrlHelpers() {
        binding.btnHelperHttps.setOnClickListener {
            val current = binding.editRemoteUrl.text.toString()
            if (!current.startsWith("https://", ignoreCase = true)) {
                binding.editRemoteUrl.setText("https://$current")
                binding.editRemoteUrl.setSelection(binding.editRemoteUrl.text.length)
            }
        }

        binding.btnHelperDotCom.setOnClickListener {
            val current = binding.editRemoteUrl.text.toString()
            if (!current.endsWith(".com") && !current.endsWith(".com/")) {
                binding.editRemoteUrl.setText("$current.com/")
                binding.editRemoteUrl.setSelection(binding.editRemoteUrl.text.length)
            }
        }

        binding.btnHelperExample.setOnClickListener {
            binding.editRemoteUrl.setText("https://example.com/")
            binding.editRemoteUrl.setSelection(binding.editRemoteUrl.text.length)
        }

        binding.btnHelperClearUrl.setOnClickListener {
            binding.editRemoteUrl.setText("")
            binding.editRemoteUrl.requestFocus()
        }

        binding.btnPickHtmlFile.setOnClickListener {
            onPickHtmlRequested?.invoke()
        }
    }

    private fun setupPolicyButtons() {
        binding.btnPolicyConfiguredHost.setOnClickListener {
            selectPolicyMode(NavigationMode.CONFIGURED_HOST_ONLY)
        }

        binding.btnPolicyAllowlist.setOnClickListener {
            selectPolicyMode(NavigationMode.ALLOWLIST_ONLY)
        }

        binding.btnPolicyAnyHttp.setOnClickListener {
            selectPolicyMode(NavigationMode.ANY_HTTP_HOST)
        }
    }

    private fun selectPolicyMode(mode: NavigationMode) {
        stagedNavigationMode = mode
        val primary = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        binding.btnPolicyConfiguredHost.setTextColor(if (mode == NavigationMode.CONFIGURED_HOST_ONLY) primary else secondary)
        binding.btnPolicyAllowlist.setTextColor(if (mode == NavigationMode.ALLOWLIST_ONLY) primary else secondary)
        binding.btnPolicyAnyHttp.setTextColor(if (mode == NavigationMode.ANY_HTTP_HOST) primary else secondary)

        contentController?.stageNavigationPolicy(mode = mode)
    }

    private fun setupActions() {
        binding.btnPickHtmlFile.setOnClickListener {
            onPickHtmlRequested?.invoke()
        }

        binding.btnStartWifiTransfer.setOnClickListener {
            onWifiTransferRequested?.invoke()
        }

        binding.btnReloadActive.setOnClickListener {
            onReloadActiveRequested?.invoke()
            showFeedback("Active kiosk content reloaded successfully.")
        }

        binding.btnPreviewCandidate.setOnClickListener {
            validateAndPreviewStagedContent()
        }

        binding.btnApplyChanges.setOnClickListener {
            applyChanges()
        }

        binding.btnCancelRevert.setOnClickListener {
            val controller = contentController ?: return@setOnClickListener
            controller.revertToActive()
            refreshFromController()
            showFeedback("Unapplied changes reverted to currently active configuration.")
        }

        binding.btnCloseAdmin.setOnClickListener {
            dismiss()
        }
    }

    private fun refreshFromController() {
        val controller = contentController ?: return
        val currentStaged = controller.state.value ?: return

        // Display active live content summary
        val activeSummary = when (val source = currentStaged.activeConfiguration.contentSource) {
            is ContentSource.None -> "None (Setup Incomplete)"
            is ContentSource.RemoteUrl -> "${source.url} (${currentStaged.activeConfiguration.navigationPolicy.mode})"
            is ContentSource.LocalHtml -> "Local HTML: ${source.metadata.displayName} (${source.metadata.sizeBytes / 1024} KB)"
        }
        binding.txtActiveContentSummary.text = activeSummary

        // Update fields with staged content
        when (val staged = currentStaged.stagedSource) {
            is ContentSource.RemoteUrl -> {
                selectTab(SourceTab.REMOTE_URL)
                binding.editRemoteUrl.setText(staged.url)
            }
            is ContentSource.LocalHtml -> {
                selectTab(SourceTab.LOCAL_HTML)
                binding.txtSelectedLocalHtmlInfo.text = "Selected: ${staged.metadata.displayName} (${staged.metadata.sizeBytes / 1024} KB)"
            }
            is ContentSource.None -> {
                selectTab(SourceTab.REMOTE_URL)
            }
        }

        selectPolicyMode(currentStaged.stagedPolicy.mode)
    }

    /**
     * Called by host activity when an HTML document is successfully imported via SAF or Wi-Fi.
     */
    fun onLocalHtmlImported(metadata: LocalHtmlMetadata) {
        val controller = contentController ?: return
        val result = controller.stageLocalHtml(metadata)
        when (result) {
            is CandidateValidationResult.Valid -> {
                selectTab(SourceTab.LOCAL_HTML)
                val shaShort = metadata.sha256Checksum?.take(12) ?: "N/A"
                binding.txtSelectedLocalHtmlInfo.text = "Imported: ${metadata.displayName} (${metadata.sizeBytes / 1024} KB)\nSHA: $shaShort..."
                hideFeedback()
            }
            is CandidateValidationResult.Invalid -> {
                showFeedback(result.errorMessage)
            }
        }
    }

    private fun validateAndPreviewStagedContent() {
        val controller = contentController ?: return

        if (selectedTab == SourceTab.REMOTE_URL) {
            val rawUrl = binding.editRemoteUrl.text.toString().trim()
            if (rawUrl.isBlank()) {
                showFeedback("Please enter a valid remote URL.")
                return
            }
            when (val validation = controller.stageRemoteUrl(rawUrl)) {
                is CandidateValidationResult.Valid -> {
                    hideFeedback()
                    onPreviewRequested?.invoke(validation.normalizedSource, validation.policy)
                }
                is CandidateValidationResult.Invalid -> {
                    showFeedback(validation.errorMessage)
                }
            }
        } else {
            val state = controller.state.value
            if (state == null || state.stagedSource !is ContentSource.LocalHtml) {
                showFeedback("Please import or transfer a local HTML file first.")
                return
            }
            hideFeedback()
            onPreviewRequested?.invoke(state.stagedSource, state.stagedPolicy)
        }
    }

    private fun applyChanges() {
        val controller = contentController ?: return

        // If URL tab is active, stage the URL text first
        if (selectedTab == SourceTab.REMOTE_URL) {
            val rawUrl = binding.editRemoteUrl.text.toString().trim()
            val valResult = controller.stageRemoteUrl(rawUrl)
            if (valResult is CandidateValidationResult.Invalid) {
                showFeedback(valResult.errorMessage)
                return
            }
        }

        lifecycleScope.launch {
            binding.btnApplyChanges.isEnabled = false
            val result = controller.applyStagedConfiguration()
            binding.btnApplyChanges.isEnabled = true

            result.onSuccess {
                refreshFromController()
                showFeedback("Changes applied successfully! Kiosk configuration updated.")
            }.onFailure { error ->
                showFeedback("Failed to apply changes: ${error.message}")
            }
        }
    }

    private fun showFeedback(message: String) {
        binding.txtAdminFeedback.apply {
            visibility = View.VISIBLE
            text = message
        }
    }

    private fun hideFeedback() {
        binding.txtAdminFeedback.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onDismissed?.invoke()
        _binding = null
    }

    companion object {
        const val TAG = "AdminContentDialog"

        fun newInstance(
            contentController: AdminContentController,
            deviceInfoProvider: DeviceManagementInfoProvider,
            onPickHtmlRequested: () -> Unit,
            onWifiTransferRequested: () -> Unit = {},
            onPreviewRequested: (ContentSource, NavigationPolicy) -> Unit,
            onReloadActiveRequested: () -> Unit,
            onDismissed: () -> Unit
        ): AdminContentDialog {
            return AdminContentDialog().apply {
                this.contentController = contentController
                this.deviceInfoProvider = deviceInfoProvider
                this.onPickHtmlRequested = onPickHtmlRequested
                this.onWifiTransferRequested = onWifiTransferRequested
                this.onPreviewRequested = onPreviewRequested
                this.onReloadActiveRequested = onReloadActiveRequested
                this.onDismissed = onDismissed
            }
        }
    }
}
