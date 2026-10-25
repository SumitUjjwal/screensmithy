package com.screensmithy.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.DisplayPreferences
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.model.RetryPolicy
import com.screensmithy.app.domain.repository.ConfigurationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.configurationDataStore: DataStore<Preferences> by preferencesDataStore(name = "screensmithy_configuration")

/**
 * Jetpack DataStore implementation of [ConfigurationRepository].
 *
 * Implements atomic persistence, transactional validation, safe error recovery,
 * and versioned schema migrations without exposing sensitive material.
 */
class DataStoreConfigurationRepository(
    private val dataStore: DataStore<Preferences>
) : ConfigurationRepository {

    override val configurationFlow: Flow<KioskConfiguration> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                // Recover safely from disk read/corruption errors by emitting empty preferences
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            mapPreferencesToConfiguration(preferences)
        }

    override suspend fun getConfiguration(): KioskConfiguration {
        return configurationFlow.first()
    }

    override suspend fun updateConfiguration(
        transform: (current: KioskConfiguration) -> KioskConfiguration
    ): Result<KioskConfiguration> = runCatching {
        var updatedConfig: KioskConfiguration? = null

        dataStore.edit { preferences ->
            val current = mapPreferencesToConfiguration(preferences)
            val next = transform(current)

            // Validate invariants before committing
            validateConfiguration(next)

            writeConfigurationToPreferences(next, preferences)
            updatedConfig = next
        }

        updatedConfig ?: throw IllegalStateException("Configuration update did not yield a valid state")
    }

    override suspend fun resetConfiguration(): Result<KioskConfiguration> = runCatching {
        dataStore.edit { preferences ->
            preferences.clear()
            writeConfigurationToPreferences(KioskConfiguration.DEFAULT, preferences)
        }
        KioskConfiguration.DEFAULT
    }

    private fun validateConfiguration(config: KioskConfiguration) {
        require(config.schemaVersion > 0) { "schemaVersion must be positive" }

        when (val source = config.contentSource) {
            is ContentSource.None -> {
                // Always valid
            }

            is ContentSource.RemoteUrl -> {
                val url = source.url.trim()
                require(url.isNotBlank()) { "Remote URL cannot be blank" }
                val isHttp = url.startsWith("http://", ignoreCase = true) ||
                        url.startsWith("https://", ignoreCase = true)
                require(isHttp) { "Remote URL must use http:// or https:// scheme" }
            }

            is ContentSource.LocalHtml -> {
                // LocalHtmlMetadata enforces internal validity on construction
                require(source.metadata.relativePath.isNotBlank()) {
                    "Local HTML relativePath must not be blank"
                }
            }
        }
    }

    private fun mapPreferencesToConfiguration(preferences: Preferences): KioskConfiguration {
        val storedVersion = preferences[KEY_SCHEMA_VERSION] ?: return KioskConfiguration.DEFAULT

        // Handle schema migration if stored version is older
        val effectiveVersion = if (storedVersion < KioskConfiguration.CURRENT_SCHEMA_VERSION) {
            migrateSchema(storedVersion, KioskConfiguration.CURRENT_SCHEMA_VERSION)
        } else {
            storedVersion
        }

        val isSetupCompleted = preferences[KEY_SETUP_COMPLETED] ?: false

        val contentSourceType = preferences[KEY_CONTENT_SOURCE_TYPE]
        val contentSource: ContentSource = when (contentSourceType) {
            TYPE_REMOTE_URL -> {
                val url = preferences[KEY_REMOTE_URL]
                if (!url.isNullOrBlank()) {
                    ContentSource.RemoteUrl(url)
                } else {
                    ContentSource.None
                }
            }

            TYPE_LOCAL_HTML -> {
                val docId = preferences[KEY_LOCAL_DOC_ID]
                val displayName = preferences[KEY_LOCAL_DISPLAY_NAME]
                val relativePath = preferences[KEY_LOCAL_RELATIVE_PATH]
                val sizeBytes = preferences[KEY_LOCAL_SIZE_BYTES]
                val importedAt = preferences[KEY_LOCAL_IMPORTED_AT]
                val checksum = preferences[KEY_LOCAL_CHECKSUM]

                if (docId != null && displayName != null && relativePath != null &&
                    sizeBytes != null && importedAt != null
                ) {
                    try {
                        ContentSource.LocalHtml(
                            LocalHtmlMetadata(
                                documentId = docId,
                                displayName = displayName,
                                relativePath = relativePath,
                                sizeBytes = sizeBytes,
                                importedAtEpochMs = importedAt,
                                sha256Checksum = checksum
                            )
                        )
                    } catch (_: Exception) {
                        // If stored metadata violates invariants, do not invent content
                        ContentSource.None
                    }
                } else {
                    ContentSource.None
                }
            }

            else -> ContentSource.None
        }

        val navModeStr = preferences[KEY_NAV_MODE]
        val navMode = try {
            if (navModeStr != null) NavigationMode.valueOf(navModeStr) else NavigationMode.CONFIGURED_HOST_ONLY
        } catch (_: Exception) {
            NavigationMode.CONFIGURED_HOST_ONLY
        }
        val trustedHosts = preferences[KEY_NAV_TRUSTED_HOSTS] ?: emptySet()
        val navigationPolicy = NavigationPolicy(mode = navMode, trustedHosts = trustedHosts)

        val keepAwake = preferences[KEY_DISPLAY_KEEP_AWAKE] ?: true
        val displayPreferences = DisplayPreferences(keepScreenAwake = keepAwake)

        val retryPolicy = try {
            RetryPolicy(
                maxRetries = preferences[KEY_RETRY_MAX_RETRIES] ?: RetryPolicy.DEFAULT_MAX_RETRIES,
                initialBackoffSeconds = preferences[KEY_RETRY_INITIAL_BACKOFF] ?: RetryPolicy.DEFAULT_INITIAL_BACKOFF_SECONDS,
                maxBackoffSeconds = preferences[KEY_RETRY_MAX_BACKOFF] ?: RetryPolicy.DEFAULT_MAX_BACKOFF_SECONDS
            )
        } catch (_: Exception) {
            RetryPolicy()
        }

        return KioskConfiguration(
            schemaVersion = effectiveVersion,
            isSetupCompleted = isSetupCompleted,
            contentSource = contentSource,
            navigationPolicy = navigationPolicy,
            displayPreferences = displayPreferences,
            retryPolicy = retryPolicy
        )
    }

    private fun writeConfigurationToPreferences(
        config: KioskConfiguration,
        preferences: MutablePreferences
    ) {
        preferences[KEY_SCHEMA_VERSION] = config.schemaVersion
        preferences[KEY_SETUP_COMPLETED] = config.isSetupCompleted

        when (val source = config.contentSource) {
            is ContentSource.None -> {
                preferences[KEY_CONTENT_SOURCE_TYPE] = TYPE_NONE
                preferences.remove(KEY_REMOTE_URL)
                clearLocalHtmlPreferences(preferences)
            }

            is ContentSource.RemoteUrl -> {
                preferences[KEY_CONTENT_SOURCE_TYPE] = TYPE_REMOTE_URL
                preferences[KEY_REMOTE_URL] = source.url
                clearLocalHtmlPreferences(preferences)
            }

            is ContentSource.LocalHtml -> {
                preferences[KEY_CONTENT_SOURCE_TYPE] = TYPE_LOCAL_HTML
                preferences.remove(KEY_REMOTE_URL)
                preferences[KEY_LOCAL_DOC_ID] = source.metadata.documentId
                preferences[KEY_LOCAL_DISPLAY_NAME] = source.metadata.displayName
                preferences[KEY_LOCAL_RELATIVE_PATH] = source.metadata.relativePath
                preferences[KEY_LOCAL_SIZE_BYTES] = source.metadata.sizeBytes
                preferences[KEY_LOCAL_IMPORTED_AT] = source.metadata.importedAtEpochMs
                if (source.metadata.sha256Checksum != null) {
                    preferences[KEY_LOCAL_CHECKSUM] = source.metadata.sha256Checksum
                } else {
                    preferences.remove(KEY_LOCAL_CHECKSUM)
                }
            }
        }

        preferences[KEY_NAV_MODE] = config.navigationPolicy.mode.name
        preferences[KEY_NAV_TRUSTED_HOSTS] = config.navigationPolicy.trustedHosts

        preferences[KEY_DISPLAY_KEEP_AWAKE] = config.displayPreferences.keepScreenAwake

        preferences[KEY_RETRY_MAX_RETRIES] = config.retryPolicy.maxRetries
        preferences[KEY_RETRY_INITIAL_BACKOFF] = config.retryPolicy.initialBackoffSeconds
        preferences[KEY_RETRY_MAX_BACKOFF] = config.retryPolicy.maxBackoffSeconds
    }

    private fun clearLocalHtmlPreferences(preferences: MutablePreferences) {
        preferences.remove(KEY_LOCAL_DOC_ID)
        preferences.remove(KEY_LOCAL_DISPLAY_NAME)
        preferences.remove(KEY_LOCAL_RELATIVE_PATH)
        preferences.remove(KEY_LOCAL_SIZE_BYTES)
        preferences.remove(KEY_LOCAL_IMPORTED_AT)
        preferences.remove(KEY_LOCAL_CHECKSUM)
    }

    private fun migrateSchema(fromVersion: Int, targetVersion: Int): Int {
        // Incremental migration steps for future schema version increases.
        // Currently on Schema Version 1.
        return targetVersion
    }

    companion object {
        private const val TYPE_NONE = "NONE"
        private const val TYPE_REMOTE_URL = "REMOTE_URL"
        private const val TYPE_LOCAL_HTML = "LOCAL_HTML"

        val KEY_SCHEMA_VERSION = intPreferencesKey("config_schema_version")
        val KEY_SETUP_COMPLETED = booleanPreferencesKey("config_setup_completed")
        val KEY_CONTENT_SOURCE_TYPE = stringPreferencesKey("config_content_source_type")
        val KEY_REMOTE_URL = stringPreferencesKey("config_remote_url")

        val KEY_LOCAL_DOC_ID = stringPreferencesKey("config_local_doc_id")
        val KEY_LOCAL_DISPLAY_NAME = stringPreferencesKey("config_local_display_name")
        val KEY_LOCAL_RELATIVE_PATH = stringPreferencesKey("config_local_relative_path")
        val KEY_LOCAL_SIZE_BYTES = longPreferencesKey("config_local_size_bytes")
        val KEY_LOCAL_IMPORTED_AT = longPreferencesKey("config_local_imported_at")
        val KEY_LOCAL_CHECKSUM = stringPreferencesKey("config_local_checksum")

        val KEY_NAV_MODE = stringPreferencesKey("config_nav_mode")
        val KEY_NAV_TRUSTED_HOSTS = stringSetPreferencesKey("config_nav_trusted_hosts")

        val KEY_DISPLAY_KEEP_AWAKE = booleanPreferencesKey("config_display_keep_awake")

        val KEY_RETRY_MAX_RETRIES = intPreferencesKey("config_retry_max_retries")
        val KEY_RETRY_INITIAL_BACKOFF = intPreferencesKey("config_retry_initial_backoff")
        val KEY_RETRY_MAX_BACKOFF = intPreferencesKey("config_retry_max_backoff")
    }
}
