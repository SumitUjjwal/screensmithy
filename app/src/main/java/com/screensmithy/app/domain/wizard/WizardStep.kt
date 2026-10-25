package com.screensmithy.app.domain.wizard

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.NavigationPolicy

/**
 * Steps in the first-run ScreenSmithy setup wizard.
 */
enum class WizardStep {
    /**
     * Introduction explaining normal vs managed Device Owner modes,
     * showing actual current device provisioning state.
     */
    WELCOME_AND_MODE,

    /**
     * Security setup: guides admin through 6-digit PIN configuration.
     */
    PIN_SETUP,

    /**
     * Content selection: choose between Local HTML and Remote URL.
     */
    CONTENT_SELECTION,

    /**
     * Preview and final confirmation before activating kiosk mode.
     */
    PREVIEW_AND_CONFIRM
}

/**
 * Immutable state snapshot of the setup wizard.
 */
data class SetupWizardSnapshot(
    val currentStep: WizardStep = WizardStep.WELCOME_AND_MODE,
    val isPinConfigured: Boolean = false,
    val candidateSource: ContentSource = ContentSource.None,
    val candidatePolicy: NavigationPolicy = NavigationPolicy.DEFAULT,
    val isSetupComplete: Boolean = false,
    val validationError: String? = null
)
