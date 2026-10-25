package com.screensmithy.app.domain.url

/**
 * Result of evaluating and normalizing a URL input.
 */
sealed interface UrlValidationResult {
    /**
     * The input is a structurally valid HTTP or HTTPS URL.
     *
     * @property normalizedUrl Canonical normalized URL string.
     * @property scheme "http" or "https" (lowercase).
     * @property host Canonical lowercase hostname or IP address.
     * @property port Explicit port if specified and non-standard.
     * @property hasInsecureHttpWarning True if the URL uses unencrypted plain HTTP.
     * @property warningMessage User-facing guidance or warning (e.g. HTTP transport insecurity).
     */
    data class Valid(
        val normalizedUrl: String,
        val scheme: String,
        val host: String,
        val port: Int?,
        val hasInsecureHttpWarning: Boolean = false,
        val warningMessage: String? = null
    ) : UrlValidationResult

    /**
     * The input was malformed, used an unsafe scheme, or violated security constraints.
     */
    data class Invalid(
        val error: UrlValidationError,
        val userMessage: String
    ) : UrlValidationResult
}

/**
 * Specific causes for URL rejection.
 */
sealed interface UrlValidationError {
    data object BlankInput : UrlValidationError
    data class UnsupportedScheme(val scheme: String) : UrlValidationError
    data object MissingHost : UrlValidationError
    data class InvalidHost(val host: String, val reason: String) : UrlValidationError
    data class EmbeddedCredentialsNotAllowed(val userInfo: String) : UrlValidationError
    data class InvalidPort(val port: Int) : UrlValidationError
    data class MalformedUri(val reason: String) : UrlValidationError
}
