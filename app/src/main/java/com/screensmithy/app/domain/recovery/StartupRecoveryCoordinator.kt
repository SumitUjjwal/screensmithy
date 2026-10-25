package com.screensmithy.app.domain.recovery

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.model.RetryPolicy
import com.screensmithy.app.domain.renderer.RendererState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable recovery state of the kiosk presentation engine.
 */
sealed interface RecoveryState {
    data object Idle : RecoveryState

    data class Presenting(
        val source: ContentSource,
        val policy: NavigationPolicy
    ) : RecoveryState

    data class Loading(
        val source: ContentSource,
        val policy: NavigationPolicy,
        val attempt: Int
    ) : RecoveryState

    data class WaitingForRetry(
        val source: ContentSource,
        val policy: NavigationPolicy,
        val attempt: Int,
        val maxRetries: Int,
        val delayMs: Long,
        val reason: String
    ) : RecoveryState

    data class Failed(
        val source: ContentSource,
        val reason: String,
        val isPermanent: Boolean,
        val nonRetryableReason: NonRetryableReason? = null
    ) : RecoveryState
}

/**
 * Action directed by the recovery coordinator to the host Activity or Renderer.
 */
sealed interface RecoveryAction {
    data class ProceedWithLoad(
        val source: ContentSource,
        val policy: NavigationPolicy
    ) : RecoveryAction

    data object SkipDuplicateLoad : RecoveryAction

    data class ScheduleRetry(
        val source: ContentSource,
        val policy: NavigationPolicy,
        val attempt: Int,
        val maxRetries: Int,
        val delayMs: Long,
        val reason: String
    ) : RecoveryAction

    data class PermanentFailure(
        val reason: String,
        val nonRetryableReason: NonRetryableReason
    ) : RecoveryAction

    data class RecreateRenderer(
        val reason: String
    ) : RecoveryAction
}

/**
 * Pure domain coordinator managing idempotent presentation loading, bounded exponential
 * backoff retry scheduling, network restoration triggers, and renderer crash recovery.
 */
