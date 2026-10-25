package com.screensmithy.app.domain.recovery

/**
 * Reason why an error is deemed non-retryable without administrator intervention.
 */
enum class NonRetryableReason {
    /**
     * TLS/SSL certificate validation failed (expired, self-signed, untrusted CA, hostname mismatch).
     * Strictly fail-closed: never bypassed or automatically retried.
     */
    TLS_FAILURE,

    /**
     * Invalid configuration parameter, malformed URL, unsupported schema version, or corrupted data.
     */
    INVALID_CONFIGURATION,

    /**
     * Imported local HTML document was not found on app-private storage.
     */
    MISSING_LOCAL_FILE,

    /**
     * HTTP 4xx client error (400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 410 Gone).
     */
    PERMANENT_CLIENT_ERROR,

    /**
     * Navigation or intent launch was blocked by kiosk security policy.
     */
    POLICY_BLOCKED,

    /**
     * Maximum retry attempts exhausted.
     */
    MAX_RETRIES_EXCEEDED
}

/**
 * Result of evaluating whether a failure can be safely retried with exponential backoff.
 */
sealed interface ErrorClassification {
    val isRetryable: Boolean get() = this is Retryable

    data class Retryable(
        val userMessage: String,
        val technicalDetails: String,
        val isNetworkRelated: Boolean = true
    ) : ErrorClassification

    data class NonRetryable(
        val userMessage: String,
        val reason: NonRetryableReason,
        val technicalDetails: String
    ) : ErrorClassification
}
