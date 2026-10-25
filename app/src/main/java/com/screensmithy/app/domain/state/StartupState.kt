package com.screensmithy.app.domain.state

import com.screensmithy.app.domain.model.KioskConfiguration

/**
 * Deterministic states evaluated at startup to guide application flow.
 */
sealed interface StartupState {
    /**
     * Initial transient evaluation state before configuration is loaded.
     */
    data object Initializing : StartupState

    /**
     * Setup wizard is needed because setup has not been completed or content is unconfigured.
     */
    data class SetupRequired(
        val reason: SetupReason
    ) : StartupState

    /**
     * Configuration is complete, validated, and ready for content presentation.
     */
    data class ReadyToPresent(
        val configuration: KioskConfiguration
    ) : StartupState

    /**
     * Configuration is present but in an invalid, unrecoverable, or corrupted state.
     * Must never crash; provides actionable diagnostics and allows recovery via setup/admin.
     */
    data class RecoverableConfigurationError(
        val error: ConfigurationError,
        val fallbackConfiguration: KioskConfiguration? = null
    ) : StartupState
}

/**
 * Explanatory context for why setup is required.
 */
enum class SetupReason {
    FIRST_RUN,
    SETUP_INCOMPLETE,
    NO_CONTENT_CONFIGURED,
    RESET_REQUESTED
}

/**
 * Categorized configuration and startup anomalies.
 */
sealed interface ConfigurationError {
    data class CorruptedData(val message: String) : ConfigurationError
    data class UnsupportedSchemaVersion(val foundVersion: Int, val maxSupportedVersion: Int) : ConfigurationError
    data class InvalidUrl(val url: String, val reason: String) : ConfigurationError
    data class MissingLocalFile(val relativePath: String, val reason: String) : ConfigurationError
    data class InvalidConfigurationParameter(val parameterName: String, val reason: String) : ConfigurationError
}
