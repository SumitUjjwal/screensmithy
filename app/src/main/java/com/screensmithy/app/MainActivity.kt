package com.screensmithy.app

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.screensmithy.app.data.auth.DataStoreAdminAuthRepository
import com.screensmithy.app.data.auth.adminAuthDataStore
import com.screensmithy.app.data.importer.AndroidContentStreamResolver
import com.screensmithy.app.data.repository.DataStoreConfigurationRepository
import com.screensmithy.app.data.repository.configurationDataStore
import com.screensmithy.app.databinding.ActivityMainBinding
import com.screensmithy.app.domain.admin.AdminContentController
import com.screensmithy.app.domain.admin.DeviceManagementInfoProvider
import com.screensmithy.app.domain.admin.SystemDeviceManagementInfoProvider
import com.screensmithy.app.domain.auth.AdminAuthRepository
import com.screensmithy.app.domain.auth.AdminSessionManager
import com.screensmithy.app.domain.auth.RemoteSequenceDetector
import com.screensmithy.app.domain.importer.ImportResult
import com.screensmithy.app.domain.importer.LocalHtmlImporter
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.renderer.RendererEvent
import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.domain.repository.ConfigurationRepository
import com.screensmithy.app.domain.state.ConfigurationError
import com.screensmithy.app.domain.state.StartupDecisionEngine
import com.screensmithy.app.domain.state.StartupState
import com.screensmithy.app.domain.wizard.SetupWizardManager
import com.screensmithy.app.domain.wizard.SetupWizardSnapshot
import com.screensmithy.app.domain.wizard.WizardStep
import com.screensmithy.app.ui.admin.AdminContentDialog
import com.screensmithy.app.ui.auth.AdminPinDialog
import com.screensmithy.app.ui.auth.PinDialogMode
import com.screensmithy.app.ui.importer.HtmlDocumentPickerContract
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Main Activity of ScreenSmithy.
 * Orchestrates first-run setup wizard, full-screen presentation kiosk renderer,
 * hidden remote sequence detection, and authenticated administration.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Core Domain Services
    private lateinit var authRepository: AdminAuthRepository
    private lateinit var sessionManager: AdminSessionManager
    private lateinit var configRepository: ConfigurationRepository
    private lateinit var startupEngine: StartupDecisionEngine
    private lateinit var deviceInfoProvider: DeviceManagementInfoProvider
    private lateinit var localHtmlImporter: LocalHtmlImporter
    private lateinit var contentController: AdminContentController
    private lateinit var wizardManager: SetupWizardManager
    private lateinit var sequenceDetector: RemoteSequenceDetector

    // File Picker Launcher (Storage Access Framework)
    private lateinit var htmlPickerLauncher: ActivityResultLauncher<Unit>

    // State Tracking
    private var isPreviewing: Boolean = false
    private var activeAdminDialog: AdminContentDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initServices()
        initFilePicker()
        setupBackNavigation()
        setupWizardUiListeners()
        setupPreviewControls()
        setupErrorCard()
        observeRenderer()
        observeConfigurationAndStartup()
    }

    private fun initServices() {
        val filesDirectory = filesDir

        authRepository = DataStoreAdminAuthRepository(applicationContext.adminAuthDataStore)
        sessionManager = AdminSessionManager(authRepository)
        configRepository = DataStoreConfigurationRepository(applicationContext.configurationDataStore)

        startupEngine = StartupDecisionEngine(
            localFileVerifier = { relativePath ->
                File(filesDirectory, relativePath).let { it.exists() && it.isFile && it.length() > 0 }
            }
        )

        deviceInfoProvider = SystemDeviceManagementInfoProvider(this)

        localHtmlImporter = LocalHtmlImporter(
            appFilesDir = filesDirectory,
            streamResolver = AndroidContentStreamResolver(contentResolver)
        )

        contentController = AdminContentController(
            repository = configRepository,
            appFilesDir = filesDirectory
        )

        wizardManager = SetupWizardManager(
            configRepository = configRepository,
            authRepository = authRepository,
            localFileVerifier = { relativePath ->
                File(filesDirectory, relativePath).let { it.exists() && it.isFile && it.length() > 0 }
            }
        )

        sequenceDetector = RemoteSequenceDetector {
            triggerAdminPinGate()
        }
    }

    private fun initFilePicker() {
        htmlPickerLauncher = registerForActivityResult(HtmlDocumentPickerContract()) { uri: Uri? ->
            if (uri != null) {
                handleHtmlDocumentPicked(uri)
            }
        }
    }

    private fun handleHtmlDocumentPicked(uri: Uri) {
        lifecycleScope.launch {
            val result = localHtmlImporter.importDocument(uri.toString())
            when (result) {
                is ImportResult.Success -> {
                    val metadata = result.metadata
                    // If in wizard, update wizard state
                    if (binding.includeSetupWizard.wizardRoot.visibility == View.VISIBLE) {
                        wizardManager.setLocalHtml(metadata)
                    }
                    // If in admin dialog, inform dialog
                    activeAdminDialog?.onLocalHtmlImported(metadata)
                }
                is ImportResult.Failure -> {
                    val errorMsg = result.error.userMessage
                    binding.includeSetupWizard.txtWizardError.apply {
                        visibility = View.VISIBLE
                        text = errorMsg
                    }
                }
                is ImportResult.Cancelled -> Unit
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercept Android native input layer for hidden remote sequence
        // Intermediate keys return false (not consumed) to preserve webpage navigation.
        // Completing OK key returns true (consumed) to trigger the admin gate.
        if (sequenceDetector.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isPreviewing) {
                    exitPreview()
                } else if (binding.includeSetupWizard.wizardRoot.visibility == View.VISIBLE) {
                    val handled = wizardManager.previousStep()
                    if (!handled) {
                        // At first step of wizard; let system handle exit
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                } else {
                    // In presentation mode: do not exit on accidental back press
                    // (Managed kiosk or fullscreen presentation)
                }
            }
        })
    }

    private fun observeConfigurationAndStartup() {
        lifecycleScope.launch {
            configRepository.configurationFlow.collectLatest { config ->
                applyDisplayPreferences(config)
                val startupState = startupEngine.evaluate(config)

                if (!isPreviewing) {
                    handleStartupState(startupState, config)
                }
            }
        }

        lifecycleScope.launch {
            wizardManager.snapshot.collectLatest { snapshot ->
                renderWizardSnapshot(snapshot)
            }
        }
    }

    private fun applyDisplayPreferences(config: KioskConfiguration) {
        if (config.displayPreferences.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun handleStartupState(state: StartupState, config: KioskConfiguration) {
        when (state) {
            is StartupState.Initializing -> {
                // Show clean dark background while loading
                binding.includeSetupWizard.wizardRoot.visibility = View.GONE
                binding.kioskWebView.visibility = View.GONE
                binding.cardError.visibility = View.GONE
            }

            is StartupState.SetupRequired -> {
                // Show first-run setup wizard
                binding.kioskWebView.visibility = View.GONE
                binding.cardError.visibility = View.GONE
                binding.includeSetupWizard.wizardRoot.visibility = View.VISIBLE

                lifecycleScope.launch {
                    val currentSnapshot = wizardManager.snapshot.value
                    if (currentSnapshot.candidateSource is ContentSource.None &&
                        currentSnapshot.currentStep == WizardStep.WELCOME_AND_MODE
                    ) {
                        wizardManager.initialize()
                    }
                }
            }

            is StartupState.ReadyToPresent -> {
                // Fullscreen kiosk presentation
                // Note: NO admin buttons are displayed on this screen!
                binding.includeSetupWizard.wizardRoot.visibility = View.GONE
                binding.cardError.visibility = View.GONE
                binding.kioskWebView.visibility = View.VISIBLE

                binding.kioskWebView.loadContent(
                    source = config.contentSource,
                    policy = config.navigationPolicy
                )
            }

            is StartupState.RecoverableConfigurationError -> {
                binding.includeSetupWizard.wizardRoot.visibility = View.GONE
                binding.kioskWebView.visibility = View.GONE
                binding.cardError.visibility = View.VISIBLE

                binding.txtErrorDetails.text = when (val err = state.error) {
                    is ConfigurationError.InvalidUrl -> "Invalid URL: ${err.reason}"
                    is ConfigurationError.MissingLocalFile -> "Missing HTML Document: ${err.reason}"
                    is ConfigurationError.UnsupportedSchemaVersion -> "Unsupported schema version: ${err.foundVersion}"
                    is ConfigurationError.CorruptedData -> "Data corruption detected: ${err.message}"
                    is ConfigurationError.InvalidConfigurationParameter -> "Configuration error: ${err.parameterName} (${err.reason})"
                }
                binding.btnErrorOpenAdmin.requestFocus()
            }
        }
    }

    // =========================================================================
    // Setup Wizard UI Handling
    // =========================================================================

    private fun setupWizardUiListeners() {
        val wizard = binding.includeSetupWizard

        // Step 1: Mode & Capabilities
        val deviceInfo = deviceInfoProvider.getInfo()
        wizard.txtActualDeviceMode.text = deviceInfo.modeDescription
        wizard.txtDeviceCapabilitiesSummary.text = deviceInfo.capabilitiesSummary

        // Step 2: PIN Setup button
        wizard.btnWizardConfigurePin.setOnClickListener {
            launchPinSetupDialog {
                lifecycleScope.launch {
                    wizardManager.refreshPinStatus()
                }
            }
        }

        // Step 3: Content Selection
        wizard.btnWizardTypeUrl.setOnClickListener {
            selectWizardSourceType(isUrl = true)
        }
        wizard.btnWizardTypeLocal.setOnClickListener {
            selectWizardSourceType(isUrl = false)
        }

        wizard.btnWizardHelperHttps.setOnClickListener {
            val curr = wizard.editWizardUrl.text.toString()
            if (!curr.startsWith("https://", ignoreCase = true)) {
                wizard.editWizardUrl.setText("https://$curr")
                wizard.editWizardUrl.setSelection(wizard.editWizardUrl.text.length)
            }
        }
        wizard.btnWizardHelperDotCom.setOnClickListener {
            val curr = wizard.editWizardUrl.text.toString()
            if (!curr.endsWith(".com") && !curr.endsWith(".com/")) {
                wizard.editWizardUrl.setText("$curr.com/")
                wizard.editWizardUrl.setSelection(wizard.editWizardUrl.text.length)
            }
        }
        wizard.btnWizardHelperPreset.setOnClickListener {
            wizard.editWizardUrl.setText("https://example.com/")
            wizard.editWizardUrl.setSelection(wizard.editWizardUrl.text.length)
        }
        wizard.btnWizardHelperClear.setOnClickListener {
            wizard.editWizardUrl.setText("")
            wizard.editWizardUrl.requestFocus()
        }

        wizard.btnWizardPickHtml.setOnClickListener {
            htmlPickerLauncher.launch(Unit)
        }

        // Navigation Policy selection
        wizard.btnWizardPolicyConfigured.setOnClickListener {
            selectWizardPolicy(NavigationMode.CONFIGURED_HOST_ONLY)
        }
        wizard.btnWizardPolicyAllowlist.setOnClickListener {
            selectWizardPolicy(NavigationMode.ALLOWLIST_ONLY)
        }
        wizard.btnWizardPolicyAny.setOnClickListener {
            selectWizardPolicy(NavigationMode.ANY_HTTP_HOST)
        }

        // Step 4: Preview button
        wizard.btnWizardPreview.setOnClickListener {
            val snapshot = wizardManager.snapshot.value
            launchPreview(snapshot.candidateSource, snapshot.candidatePolicy)
        }

        // Wizard Navigation: Back & Next
        wizard.btnWizardBack.setOnClickListener {
            wizardManager.previousStep()
        }

        wizard.btnWizardNext.setOnClickListener {
            val step = wizardManager.snapshot.value.currentStep
            if (step == WizardStep.CONTENT_SELECTION) {
                // If on URL tab, stage URL before advancing
                if (wizard.wizardPanelUrl.visibility == View.VISIBLE) {
                    val rawUrl = wizard.editWizardUrl.text.toString().trim()
                    val valid = wizardManager.setRemoteUrl(rawUrl)
                    if (!valid) return@setOnClickListener
                }
            }

            if (step == WizardStep.PREVIEW_AND_CONFIRM) {
                // Final activation
                lifecycleScope.launch {
                    wizard.btnWizardNext.isEnabled = false
                    val result = wizardManager.completeSetup()
                    wizard.btnWizardNext.isEnabled = true
                    if (result.isFailure) {
                        wizard.txtWizardError.apply {
                            visibility = View.VISIBLE
                            text = result.exceptionOrNull()?.message ?: "Failed to activate configuration."
                        }
                    }
                }
            } else {
                lifecycleScope.launch {
                    wizardManager.nextStep()
                }
            }
        }
    }

    private fun selectWizardSourceType(isUrl: Boolean) {
        val wizard = binding.includeSetupWizard
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        if (isUrl) {
            wizard.btnWizardTypeUrl.setTextColor(primary)
            wizard.btnWizardTypeLocal.setTextColor(secondary)
            wizard.wizardPanelUrl.visibility = View.VISIBLE
            wizard.wizardPanelLocal.visibility = View.GONE
            wizard.editWizardUrl.requestFocus()
        } else {
            wizard.btnWizardTypeLocal.setTextColor(primary)
            wizard.btnWizardTypeUrl.setTextColor(secondary)
            wizard.wizardPanelLocal.visibility = View.VISIBLE
            wizard.wizardPanelUrl.visibility = View.GONE
            wizard.btnWizardPickHtml.requestFocus()
        }
    }

    private fun selectWizardPolicy(mode: NavigationMode) {
        val wizard = binding.includeSetupWizard
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        wizard.btnWizardPolicyConfigured.setTextColor(if (mode == NavigationMode.CONFIGURED_HOST_ONLY) primary else secondary)
        wizard.btnWizardPolicyAllowlist.setTextColor(if (mode == NavigationMode.ALLOWLIST_ONLY) primary else secondary)
        wizard.btnWizardPolicyAny.setTextColor(if (mode == NavigationMode.ANY_HTTP_HOST) primary else secondary)

        wizardManager.setNavigationPolicy(NavigationPolicy(mode = mode))
    }

    private fun renderWizardSnapshot(snapshot: SetupWizardSnapshot) {
        val wizard = binding.includeSetupWizard
        val activeBg = ContextCompat.getColor(this, R.color.focus_background)
        val inactiveBg = ContextCompat.getColor(this, R.color.surface_card)
        val primaryText = ContextCompat.getColor(this, R.color.text_primary)
        val secondaryText = ContextCompat.getColor(this, R.color.text_secondary)

        // Update pills
        wizard.stepPill1.setBackgroundColor(if (snapshot.currentStep == WizardStep.WELCOME_AND_MODE) activeBg else inactiveBg)
        wizard.stepPill1.setTextColor(if (snapshot.currentStep == WizardStep.WELCOME_AND_MODE) primaryText else secondaryText)

        wizard.stepPill2.setBackgroundColor(if (snapshot.currentStep == WizardStep.PIN_SETUP) activeBg else inactiveBg)
        wizard.stepPill2.setTextColor(if (snapshot.currentStep == WizardStep.PIN_SETUP) primaryText else secondaryText)

        wizard.stepPill3.setBackgroundColor(if (snapshot.currentStep == WizardStep.CONTENT_SELECTION) activeBg else inactiveBg)
        wizard.stepPill3.setTextColor(if (snapshot.currentStep == WizardStep.CONTENT_SELECTION) primaryText else secondaryText)

        wizard.stepPill4.setBackgroundColor(if (snapshot.currentStep == WizardStep.PREVIEW_AND_CONFIRM) activeBg else inactiveBg)
        wizard.stepPill4.setTextColor(if (snapshot.currentStep == WizardStep.PREVIEW_AND_CONFIRM) primaryText else secondaryText)

        // Show/hide containers
        wizard.containerStep1.visibility = if (snapshot.currentStep == WizardStep.WELCOME_AND_MODE) View.VISIBLE else View.GONE
        wizard.containerStep2.visibility = if (snapshot.currentStep == WizardStep.PIN_SETUP) View.VISIBLE else View.GONE
        wizard.containerStep3.visibility = if (snapshot.currentStep == WizardStep.CONTENT_SELECTION) View.VISIBLE else View.GONE
        wizard.containerStep4.visibility = if (snapshot.currentStep == WizardStep.PREVIEW_AND_CONFIRM) View.VISIBLE else View.GONE

        // Back button visibility
        wizard.btnWizardBack.visibility = if (snapshot.currentStep != WizardStep.WELCOME_AND_MODE) View.VISIBLE else View.GONE

        // Next button text
        wizard.btnWizardNext.text = if (snapshot.currentStep == WizardStep.PREVIEW_AND_CONFIRM) {
            "Activate Kiosk Presentation"
        } else {
            "Continue"
        }

        // PIN step specifics
        if (snapshot.isPinConfigured) {
            wizard.txtWizardPinStatus.text = "● Admin PIN Configured & Active"
            wizard.txtWizardPinStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            wizard.btnWizardConfigurePin.text = "Change Admin PIN"
        } else {
            wizard.txtWizardPinStatus.text = "● Admin PIN Not Configured"
            wizard.txtWizardPinStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
            wizard.btnWizardConfigurePin.text = "Configure Admin PIN"
        }

        // Summary step specifics
        when (val src = snapshot.candidateSource) {
            is ContentSource.RemoteUrl -> {
                wizard.txtSummaryContentSource.text = "Remote URL: ${src.url}"
            }
            is ContentSource.LocalHtml -> {
                wizard.txtSummaryContentSource.text = "Local HTML: ${src.metadata.displayName} (${src.metadata.sizeBytes / 1024} KB)"
            }
            is ContentSource.None -> {
                wizard.txtSummaryContentSource.text = "None Selected"
            }
        }
        wizard.txtSummaryPolicy.text = snapshot.candidatePolicy.mode.name

        // Error banner
        if (snapshot.validationError != null) {
            wizard.txtWizardError.apply {
                visibility = View.VISIBLE
                text = snapshot.validationError
            }
        } else {
            wizard.txtWizardError.visibility = View.GONE
        }

        // TV Focus management: focus on primary interactive element
        when (snapshot.currentStep) {
            WizardStep.WELCOME_AND_MODE -> wizard.btnWizardNext.requestFocus()
            WizardStep.PIN_SETUP -> {
                if (snapshot.isPinConfigured) wizard.btnWizardNext.requestFocus() else wizard.btnWizardConfigurePin.requestFocus()
            }
            WizardStep.CONTENT_SELECTION -> {
                if (wizard.wizardPanelUrl.visibility == View.VISIBLE) {
                    wizard.editWizardUrl.requestFocus()
                } else {
                    wizard.btnWizardPickHtml.requestFocus()
                }
            }
            WizardStep.PREVIEW_AND_CONFIRM -> wizard.btnWizardNext.requestFocus()
        }
    }

    // =========================================================================
    // Candidate Preview Controls & Rollback
    // =========================================================================

    private fun setupPreviewControls() {
        binding.btnExitPreview.setOnClickListener {
            exitPreview()
        }
    }

    private fun launchPreview(source: ContentSource, policy: NavigationPolicy) {
        isPreviewing = true
        contentController.setPreviewing(true)

        // Show WebView with Preview Bar overlay
        binding.includeSetupWizard.wizardRoot.visibility = View.GONE
        binding.cardError.visibility = View.GONE
        binding.kioskWebView.visibility = View.VISIBLE
        binding.previewBar.visibility = View.VISIBLE
        binding.btnExitPreview.requestFocus()

        val summary = when (source) {
            is ContentSource.RemoteUrl -> source.url
            is ContentSource.LocalHtml -> source.metadata.displayName
            is ContentSource.None -> "None"
        }
        binding.txtPreviewTitle.text = "👁 PREVIEW MODE — $summary"

        binding.kioskWebView.loadContent(source, policy)
    }

    private fun exitPreview() {
        isPreviewing = false
        contentController.setPreviewing(false)
        binding.previewBar.visibility = View.GONE

        lifecycleScope.launch {
            val config = configRepository.getConfiguration()
            if (!config.isSetupCompleted) {
                binding.kioskWebView.loadContent(ContentSource.None)
                binding.kioskWebView.visibility = View.GONE
                binding.cardError.visibility = View.GONE
                binding.includeSetupWizard.wizardRoot.visibility = View.VISIBLE
                binding.includeSetupWizard.btnWizardNext.requestFocus()
            } else {
                val state = startupEngine.evaluate(config)
                handleStartupState(state, config)
            }
        }
    }

    private fun setupErrorCard() {
        binding.btnErrorOpenAdmin.setOnClickListener {
            triggerAdminPinGate()
        }
    }

    // =========================================================================
    // Admin PIN Gate & Protected Administration Dialog
    // =========================================================================

    private fun triggerAdminPinGate() {
        lifecycleScope.launch {
            val isConfigured = authRepository.isPinConfigured()
            val mode = if (isConfigured) PinDialogMode.VERIFY else PinDialogMode.SETUP

            val pinDialog = AdminPinDialog.newInstance(
                mode = mode,
                authRepository = authRepository,
                sessionManager = sessionManager,
                onAuthenticated = {
                    openAdminContentDialog()
                },
                onPinSetupCompleted = {
                    openAdminContentDialog()
                },
                onCancelled = {
                    // Restores focus to active screen
                }
            )

            pinDialog.show(supportFragmentManager, AdminPinDialog.TAG)
        }
    }

    private fun launchPinSetupDialog(onSuccess: () -> Unit) {
        val pinDialog = AdminPinDialog.newInstance(
            mode = PinDialogMode.SETUP,
            authRepository = authRepository,
            sessionManager = sessionManager,
            onPinSetupCompleted = {
                onSuccess()
            }
        )
        pinDialog.show(supportFragmentManager, AdminPinDialog.TAG)
    }

    private fun openAdminContentDialog() {
        lifecycleScope.launch {
            contentController.initialize()

            val dialog = AdminContentDialog.newInstance(
                contentController = contentController,
                deviceInfoProvider = deviceInfoProvider,
                onPickHtmlRequested = {
                    htmlPickerLauncher.launch(Unit)
                },
                onPreviewRequested = { candidateSource, candidatePolicy ->
                    launchPreview(candidateSource, candidatePolicy)
                },
                onReloadActiveRequested = {
                    lifecycleScope.launch {
                        val config = configRepository.getConfiguration()
                        binding.kioskWebView.loadContent(config.contentSource, config.navigationPolicy)
                    }
                },
                onDismissed = {
                    activeAdminDialog = null
                    // If not previewing, refresh presentation state
                    if (!isPreviewing) {
                        lifecycleScope.launch {
                            val config = configRepository.getConfiguration()
                            val state = startupEngine.evaluate(config)
                            handleStartupState(state, config)
                        }
                    }
                }
            )

            activeAdminDialog = dialog
            dialog.show(supportFragmentManager, AdminContentDialog.TAG)
        }
    }

    private fun observeRenderer() {
        lifecycleScope.launch {
            binding.kioskWebView.state.collectLatest { state ->
                when (state) {
                    is RendererState.MainFrameError -> {
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Preview Error [${state.errorCode}]: ${state.description}")
                        }
                    }
                    is RendererState.TlsError -> {
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Preview TLS Error: ${state.errorDescription}")
                        }
                    }
                    else -> Unit
                }
            }
        }

        lifecycleScope.launch {
            binding.kioskWebView.events.collect { event ->
                when (event) {
                    is RendererEvent.NavigationBlocked -> {
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Navigation blocked by policy: ${event.targetUrl}")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sequenceDetector.reset()
        sessionManager.endSession()
        binding.kioskWebView.pauseTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.kioskWebView.destroyRenderer()
    }
}
