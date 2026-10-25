package com.screensmithy.app.ui.admin

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.screensmithy.app.R
import com.screensmithy.app.databinding.DialogAdminDashboardBinding
import com.screensmithy.app.domain.diagnostics.DefaultLauncherStatus
import com.screensmithy.app.domain.diagnostics.DiagnosticLogger
import com.screensmithy.app.domain.diagnostics.LogCategory
import com.screensmithy.app.domain.diagnostics.LogLevel
import com.screensmithy.app.domain.diagnostics.SystemDiagnosticInspector
import com.screensmithy.app.domain.diagnostics.SystemDiagnosticSnapshot
import com.screensmithy.app.domain.repository.ConfigurationRepository
import kotlinx.coroutines.launch

/**
 * TV-first comprehensive administration and diagnostics hub.
 * Displays grounded system status, real implementable settings toggles,
 * and launches content configuration, PIN changes, log inspections, and report export.
 */
class AdminDashboardDialog : DialogFragment() {

    private var _binding: DialogAdminDashboardBinding? = null
    private val binding get() = _binding!!

    private var configRepository: ConfigurationRepository? = null
    private var diagnosticInspector: SystemDiagnosticInspector? = null
    private var diagnosticLogger: DiagnosticLogger? = null

    private var onReloadContentRequested: (() -> Unit)? = null
    private var onOpenContentAdminRequested: (() -> Unit)? = null
    private var onChangePinRequested: (() -> Unit)? = null
    private var onExportDiagnosticsRequested: (() -> Unit)? = null
    private var onResumeKioskRequested: (() -> Unit)? = null
    private var onDismissed: (() -> Unit)? = null

    private var sessionTimer: CountDownTimer? = null
    private var currentSnapshot: SystemDiagnosticSnapshot? = null

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
        _binding = DialogAdminDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        startSessionTimer()
        setupActionListeners()
        loadDiagnosticsAndSettings()

