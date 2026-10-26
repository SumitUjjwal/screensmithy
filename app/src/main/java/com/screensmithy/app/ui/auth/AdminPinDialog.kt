package com.screensmithy.app.ui.auth

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.screensmithy.app.R
import com.screensmithy.app.databinding.DialogAdminPinBinding
import com.screensmithy.app.domain.auth.AdminAuthRepository
import com.screensmithy.app.domain.auth.AdminSessionManager
import com.screensmithy.app.domain.auth.AuthResult
import com.screensmithy.app.domain.auth.PinSecurity
import kotlinx.coroutines.launch

enum class PinDialogMode {
    /**
     * Authenticates existing PIN against stored PBKDF2 hash.
     */
    VERIFY,

    /**
     * Guides admin through two-step PIN setup (enter & confirm).
     */
    SETUP
}

/**
 * TV-accessible PIN entry and setup dialog.
 * Navigable via remote D-pad, OK button, and physical remote numeric keys.
 */
class AdminPinDialog : DialogFragment() {

    private var _binding: DialogAdminPinBinding? = null
    private val binding get() = _binding!!

    private var mode: PinDialogMode = PinDialogMode.VERIFY
    private var authRepository: AdminAuthRepository? = null
    private var sessionManager: AdminSessionManager? = null

    private var onAuthenticated: (() -> Unit)? = null
    private var onPinSetupCompleted: (() -> Unit)? = null
    private var onCancelled: (() -> Unit)? = null

