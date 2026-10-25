package com.screensmithy.app.domain.recovery

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.model.RetryPolicy
import com.screensmithy.app.domain.renderer.RendererState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecoveryCoordinatorTest {

    private val retryPolicy = RetryPolicy(
        maxRetries = 3,
        initialBackoffSeconds = 5,
        maxBackoffSeconds = 60
    )

    private val sampleSource = ContentSource.RemoteUrl("https://example.com/kiosk")
    private val otherSource = ContentSource.RemoteUrl("https://other.com/kiosk")
    private val samplePolicy = NavigationPolicy.DEFAULT

    @Test
    fun `evaluateLoad is idempotent when active content is already presenting`() {
        val coordinator = StartupRecoveryCoordinator()

        val firstAction = coordinator.evaluateLoad(sampleSource, samplePolicy)
        assertTrue(firstAction is RecoveryAction.ProceedWithLoad)

        coordinator.onLoadSuccess("https://example.com/kiosk")
        assertTrue(coordinator.state.value is RecoveryState.Presenting)

        // Duplicate load request (e.g. from onResume or onNewIntent) should be skipped
        val duplicateAction = coordinator.evaluateLoad(sampleSource, samplePolicy)
        assertEquals(RecoveryAction.SkipDuplicateLoad, duplicateAction)

        // Request with different source proceeds
        val diffAction = coordinator.evaluateLoad(otherSource, samplePolicy)
        assertTrue(diffAction is RecoveryAction.ProceedWithLoad)
    }

    @Test
    fun `evaluateLoad with force true proceeds even if same content`() {
        val coordinator = StartupRecoveryCoordinator()

        coordinator.evaluateLoad(sampleSource, samplePolicy)
        coordinator.onLoadSuccess("https://example.com/kiosk")

        val forcedAction = coordinator.evaluateLoad(sampleSource, samplePolicy, force = true)
        assertTrue(forcedAction is RecoveryAction.ProceedWithLoad)
    }

    @Test
    fun `transient network error schedules exponential backoff until exhausted`() {
        val calculator = BackoffCalculator(randomFractionProvider = { 0.5 }) // Neutral jitter
        val coordinator = StartupRecoveryCoordinator(backoffCalculator = calculator)

        coordinator.evaluateLoad(sampleSource, samplePolicy)

        val netError = RendererState.MainFrameError(
            errorCode = -2,
            description = "net::ERR_NAME_NOT_RESOLVED",
            failingUrl = "https://example.com/kiosk"
        )

        // Attempt 1
        val action1 = coordinator.onLoadError(netError, retryPolicy)
        assertTrue(action1 is RecoveryAction.ScheduleRetry)
        val retry1 = action1 as RecoveryAction.ScheduleRetry
        assertEquals(1, retry1.attempt)
        assertEquals(5_000L, retry1.delayMs)

        // Attempt 2
        val action2 = coordinator.onLoadError(netError, retryPolicy)
        assertTrue(action2 is RecoveryAction.ScheduleRetry)
        val retry2 = action2 as RecoveryAction.ScheduleRetry
        assertEquals(2, retry2.attempt)
        assertEquals(10_000L, retry2.delayMs)

        // Attempt 3 (maxRetries = 3)
        val action3 = coordinator.onLoadError(netError, retryPolicy)
        assertTrue(action3 is RecoveryAction.ScheduleRetry)
        val retry3 = action3 as RecoveryAction.ScheduleRetry
        assertEquals(3, retry3.attempt)
        assertEquals(20_000L, retry3.delayMs)

        // Attempt 4 -> Exhausted
        val action4 = coordinator.onLoadError(netError, retryPolicy)
        assertTrue(action4 is RecoveryAction.PermanentFailure)
        val failure = action4 as RecoveryAction.PermanentFailure
        assertEquals(NonRetryableReason.MAX_RETRIES_EXCEEDED, failure.nonRetryableReason)
    }

    @Test
    fun `TLS errors fail closed immediately without retry loop`() {
        val coordinator = StartupRecoveryCoordinator()
        coordinator.evaluateLoad(sampleSource, samplePolicy)

        val tlsError = RendererState.TlsError(
            primaryErrorCode = 3,
            errorDescription = "SSL_UNTRUSTED",
            failingUrl = "https://example.com/kiosk"
        )

        val action = coordinator.onLoadError(tlsError, retryPolicy)

        assertTrue(action is RecoveryAction.PermanentFailure)
        val failure = action as RecoveryAction.PermanentFailure
        assertEquals(NonRetryableReason.TLS_FAILURE, failure.nonRetryableReason)
        assertEquals(0, coordinator.currentAttempt)
    }

    @Test
    fun `missing local content fails immediately without retry loop`() {
        val coordinator = StartupRecoveryCoordinator()
        val localSource = ContentSource.LocalHtml(
            com.screensmithy.app.domain.model.LocalHtmlMetadata(
                documentId = "doc-1",
                displayName = "Offline Kiosk",
                relativePath = "imported_html/index.html",
                sizeBytes = 1024L,
                importedAtEpochMs = 123456789L
            )
        )
        coordinator.evaluateLoad(localSource, samplePolicy)

        val missingError = RendererState.MissingLocalContent(
            relativePath = "imported_html/index.html",
            reason = "File does not exist"
        )

        val action = coordinator.onLoadError(missingError, retryPolicy)

        assertTrue(action is RecoveryAction.PermanentFailure)
        val failure = action as RecoveryAction.PermanentFailure
        assertEquals(NonRetryableReason.MISSING_LOCAL_FILE, failure.nonRetryableReason)
    }

    @Test
    fun `renderer crash triggers recreate and halts if crashing repeatedly`() {
        var currentTime = 1000L
        val coordinator = StartupRecoveryCoordinator(
            maxConsecutiveCrashes = 3,
            crashWindowMs = 60_000L,
            timeProvider = { currentTime }
        )
        coordinator.evaluateLoad(sampleSource, samplePolicy)

        val crash = RendererState.RendererCrash(didCrash = true)

        // Crashes 1, 2, 3 should trigger recreate
        currentTime += 5000L
        val action1 = coordinator.onLoadError(crash, retryPolicy)
        assertTrue(action1 is RecoveryAction.RecreateRenderer)

        currentTime += 5000L
        val action2 = coordinator.onLoadError(crash, retryPolicy)
        assertTrue(action2 is RecoveryAction.RecreateRenderer)

        currentTime += 5000L
        val action3 = coordinator.onLoadError(crash, retryPolicy)
        assertTrue(action3 is RecoveryAction.RecreateRenderer)

        // Crash 4 exceeds maxConsecutiveCrashes (3) within 60s -> halts crash loop
        currentTime += 5000L
        val action4 = coordinator.onLoadError(crash, retryPolicy)
        assertTrue(action4 is RecoveryAction.PermanentFailure)
        val failure = action4 as RecoveryAction.PermanentFailure
        assertEquals(NonRetryableReason.MAX_RETRIES_EXCEEDED, failure.nonRetryableReason)
    }

    @Test
    fun `network restoration triggers reload when waiting for retry or exhausted transient error`() {
        val coordinator = StartupRecoveryCoordinator()
        coordinator.evaluateLoad(sampleSource, samplePolicy)

        val netError = RendererState.MainFrameError(-2, "No connection", "https://example.com")
        coordinator.onLoadError(netError, retryPolicy)
        assertTrue(coordinator.state.value is RecoveryState.WaitingForRetry)

        val action = coordinator.onNetworkRestored()
        assertTrue(action is RecoveryAction.ProceedWithLoad)
        val load = action as RecoveryAction.ProceedWithLoad
        assertEquals(sampleSource, load.source)
        assertEquals(0, coordinator.currentAttempt)
    }

    @Test
    fun `network restoration does not trigger reload if presenting or permanent TLS failure`() {
        val coordinator = StartupRecoveryCoordinator()
        coordinator.evaluateLoad(sampleSource, samplePolicy)
        coordinator.onLoadSuccess("https://example.com")

        // Clean presentation: network toggle should not reload
        assertNull(coordinator.onNetworkRestored())

        // Fatal TLS error: network toggle should not auto-retry
        val tlsCoordinator = StartupRecoveryCoordinator()
        tlsCoordinator.evaluateLoad(sampleSource, samplePolicy)
        tlsCoordinator.onLoadError(
            RendererState.TlsError(
                failingUrl = "https://example.com",
                errorDescription = "BAD_CERT",
                primaryErrorCode = 3
            ),
            retryPolicy
        )
        assertTrue((tlsCoordinator.state.value as RecoveryState.Failed).isPermanent)

        assertNull(tlsCoordinator.onNetworkRestored())
    }
}
