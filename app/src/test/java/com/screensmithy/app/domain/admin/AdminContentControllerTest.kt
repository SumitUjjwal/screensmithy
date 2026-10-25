package com.screensmithy.app.domain.admin

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
import java.io.File

class AdminContentControllerTest {

    private lateinit var fakeConfigRepo: FakeConfigRepository
    private lateinit var validLocalFiles: MutableSet<String>
    private lateinit var controller: AdminContentController

    @Before
    fun setUp() {
        fakeConfigRepo = FakeConfigRepository()
        validLocalFiles = mutableSetOf("imported_html/test/index.html")
        controller = AdminContentController(
            repository = fakeConfigRepo,
            appFilesDir = File("/fake/app/files"),
            localFileVerifier = { path -> validLocalFiles.contains(path) }
        )
    }

    @Test
    fun `initializes staging state from active configuration`() = runBlocking {
        fakeConfigRepo.setConfig(
            KioskConfiguration(
                isSetupCompleted = true,
                contentSource = ContentSource.RemoteUrl("https://current.com/"),
                navigationPolicy = NavigationPolicy(mode = NavigationMode.CONFIGURED_HOST_ONLY)
            )
        )

        val state = controller.initialize()
        assertEquals(ContentSource.RemoteUrl("https://current.com/"), state.stagedSource)
        assertEquals(NavigationMode.CONFIGURED_HOST_ONLY, state.stagedPolicy.mode)
        assertFalse(state.isDirty)
        assertFalse(state.isPreviewing)
        assertNull(state.lastError)
    }

    @Test
    fun `staging valid remote URL marks state dirty and updates candidate`() = runBlocking {
        controller.initialize()

        val result = controller.stageRemoteUrl("kiosk.newdomain.com/screen")
        assertTrue(result is CandidateValidationResult.Valid)

        val valid = result as CandidateValidationResult.Valid
        val expectedUrl = "https://kiosk.newdomain.com/screen"
        assertEquals(ContentSource.RemoteUrl(expectedUrl), valid.normalizedSource)

        val currentState = controller.state.value
        assertNotNull(currentState)
        assertEquals(ContentSource.RemoteUrl(expectedUrl), currentState?.stagedSource)
        assertTrue(currentState!!.isDirty)
        assertNull(currentState.lastError)
    }

    @Test
    fun `staging invalid remote URL retains active configuration and sets lastError`() = runBlocking {
        fakeConfigRepo.setConfig(
            KioskConfiguration(
                contentSource = ContentSource.RemoteUrl("https://original.com/")
            )
        )
        controller.initialize()

        val result = controller.stageRemoteUrl("invalid::url::scheme")
        assertTrue(result is CandidateValidationResult.Invalid)

        val currentState = controller.state.value
        assertNotNull(currentState?.lastError)
        assertEquals(ContentSource.RemoteUrl("https://original.com/"), currentState?.activeConfiguration?.contentSource)
    }

    @Test
    fun `staging local HTML verifies file existence`() = runBlocking {
        controller.initialize()

        val validMetadata = LocalHtmlMetadata(
            documentId = "test",
            displayName = "index.html",
            relativePath = "imported_html/test/index.html",
            sizeBytes = 4096,
            importedAtEpochMs = 1700000000L
        )

        val result = controller.stageLocalHtml(validMetadata)
        assertTrue(result is CandidateValidationResult.Valid)
        assertEquals(ContentSource.LocalHtml(validMetadata), controller.state.value?.stagedSource)
        assertTrue(controller.state.value!!.isDirty)
    }

    @Test
    fun `staging missing local HTML is rejected`() = runBlocking {
        controller.initialize()

        val missingMetadata = LocalHtmlMetadata(
            documentId = "missing",
            displayName = "not_found.html",
            relativePath = "imported_html/not_found/index.html",
            sizeBytes = 4096,
            importedAtEpochMs = 1700000000L
        )

        val result = controller.stageLocalHtml(missingMetadata)
        assertTrue(result is CandidateValidationResult.Invalid)
        assertNotNull(controller.state.value?.lastError)
    }

    @Test
    fun `staging navigation policy updates candidate policy`() = runBlocking {
        controller.initialize()

        controller.stageNavigationPolicy(
            mode = NavigationMode.ALLOWLIST_ONLY,
            approvedRedirectHosts = setOf("auth.domain.com"),
            includeSubdomains = true
        )

        val policy = controller.state.value?.stagedPolicy
        assertNotNull(policy)
        assertEquals(NavigationMode.ALLOWLIST_ONLY, policy?.mode)
        assertTrue(policy?.approvedRedirectHosts?.contains("auth.domain.com") == true)
        assertTrue(policy?.includeSubdomains == true)
        assertTrue(controller.state.value!!.isDirty)
    }

    @Test
    fun `revertToActive discards unapplied edits and restores active configuration`() = runBlocking {
        fakeConfigRepo.setConfig(
            KioskConfiguration(
                contentSource = ContentSource.RemoteUrl("https://active-kiosk.com/"),
                navigationPolicy = NavigationPolicy(mode = NavigationMode.CONFIGURED_HOST_ONLY)
            )
        )
        controller.initialize()

        // Make edits
        controller.stageRemoteUrl("https://replacement.com/")
        controller.stageNavigationPolicy(mode = NavigationMode.ANY_HTTP_HOST)
        assertTrue(controller.state.value!!.isDirty)

        // Revert
        val reverted = controller.revertToActive()
        assertNotNull(reverted)
        assertFalse(reverted!!.isDirty)
        assertEquals(ContentSource.RemoteUrl("https://active-kiosk.com/"), reverted.stagedSource)
        assertEquals(NavigationMode.CONFIGURED_HOST_ONLY, reverted.stagedPolicy.mode)
    }

    @Test
    fun `applyStagedConfiguration atomically commits changes to repository`() = runBlocking {
        controller.initialize()

        controller.stageRemoteUrl("https://production.kiosk.com/")
        controller.stageNavigationPolicy(mode = NavigationMode.ALLOWLIST_ONLY)

        val result = controller.applyStagedConfiguration()
        assertTrue(result.isSuccess)

        val updated = fakeConfigRepo.getConfiguration()
        assertTrue(updated.isSetupCompleted)
        assertEquals(ContentSource.RemoteUrl("https://production.kiosk.com/"), updated.contentSource)
        assertEquals(NavigationMode.ALLOWLIST_ONLY, updated.navigationPolicy.mode)

        assertFalse(controller.state.value!!.isDirty)
        assertNull(controller.state.value!!.lastError)
    }

    @Test
    fun `applyStagedConfiguration rejects empty content source`() = runBlocking {
        controller.initialize() // contentSource is None

        val result = controller.applyStagedConfiguration()
        assertFalse(result.isSuccess)
    }

    @Test
    fun `reportRendererFailure records error and cancels previewing`() = runBlocking {
        controller.initialize()
        controller.setPreviewing(true)
        assertTrue(controller.state.value!!.isPreviewing)

        controller.reportRendererFailure("MainFrameError: ERR_CONNECTION_REFUSED")
        assertFalse(controller.state.value!!.isPreviewing)
        assertEquals("MainFrameError: ERR_CONNECTION_REFUSED", controller.state.value!!.lastError)
    }

    // =========================================================================
    // Test Fakes
    // =========================================================================

    private class FakeConfigRepository : ConfigurationRepository {
        private var config = KioskConfiguration()
        private val state = MutableStateFlow(config)

        fun setConfig(newConfig: KioskConfiguration) {
            config = newConfig
            state.value = config
        }

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
}