class StartupRecoveryCoordinator(
    private val backoffCalculator: BackoffCalculator = BackoffCalculator(),
    private val maxConsecutiveCrashes: Int = 3,
    private val crashWindowMs: Long = 60_000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private val _state = MutableStateFlow<RecoveryState>(RecoveryState.Idle)
    val state: StateFlow<RecoveryState> = _state.asStateFlow()

    private var activeSource: ContentSource = ContentSource.None
    private var activePolicy: NavigationPolicy = NavigationPolicy.DEFAULT
    private var currentRetryAttempt: Int = 0
    private val recentCrashTimestamps = mutableListOf<Long>()

    val currentAttempt: Int get() = currentRetryAttempt

    /**
     * Evaluates a requested content load. Guarantees idempotency by discarding
     * duplicate load requests when already presenting or actively loading identical content.
     */
    fun evaluateLoad(
        source: ContentSource,
        policy: NavigationPolicy,
        force: Boolean = false
    ): RecoveryAction {
        if (!force) {
            val currentState = _state.value
            val isSameContent = (activeSource == source && activePolicy == policy)
            if (isSameContent && (currentState is RecoveryState.Presenting || currentState is RecoveryState.Loading)) {
                return RecoveryAction.SkipDuplicateLoad
            }
        }

        activeSource = source
        activePolicy = policy
        currentRetryAttempt = 0
        _state.value = RecoveryState.Loading(source, policy, attempt = 1)
        return RecoveryAction.ProceedWithLoad(source, policy)
    }

    /**
     * Invoked when content finishes loading successfully.
     */
    fun onLoadSuccess(url: String) {
        currentRetryAttempt = 0
        recentCrashTimestamps.clear()
        _state.value = RecoveryState.Presenting(activeSource, activePolicy)
    }

    /**
     * Evaluates a renderer failure and coordinates bounded retry or fail-fast routing.
     */
    fun onLoadError(
        rendererState: RendererState,
        retryPolicy: RetryPolicy
    ): RecoveryAction {
        val classification = ErrorClassifier.classifyRendererState(rendererState)

        when (classification) {
            is ErrorClassification.NonRetryable -> {
                _state.value = RecoveryState.Failed(
                    source = activeSource,
                    reason = classification.userMessage,
                    isPermanent = true,
                    nonRetryableReason = classification.reason
                )
                return RecoveryAction.PermanentFailure(
                    reason = classification.userMessage,
                    nonRetryableReason = classification.reason
                )
            }

            is ErrorClassification.Retryable -> {
                if (rendererState is RendererState.RendererCrash) {
                    val now = timeProvider()
                    recentCrashTimestamps.removeAll { now - it > crashWindowMs }
                    recentCrashTimestamps.add(now)

                    if (recentCrashTimestamps.size > maxConsecutiveCrashes) {
                        _state.value = RecoveryState.Failed(
                            source = activeSource,
                            reason = "Renderer process crashed repeatedly ($maxConsecutiveCrashes times in ${crashWindowMs / 1000}s)",
                            isPermanent = true,
                            nonRetryableReason = NonRetryableReason.MAX_RETRIES_EXCEEDED
                        )
                        return RecoveryAction.PermanentFailure(
                            reason = "Renderer crashed repeatedly; halted to prevent crash loop",
                            nonRetryableReason = NonRetryableReason.MAX_RETRIES_EXCEEDED
                        )
                    }
                    return RecoveryAction.RecreateRenderer(
                        reason = "Renderer process terminated; recreating WebView"
                    )
                }

                currentRetryAttempt++
                val delayMs = backoffCalculator.calculateDelayMs(retryPolicy, currentRetryAttempt)

                return if (delayMs != null) {
                    _state.value = RecoveryState.WaitingForRetry(
                        source = activeSource,
                        policy = activePolicy,
                        attempt = currentRetryAttempt,
                        maxRetries = retryPolicy.maxRetries,
                        delayMs = delayMs,
                        reason = classification.userMessage
                    )
                    RecoveryAction.ScheduleRetry(
                        source = activeSource,
                        policy = activePolicy,
                        attempt = currentRetryAttempt,
                        maxRetries = retryPolicy.maxRetries,
                        delayMs = delayMs,
                        reason = classification.userMessage
                    )
                } else {
                    _state.value = RecoveryState.Failed(
                        source = activeSource,
                        reason = "Connection failed after ${retryPolicy.maxRetries} retry attempts.",
                        isPermanent = false,
                        nonRetryableReason = NonRetryableReason.MAX_RETRIES_EXCEEDED
                    )
                    RecoveryAction.PermanentFailure(
                        reason = "Exhausted all ${retryPolicy.maxRetries} retry attempts",
                        nonRetryableReason = NonRetryableReason.MAX_RETRIES_EXCEEDED
                    )
                }
            }
        }
    }

    /**
     * Handles network connectivity restoration. If waiting in retry or non-permanent error,
     * resets attempt counter and triggers immediate reload without busy polling.
     */
    fun onNetworkRestored(): RecoveryAction? {
        val currentState = _state.value
        return when (currentState) {
            is RecoveryState.WaitingForRetry,
            is RecoveryState.Failed -> {
                if (currentState is RecoveryState.Failed && currentState.isPermanent) {
                    null // Don't auto-retry permanent TLS or invalid configuration errors on network return
                } else {
                    currentRetryAttempt = 0
                    _state.value = RecoveryState.Loading(activeSource, activePolicy, attempt = 1)
                    RecoveryAction.ProceedWithLoad(activeSource, activePolicy)
                }
            }
            is RecoveryState.Presenting,
            is RecoveryState.Loading,
            is RecoveryState.Idle -> null
        }
    }

    /**
     * Resets coordinator state to Idle.
     */
    fun reset() {
        currentRetryAttempt = 0
        recentCrashTimestamps.clear()
        _state.value = RecoveryState.Idle
    }
}
