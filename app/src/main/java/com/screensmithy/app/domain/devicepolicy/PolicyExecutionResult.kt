package com.screensmithy.app.domain.devicepolicy

/**
 * Result of executing a device owner or kiosk policy operation.
 */
sealed interface PolicyExecutionResult {
    val isSuccess: Boolean get() = this is Success

    data class Success(val message: String) : PolicyExecutionResult
    data class Failure(val reason: String, val error: Throwable? = null) : PolicyExecutionResult
    data class NotEligible(val reason: String) : PolicyExecutionResult
}
