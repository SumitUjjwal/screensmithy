package com.screensmithy.app.domain.auth

import android.view.KeyEvent

/**
 * State machine detector for the hidden TV remote admin access sequence:
 * `UP → UP → DOWN → DOWN → LEFT → RIGHT → LEFT → RIGHT → OK`
 *
 * Enforces the following constraints:
 * - Operates entirely within the Android native input layer (independent of WebView JS).
 * - Imposes a strict 10-second total sequence timeout from the initial matching key press.
 * - Ignores key repeat events from held buttons (`repeatCount > 0`).
 * - Preserves ordinary webpage D-pad behavior: intermediate matching keys return `false`
 *   (not consumed) to permit standard web navigation and focus traversal.
 * - Only the final completing `OK` key returns `true` (consumed) to trigger admin access
 *   without triggering unintended click events on underlying web elements.
 * - Resets on mismatched keys, sequence timeout, backgrounding, or explicit reset.
 */
class RemoteSequenceDetector(
    private val onSequenceCompleted: () -> Unit
) {

    private var currentStep: Int = 0
    private var sequenceStartTimeMs: Long = 0L

    val activeStep: Int
        get() = currentStep

    val isSequenceActive: Boolean
        get() = currentStep > 0

    /**
     * Processes a [KeyEvent].
     */
    fun onKeyEvent(
        event: KeyEvent,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        return onKey(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Core state machine evaluation for key code, action, and repeat parameters.
     * Operates purely on primitives for maximum testability.
     *
     * @return true if the event completed the sequence and was consumed; false otherwise.
     */
    fun onKey(
        keyCode: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        // Only inspect primary press events; ignore key releases and repeat events from held buttons
        if (action != KeyEvent.ACTION_DOWN || repeatCount > 0) {
            return false
        }

        // Check for sequence timeout
        if (currentStep > 0 && (currentTimeMs - sequenceStartTimeMs > SEQUENCE_TIMEOUT_MS)) {
            reset()
        }

        val expectedKey = SEQUENCE[currentStep]
        val isMatch = isKeyMatching(keyCode, expectedKey)

        if (isMatch) {
            if (currentStep == 0) {
                sequenceStartTimeMs = currentTimeMs
            }

            currentStep++

            if (currentStep == SEQUENCE.size) {
                // Sequence completed! Reset detector and notify listener.
                reset()
                onSequenceCompleted()
                // Consume the completing key event to prevent clicking underlying UI elements
                return true
            }

            // Intermediate sequence steps must NOT be consumed so webpage navigation is preserved
            return false
        } else {
            // Mismatch: reset current sequence.
            // If the mismatched key happens to be the start of a new sequence (UP), start step 1.
            if (isKeyMatching(keyCode, SEQUENCE[0])) {
                currentStep = 1
                sequenceStartTimeMs = currentTimeMs
            } else {
                reset()
            }
            return false
        }
    }

    /**
     * Resets the detector state to initial idle step.
     */
    fun reset() {
        currentStep = 0
        sequenceStartTimeMs = 0L
    }

    private fun isKeyMatching(actualKeyCode: Int, expectedKey: SequenceKey): Boolean {
        return when (expectedKey) {
            SequenceKey.UP -> actualKeyCode == KeyEvent.KEYCODE_DPAD_UP
            SequenceKey.DOWN -> actualKeyCode == KeyEvent.KEYCODE_DPAD_DOWN
            SequenceKey.LEFT -> actualKeyCode == KeyEvent.KEYCODE_DPAD_LEFT
            SequenceKey.RIGHT -> actualKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            SequenceKey.OK -> actualKeyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    actualKeyCode == KeyEvent.KEYCODE_ENTER ||
                    actualKeyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        }
    }

    enum class SequenceKey {
        UP, DOWN, LEFT, RIGHT, OK
    }

    companion object {
        /**
         * 10-second maximum window for full sequence entry as specified in MASTER_PROMPT.md.
         */
        const val SEQUENCE_TIMEOUT_MS: Long = 10_000L

        /**
         * Konami-style sequence adapted for Android TV D-pad remotes:
         * UP → UP → DOWN → DOWN → LEFT → RIGHT → LEFT → RIGHT → OK
         */
        val SEQUENCE = listOf(
            SequenceKey.UP,
            SequenceKey.UP,
            SequenceKey.DOWN,
            SequenceKey.DOWN,
            SequenceKey.LEFT,
            SequenceKey.RIGHT,
            SequenceKey.LEFT,
            SequenceKey.RIGHT,
            SequenceKey.OK
        )
    }
}
