package com.screensmithy.app.domain.wizard

import com.screensmithy.app.domain.auth.AdminAuthRepository
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.repository.ConfigurationRepository
import com.screensmithy.app.domain.url.UrlValidationResult
import com.screensmithy.app.domain.url.UrlValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * State machine managing the first-run setup wizard.
 * Ensures each step is properly validated and data is persisted safely without
 * marking setup complete prematurely if exited mid-flow.
 */
class SetupWizardManager(
    private val configRepository: ConfigurationRepository,
    private val authRepository: AdminAuthRepository,
    private val localFileVerifier: (relativePath: String) -> Boolean = { true }
) {

    private val _snapshot = MutableStateFlow(SetupWizardSnapshot())
    val snapshot: StateFlow<SetupWizardSnapshot> = _snapshot.asStateFlow()

    /**
     * Initializes the wizard with current auth and configuration state.
     */
    suspend fun initialize() {
        val pinConfigured = authRepository.isPinConfigured()
        val currentConfig = configRepository.getConfiguration()

        val initialStep = when {
            !pinConfigured -> WizardStep.WELCOME_AND_MODE
            currentConfig.contentSource is ContentSource.None -> WizardStep.CONTENT_SELECTION
            else -> WizardStep.WELCOME_AND_MODE
        }

        _snapshot.value = SetupWizardSnapshot(
            currentStep = initialStep,
            isPinConfigured = pinConfigured,
            candidateSource = currentConfig.contentSource,
            candidatePolicy = currentConfig.navigationPolicy,
            isSetupComplete = currentConfig.isSetupCompleted,
            validationError = null
        )
    }

    /**
     * Advances to the next step, validating step invariants.
     */
    suspend fun nextStep(): Boolean {
        val current = _snapshot.value
        when (current.currentStep) {
            WizardStep.WELCOME_AND_MODE -> {
                val isPinConfigured = authRepository.isPinConfigured()
                val next = if (!isPinConfigured) WizardStep.PIN_SETUP else WizardStep.CONTENT_SELECTION
                _snapshot.value = current.copy(
                    currentStep = next,
                    isPinConfigured = isPinConfigured,
                    validationError = null
                )
                return true
            }

            WizardStep.PIN_SETUP -> {
                val isPinConfigured = authRepository.isPinConfigured()
                if (!isPinConfigured) {
                    _snapshot.value = current.copy(
                        validationError = "Admin PIN must be configured to protect kiosk settings."
                    )
                    return false
                }
                _snapshot.value = current.copy(
                    currentStep = WizardStep.CONTENT_SELECTION,
                    isPinConfigured = true,
                    validationError = null
                )
                return true
            }

            WizardStep.CONTENT_SELECTION -> {
                when (val source = current.candidateSource) {
                    is ContentSource.None -> {
                        _snapshot.value = current.copy(
                            validationError = "Please select either a Remote URL or import Local HTML content."
                        )
                        return false
                    }
                    is ContentSource.RemoteUrl -> {
                        when (val valResult = UrlValidator.validate(source.url)) {
                            is UrlValidationResult.Invalid -> {
                                _snapshot.value = current.copy(validationError = valResult.userMessage)
                                return false
                            }
                            is UrlValidationResult.Valid -> {
                                _snapshot.value = current.copy(
                                    currentStep = WizardStep.PREVIEW_AND_CONFIRM,
                                    candidateSource = ContentSource.RemoteUrl(valResult.normalizedUrl),
                                    validationError = null
                                )
                                return true
                            }
                        }
                    }
                    is ContentSource.LocalHtml -> {
                        if (!localFileVerifier(source.metadata.relativePath)) {
                            _snapshot.value = current.copy(
                                validationError = "Selected local HTML file is missing or unreadable in storage."
                            )
                            return false
                        }
                        _snapshot.value = current.copy(
                            currentStep = WizardStep.PREVIEW_AND_CONFIRM,
                            validationError = null
                        )
                        return true
                    }
                }
            }

            WizardStep.PREVIEW_AND_CONFIRM -> {
                // Already at final step
                return true
            }
        }
    }

    /**
     * Navigates back to the preceding step.
     */
    fun previousStep(): Boolean {
        val current = _snapshot.value
        val prevStep = when (current.currentStep) {
            WizardStep.WELCOME_AND_MODE -> null
            WizardStep.PIN_SETUP -> WizardStep.WELCOME_AND_MODE
            WizardStep.CONTENT_SELECTION -> {
                if (!current.isPinConfigured) WizardStep.PIN_SETUP else WizardStep.WELCOME_AND_MODE
            }
            WizardStep.PREVIEW_AND_CONFIRM -> WizardStep.CONTENT_SELECTION
        } ?: return false

        _snapshot.value = current.copy(
            currentStep = prevStep,
            validationError = null
        )
        return true
    }

    /**
     * Sets candidate remote URL after validation.
     */
    fun setRemoteUrl(rawUrl: String): Boolean {
        val current = _snapshot.value
        return when (val validation = UrlValidator.validate(rawUrl)) {
            is UrlValidationResult.Valid -> {
                _snapshot.value = current.copy(
                    candidateSource = ContentSource.RemoteUrl(validation.normalizedUrl),
                    validationError = null
                )
                true
            }
            is UrlValidationResult.Invalid -> {
                _snapshot.value = current.copy(validationError = validation.userMessage)
                false
            }
        }
    }

    /**
     * Sets candidate local HTML metadata after verification.
     */
    fun setLocalHtml(metadata: LocalHtmlMetadata): Boolean {
        val current = _snapshot.value
        if (!localFileVerifier(metadata.relativePath)) {
            _snapshot.value = current.copy(
                validationError = "Local HTML file could not be verified in app private storage."
            )
            return false
        }

        _snapshot.value = current.copy(
            candidateSource = ContentSource.LocalHtml(metadata),
            validationError = null
        )
        return true
    }

    /**
     * Sets navigation policy for candidate content.
     */
    fun setNavigationPolicy(policy: NavigationPolicy) {
        _snapshot.value = _snapshot.value.copy(candidatePolicy = policy)
    }

    /**
     * Refreshes PIN configuration status (e.g. after returning from PIN setup dialog).
     */
    suspend fun refreshPinStatus() {
        val configured = authRepository.isPinConfigured()
        _snapshot.value = _snapshot.value.copy(
            isPinConfigured = configured,
            validationError = if (configured && _snapshot.value.currentStep == WizardStep.PIN_SETUP) null else _snapshot.value.validationError
        )
    }

    /**
     * Commits the setup wizard configuration transactionally to storage and activates presentation.
     */
    suspend fun completeSetup(): Result<KioskConfiguration> {
        val current = _snapshot.value
        if (!current.isPinConfigured) {
            return Result.failure(IllegalStateException("Admin PIN must be configured before activating kiosk."))
        }

        val source = current.candidateSource
        if (source is ContentSource.None) {
            return Result.failure(IllegalStateException("A valid content source must be chosen before activating kiosk."))
        }

        val result = configRepository.updateConfiguration { prev ->
            prev.copy(
                isSetupCompleted = true,
                contentSource = current.candidateSource,
                navigationPolicy = current.candidatePolicy
            )
        }

        if (result.isSuccess) {
            _snapshot.value = current.copy(
                isSetupComplete = true,
                validationError = null
            )
        } else {
            val error = result.exceptionOrNull()?.message ?: "Failed to save kiosk configuration."
            _snapshot.value = current.copy(validationError = error)
        }

        return result
    }
}