        // Set initial TV remote focus
        binding.btnReloadLiveContent.requestFocus()
    }

    private fun startSessionTimer() {
        sessionTimer?.cancel()
        // 5-minute administrative inactivity session countdown
        sessionTimer = object : CountDownTimer(SESSION_DURATION_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null) return
                val minutes = millisUntilFinished / 60000
                val seconds = (millisUntilFinished % 60000) / 1000
                binding.badgeDashboardSession.text = String.format(
                    java.util.Locale.US,
                    "● Active Session (%02d:%02d remaining)",
                    minutes,
                    seconds
                )
            }

            override fun onFinish() {
                if (_binding == null) return
                diagnosticLogger?.log(
                    LogLevel.INFO,
                    LogCategory.AUTH,
                    "Admin dashboard session timed out after 5 minutes of inactivity"
                )
                dismiss()
            }
        }.start()
    }

    private fun loadDiagnosticsAndSettings() {
        val inspector = diagnosticInspector ?: return
        lifecycleScope.launch {
            val snapshot = inspector.inspect()
            currentSnapshot = snapshot
            renderDiagnostics(snapshot)
            renderSettings(snapshot)
        }
    }

    private fun renderDiagnostics(snapshot: SystemDiagnosticSnapshot) {
        val context = context ?: return

        // Operating Mode
        binding.valDiagMode.text = snapshot.operatingMode
        binding.badgeDashboardOperatingMode.text = snapshot.operatingMode

        // Default Home Launcher
        when (val dl = snapshot.defaultHomeLauncher) {
            is DefaultLauncherStatus.Yes -> {
                binding.valDiagLauncher.text = "ScreenSmithy (Default Home Launcher)"
                binding.valDiagLauncher.setTextColor(ContextCompat.getColor(context, R.color.status_success_text))
            }
            is DefaultLauncherStatus.No -> {
                val current = dl.currentDefaultPackage ?: "Other"
                binding.valDiagLauncher.text = "Not Default (Current: $current)"
                binding.valDiagLauncher.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            is DefaultLauncherStatus.Unknown -> {
                binding.valDiagLauncher.text = "Unavailable / Undetectable"
                binding.valDiagLauncher.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            }
        }

        // Lock Task
        binding.valDiagLockTask.text = snapshot.lockTaskState

        // Network Link
        val net = snapshot.networkStatus
        if (net.isConnected) {
            val validatedPart = if (net.isValidated) ", Validated" else ""
            binding.valDiagNetwork.text = "Connected (${net.transportType}$validatedPart)"
            binding.valDiagNetwork.setTextColor(ContextCompat.getColor(context, R.color.status_success_text))
            binding.badgeDashboardNetwork.text = "Network: ${net.transportType}"
            binding.badgeDashboardNetwork.setTextColor(ContextCompat.getColor(context, R.color.status_success_text))
        } else {
            binding.valDiagNetwork.text = "Disconnected (No internet connection)"
            binding.valDiagNetwork.setTextColor(ContextCompat.getColor(context, R.color.status_error_text))
            binding.badgeDashboardNetwork.text = "Network: Disconnected"
            binding.badgeDashboardNetwork.setTextColor(ContextCompat.getColor(context, R.color.status_error_text))
        }

        // Live Content
        binding.valDiagContent.text = "${snapshot.contentSourceType}: ${snapshot.sanitizedContentIdentifier}"

        // Load Status
        when (snapshot.lastLoadSuccess) {
            true -> {
                val timeStr = snapshot.lastLoadAttemptEpochMs?.let {
                    DateUtils.getRelativeTimeSpanString(it)
                } ?: "recently"
                binding.valDiagLoadStatus.text = "Success ($timeStr)"
                binding.valDiagLoadStatus.setTextColor(ContextCompat.getColor(context, R.color.status_success_text))
            }
            false -> {
                val errorStr = snapshot.lastErrorMessage?.let { ": $it" } ?: ""
                binding.valDiagLoadStatus.text = "Failed$errorStr"
                binding.valDiagLoadStatus.setTextColor(ContextCompat.getColor(context, R.color.status_error_text))
            }
            null -> {
                binding.valDiagLoadStatus.text = "No load attempts recorded"
                binding.valDiagLoadStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
        }

        // Device, OS & WebView
        binding.valDiagDevice.text = "${snapshot.deviceModel} | ${snapshot.osVersion} | ${snapshot.webViewPackage}"
    }

    private fun renderSettings(snapshot: SystemDiagnosticSnapshot) {
        updateToggleUi(binding.btnToggleKeepAwake, snapshot.keepScreenAwake)
        updateToggleUi(binding.btnToggleImmersive, snapshot.immersiveSticky)
        updateToggleUi(binding.btnToggleReloadOnNetwork, snapshot.reloadOnNetworkRestored)
    }

    private fun updateToggleUi(button: android.widget.Button, isEnabled: Boolean) {
        val context = context ?: return
        if (isEnabled) {
            button.text = "[ ON ]"
            button.setTextColor(ContextCompat.getColor(context, R.color.status_success_text))
        } else {
            button.text = "[ OFF ]"
            button.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        }
    }

    private fun setupActionListeners() {
        val repo = configRepository ?: return

        // Toggle: Keep Screen Awake
        binding.btnToggleKeepAwake.setOnClickListener {
            lifecycleScope.launch {
                val current = repo.getConfiguration()
                val newVal = !current.displayPreferences.keepScreenAwake
                repo.updateConfiguration { c ->
                    c.copy(displayPreferences = c.displayPreferences.copy(keepScreenAwake = newVal))
                }
                updateToggleUi(binding.btnToggleKeepAwake, newVal)
                diagnosticLogger?.log(
                    LogLevel.INFO,
                    LogCategory.CONFIG,
                    "Keep screen awake setting updated to: $newVal"
                )
            }
        }

        // Toggle: Sticky Immersive
        binding.btnToggleImmersive.setOnClickListener {
            lifecycleScope.launch {
                val current = repo.getConfiguration()
                val newVal = !current.displayPreferences.immersiveSticky
                repo.updateConfiguration { c ->
                    c.copy(displayPreferences = c.displayPreferences.copy(immersiveSticky = newVal))
                }
                updateToggleUi(binding.btnToggleImmersive, newVal)
                diagnosticLogger?.log(
                    LogLevel.INFO,
                    LogCategory.CONFIG,
                    "Sticky immersive setting updated to: $newVal"
                )
            }
        }

        // Toggle: Reload on Network Restored
        binding.btnToggleReloadOnNetwork.setOnClickListener {
            lifecycleScope.launch {
                val current = repo.getConfiguration()
                val newVal = !current.reloadOnNetworkRestored
                repo.updateConfiguration { c ->
                    c.copy(reloadOnNetworkRestored = newVal)
                }
                updateToggleUi(binding.btnToggleReloadOnNetwork, newVal)
                diagnosticLogger?.log(
                    LogLevel.INFO,
                    LogCategory.CONFIG,
                    "Reload on network restored setting updated to: $newVal"
                )
            }
        }

        // Action: Reload Content Now
        binding.btnReloadLiveContent.setOnClickListener {
            diagnosticLogger?.log(
                LogLevel.INFO,
                LogCategory.RENDERER,
                "Admin manually requested live content reload"
            )
            onReloadContentRequested?.invoke()
            binding.valDiagLoadStatus.text = "Reload requested..."
            binding.valDiagLoadStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_accent))
        }

        // Action: Content & Policy
        binding.btnContentAdmin.setOnClickListener {
            onOpenContentAdminRequested?.invoke()
            dismiss()
        }

        // Action: Change PIN
        binding.btnChangeAdminPin.setOnClickListener {
            onChangePinRequested?.invoke()
            dismiss()
        }

        // Action: Export Diagnostics
        binding.btnExportDiagnostics.setOnClickListener {
            onExportDiagnosticsRequested?.invoke()
        }

        // Action: View Logs
        binding.btnViewLogs.setOnClickListener {
            diagnosticLogger?.let { logger ->
                val logsDialog = DiagnosticLogsDialog.newInstance(logger)
                logsDialog.show(parentFragmentManager, DiagnosticLogsDialog.TAG)
            }
        }

        // Action: Resume Kiosk
        binding.btnResumeKiosk.setOnClickListener {
            onResumeKioskRequested?.invoke()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sessionTimer?.cancel()
        sessionTimer = null
        _binding = null
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        sessionTimer?.cancel()
        sessionTimer = null
        onDismissed?.invoke()
    }

    companion object {
        const val TAG = "AdminDashboardDialog"
        private const val SESSION_DURATION_MS = 5 * 60 * 1000L

        fun newInstance(
            configRepository: ConfigurationRepository,
            diagnosticInspector: SystemDiagnosticInspector,
            diagnosticLogger: DiagnosticLogger,
            onReloadContentRequested: () -> Unit,
            onOpenContentAdminRequested: () -> Unit,
            onChangePinRequested: () -> Unit,
            onExportDiagnosticsRequested: () -> Unit,
            onResumeKioskRequested: () -> Unit,
            onDismissed: () -> Unit
        ): AdminDashboardDialog {
            return AdminDashboardDialog().apply {
                this.configRepository = configRepository
                this.diagnosticInspector = diagnosticInspector
                this.diagnosticLogger = diagnosticLogger
                this.onReloadContentRequested = onReloadContentRequested
                this.onOpenContentAdminRequested = onOpenContentAdminRequested
                this.onChangePinRequested = onChangePinRequested
                this.onExportDiagnosticsRequested = onExportDiagnosticsRequested
                this.onResumeKioskRequested = onResumeKioskRequested
                this.onDismissed = onDismissed
            }
        }
    }
}
