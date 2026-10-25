package com.screensmithy.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.screensmithy.app.data.auth.DataStoreAdminAuthRepository
import com.screensmithy.app.data.auth.adminAuthDataStore
import com.screensmithy.app.data.diagnostics.AndroidSystemDiagnosticInspector
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
import com.screensmithy.app.domain.diagnostics.BoundedDiagnosticLogger
import com.screensmithy.app.domain.diagnostics.DiagnosticLogger
import com.screensmithy.app.domain.diagnostics.DiagnosticReportExporter
import com.screensmithy.app.domain.diagnostics.LogCategory
import com.screensmithy.app.domain.diagnostics.LogLevel
import com.screensmithy.app.domain.diagnostics.SecretSanitizer
import com.screensmithy.app.domain.diagnostics.SystemDiagnosticInspector
import com.screensmithy.app.domain.importer.ImportResult
import com.screensmithy.app.domain.importer.LocalHtmlImporter
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.model.RuntimeStatus
import com.screensmithy.app.domain.renderer.RendererEvent
import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.domain.repository.ConfigurationRepository
import com.screensmithy.app.domain.state.ConfigurationError
import com.screensmithy.app.domain.state.StartupDecisionEngine
import com.screensmithy.app.domain.state.StartupState
import com.screensmithy.app.domain.wizard.SetupWizardManager
import com.screensmithy.app.domain.wizard.SetupWizardSnapshot
import com.screensmithy.app.data.devicepolicy.AndroidDeviceOwnerPolicyManager
import com.screensmithy.app.domain.devicepolicy.DeviceOwnerPolicyManager
import com.screensmithy.app.domain.devicepolicy.PolicyExecutionResult
import com.screensmithy.app.domain.recovery.ErrorClassifier
import com.screensmithy.app.domain.recovery.NonRetryableReason
import com.screensmithy.app.domain.recovery.RecoveryAction
import com.screensmithy.app.domain.recovery.StartupRecoveryCoordinator
import com.screensmithy.app.domain.wizard.WizardStep
import com.screensmithy.app.ui.admin.AdminContentDialog
import com.screensmithy.app.ui.admin.AdminDashboardDialog
import com.screensmithy.app.ui.auth.AdminPinDialog
import com.screensmithy.app.ui.auth.PinDialogMode
import android.app.AlertDialog
import com.screensmithy.app.ui.importer.HtmlDocumentPickerContract
import com.screensmithy.app.ui.transfer.WifiTransferDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private lateinit var policyManager: DeviceOwnerPolicyManager
    private lateinit var localHtmlImporter: LocalHtmlImporter
    private lateinit var contentController: AdminContentController
    private lateinit var wizardManager: SetupWizardManager
    private lateinit var sequenceDetector: RemoteSequenceDetector

    // Diagnostics & Supported Settings
    private lateinit var diagnosticLogger: DiagnosticLogger
    private lateinit var diagnosticInspector: SystemDiagnosticInspector
    private lateinit var diagnosticReportExporter: DiagnosticReportExporter
    private var currentRuntimeStatus: RuntimeStatus = RuntimeStatus()

    // File Pickers (Storage Access Framework)
    private lateinit var htmlPickerLauncher: ActivityResultLauncher<Unit>
    private lateinit var exportDiagnosticsLauncher: ActivityResultLauncher<String>

    // Network Connectivity Monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkDisconnected: Boolean = false

    // State Tracking
    private var isPreviewing: Boolean = false
    private var previewingSource: ContentSource? = null
    private var previewingPolicy: NavigationPolicy? = null
    private var activeAdminDialog: AdminContentDialog? = null
    private var activeDashboardDialog: AdminDashboardDialog? = null
    private var activeWifiTransferDialog: WifiTransferDialog? = null

    // Recovery & Bounded Retry Coordinator
    private lateinit var recoveryCoordinator: StartupRecoveryCoordinator
    private var retryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initServices()
        initFilePicker()
        setupNetworkMonitoring()
        setupBackNavigation()
        setupWizardUiListeners()
        setupPreviewControls()
        setupErrorCard()
        observeRenderer()
        observeConfigurationAndStartup()

        diagnosticLogger.log(
            LogLevel.INFO,
            LogCategory.STARTUP,
            "ScreenSmithy cold started"
        )
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
        policyManager = AndroidDeviceOwnerPolicyManager(this)
        recoveryCoordinator = StartupRecoveryCoordinator()

        diagnosticLogger = BoundedDiagnosticLogger()
        diagnosticInspector = AndroidSystemDiagnosticInspector(
            context = applicationContext,
            configRepository = configRepository,
            deviceManagementInfoProvider = deviceInfoProvider,
            runtimeStatusProvider = { currentRuntimeStatus }
        )
        diagnosticReportExporter = DiagnosticReportExporter()

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
            diagnosticLogger.log(
                LogLevel.INFO,
                LogCategory.AUTH,
                "Hidden remote sequence detected"
            )
            triggerAdminPinGate()
        }
    }

    private fun initFilePicker() {
        htmlPickerLauncher = registerForActivityResult(HtmlDocumentPickerContract()) { uri: Uri? ->
            if (uri != null) {
                handleHtmlDocumentPicked(uri)
            }
        }

        exportDiagnosticsLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val snapshot = diagnosticInspector.inspect()
                        val logs = diagnosticLogger.getRecentLogs()
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            diagnosticReportExporter.exportToStream(snapshot, logs, stream)
                        }
                        diagnosticLogger.log(
                            LogLevel.INFO,
                            LogCategory.CONFIG,
                            "Diagnostic report successfully exported to SAF URI"
                        )
                        Toast.makeText(this@MainActivity, "Diagnostic report exported successfully", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        diagnosticLogger.log(
                            LogLevel.ERROR,
                            LogCategory.CONFIG,
                            "Failed to export diagnostic report: ${e.message}"
                        )
                        Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                diagnosticLogger.log(
                    LogLevel.INFO,
                    LogCategory.NETWORK,
                    "Network connectivity established"
                )
                if (isNetworkDisconnected) {
                    isNetworkDisconnected = false
                    lifecycleScope.launch {
                        val config = configRepository.getConfiguration()
                        if (config.reloadOnNetworkRestored && config.isSetupCompleted && !isPreviewing) {
                            val action = recoveryCoordinator.onNetworkRestored()
                            if (action is RecoveryAction.ProceedWithLoad) {
                                retryJob?.cancel()
                                diagnosticLogger.log(
                                    LogLevel.INFO,
                                    LogCategory.NETWORK,
                                    "Network restored; triggering automatic recovery of live kiosk presentation"
                                )
                                binding.cardError.visibility = View.GONE
                                binding.kioskWebView.visibility = View.VISIBLE
                                binding.kioskWebView.loadContent(action.source, action.policy)
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                isNetworkDisconnected = true
                diagnosticLogger.log(
                    LogLevel.WARN,
                    LogCategory.NETWORK,
                    "Network connectivity lost"
                )
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            diagnosticLogger.log(
                LogLevel.WARN,
                LogCategory.NETWORK,
                "Could not register network callback: ${e.message}"
            )
        }
    }

    private fun requestHtmlDocumentPick() {
        if (HtmlDocumentPickerContract.isAvailable(this)) {
            htmlPickerLauncher.launch(Unit)
        } else {
            showNoDocumentPickerAvailableDialog()
        }
    }

    private fun showNoDocumentPickerAvailableDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Document Picker App Installed")
            .setMessage("Android TV does not include a built-in file manager or document picker application.\n\nYou can transfer HTML documents directly from another device (phone, laptop, or PC) over local Wi-Fi without installing any additional apps.")
            .setPositiveButton("Transfer over Wi-Fi") { _, _ ->
                openWifiTransferDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleLocalHtmlImported(metadata: LocalHtmlMetadata) {
        if (binding.includeSetupWizard.wizardRoot.visibility == View.VISIBLE) {
            wizardManager.setLocalHtml(metadata)
        }
        activeAdminDialog?.onLocalHtmlImported(metadata)
    }

    private fun handleHtmlDocumentPicked(uri: Uri) {
        lifecycleScope.launch {
            val result = localHtmlImporter.importDocument(uri.toString())
            when (result) {
                is ImportResult.Success -> {
                    handleLocalHtmlImported(result.metadata)
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
                    // In presentation mode:
                    // 1. If WebView has navigation history, navigate back within the content.
                    // 2. Otherwise, safely remain on the start page without exiting the app
                    //    or exposing any administrative controls.
                    if (binding.kioskWebView.canGoBack()) {
                        binding.kioskWebView.goBack()
                        diagnosticLogger.log(
                            LogLevel.INFO,
                            LogCategory.RENDERER,
                            "Navigated back in WebView history via remote Back key"
                        )
                    } else {
                        // Accidental Back on root start page: safely ignore to prevent exit or settings exposure
                    }
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

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (config.displayPreferences.immersiveSticky) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
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
                val action = recoveryCoordinator.evaluateLoad(config.contentSource, config.navigationPolicy)
                when (action) {
                    is RecoveryAction.ProceedWithLoad -> {
                        retryJob?.cancel()
                        binding.includeSetupWizard.wizardRoot.visibility = View.GONE
                        binding.cardError.visibility = View.GONE
                        binding.kioskWebView.visibility = View.VISIBLE

                        binding.kioskWebView.loadContent(
                            source = action.source,
                            policy = action.policy
                        )

                        checkAndEnterManagedLockTask()
                    }
                    is RecoveryAction.SkipDuplicateLoad -> {
                        diagnosticLogger.log(
                            LogLevel.INFO,
                            LogCategory.STARTUP,
                            "Idempotent startup: active presentation matches configuration; skipping duplicate load"
                        )
                        checkAndEnterManagedLockTask()
                    }
                    else -> Unit
                }
            }

            is StartupState.RecoverableConfigurationError -> {
                retryJob?.cancel()
                binding.includeSetupWizard.wizardRoot.visibility = View.GONE
                binding.kioskWebView.visibility = View.GONE
                binding.cardError.visibility = View.VISIBLE

                val classification = ErrorClassifier.classifyConfigurationError(state.error)
                binding.txtErrorTitle.text = "Configuration Error"
                binding.txtErrorDetails.text = classification.userMessage
                binding.txtErrorRecoveryStatus.visibility = View.GONE
                binding.btnErrorRetry.visibility = View.GONE
                binding.btnErrorOpenAdmin.requestFocus()
            }
        }
    }

    private fun checkAndEnterManagedLockTask() {
        if (policyManager.canEnterLockTask()) {
            val result = policyManager.enterLockTask(this)
            diagnosticLogger.log(
                if (result is PolicyExecutionResult.Success) LogLevel.INFO else LogLevel.WARN,
                LogCategory.KIOSK_POLICY,
                "Managed Lock Task mode on presentation: $result"
            )
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
        // Step 3: Content Selection
        wizard.btnWizardTypeUrl.setOnClickListener {
            selectWizardSourceType(WizardSourceType.REMOTE_URL)
        }
        wizard.btnWizardTypeLocal.setOnClickListener {
            selectWizardSourceType(WizardSourceType.LOCAL_HTML)
        }
        wizard.btnWizardTypeWifi.setOnClickListener {
            selectWizardSourceType(WizardSourceType.WIFI_TRANSFER)
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
            requestHtmlDocumentPick()
        }

        wizard.btnWizardStartWifiTransfer.setOnClickListener {
            openWifiTransferDialog()
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

    private enum class WizardSourceType {
        REMOTE_URL,
        LOCAL_HTML,
        WIFI_TRANSFER
    }

    private fun selectWizardSourceType(type: WizardSourceType) {
        val wizard = binding.includeSetupWizard
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        when (type) {
            WizardSourceType.REMOTE_URL -> {
                wizard.btnWizardTypeUrl.setTextColor(primary)
                wizard.btnWizardTypeLocal.setTextColor(secondary)
                wizard.btnWizardTypeWifi.setTextColor(secondary)
                wizard.wizardPanelUrl.visibility = View.VISIBLE
                wizard.wizardPanelLocal.visibility = View.GONE
                wizard.wizardPanelWifi.visibility = View.GONE
                wizard.editWizardUrl.requestFocus()
            }
            WizardSourceType.LOCAL_HTML -> {
                wizard.btnWizardTypeLocal.setTextColor(primary)
                wizard.btnWizardTypeUrl.setTextColor(secondary)
                wizard.btnWizardTypeWifi.setTextColor(secondary)
                wizard.wizardPanelLocal.visibility = View.VISIBLE
                wizard.wizardPanelUrl.visibility = View.GONE
                wizard.wizardPanelWifi.visibility = View.GONE
                wizard.btnWizardPickHtml.requestFocus()
            }
            WizardSourceType.WIFI_TRANSFER -> {
                wizard.btnWizardTypeWifi.setTextColor(primary)
                wizard.btnWizardTypeUrl.setTextColor(secondary)
                wizard.btnWizardTypeLocal.setTextColor(secondary)
                wizard.wizardPanelWifi.visibility = View.VISIBLE
                wizard.wizardPanelUrl.visibility = View.GONE
                wizard.wizardPanelLocal.visibility = View.GONE
                wizard.btnWizardStartWifiTransfer.requestFocus()
            }
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
        binding.btnConfirmPreview.setOnClickListener {
            confirmAndActivatePreview()
        }
        binding.btnExitPreview.setOnClickListener {
            exitPreview()
        }
    }

    private fun launchPreview(source: ContentSource, policy: NavigationPolicy) {
        previewingSource = source
        previewingPolicy = policy
        isPreviewing = true
        contentController.setPreviewing(true)

        // Dismiss active dialogs so the preview is visible
        activeWifiTransferDialog?.dismiss()
        activeAdminDialog?.dismiss()

        // Show WebView with Preview Bar overlay
        binding.includeSetupWizard.wizardRoot.visibility = View.GONE
        binding.cardError.visibility = View.GONE
        binding.kioskWebView.visibility = View.VISIBLE
        binding.previewBar.visibility = View.VISIBLE
        binding.btnConfirmPreview.requestFocus()

        val summary = when (source) {
            is ContentSource.RemoteUrl -> source.url
            is ContentSource.LocalHtml -> source.metadata.displayName
            is ContentSource.None -> "None"
        }
        binding.txtPreviewTitle.text = "👁 PREVIEW MODE — $summary"

        binding.kioskWebView.loadContent(source, policy)
    }

    private fun confirmAndActivatePreview() {
        val source = previewingSource ?: return
        val policy = previewingPolicy ?: NavigationPolicy.DEFAULT
        isPreviewing = false
        contentController.setPreviewing(false)
        binding.previewBar.visibility = View.GONE

        lifecycleScope.launch {
            if (source is ContentSource.LocalHtml) {
                handleLocalHtmlImported(source.metadata)
            }
            val result = configRepository.updateConfiguration { current ->
                current.copy(
                    contentSource = source,
                    navigationPolicy = policy,
                    isSetupCompleted = true
                )
            }
            val updatedConfig = result.getOrNull() ?: configRepository.getConfiguration()
            binding.kioskWebView.loadContent(source, updatedConfig.navigationPolicy)
            diagnosticLogger.log(
                LogLevel.INFO,
                LogCategory.CONFIG,
                "Activated new content source from preview confirmation: $source"
            )
        }
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
        binding.btnErrorRetry.setOnClickListener {
            retryJob?.cancel()
            lifecycleScope.launch {
                val config = configRepository.getConfiguration()
                recoveryCoordinator.evaluateLoad(config.contentSource, config.navigationPolicy, force = true)
                binding.cardError.visibility = View.GONE
                binding.kioskWebView.visibility = View.VISIBLE
                binding.kioskWebView.loadContent(config.contentSource, config.navigationPolicy)
            }
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
                    diagnosticLogger.log(
                        LogLevel.INFO,
                        LogCategory.AUTH,
                        "Admin authenticated via remote sequence"
                    )
                    openAdminDashboardDialog()
                },
                onPinSetupCompleted = {
                    diagnosticLogger.log(
                        LogLevel.INFO,
                        LogCategory.AUTH,
                        "Admin PIN setup completed"
                    )
                    openAdminDashboardDialog()
                },
                onCancelled = {
                    diagnosticLogger.log(
                        LogLevel.INFO,
                        LogCategory.AUTH,
                        "Admin authentication cancelled"
                    )
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

    private fun openAdminDashboardDialog() {
        val dialog = AdminDashboardDialog.newInstance(
            configRepository = configRepository,
            diagnosticInspector = diagnosticInspector,
            diagnosticLogger = diagnosticLogger,
            policyManager = policyManager,
            onReloadContentRequested = {
                lifecycleScope.launch {
                    val config = configRepository.getConfiguration()
                    binding.kioskWebView.loadContent(config.contentSource, config.navigationPolicy)
                }
            },
            onOpenContentAdminRequested = {
                openAdminContentDialog()
            },
            onChangePinRequested = {
                launchPinSetupDialog {
                    openAdminDashboardDialog()
                }
            },
            onExportDiagnosticsRequested = {
                exportDiagnosticsLauncher.launch("screensmithy-diagnostic-report-${System.currentTimeMillis()}.json")
            },
            onResumeKioskRequested = {
                // Focus resumes to presentation
            },
            onDismissed = {
                activeDashboardDialog = null
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

        activeDashboardDialog = dialog
        dialog.show(supportFragmentManager, AdminDashboardDialog.TAG)
    }

    private fun openAdminContentDialog() {
        lifecycleScope.launch {
            contentController.initialize()

            val dialog = AdminContentDialog.newInstance(
                contentController = contentController,
                deviceInfoProvider = deviceInfoProvider,
                onPickHtmlRequested = {
                    requestHtmlDocumentPick()
                },
                onWifiTransferRequested = {
                    openWifiTransferDialog()
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

    private fun openWifiTransferDialog() {
        val dialog = WifiTransferDialog.newInstance(
            importer = localHtmlImporter,
            onFileImported = { metadata ->
                handleLocalHtmlImported(metadata)
            },
            onPreviewRequested = { candidateSource, candidatePolicy ->
                launchPreview(candidateSource, candidatePolicy)
            },
            onActivationConfirmed = { candidateSource ->
                lifecycleScope.launch {
                    if (candidateSource is ContentSource.LocalHtml) {
                        handleLocalHtmlImported(candidateSource.metadata)
                    }
                    val result = configRepository.updateConfiguration { current ->
                        current.copy(
                            contentSource = candidateSource,
                            isSetupCompleted = true
                        )
                    }
                    val updatedConfig = result.getOrNull() ?: configRepository.getConfiguration()
                    binding.kioskWebView.loadContent(candidateSource, updatedConfig.navigationPolicy)
                    diagnosticLogger.log(
                        LogLevel.INFO,
                        LogCategory.CONFIG,
                        "Activated new content source from Wi-Fi transfer: $candidateSource"
                    )
                }
            },
            onDismissed = {
                activeWifiTransferDialog = null
                if (!isPreviewing) {
                    lifecycleScope.launch {
                        val config = configRepository.getConfiguration()
                        val state = startupEngine.evaluate(config)
                        handleStartupState(state, config)
                    }
                }
            }
        )

        activeWifiTransferDialog = dialog
        dialog.show(supportFragmentManager, WifiTransferDialog.TAG)
    }

    private fun observeRenderer() {
        lifecycleScope.launch {
            binding.kioskWebView.state.collectLatest { state ->
                when (state) {
                    is RendererState.Loading -> {
                        currentRuntimeStatus = currentRuntimeStatus.copy(
                            lastLoadAttemptEpochMs = System.currentTimeMillis()
                        )
                    }
                    is RendererState.Ready -> {
                        currentRuntimeStatus = currentRuntimeStatus.copy(
                            lastLoadSuccess = true,
                            lastErrorMessage = null
                        )
                        recoveryCoordinator.onLoadSuccess(state.url)
                        binding.cardError.visibility = View.GONE
                        diagnosticLogger.log(
                            LogLevel.INFO,
                            LogCategory.RENDERER,
                            "Content loaded successfully: ${SecretSanitizer.sanitizeUrl(state.url)}"
                        )
                    }
                    is RendererState.MainFrameError -> {
                        val sanitizedDesc = SecretSanitizer.sanitizeMessage(state.description)
                        currentRuntimeStatus = currentRuntimeStatus.copy(
                            lastLoadSuccess = false,
                            lastErrorMessage = "[${state.errorCode}] $sanitizedDesc"
                        )
                        diagnosticLogger.log(
                            LogLevel.ERROR,
                            LogCategory.RENDERER,
                            "Main frame load error [${state.errorCode}]: $sanitizedDesc"
                        )
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Preview Error [${state.errorCode}]: ${state.description}")
                        } else {
                            handleRendererFailure(state)
                        }
                    }
                    is RendererState.HttpError -> {
                        currentRuntimeStatus = currentRuntimeStatus.copy(
                            lastLoadSuccess = false,
                            lastErrorMessage = "HTTP ${state.statusCode}: ${state.reasonPhrase}"
                        )
                        diagnosticLogger.log(
                            LogLevel.WARN,
                            LogCategory.RENDERER,
                            "HTTP error [${state.statusCode}] on ${SecretSanitizer.sanitizeUrl(state.failingUrl)}"
                        )
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Preview HTTP Error [${state.statusCode}]")
                        } else {
                            handleRendererFailure(state)
                        }
                    }
                    is RendererState.TlsError -> {
                        currentRuntimeStatus = currentRuntimeStatus.copy(
                            lastLoadSuccess = false,
                            lastErrorMessage = "TLS Error: ${state.errorDescription}"
                        )
                        diagnosticLogger.log(
                            LogLevel.ERROR,
                            LogCategory.RENDERER,
                            "TLS Error: ${state.errorDescription}"
                        )
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Preview TLS Error: ${state.errorDescription}")
                        } else {
                            handleRendererFailure(state)
                        }
                    }
                    is RendererState.MissingLocalContent -> {
                        currentRuntimeStatus = currentRuntimeStatus.copy(
                            lastLoadSuccess = false,
                            lastErrorMessage = "Missing file: ${state.relativePath}"
                        )
                        diagnosticLogger.log(
                            LogLevel.ERROR,
                            LogCategory.RENDERER,
                            "Missing local file: ${state.relativePath}"
                        )
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Preview missing local file: ${state.relativePath}")
                        } else {
                            handleRendererFailure(state)
                        }
                    }
                    is RendererState.RendererCrash -> {
                        currentRuntimeStatus = currentRuntimeStatus.copy(
                            lastLoadSuccess = false,
                            lastErrorMessage = "Renderer process terminated"
                        )
                        diagnosticLogger.log(
                            LogLevel.ERROR,
                            LogCategory.RENDERER,
                            "Renderer process terminated (didCrash=${state.didCrash})"
                        )
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Renderer process terminated")
                        } else {
                            handleRendererFailure(state)
                        }
                    }
                    is RendererState.Idle -> Unit
                }
            }
        }

        lifecycleScope.launch {
            binding.kioskWebView.events.collect { event ->
                when (event) {
                    is RendererEvent.NavigationBlocked -> {
                        val sanitizedUrl = SecretSanitizer.sanitizeUrl(event.targetUrl)
                        diagnosticLogger.log(
                            LogLevel.WARN,
                            LogCategory.KIOSK_POLICY,
                            "Navigation blocked by policy: $sanitizedUrl (reason: ${event.reason})"
                        )
                        if (isPreviewing) {
                            contentController.reportRendererFailure("Navigation blocked by policy: $sanitizedUrl")
                        }
                    }
                    is RendererEvent.ExternalAppLaunchBlocked -> {
                        val sanitizedUrl = SecretSanitizer.sanitizeUrl(event.targetUrl)
                        diagnosticLogger.log(
                            LogLevel.WARN,
                            LogCategory.KIOSK_POLICY,
                            "External app launch blocked: $sanitizedUrl"
                        )
                    }
                    is RendererEvent.TitleReceived -> {
                        diagnosticLogger.log(
                            LogLevel.INFO,
                            LogCategory.RENDERER,
                            "Document title received: ${event.title}"
                        )
                    }
                }
            }
        }
    }

    private fun handleRendererFailure(state: RendererState) {
        retryJob?.cancel()
        lifecycleScope.launch {
            val config = configRepository.getConfiguration()
            val action = recoveryCoordinator.onLoadError(state, config.retryPolicy)
            when (action) {
                is RecoveryAction.ScheduleRetry -> {
                    binding.kioskWebView.visibility = View.GONE
                    binding.cardError.visibility = View.VISIBLE
                    binding.txtErrorTitle.text = "Connection Error"
                    binding.txtErrorDetails.text = action.reason
                    binding.txtErrorRecoveryStatus.visibility = View.VISIBLE
                    binding.txtErrorRecoveryStatus.text = "Retrying in ${action.delayMs / 1000}s (attempt ${action.attempt} of ${action.maxRetries})..."
                    binding.btnErrorRetry.visibility = View.VISIBLE
                    binding.btnErrorOpenAdmin.requestFocus()

                    retryJob = lifecycleScope.launch {
                        delay(action.delayMs)
                        binding.txtErrorRecoveryStatus.text = "Retrying..."
                        binding.kioskWebView.visibility = View.VISIBLE
                        binding.cardError.visibility = View.GONE
                        binding.kioskWebView.loadContent(action.source, action.policy)
                    }
                }

                is RecoveryAction.PermanentFailure -> {
                    binding.kioskWebView.visibility = View.GONE
                    binding.cardError.visibility = View.VISIBLE
                    binding.txtErrorTitle.text = if (action.nonRetryableReason == NonRetryableReason.TLS_FAILURE) {
                        "Security Certificate Error"
                    } else {
                        "Playback Error"
                    }
                    binding.txtErrorDetails.text = action.reason
                    binding.txtErrorRecoveryStatus.visibility = View.GONE
                    if (action.nonRetryableReason == NonRetryableReason.TLS_FAILURE ||
                        action.nonRetryableReason == NonRetryableReason.INVALID_CONFIGURATION
                    ) {
                        binding.btnErrorRetry.visibility = View.GONE
                    } else {
                        binding.btnErrorRetry.visibility = View.VISIBLE
                    }
                    binding.btnErrorOpenAdmin.requestFocus()
                }

                is RecoveryAction.RecreateRenderer -> {
                    diagnosticLogger.log(
                        LogLevel.WARN,
                        LogCategory.RENDERER,
                        "Recovering from renderer termination: detaching, recreating, and reloading WebView"
                    )
                    binding.kioskWebView.recoverFromRendererCrash(config.contentSource, config.navigationPolicy)
                }

                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.kioskWebView.resumeTimers()
        lifecycleScope.launch {
            val config = configRepository.getConfiguration()
            applyDisplayPreferences(config)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        diagnosticLogger.log(
            LogLevel.INFO,
            LogCategory.STARTUP,
            "MainActivity resumed via new intent (action=${intent?.action})"
        )
        lifecycleScope.launch {
            val config = configRepository.getConfiguration()
            applyDisplayPreferences(config)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            lifecycleScope.launch {
                val config = configRepository.getConfiguration()
                applyDisplayPreferences(config)
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
        retryJob?.cancel()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        binding.kioskWebView.destroyRenderer()
    }
}