    private val pinBuilder = StringBuilder(PinSecurity.PIN_LENGTH)
    private var initialSetupPin: CharArray? = null
    private var isConfirmStep: Boolean = false
    private var lockoutTimer: CountDownTimer? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Intercept physical remote numeric keys and navigation keys at dialog window level
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val digit = getDigitFromKeyCode(keyCode)
                if (digit != null) {
                    appendDigit(digit)
                    return@setOnKeyListener true
                }
            }
            false
        }

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAdminPinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply authoritative display margins to dialog root
        val margins = (activity as? com.screensmithy.app.MainActivity)?.currentDisplayMargins ?: com.screensmithy.app.domain.model.DisplayMargins.ZERO
        com.screensmithy.app.ui.display.DisplayMarginApplicator.applyMargins(binding.dialogPinRoot, margins, basePaddingDp = 24)

        setupKeypadListeners()
        setupActionListeners()
        refreshUiForCurrentState()

        if (mode == PinDialogMode.VERIFY) {
            checkInitialLockout()
        }

        // On TV, focus on digit '5' (central button) by default for optimal D-pad reach
        binding.btnKey5.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun setupKeypadListeners() {
        binding.btnKey0.setOnClickListener { appendDigit('0') }
        binding.btnKey1.setOnClickListener { appendDigit('1') }
        binding.btnKey2.setOnClickListener { appendDigit('2') }
        binding.btnKey3.setOnClickListener { appendDigit('3') }
        binding.btnKey4.setOnClickListener { appendDigit('4') }
        binding.btnKey5.setOnClickListener { appendDigit('5') }
        binding.btnKey6.setOnClickListener { appendDigit('6') }
        binding.btnKey7.setOnClickListener { appendDigit('7') }
        binding.btnKey8.setOnClickListener { appendDigit('8') }
        binding.btnKey9.setOnClickListener { appendDigit('9') }

        binding.btnClear.setOnClickListener { clearPin() }
        binding.btnDelete.setOnClickListener { deleteLastDigit() }
    }

    private fun setupActionListeners() {
        binding.btnCancel.setOnClickListener {
            onCancelled?.invoke()
            dismiss()
        }

        binding.btnSubmit.setOnClickListener {
            handleSubmit()
        }
    }

    private fun appendDigit(digit: Char) {
        if (pinBuilder.length < PinSecurity.PIN_LENGTH) {
            pinBuilder.append(digit)
            updateMaskedDisplay()
            hideError()
        }
    }

    private fun deleteLastDigit() {
        if (pinBuilder.isNotEmpty()) {
            pinBuilder.deleteCharAt(pinBuilder.length - 1)
            updateMaskedDisplay()
            hideError()
        }
    }

    private fun clearPin() {
        pinBuilder.clear()
        updateMaskedDisplay()
        hideError()
    }

    private fun updateMaskedDisplay() {
        val length = pinBuilder.length
        val masked = buildString {
            for (i in 0 until PinSecurity.PIN_LENGTH) {
                if (i < length) append("● ") else append("_ ")
            }
        }.trimEnd()

        binding.txtMaskedPin.text = masked
        binding.btnSubmit.isEnabled = length == PinSecurity.PIN_LENGTH
    }

    private fun refreshUiForCurrentState() {
        when (mode) {
            PinDialogMode.VERIFY -> {
                binding.txtPinTitle.text = "Admin Authentication"
                binding.txtPinSubtitle.text = "Enter your 4-digit PIN to access settings"
            }
            PinDialogMode.SETUP -> {
                if (isConfirmStep) {
                    binding.txtPinTitle.text = "Confirm Admin PIN"
                    binding.txtPinSubtitle.text = "Re-enter your 4-digit PIN to verify"
                } else {
                    binding.txtPinTitle.text = "Create Admin PIN"
                    binding.txtPinSubtitle.text = "Enter a 4-digit PIN for admin security"
                }
            }
        }
        updateMaskedDisplay()
    }

    private fun handleSubmit() {
        if (pinBuilder.length != PinSecurity.PIN_LENGTH) {
            showError("PIN must be exactly ${PinSecurity.PIN_LENGTH} digits.")
            return
        }

        val enteredPin = pinBuilder.toString().toCharArray()

        when (mode) {
            PinDialogMode.VERIFY -> verifyPinSubmission(enteredPin)
            PinDialogMode.SETUP -> handleSetupSubmission(enteredPin)
        }
    }

    private fun verifyPinSubmission(enteredPin: CharArray) {
        val manager = sessionManager ?: run {
            showError("Authentication service unavailable.")
            return
        }

        lifecycleScope.launch {
            binding.btnSubmit.isEnabled = false
            val result = manager.authenticate(enteredPin)
            // Zero out local buffer immediately after use
            enteredPin.fill('\u0000')

            when (result) {
                is AuthResult.Success -> {
                    onAuthenticated?.invoke()
                    dismiss()
                }
                is AuthResult.LockedOut -> {
                    clearPin()
                    startLockoutCountdown(result.remainingSeconds)
                }
                is AuthResult.InvalidPin -> {
                    clearPin()
                    binding.btnKey5.requestFocus()
                    showError("Incorrect PIN. (Attempt ${result.failedAttempts})")
                }
                is AuthResult.PinNotConfigured -> {
                    clearPin()
                    showError("No admin PIN has been configured.")
                }
            }
        }
    }

    private fun handleSetupSubmission(enteredPin: CharArray) {
        if (!isConfirmStep) {
            // First step completed; move to confirmation step
            initialSetupPin = enteredPin.clone()
            enteredPin.fill('\u0000')
            pinBuilder.clear()
            isConfirmStep = true
            refreshUiForCurrentState()
            binding.btnKey5.requestFocus()
        } else {
            // Confirmation step: compare with initialSetupPin
            val firstPin = initialSetupPin
            if (firstPin != null && firstPin.contentEquals(enteredPin)) {
                // Match! Hash and persist PIN
                val hashed = PinSecurity.hashPin(enteredPin)
                firstPin.fill('\u0000')
                enteredPin.fill('\u0000')
                initialSetupPin = null

                lifecycleScope.launch {
                    authRepository?.savePin(hashed)
                    onPinSetupCompleted?.invoke()
                    dismiss()
                }
            } else {
                // Mismatch: reset back to step 1
                firstPin?.fill('\u0000')
                enteredPin.fill('\u0000')
                initialSetupPin = null
                isConfirmStep = false
                pinBuilder.clear()
                refreshUiForCurrentState()
                showError("PINs did not match. Please try again.")
                binding.btnKey5.requestFocus()
            }
        }
    }

    private fun checkInitialLockout() {
        val repo = authRepository ?: return
        lifecycleScope.launch {
            val lockout = repo.getLockoutState()
            if (lockout.isLockedOut) {
                startLockoutCountdown(lockout.remainingSeconds)
            }
        }
    }

    private fun startLockoutCountdown(seconds: Long) {
        lockoutTimer?.cancel()
        setKeypadEnabled(false)

        lockoutTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secRemaining = (millisUntilFinished / 1000L) + 1
                showError("Too many failed attempts. Try again in ${secRemaining}s.")
            }

            override fun onFinish() {
                hideError()
                setKeypadEnabled(true)
                binding.btnKey5.requestFocus()
            }
        }.start()
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        binding.btnKey0.isEnabled = enabled
        binding.btnKey1.isEnabled = enabled
        binding.btnKey2.isEnabled = enabled
        binding.btnKey3.isEnabled = enabled
        binding.btnKey4.isEnabled = enabled
        binding.btnKey5.isEnabled = enabled
        binding.btnKey6.isEnabled = enabled
        binding.btnKey7.isEnabled = enabled
        binding.btnKey8.isEnabled = enabled
        binding.btnKey9.isEnabled = enabled
        binding.btnClear.isEnabled = enabled
        binding.btnDelete.isEnabled = enabled
        binding.btnSubmit.isEnabled = enabled && pinBuilder.length == PinSecurity.PIN_LENGTH
    }

    private fun showError(message: String) {
        binding.txtPinError.visibility = View.VISIBLE
        binding.txtPinError.text = message
    }

    private fun hideError() {
        binding.txtPinError.visibility = View.GONE
    }

    private fun getDigitFromKeyCode(keyCode: Int): Char? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
            else -> null
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onCancelled?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lockoutTimer?.cancel()
        lockoutTimer = null
        // Security: clear secret buffers from memory
        pinBuilder.clear()
        initialSetupPin?.fill('\u0000')
        initialSetupPin = null
        _binding = null
    }

    companion object {
        const val TAG = "AdminPinDialog"

        fun newInstance(
            mode: PinDialogMode,
            authRepository: AdminAuthRepository,
            sessionManager: AdminSessionManager? = null,
            onAuthenticated: (() -> Unit)? = null,
            onPinSetupCompleted: (() -> Unit)? = null,
            onCancelled: (() -> Unit)? = null
        ): AdminPinDialog {
            return AdminPinDialog().apply {
                this.mode = mode
                this.authRepository = authRepository
                this.sessionManager = sessionManager
                this.onAuthenticated = onAuthenticated
                this.onPinSetupCompleted = onPinSetupCompleted
                this.onCancelled = onCancelled
            }
        }
    }
}
