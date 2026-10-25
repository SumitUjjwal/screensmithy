package com.screensmithy.app.domain.wizard

import com.screensmithy.app.domain.auth.AdminAuthRepository
import com.screensmithy.app.domain.auth.LockoutState
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.repository.ConfigurationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SetupWizardManagerTest {

    private lateinit var fakeConfigRepo: FakeConfigRepository
    private lateinit var fakeAuthRepo: FakeAuthRepository
    private lateinit var validLocalFiles: MutableSet<String>
    private lateinit var wizardManager: SetupWizardManager

    @Before
    fun setUp() {
        fakeConfigRepo = FakeConfigRepository()
        fakeAuthRepo = FakeAuthRepository()
        validLocalFiles = mutableSetOf("imported_html/doc1/index.html")
        wizardManager = SetupWizardManager(
            configRepository = fakeConfigRepo,
            authRepository = fakeAuthRepo,
            localFileVerifier = { path -> validLocalFiles.contains(path) }
        )
    }

    @Test
    fun `initializes to WELCOME_AND_MODE when PIN is not configured`() = runBlocking {
        fakeAuthRepo.isConfigured = false
        wizardManager.initialize()

        val snapshot = wizardManager.snapshot.value
        assertEquals(WizardStep.WELCOME_AND_MODE, snapshot.currentStep)
        assertFalse(snapshot.isPinConfigured)
        assertFalse(snapshot.isSetupComplete)
    }

    @Test
    fun `blocks advancing from PIN_SETUP when PIN is not configured`() = runBlocking {
        fakeAuthRepo.isConfigured = false
        wizardManager.initialize()

        // Advance from WELCOME to PIN_SETUP
        val movedToPin = wizardManager.nextStep()
        assertTrue(movedToPin)
        assertEquals(WizardStep.PIN_SETUP, wizardManager.snapshot.value.currentStep)

        // Try advancing from PIN_SETUP without configuring PIN
        val advanced = wizardManager.nextStep()
        assertFalse(advanced)
        assertEquals(WizardStep.PIN_SETUP, wizardManager.snapshot.value.currentStep)
        assertNotNull(wizardManager.snapshot.value.validationError)
    }

    @Test
    fun `advances to CONTENT_SELECTION when PIN is configured`() = runBlocking {
        fakeAuthRepo.isConfigured = false
        wizardManager.initialize()
        wizardManager.nextStep() // at PIN_SETUP

        // Simulate PIN configuration
        fakeAuthRepo.isConfigured = true
        wizardManager.refreshPinStatus()

        val advanced = wizardManager.nextStep()
        assertTrue(advanced)
        assertEquals(WizardStep.CONTENT_SELECTION, wizardManager.snapshot.value.currentStep)
        assertNull(wizardManager.snapshot.value.validationError)
    }

    @Test
    fun `blocks advancing from CONTENT_SELECTION when no content is chosen`() = runBlocking {
        fakeAuthRepo.isConfigured = true
        wizardManager.initialize()
        wizardManager.nextStep() // at CONTENT_SELECTION

        val advanced = wizardManager.nextStep()
        assertFalse(advanced)
        assertEquals(WizardStep.CONTENT_SELECTION, wizardManager.snapshot.value.currentStep)
        assertNotNull(wizardManager.snapshot.value.validationError)
    }

    @Test
    fun `rejects invalid remote URL and remains on CONTENT_SELECTION`() = runBlocking {
        fakeAuthRepo.isConfigured = true
        wizardManager.initialize()
        wizardManager.nextStep() // at CONTENT_SELECTION

        val valid = wizardManager.setRemoteUrl("javascript:alert(1)")
        assertFalse(valid)
        assertNotNull(wizardManager.snapshot.value.validationError)

        val advanced = wizardManager.nextStep()
        assertFalse(advanced)
    }

    @Test
    fun `advances to PREVIEW_AND_CONFIRM with valid remote URL`() = runBlocking {
        fakeAuthRepo.isConfigured = true
        wizardManager.initialize()
        wizardManager.nextStep() // at CONTENT_SELECTION

        val valid = wizardManager.setRemoteUrl("kiosk.example.com")
        assertTrue(valid)

        val advanced = wizardManager.nextStep()
        assertTrue(advanced)
        assertEquals(WizardStep.PREVIEW_AND_CONFIRM, wizardManager.snapshot.value.currentStep)

        val candidate = wizardManager.snapshot.value.candidateSource
        assertTrue(candidate is ContentSource.RemoteUrl)
        assertEquals("https://kiosk.example.com/", (candidate as ContentSource.RemoteUrl).url)
    }

    @Test
    fun `verifies and stages valid local HTML file`() = runBlocking {
        fakeAuthRepo.isConfigured = true
        wizardManager.initialize()
        wizardManager.nextStep() // at CONTENT_SELECTION

        val metadata = LocalHtmlMetadata(
            documentId = "doc1",
            displayName = "index.html",
            relativePath = "imported_html/doc1/index.html",
            sizeBytes = 2048,
            importedAtEpochMs = 1700000000L
        )

        val setSuccess = wizardManager.setLocalHtml(metadata)
        assertTrue(setSuccess)

        val advanced = wizardManager.nextStep()
        assertTrue(advanced)
        assertEquals(WizardStep.PREVIEW_AND_CONFIRM, wizardManager.snapshot.value.currentStep)
    }

    @Test
    fun `rejects missing local HTML file`() = runBlocking {
        fakeAuthRepo.isConfigured = true
        wizardManager.initialize()
        wizardManager.nextStep() // at CONTENT_SELECTION

        val metadata = LocalHtmlMetadata(
            documentId = "doc2",
            displayName = "missing.html",
            relativePath = "imported_html/missing/index.html",
            sizeBytes = 1024,
            importedAtEpochMs = 1700000000L
        )

        val setSuccess = wizardManager.setLocalHtml(metadata)
        assertFalse(setSuccess)
        assertNotNull(wizardManager.snapshot.value.validationError)
    }

    @Test
    fun `previousStep navigates backwards safely preserving candidate data`() = runBlocking {
        fakeAuthRepo.isConfigured = true
        wizardManager.initialize()
        wizardManager.nextStep() // at CONTENT_SELECTION
        wizardManager.setRemoteUrl("https://example.com/tv")
        wizardManager.nextStep() // at PREVIEW_AND_CONFIRM

        assertEquals(WizardStep.PREVIEW_AND_CONFIRM, wizardManager.snapshot.value.currentStep)

        val steppedBack = wizardManager.previousStep()
        assertTrue(steppedBack)
        assertEquals(WizardStep.CONTENT_SELECTION, wizardManager.snapshot.value.currentStep)

        // Preserved candidate URL
        val candidate = wizardManager.snapshot.value.candidateSource
        assertEquals(ContentSource.RemoteUrl("https://example.com/tv"), candidate)
    }

    @Test
    fun `completeSetup updates configuration transactionally and sets isSetupCompleted true`() = runBlocking {
        fakeAuthRepo.isConfigured = true
        wizardManager.initialize()
        wizardManager.nextStep() // at CONTENT_SELECTION
        wizardManager.setRemoteUrl("https://kiosk.store.com/")
        wizardManager.setNavigationPolicy(NavigationPolicy(mode = NavigationMode.ALLOWLIST_ONLY))
        wizardManager.nextStep() // at PREVIEW_AND_CONFIRM

        val result = wizardManager.completeSetup()
        assertTrue(result.isSuccess)

        val saved = fakeConfigRepo.getConfiguration()
        assertTrue(saved.isSetupCompleted)
        assertEquals(ContentSource.RemoteUrl("https://kiosk.store.com/"), saved.contentSource)
        assertEquals(NavigationMode.ALLOWLIST_ONLY, saved.navigationPolicy.mode)

        assertTrue(wizardManager.snapshot.value.isSetupComplete)
    }

    // =========================================================================
    // Test Fakes
    // =========================================================================

    private class FakeConfigRepository : ConfigurationRepository {
        private var config = KioskConfiguration()
        private val state = MutableStateFlow(config)

        override val configurationFlow: Flow<KioskConfiguration> = state.asStateFlow()

        override suspend fun getConfiguration(): KioskConfiguration = config

        override suspend fun updateConfiguration(
            transform: (current: KioskConfiguration) -> KioskConfiguration
        ): Result<KioskConfiguration> {
            config = transform(config)
            state.value = config
            return Result.success(config)
        }

        override suspend fun resetConfiguration(): Result<KioskConfiguration> {
            config = KioskConfiguration()
            state.value = config
            return Result.success(config)
        }
    }

    private class FakeAuthRepository : AdminAuthRepository {
        var isConfigured: Boolean = false

        override suspend fun isPinConfigured(): Boolean = isConfigured

        override suspend fun getHashedPin(): com.screensmithy.app.domain.auth.HashedPin? = null

        override suspend fun savePin(hashedPin: com.screensmithy.app.domain.auth.HashedPin) {
            isConfigured = true
        }

        override suspend fun clearPin() {
            isConfigured = false
        }

        override suspend fun getFailedAttempts(): Int = 0

        override suspend fun recordFailedAttempt(nowEpochMs: Long): LockoutState = LockoutState(false, 0)

        override suspend fun resetFailedAttempts() {}

        override suspend fun getLockoutState(nowEpochMs: Long): LockoutState = LockoutState(false, 0)
    }
}
