package com.screensmithy.app.domain.model

/**
 * Bounded retry parameters for loading content failures.
 * Prevents rapid or infinite retry loops on network/server failures.
 */
data class RetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialBackoffSeconds: Int = DEFAULT_INITIAL_BACKOFF_SECONDS,
    val maxBackoffSeconds: Int = DEFAULT_MAX_BACKOFF_SECONDS
) {
    init {
        require(maxRetries in MIN_RETRIES..MAX_ALLOWED_RETRIES) {
            "maxRetries ($maxRetries) must be between $MIN_RETRIES and $MAX_ALLOWED_RETRIES"
        }
        require(initialBackoffSeconds in MIN_BACKOFF_SECONDS..maxBackoffSeconds) {
            "initialBackoffSeconds ($initialBackoffSeconds) must be between $MIN_BACKOFF_SECONDS and maxBackoffSeconds ($maxBackoffSeconds)"
        }
        require(maxBackoffSeconds in initialBackoffSeconds..MAX_ALLOWED_BACKOFF_SECONDS) {
            "maxBackoffSeconds ($maxBackoffSeconds) must be between initialBackoffSeconds ($initialBackoffSeconds) and $MAX_ALLOWED_BACKOFF_SECONDS"
        }
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val MIN_RETRIES = 1
        const val MAX_ALLOWED_RETRIES = 10

        const val DEFAULT_INITIAL_BACKOFF_SECONDS = 5
        const val MIN_BACKOFF_SECONDS = 1

        const val DEFAULT_MAX_BACKOFF_SECONDS = 60
        const val MAX_ALLOWED_BACKOFF_SECONDS = 300
    }
}
