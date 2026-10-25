package com.screensmithy.app.domain.recovery

import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.domain.state.ConfigurationError

/**
 * Pure evaluator classifying errors into retryable transient failures vs non-retryable fatal states.
 * Enforces the strict kiosk policy: never busy-loop on TLS errors, invalid configs, or missing files.
 */
object ErrorClassifier {

    /**
     * Classifies a [RendererState] failure into retryable or non-retryable.
     */
    fun classifyRendererState(state: RendererState): ErrorClassification {
        return when (state) {
            is RendererState.MainFrameError -> {
                ErrorClassification.Retryable(
                    userMessage = "Network connection failed. Will retry automatically.",
                    technicalDetails = "Main frame error [code=${state.errorCode}]: ${state.description}",
                    isNetworkRelated = true
                )
            }

            is RendererState.HttpError -> {
                if (state.statusCode in 500..599 || state.statusCode == 408 || state.statusCode == 429) {
                    ErrorClassification.Retryable(
                        userMessage = "Server temporarily unavailable (HTTP ${state.statusCode}). Retrying.",
                        technicalDetails = "HTTP ${state.statusCode}: ${state.reasonPhrase} on ${state.failingUrl}",
                        isNetworkRelated = true
                    )
                } else {
                    ErrorClassification.NonRetryable(
                        userMessage = "Cannot load content (HTTP ${state.statusCode} ${state.reasonPhrase}).",
                        reason = NonRetryableReason.PERMANENT_CLIENT_ERROR,
                        technicalDetails = "Permanent HTTP client error ${state.statusCode} on ${state.failingUrl}"
                    )
                }
            }

            is RendererState.TlsError -> {
                ErrorClassification.NonRetryable(
                    userMessage = "Security verification failed (TLS/SSL certificate error).",
                    reason = NonRetryableReason.TLS_FAILURE,
                    technicalDetails = "TLS failure on ${state.failingUrl}: ${state.errorDescription} (primaryCode=${state.primaryErrorCode})"
                )
            }

            is RendererState.MissingLocalContent -> {
                ErrorClassification.NonRetryable(
                    userMessage = "Configured local document is missing from app storage.",
                    reason = NonRetryableReason.MISSING_LOCAL_FILE,
                    technicalDetails = "Missing local file at: ${state.relativePath} (${state.reason})"
                )
            }

            is RendererState.RendererCrash -> {
                ErrorClassification.Retryable(
                    userMessage = "WebView renderer process exited. Reinitializing renderer.",
                    technicalDetails = "Chromium render process terminated (didCrash=${state.didCrash})",
                    isNetworkRelated = false
                )
            }

            is RendererState.Loading,
            is RendererState.Ready,
            is RendererState.Idle -> {
                ErrorClassification.NonRetryable(
                    userMessage = "Not an error state.",
                    reason = NonRetryableReason.INVALID_CONFIGURATION,
                    technicalDetails = "Unexpected call to classify on non-error state $state"
                )
            }
        }
    }

    /**
     * Classifies a [ConfigurationError] failure. All configuration errors are non-retryable
     * without administrative reconfiguration.
     */
    fun classifyConfigurationError(error: ConfigurationError): ErrorClassification.NonRetryable {
        return when (error) {
            is ConfigurationError.InvalidUrl -> ErrorClassification.NonRetryable(
                userMessage = "Configured URL is invalid: ${error.reason}",
                reason = NonRetryableReason.INVALID_CONFIGURATION,
                technicalDetails = "Invalid URL '${error.url}': ${error.reason}"
            )
            is ConfigurationError.MissingLocalFile -> ErrorClassification.NonRetryable(
                userMessage = "Configured local document is missing: ${error.reason}",
                reason = NonRetryableReason.MISSING_LOCAL_FILE,
                technicalDetails = "Missing local file '${error.relativePath}': ${error.reason}"
            )
            is ConfigurationError.UnsupportedSchemaVersion -> ErrorClassification.NonRetryable(
                userMessage = "Configuration version is unsupported (${error.foundVersion}).",
                reason = NonRetryableReason.INVALID_CONFIGURATION,
                technicalDetails = "Unsupported schema version: ${error.foundVersion}"
            )
            is ConfigurationError.CorruptedData -> ErrorClassification.NonRetryable(
                userMessage = "Configuration data was corrupted.",
                reason = NonRetryableReason.INVALID_CONFIGURATION,
                technicalDetails = "Corrupted data: ${error.message}"
            )
            is ConfigurationError.InvalidConfigurationParameter -> ErrorClassification.NonRetryable(
                userMessage = "Configuration parameter '${error.parameterName}' is invalid.",
                reason = NonRetryableReason.INVALID_CONFIGURATION,
                technicalDetails = "Invalid parameter '${error.parameterName}': ${error.reason}"
            )
        }
    }
}
