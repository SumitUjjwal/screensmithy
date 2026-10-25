package com.screensmithy.app.domain.state

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration

/**
 * Pure domain engine that computes the deterministic [StartupState] from configuration.
 *
 * @param localFileVerifier Optional predicate to verify physical availability of imported local files.
 */
class StartupDecisionEngine(
    private val localFileVerifier: (relativePath: String) -> Boolean = { true }
) {
    /**
     * Evaluates the loaded [KioskConfiguration] and returns the appropriate [StartupState].
     */
    fun evaluate(configuration: KioskConfiguration): StartupState {
        // 1. Check schema compatibility
        if (configuration.schemaVersion > KioskConfiguration.CURRENT_SCHEMA_VERSION) {
            return StartupState.RecoverableConfigurationError(
                error = ConfigurationError.UnsupportedSchemaVersion(
                    foundVersion = configuration.schemaVersion,
                    maxSupportedVersion = KioskConfiguration.CURRENT_SCHEMA_VERSION
                ),
                fallbackConfiguration = configuration
            )
        }

        // 2. Check setup completion
        if (!configuration.isSetupCompleted) {
            return StartupState.SetupRequired(SetupReason.FIRST_RUN)
        }

        // 3. Validate content source
        return when (val source = configuration.contentSource) {
            is ContentSource.None -> {
                StartupState.SetupRequired(SetupReason.NO_CONTENT_CONFIGURED)
            }

            is ContentSource.RemoteUrl -> {
                evaluateRemoteUrl(source.url, configuration)
            }

            is ContentSource.LocalHtml -> {
                evaluateLocalHtml(source.metadata.relativePath, configuration)
            }
        }
    }

    private fun evaluateRemoteUrl(url: String, configuration: KioskConfiguration): StartupState {
        return when (val result = com.screensmithy.app.domain.url.UrlValidator.validate(url)) {
            is com.screensmithy.app.domain.url.UrlValidationResult.Valid -> {
                StartupState.ReadyToPresent(configuration)
            }
            is com.screensmithy.app.domain.url.UrlValidationResult.Invalid -> {
                StartupState.RecoverableConfigurationError(
                    error = ConfigurationError.InvalidUrl(url, result.userMessage),
                    fallbackConfiguration = configuration
                )
            }
        }
    }

    private fun evaluateLocalHtml(relativePath: String, configuration: KioskConfiguration): StartupState {
        if (relativePath.isBlank()) {
            return StartupState.RecoverableConfigurationError(
                error = ConfigurationError.MissingLocalFile(relativePath, "Local HTML file path is blank"),
                fallbackConfiguration = configuration
            )
        }

        if (!localFileVerifier(relativePath)) {
            return StartupState.RecoverableConfigurationError(
                error = ConfigurationError.MissingLocalFile(relativePath, "Referenced local HTML file could not be found"),
                fallbackConfiguration = configuration
            )
        }

        return StartupState.ReadyToPresent(configuration)
    }
}
