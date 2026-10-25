package com.screensmithy.app.ui.admin

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.screensmithy.app.databinding.DialogDiagnosticLogsBinding
import com.screensmithy.app.domain.diagnostics.DiagnosticLogger

/**
 * TV-accessible modal dialog displaying recent structured, bounded diagnostic logs.
 */
class DiagnosticLogsDialog : DialogFragment() {

    private var _binding: DialogDiagnosticLogsBinding? = null
    private val binding get() = _binding!!

    private var diagnosticLogger: DiagnosticLogger? = null

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
        _binding = DialogDiagnosticLogsBinding.inflate(inflater, container, false)
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
        refreshLogs()

        binding.btnClearLogs.setOnClickListener {
            diagnosticLogger?.clear()
            refreshLogs()
        }

        binding.btnCloseLogs.setOnClickListener {
            dismiss()
        }

        binding.btnCloseLogs.requestFocus()
    }

    private fun refreshLogs() {
        val logs = diagnosticLogger?.getRecentLogs() ?: emptyList()
        if (logs.isEmpty()) {
            binding.txtLogEntries.text = "No diagnostic events recorded yet."
            return
        }

        val sb = StringBuilder()
        for (log in logs.asReversed()) {
            sb.append("[")
            sb.append(log.formattedTimestamp)
            sb.append("] ")
            sb.append(log.level.name.padEnd(5))
            sb.append(" [")
            sb.append(log.category.name)
            sb.append("] ")
            sb.append(log.message)
            if (log.details != null) {
                sb.append("\n    Details: ")
                sb.append(log.details)
            }
            sb.append("\n\n")
        }
        binding.txtLogEntries.text = sb.toString().trimEnd()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DiagnosticLogsDialog"

        fun newInstance(logger: DiagnosticLogger): DiagnosticLogsDialog {
            return DiagnosticLogsDialog().apply {
                this.diagnosticLogger = logger
            }
        }
    }
}
