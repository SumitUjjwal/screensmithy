package com.screensmithy.app.ui.transfer

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
import com.screensmithy.app.databinding.DialogWifiTransferBinding
import com.screensmithy.app.domain.importer.ImportResult
import com.screensmithy.app.domain.importer.LocalHtmlImporter
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.transfer.TransferSessionState
import com.screensmithy.app.domain.transfer.WifiTransferManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * TV-first modal dialog for receiving local HTML transfers over Wi-Fi.
 * Displays QR code, transfer address, pairing code, live countdown, progress bar,
 * and post-upload preview and activation actions.
 */
class WifiTransferDialog : DialogFragment() {

    private var _binding: DialogWifiTransferBinding? = null
    private val binding get() = _binding!!

    private var transferManager: WifiTransferManager? = null
    private var htmlImporter: LocalHtmlImporter? = null

    private var onFileImported: ((LocalHtmlMetadata) -> Unit)? = null
    private var onPreviewRequested: ((ContentSource, NavigationPolicy) -> Unit)? = null
    private var onActivationConfirmed: ((ContentSource) -> Unit)? = null
    private var onDismissed: (() -> Unit)? = null

    private var importedMetadata: LocalHtmlMetadata? = null

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
        _binding = DialogWifiTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val stagingDir = File(requireContext().filesDir, "imported_html/staging")
        val manager = transferManager ?: WifiTransferManager(stagingDir).also { transferManager = it }

        setupActions()
        observeSession(manager)
        startOrRetrySession(manager)

        binding.btnCancelSession.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun setupActions() {
        binding.btnRetryNetwork.setOnClickListener {
            transferManager?.let { startOrRetrySession(it) }
        }

        binding.btnCancelSession.setOnClickListener {
            transferManager?.stopSession()
            dismiss()
        }

        binding.btnPreviewTransferred.setOnClickListener {
            val meta = importedMetadata ?: return@setOnClickListener
            val source = ContentSource.LocalHtml(meta)
            dismiss()
            onPreviewRequested?.invoke(source, NavigationPolicy.DEFAULT)
        }

        binding.btnConfirmActivate.setOnClickListener {
            val meta = importedMetadata ?: return@setOnClickListener
            val source = ContentSource.LocalHtml(meta)
            onActivationConfirmed?.invoke(source)
            dismiss()
        }
    }

    private fun startOrRetrySession(manager: WifiTransferManager) {
        val success = manager.startSession()
        if (success) {
            binding.containerNetworkError.visibility = View.GONE
            binding.containerActiveSession.visibility = View.VISIBLE
        } else {
            binding.containerNetworkError.visibility = View.VISIBLE
            binding.containerActiveSession.visibility = View.GONE
            binding.btnRetryNetwork.requestFocus()
        }
    }

