package com.screensmithy.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.DisplayPreferences
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.model.RetryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreConfigurationRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreConfigurationRepository

    @Before
    fun setUp() {
        val testFile = tempFolder.newFile("test_screensmithy_config.preferences_pb")
        testDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { testFile }
        )
        repository = DataStoreConfigurationRepository(testDataStore)
    }

    @Test
    fun getConfiguration_whenEmpty_returnsDefaultConfiguration() = testScope.runTest {
        val config = repository.getConfiguration()
        assertEquals(KioskConfiguration.DEFAULT, config)
    }

    @Test
    fun updateConfiguration_savesRemoteUrlContentSource() = testScope.runTest {
        val updateResult = repository.updateConfiguration { current ->
            current.copy(
                isSetupCompleted = true,
                contentSource = ContentSource.RemoteUrl("https://kiosk.company.lan/status"),
                navigationPolicy = NavigationPolicy(
                    mode = NavigationMode.ALLOWLIST_ONLY,
                    trustedHosts = setOf("kiosk.company.lan", "auth.company.lan"),
                    approvedRedirectHosts = setOf("idp.sso.lan"),
                    includeSubdomains = true
                ),
                retryPolicy = RetryPolicy(maxRetries = 5, initialBackoffSeconds = 10, maxBackoffSeconds = 120)
            )
        }

        assertTrue(updateResult.isSuccess)
        val saved = repository.getConfiguration()

        assertTrue(saved.isSetupCompleted)
        assertTrue(saved.contentSource is ContentSource.RemoteUrl)
        assertEquals("https://kiosk.company.lan/status", (saved.contentSource as ContentSource.RemoteUrl).url)
        assertEquals(NavigationMode.ALLOWLIST_ONLY, saved.navigationPolicy.mode)
        assertEquals(setOf("kiosk.company.lan", "auth.company.lan"), saved.navigationPolicy.trustedHosts)
        assertEquals(setOf("idp.sso.lan"), saved.navigationPolicy.approvedRedirectHosts)
        assertTrue(saved.navigationPolicy.includeSubdomains)
        assertEquals(5, saved.retryPolicy.maxRetries)
        assertEquals(10, saved.retryPolicy.initialBackoffSeconds)
        assertEquals(120, saved.retryPolicy.maxBackoffSeconds)
    }

    @Test
    fun updateConfiguration_savesLocalHtmlMetadata() = testScope.runTest {
        val metadata = LocalHtmlMetadata(
            documentId = "doc_883",
            displayName = "presentation.html",
            relativePath = "content/doc_883/index.html",
            sizeBytes = 4096,
            importedAtEpochMs = 1700000000000L,
            sha256Checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )

        val result = repository.updateConfiguration { current ->
            current.copy(
                isSetupCompleted = true,
                contentSource = ContentSource.LocalHtml(metadata)
            )
        }

        assertTrue(result.isSuccess)
        val saved = repository.getConfiguration()

        assertTrue(saved.contentSource is ContentSource.LocalHtml)
        val savedMeta = (saved.contentSource as ContentSource.LocalHtml).metadata
        assertEquals(metadata.documentId, savedMeta.documentId)
        assertEquals(metadata.displayName, savedMeta.displayName)
        assertEquals(metadata.relativePath, savedMeta.relativePath)
        assertEquals(metadata.sizeBytes, savedMeta.sizeBytes)
        assertEquals(metadata.importedAtEpochMs, savedMeta.importedAtEpochMs)
        assertEquals(metadata.sha256Checksum, savedMeta.sha256Checksum)
    }

    @Test
    fun updateConfiguration_rejectsInvalidUrl() = testScope.runTest {
        val result = repository.updateConfiguration { current ->
            current.copy(
                contentSource = ContentSource.RemoteUrl("ftp://invalid.host/kiosk")
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun resetConfiguration_restoresDefaultState() = testScope.runTest {
        repository.updateConfiguration { current ->
            current.copy(
                isSetupCompleted = true,
                contentSource = ContentSource.RemoteUrl("https://example.com")
            )
        }

        val resetResult = repository.resetConfiguration()
        assertTrue(resetResult.isSuccess)

        val current = repository.getConfiguration()
        assertFalse(current.isSetupCompleted)
        assertEquals(ContentSource.None, current.contentSource)
    }

    @Test
    fun configurationFlow_emitsUpdatesRealTime() = testScope.runTest {
        repository.configurationFlow.test {
            // First emission is default
            val initial = awaitItem()
            assertFalse(initial.isSetupCompleted)

            // Update
            repository.updateConfiguration { current ->
                current.copy(isSetupCompleted = true)
            }

            val updated = awaitItem()
            assertTrue(updated.isSetupCompleted)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getConfiguration_whenPreferencesCorrupted_fallsBackSafelyWithoutThrowing() = testScope.runTest {
        // Write corrupted / partial preferences directly
        testDataStore.edit { prefs ->
            prefs[DataStoreConfigurationRepository.KEY_SCHEMA_VERSION] = 1
            prefs[DataStoreConfigurationRepository.KEY_CONTENT_SOURCE_TYPE] = "LOCAL_HTML"
            // Intentionally omit mandatory fields: doc_id, display_name, relative_path, etc.
            prefs[DataStoreConfigurationRepository.KEY_NAV_MODE] = "INVALID_MODE_NAME"
            prefs[DataStoreConfigurationRepository.KEY_RETRY_MAX_RETRIES] = 999 // Corrupted bound
        }

        val recovered = repository.getConfiguration()
        // Content source should safely fall back to None instead of crashing
        assertEquals(ContentSource.None, recovered.contentSource)
        // Navigation mode should fall back to CONFIGURED_HOST_ONLY
        assertEquals(NavigationMode.CONFIGURED_HOST_ONLY, recovered.navigationPolicy.mode)
        // Retry policy should fall back to bounded defaults
        assertEquals(RetryPolicy.DEFAULT_MAX_RETRIES, recovered.retryPolicy.maxRetries)
    }

    @Test
    fun getConfiguration_whenSchemaVersionOlder_migratesCleanly() = testScope.runTest {
        testDataStore.edit { prefs ->
            prefs[DataStoreConfigurationRepository.KEY_SCHEMA_VERSION] = 0 // Older version
            prefs[DataStoreConfigurationRepository.KEY_SETUP_COMPLETED] = true
            prefs[DataStoreConfigurationRepository.KEY_CONTENT_SOURCE_TYPE] = "REMOTE_URL"
            prefs[DataStoreConfigurationRepository.KEY_REMOTE_URL] = "https://example.com"
        }

        val migrated = repository.getConfiguration()
        assertEquals(KioskConfiguration.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertTrue(migrated.isSetupCompleted)
    }

    @Test
    fun updateConfiguration_savesDisplayPreferencesAndReloadOnNetworkRestored() = testScope.runTest {
        val result = repository.updateConfiguration { current ->
            current.copy(
                displayPreferences = DisplayPreferences(
                    keepScreenAwake = false,
                    immersiveSticky = false
                ),
                reloadOnNetworkRestored = false
            )
        }

        assertTrue(result.isSuccess)
        val saved = repository.getConfiguration()
        assertFalse(saved.displayPreferences.keepScreenAwake)
        assertFalse(saved.displayPreferences.immersiveSticky)
        assertFalse(saved.reloadOnNetworkRestored)
    }
}
