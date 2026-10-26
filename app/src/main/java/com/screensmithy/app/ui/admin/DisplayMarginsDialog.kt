package com.screensmithy.app.ui.admin

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.screensmithy.app.MainActivity
import com.screensmithy.app.databinding.DialogDisplayMarginsBinding
import com.screensmithy.app.domain.model.DisplayMargins
import com.screensmithy.app.ui.display.DisplayMarginApplicator

/**
 * TV-first administrative dialog for configuring overscan display margins.
 * Provides four steppers (Top, Bottom, Left, Right) with live real-time preview,
 * reset to default, and persistence.
 */
class DisplayMarginsDialog : DialogFragment() {

    private var _binding: DialogDisplayMarginsBinding? = null
    private val binding get() = _binding!!

    private var initialMargins: DisplayMargins = DisplayMargins.ZERO
    private var draftMargins: DisplayMargins = DisplayMargins.ZERO
    private var isApplied: Boolean = false

    private var onMarginsApplied: ((DisplayMargins) -> Unit)? = null

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
        _binding = DialogDisplayMarginsBinding.inflate(inflater, container, false)
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

        draftMargins = initialMargins
        isApplied = false

        applyDialogMargins(draftMargins)
        setupSteppers()
        setupActions()
        updateUi()

        // Focus on Top Margin Plus button for easy remote navigation
        binding.btnTopPlus.requestFocus()
    }

    private fun applyDialogMargins(margins: DisplayMargins) {
        if (_binding == null) return
        DisplayMarginApplicator.applyMargins(binding.dialogMarginsRoot, margins, basePaddingDp = 24)
    }

    private fun setupSteppers() {
        // Top Margin
        binding.btnTopMinus.setOnClickListener {
            if (draftMargins.topPercent > DisplayMargins.MIN_PERCENT) {
                updateDraft(draftMargins.copy(topPercent = draftMargins.topPercent - DisplayMargins.STEP_PERCENT))
            }
        }
        binding.btnTopPlus.setOnClickListener {
            if (canIncreaseTop()) {
                updateDraft(draftMargins.copy(topPercent = draftMargins.topPercent + DisplayMargins.STEP_PERCENT))
            }
        }

        // Bottom Margin
        binding.btnBottomMinus.setOnClickListener {
            if (draftMargins.bottomPercent > DisplayMargins.MIN_PERCENT) {
                updateDraft(draftMargins.copy(bottomPercent = draftMargins.bottomPercent - DisplayMargins.STEP_PERCENT))
            }
        }
        binding.btnBottomPlus.setOnClickListener {
            if (canIncreaseBottom()) {
                updateDraft(draftMargins.copy(bottomPercent = draftMargins.bottomPercent + DisplayMargins.STEP_PERCENT))
            }
        }

        // Left Margin
        binding.btnLeftMinus.setOnClickListener {
            if (draftMargins.leftPercent > DisplayMargins.MIN_PERCENT) {
                updateDraft(draftMargins.copy(leftPercent = draftMargins.leftPercent - DisplayMargins.STEP_PERCENT))
            }
        }
        binding.btnLeftPlus.setOnClickListener {
            if (canIncreaseLeft()) {
                updateDraft(draftMargins.copy(leftPercent = draftMargins.leftPercent + DisplayMargins.STEP_PERCENT))
            }
        }

        // Right Margin
        binding.btnRightMinus.setOnClickListener {
            if (draftMargins.rightPercent > DisplayMargins.MIN_PERCENT) {
                updateDraft(draftMargins.copy(rightPercent = draftMargins.rightPercent - DisplayMargins.STEP_PERCENT))
            }
        }
        binding.btnRightPlus.setOnClickListener {
            if (canIncreaseRight()) {
                updateDraft(draftMargins.copy(rightPercent = draftMargins.rightPercent + DisplayMargins.STEP_PERCENT))
            }
        }
    }

    private fun canIncreaseTop(): Boolean {
        return draftMargins.topPercent < DisplayMargins.MAX_PERCENT &&
                (draftMargins.topPercent + draftMargins.bottomPercent + DisplayMargins.STEP_PERCENT <= DisplayMargins.MAX_COMBINED_VERTICAL_PERCENT)
    }

    private fun canIncreaseBottom(): Boolean {
        return draftMargins.bottomPercent < DisplayMargins.MAX_PERCENT &&
                (draftMargins.topPercent + draftMargins.bottomPercent + DisplayMargins.STEP_PERCENT <= DisplayMargins.MAX_COMBINED_VERTICAL_PERCENT)
    }

    private fun canIncreaseLeft(): Boolean {
        return draftMargins.leftPercent < DisplayMargins.MAX_PERCENT &&
                (draftMargins.leftPercent + draftMargins.rightPercent + DisplayMargins.STEP_PERCENT <= DisplayMargins.MAX_COMBINED_HORIZONTAL_PERCENT)
    }

    private fun canIncreaseRight(): Boolean {
        return draftMargins.rightPercent < DisplayMargins.MAX_PERCENT &&
                (draftMargins.leftPercent + draftMargins.rightPercent + DisplayMargins.STEP_PERCENT <= DisplayMargins.MAX_COMBINED_HORIZONTAL_PERCENT)
    }

    private fun updateDraft(newMargins: DisplayMargins) {
        draftMargins = newMargins
        updateUi()

        // Live preview on application root and dialog root
        (activity as? MainActivity)?.previewDisplayMargins(draftMargins)
        applyDialogMargins(draftMargins)
    }

    private fun updateUi() {
        if (_binding == null) return

        binding.txtTopValue.text = "${draftMargins.topPercent}%"
        binding.txtBottomValue.text = "${draftMargins.bottomPercent}%"
        binding.txtLeftValue.text = "${draftMargins.leftPercent}%"
        binding.txtRightValue.text = "${draftMargins.rightPercent}%"

        val usableW = 100 - (draftMargins.leftPercent + draftMargins.rightPercent)
        val usableH = 100 - (draftMargins.topPercent + draftMargins.bottomPercent)
        binding.txtUsableViewport.text = "Usable Viewport: $usableW% Horizontal × $usableH% Vertical"

        // Enable/disable steppers based on bounds
        binding.btnTopMinus.isEnabled = draftMargins.topPercent > DisplayMargins.MIN_PERCENT
        binding.btnTopPlus.isEnabled = canIncreaseTop()

        binding.btnBottomMinus.isEnabled = draftMargins.bottomPercent > DisplayMargins.MIN_PERCENT
        binding.btnBottomPlus.isEnabled = canIncreaseBottom()

        binding.btnLeftMinus.isEnabled = draftMargins.leftPercent > DisplayMargins.MIN_PERCENT
        binding.btnLeftPlus.isEnabled = canIncreaseLeft()

        binding.btnRightMinus.isEnabled = draftMargins.rightPercent > DisplayMargins.MIN_PERCENT
        binding.btnRightPlus.isEnabled = canIncreaseRight()
    }

    private fun setupActions() {
        // Reset to Default (0%)
        binding.btnResetDefault.setOnClickListener {
            updateDraft(DisplayMargins.ZERO)
        }

        // Cancel
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        // Apply & Save
        binding.btnApply.setOnClickListener {
            isApplied = true
            onMarginsApplied?.invoke(draftMargins)
            dismiss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!isApplied) {
            // Restore persisted margins if user cancelled or dismissed without applying
            (activity as? MainActivity)?.restorePersistedDisplayMargins()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DisplayMarginsDialog"

        fun newInstance(
            initialMargins: DisplayMargins,
            onMarginsApplied: (DisplayMargins) -> Unit
        ): DisplayMarginsDialog {
            return DisplayMarginsDialog().apply {
                this.initialMargins = initialMargins
                this.onMarginsApplied = onMarginsApplied
            }
        }
    }
}