    private fun observeSession(manager: WifiTransferManager) {
        viewLifecycleOwner.lifecycleScope.launch {
            manager.sessionState.collect { session ->
                if (session == null) return@collect

                val urlWithCode = "${session.url}?code=${session.pairingCode}"
                binding.txtTransferUrl.text = session.url

                val formattedCode = if (session.pairingCode.length == 6) {
                    "${session.pairingCode.substring(0, 3)} ${session.pairingCode.substring(3)}"
                } else {
                    session.pairingCode
                }
                binding.txtPairingCode.text = formattedCode

                val qrBitmap = QrCodeGenerator.generateQrCodeBitmap(urlWithCode, 360, 360)
                if (qrBitmap != null) {
                    binding.imgQrCode.setImageBitmap(qrBitmap)
                }

                val remaining = session.remainingSeconds
                val mins = remaining / 60
                val secs = remaining % 60
                binding.badgeSessionTimer.text = "● Session Active (Expires in %02d:%02d)".format(mins, secs)

                when (val state = session.state) {
                    is TransferSessionState.WaitingForConnection -> {
                        binding.txtTransferStatus.text = "Waiting for sender connection over local Wi-Fi..."
                        binding.txtTransferStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        binding.progressTransfer.progress = 0
                        binding.progressTransfer.isIndeterminate = false
                    }
                    is TransferSessionState.ReceivingUpload -> {
                        binding.txtTransferStatus.text = "Receiving ${state.fileName} (${state.bytesRead / 1024} KB / ${state.totalBytes / 1024} KB)..."
                        binding.txtTransferStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_accent))
                        binding.progressTransfer.isIndeterminate = false
                        binding.progressTransfer.progress = state.progressPercent
                    }
                    is TransferSessionState.Validating -> {
                        binding.txtTransferStatus.text = "Validating HTML document and verifying SHA-256 checksum..."
                        binding.progressTransfer.isIndeterminate = true
                    }
                    is TransferSessionState.Completed -> {
                        binding.progressTransfer.isIndeterminate = false
                        binding.progressTransfer.progress = 100
                        binding.badgeSessionTimer.text = "✓ Transfer Succeeded"
                        binding.badgeSessionTimer.backgroundTintList = ContextCompat.getColorStateList(
                            requireContext(),
                            R.color.status_success_container
                        )
                        binding.badgeSessionTimer.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.status_success_text)
                        )

                        // Atomically import into app-private storage if not already done
                        if (importedMetadata == null) {
                            handleStagedFile(state.fileName, state.stagedFile)
                        }
                    }
                    is TransferSessionState.Failed -> {
                        binding.txtTransferStatus.text = "Transfer failed: ${state.reason}"
                        binding.txtTransferStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error_text))
                        binding.progressTransfer.isIndeterminate = false
                    }
                    is TransferSessionState.Expired -> {
                        binding.txtTransferStatus.text = "Transfer session expired. No file was uploaded."
                        binding.txtTransferStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error_text))
                        binding.badgeSessionTimer.text = "● Session Expired"
                        binding.badgeSessionTimer.backgroundTintList = ContextCompat.getColorStateList(
                            requireContext(),
                            R.color.status_error_container
                        )
                        binding.badgeSessionTimer.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.status_error_text)
                        )
                        binding.progressTransfer.isIndeterminate = false
                    }
                    is TransferSessionState.Cancelled -> {
                        dismiss()
                    }
                }
            }
        }
    }

    private fun handleStagedFile(fileName: String, stagedFile: File) {
        val importer = htmlImporter ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.txtTransferStatus.text = "Storing HTML document into app-private storage..."
            when (val result = importer.importStagedFile(stagedFile, fileName)) {
                is ImportResult.Success -> {
                    importedMetadata = result.metadata
                    onFileImported?.invoke(result.metadata)
                    binding.cardUploadSuccess.visibility = View.VISIBLE
                    val sizeMb = String.format("%.2f", result.metadata.sizeBytes / (1024.0 * 1024.0))
                    binding.txtUploadedFileInfo.text = "${result.metadata.displayName} ($sizeMb MB)"
                    binding.txtTransferStatus.text = "Ready to preview or activate kiosk content."
                    binding.txtTransferStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_success_text))

                    // Enable preview and activate controls
                    binding.btnPreviewTransferred.visibility = View.VISIBLE
                    binding.btnPreviewTransferred.isEnabled = true
                    binding.btnConfirmActivate.visibility = View.VISIBLE
                    binding.btnConfirmActivate.isEnabled = true
                    binding.btnPreviewTransferred.requestFocus()
                }
                is ImportResult.Failure -> {
                    binding.txtTransferStatus.text = "Validation failed: ${result.error.userMessage}"
                    binding.txtTransferStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error_text))
                }
                is ImportResult.Cancelled -> {
                    binding.txtTransferStatus.text = "Upload cancelled."
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        transferManager?.stopSession()
        _binding = null
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        transferManager?.stopSession()
        onDismissed?.invoke()
    }

    companion object {
        const val TAG = "WifiTransferDialog"

        fun newInstance(
            importer: LocalHtmlImporter,
            transferManager: WifiTransferManager? = null,
            onFileImported: ((LocalHtmlMetadata) -> Unit)? = null,
            onPreviewRequested: (ContentSource, NavigationPolicy) -> Unit,
            onActivationConfirmed: (ContentSource) -> Unit,
            onDismissed: () -> Unit = {}
        ): WifiTransferDialog {
            return WifiTransferDialog().apply {
                this.htmlImporter = importer
                this.transferManager = transferManager
                this.onFileImported = onFileImported
                this.onPreviewRequested = onPreviewRequested
                this.onActivationConfirmed = onActivationConfirmed
                this.onDismissed = onDismissed
            }
        }
    }
}
