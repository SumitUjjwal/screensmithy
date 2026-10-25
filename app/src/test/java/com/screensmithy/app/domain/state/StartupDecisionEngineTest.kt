package com.screensmithy.app.domain.state

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDecisionEngineTest {

    private val engine = StartupDecisionEngine(
        localFileVerifier = { path -> path == "valid/existing.html" }
    )

    @Test
    fun evaluate_whenSetupIncomplete_returnsSetupRequiredFirstRun() {
        val config = KioskConfiguration.DEFAULT.copy(isSetupCompleted = false)
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.SetupRequired)
        assertEquals(SetupReason.FIRST_RUN, (state as StartupState.SetupRequired).reason)
    }

    @Test
    fun evaluate_whenSetupCompleteWithNoContent_returnsSetupRequiredNoContent() {
        val config = KioskConfiguration.DEFAULT.copy(
            isSetupCompleted = true,
            contentSource = ContentSource.None
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.SetupRequired)
        assertEquals(SetupReason.NO_CONTENT_CONFIGURED, (state as StartupState.SetupRequired).reason)
    }

    @Test
    fun evaluate_whenValidHttpsUrl_returnsReadyToPresent() {
        val config = KioskConfiguration.DEFAULT.copy(
            isSetupCompleted = true,
            contentSource = ContentSource.RemoteUrl("https://kiosk.example.com/display")
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.ReadyToPresent)
        assertEquals(config, (state as StartupState.ReadyToPresent).configuration)
    }

    @Test
    fun evaluate_whenValidHttpUrl_returnsReadyToPresent() {
        val config = KioskConfiguration.DEFAULT.copy(
            isSetupCompleted = true,
            contentSource = ContentSource.RemoteUrl("http://192.168.1.50:8080/dashboard")
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.ReadyToPresent)
    }

    @Test
    fun evaluate_whenBlankUrl_returnsRecoverableConfigurationError() {
        val config = KioskConfiguration.DEFAULT.copy(
            isSetupCompleted = true,
            contentSource = ContentSource.RemoteUrl("   ")
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.RecoverableConfigurationError)
        val error = (state as StartupState.RecoverableConfigurationError).error
        assertTrue(error is ConfigurationError.InvalidUrl)
    }

    @Test
    fun evaluate_whenUnsupportedSchemeUrl_returnsRecoverableConfigurationError() {
        val config = KioskConfiguration.DEFAULT.copy(
            isSetupCompleted = true,
            contentSource = ContentSource.RemoteUrl("javascript:alert(1)")
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.RecoverableConfigurationError)
        val error = (state as StartupState.RecoverableConfigurationError).error
        assertTrue(error is ConfigurationError.InvalidUrl)
    }

    @Test
    fun evaluate_whenExistingLocalHtml_returnsReadyToPresent() {
        val metadata = LocalHtmlMetadata(
            documentId = "doc_valid",
            displayName = "Kiosk.html",
            relativePath = "valid/existing.html",
            sizeBytes = 2048,
            importedAtEpochMs = 1000L
        )
        val config = KioskConfiguration.DEFAULT.copy(
            isSetupCompleted = true,
            contentSource = ContentSource.LocalHtml(metadata)
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.ReadyToPresent)
    }

    @Test
    fun evaluate_whenMissingLocalHtmlFile_returnsRecoverableConfigurationError() {
        val metadata = LocalHtmlMetadata(
            documentId = "doc_missing",
            displayName = "Kiosk.html",
            relativePath = "non_existent.html",
            sizeBytes = 2048,
            importedAtEpochMs = 1000L
        )
        val config = KioskConfiguration.DEFAULT.copy(
            isSetupCompleted = true,
            contentSource = ContentSource.LocalHtml(metadata)
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.RecoverableConfigurationError)
        val error = (state as StartupState.RecoverableConfigurationError).error
        assertTrue(error is ConfigurationError.MissingLocalFile)
    }

    @Test
    fun evaluate_whenSchemaVersionInFuture_returnsRecoverableError() {
        val config = KioskConfiguration.DEFAULT.copy(
            schemaVersion = KioskConfiguration.CURRENT_SCHEMA_VERSION + 1,
            isSetupCompleted = true
        )
        val state = engine.evaluate(config)

        assertTrue(state is StartupState.RecoverableConfigurationError)
        val error = (state as StartupState.RecoverableConfigurationError).error
        assertTrue(error is ConfigurationError.UnsupportedSchemaVersion)
        assertEquals(2, (error as ConfigurationError.UnsupportedSchemaVersion).foundVersion)
    }
}
