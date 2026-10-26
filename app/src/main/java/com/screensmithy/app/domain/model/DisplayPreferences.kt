package com.screensmithy.app.domain.model

/**
 * Screen presentation and wakefulness preferences.
 */
data class DisplayPreferences(
    val keepScreenAwake: Boolean = true,
    val immersiveSticky: Boolean = true,
    val displayMargins: DisplayMargins = DisplayMargins.ZERO
)
