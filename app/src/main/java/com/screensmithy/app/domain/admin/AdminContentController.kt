package com.screensmithy.app.domain.admin

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.repository.ConfigurationRepository
import com.screensmithy.app.domain.url.UrlValidationResult
import com.screensmithy.app.domain.url.UrlValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Result of content candidate validation.
 */
sealed interface CandidateValidationResult {
    data class Valid(
        val normalizedSource: ContentSource,
        val policy: NavigationPolicy,
        val warningMessage: String? = null
    ) : CandidateValidationResult

    data class Invalid(val errorMessage: String) : CandidateValidationResult
}

/**
 * Staged candidate configuration state during administration.
 */
data class StagedContentState(
    val activeConfiguration: KioskConfiguration,
    val stagedSource: ContentSource,
    val stagedPolicy: NavigationPolicy,
    val isPreviewing: Boolean = false,
    val lastError: String? = null,
    val isDirty: Boolean = false
)

/**
 * Controller providing transactional content updates, validation, live preview staging,
 * and conservative rollback rules for ScreenSmithy administration.
 */
class AdminContentController(
    private val repository: ConfigurationRepository,
    private val appFilesDir: File,
    private val localFileVerifier: (relativePath: String) -> Boolean = { relativePath ->
        File(appFilesDir, relativePath).let { it.exists() && it.isFile && it.length() > 0 }
    }
) {

    private val _state = MutableStateFlow<StagedContentState?>(null)
    val state: StateFlow<StagedContentState?> = _state.asStateFlow()

    /**
     * Initializes staging state from the active persisted configuration.
     */
    suspend fun initialize(): StagedContentState {
        val current = repository.getConfiguration()
        val initial = StagedContentState(
            activeConfiguration = current,
            stagedSource = current.contentSource,
            stagedPolicy = current.navigationPolicy,
            isPreviewing = false,
            lastError = null,
            isDirty = false
        )
        _state.value = initial
        return initial
    }

    /**
     * Stages a remote URL candidate for administration.
     */
    fun stageRemoteUrl(rawUrl: String): CandidateValidationResult {
        val currentState = _state.value ?: return CandidateValidationResult.Invalid("Controller not initialized")

        return when (val validation = UrlValidator.validate(rawUrl)) {
            is UrlValidationResult.Valid -> {
                val candidateSource = ContentSource.RemoteUrl(validation.normalizedUrl)
                val isDirty = candidateSource != currentState.activeConfiguration.contentSource

                _state.value = currentState.copy(
                    stagedSource = candidateSource,
                    lastError = null,
                    isDirty = isDirty || currentState.stagedPolicy != currentState.activeConfiguration.navigationPolicy
                )

                CandidateValidationResult.Valid(
                    normalizedSource = candidateSource,
                    policy = currentState.stagedPolicy,
                    warningMessage = validation.warningMessage
                )
            }
            is UrlValidationResult.Invalid -> {
                val errorMsg = validation.userMessage
                _state.value = currentState.copy(lastError = errorMsg)
                CandidateValidationResult.Invalid(errorMsg)
            }
        }
    }

    /**
     * Stages imported local HTML metadata candidate.
     */
    fun stageLocalHtml(metadata: LocalHtmlMetadata): CandidateValidationResult {
        val currentState = _state.value ?: return CandidateValidationResult.Invalid("Controller not initialized")

        if (!localFileVerifier(metadata.relativePath)) {
            val errorMsg = "Imported local HTML file could not be verified in app storage."
            _state.value = currentState.copy(lastError = errorMsg)
            return CandidateValidationResult.Invalid(errorMsg)
        }

        val candidateSource = ContentSource.LocalHtml(metadata)
        val isDirty = candidateSource != currentState.activeConfiguration.contentSource

        _state.value = currentState.copy(
            stagedSource = candidateSource,
            lastError = null,
            isDirty = isDirty || currentState.stagedPolicy != currentState.activeConfiguration.navigationPolicy
        )

        return CandidateValidationResult.Valid(
            normalizedSource = candidateSource,
            policy = currentState.stagedPolicy
        )
    }

    /**
     * Updates the staged navigation policy.
     */
    fun stageNavigationPolicy(
        mode: NavigationMode,
        approvedRedirectHosts: Set<String> = emptySet(),
        includeSubdomains: Boolean = false
    ) {
        val currentState = _state.value ?: return
        val newPolicy = NavigationPolicy(
            mode = mode,
            approvedRedirectHosts = approvedRedirectHosts,
            includeSubdomains = includeSubdomains
        )

        val isDirty = (currentState.stagedSource != currentState.activeConfiguration.contentSource) ||
                (newPolicy != currentState.activeConfiguration.navigationPolicy)

        _state.value = currentState.copy(
            stagedPolicy = newPolicy,
            isDirty = isDirty
        )
    }

    /**
     * Sets preview mode active or inactive.
     */
    fun setPreviewing(isPreviewing: Boolean) {
        val currentState = _state.value ?: return
        _state.value = currentState.copy(isPreviewing = isPreviewing)
    }

    /**
     * Reports a renderer failure during candidate preview or activation.
     * Prevents committing broken content.
     */
    fun reportRendererFailure(errorDescription: String) {
        val currentState = _state.value ?: return
        _state.value = currentState.copy(
            lastError = errorDescription,
            isPreviewing = false
        )
    }

    /**
     * Atomically commits the staged candidate configuration to persistent storage.
     * Keeps previous configuration if persistence fails.
     */
    suspend fun applyStagedConfiguration(): Result<KioskConfiguration> {
        val currentState = _state.value ?: return Result.failure(IllegalStateException("Controller not initialized"))

        // Conservative activation rule: staged source must not be None and must validate
        val source = currentState.stagedSource
        if (source is ContentSource.None) {
            return Result.failure(IllegalStateException("Cannot apply empty content source"))
        }

        if (source is ContentSource.RemoteUrl) {
            val valResult = UrlValidator.validate(source.url)
            if (valResult is UrlValidationResult.Invalid) {
                return Result.failure(IllegalArgumentException(valResult.userMessage))
            }
        } else if (source is ContentSource.LocalHtml) {
            if (!localFileVerifier(source.metadata.relativePath)) {
                return Result.failure(IllegalArgumentException("Referenced local HTML file missing or invalid"))
            }
        }

        val updateResult = repository.updateConfiguration { current ->
            current.copy(
                isSetupCompleted = true,
                contentSource = currentState.stagedSource,
                navigationPolicy = currentState.stagedPolicy
            )
        }

        if (updateResult.isSuccess) {
            val saved = updateResult.getOrThrow()
            _state.value = StagedContentState(
                activeConfiguration = saved,
                stagedSource = saved.contentSource,
                stagedPolicy = saved.navigationPolicy,
                isPreviewing = false,
                lastError = null,
                isDirty = false
            )
        } else {
            val error = updateResult.exceptionOrNull()?.message ?: "Failed to persist configuration"
            _state.value = currentState.copy(lastError = error)
        }

        return updateResult
    }

    /**
     * Reverts staging state back to active configuration, safely discarding unapplied edits.
     */
    fun revertToActive(): StagedContentState? {
        val currentState = _state.value ?: return null
        val active = currentState.activeConfiguration
        val reverted = StagedContentState(
            activeConfiguration = active,
            stagedSource = active.contentSource,
            stagedPolicy = active.navigationPolicy,
            isPreviewing = false,
            lastError = null,
            isDirty = false
        )
        _state.value = reverted
        return reverted
    }
}
