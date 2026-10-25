package com.screensmithy.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.screensmithy.app.data.auth.DataStoreAdminAuthRepository
import com.screensmithy.app.data.auth.adminAuthDataStore
import com.screensmithy.app.databinding.ActivityMainBinding
import com.screensmithy.app.domain.auth.AdminAuthRepository
import com.screensmithy.app.domain.auth.AdminSessionManager
import com.screensmithy.app.domain.auth.RemoteSequenceDetector
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.renderer.RendererEvent
import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.ui.auth.AdminPinDialog
import com.screensmithy.app.ui.auth.PinDialogMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foundation Activity & Development Harness for ScreenSmithy.
 * Confirms application launch, TV-friendly landscape display, remote D-pad input handling,
 * [com.screensmithy.app.ui.renderer.KioskWebView] renderer, and Phase 06 hidden remote sequence
 * / PIN authentication boundary.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var authRepository: AdminAuthRepository
    private lateinit var sessionManager: AdminSessionManager
    private lateinit var sequenceDetector: RemoteSequenceDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initSecurityComponents()
        setupDpadTest()
        setupRendererDemo()
        setupAdminGateButton()
        setupBackNavigation()
        observeRenderer()
    }

    private fun initSecurityComponents() {
        authRepository = DataStoreAdminAuthRepository(applicationContext.adminAuthDataStore)
        sessionManager = AdminSessionManager(authRepository)
        sequenceDetector = RemoteSequenceDetector {
            triggerAdminPinGate("Hidden Remote Sequence Completed")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Inspect Android native input layer for hidden remote sequence
        // Intermediate keys return false (not consumed) to preserve webpage navigation.
        // The final completing key returns true (consumed) to trigger the admin gate.
        if (sequenceDetector.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupDpadTest() {
        binding.btnTestDpad.setOnClickListener {
            binding.txtFeedback.apply {
                visibility = View.VISIBLE
                text = getString(R.string.dpad_feedback_pressed)
            }
        }

        // On TV devices, ensure the test button is focused by default
        binding.btnTestDpad.requestFocus()
    }

    private fun setupRendererDemo() {
        binding.btnDemoLocalHtml.setOnClickListener {
            val sampleMetadata = ensureSampleLocalHtmlExists()
            binding.kioskWebView.visibility = View.VISIBLE
            binding.btnCloseDemo.visibility = View.VISIBLE
            binding.btnCloseDemo.requestFocus()

            binding.kioskWebView.loadContent(
                source = ContentSource.LocalHtml(sampleMetadata),
                policy = NavigationPolicy.DEFAULT
            )
        }

        binding.btnDemoRemoteUrl.setOnClickListener {
            binding.kioskWebView.visibility = View.VISIBLE
            binding.btnCloseDemo.visibility = View.VISIBLE
            binding.btnCloseDemo.requestFocus()

            binding.kioskWebView.loadContent(
                source = ContentSource.RemoteUrl("https://example.com/"),
                policy = NavigationPolicy.DEFAULT
            )
        }

        binding.btnCloseDemo.setOnClickListener {
            closeDemo()
        }
    }

    private fun setupAdminGateButton() {
        binding.btnAdminGate.setOnClickListener {
            triggerAdminPinGate("Admin Button Pressed")
        }
    }

    private fun triggerAdminPinGate(triggerSource: String) {
        lifecycleScope.launch {
            val isConfigured = authRepository.isPinConfigured()
            val mode = if (isConfigured) PinDialogMode.VERIFY else PinDialogMode.SETUP

            val dialog = AdminPinDialog.newInstance(
                mode = mode,
                authRepository = authRepository,
                sessionManager = sessionManager,
                onAuthenticated = {
                    binding.txtFeedback.apply {
                        visibility = View.VISIBLE
                        text = "Authenticated! Admin Session Active (via $triggerSource)."
                    }
                },
                onPinSetupCompleted = {
                    binding.txtFeedback.apply {
                        visibility = View.VISIBLE
                        text = "Admin PIN setup completed! Active session initialized."
                    }
                },
                onCancelled = {
                    binding.txtFeedback.apply {
                        visibility = View.VISIBLE
                        text = "Admin authentication cancelled."
                    }
                }
            )

            dialog.show(supportFragmentManager, AdminPinDialog.TAG)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.kioskWebView.visibility == View.VISIBLE) {
                    closeDemo()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun closeDemo() {
        binding.kioskWebView.loadContent(ContentSource.None)
        binding.kioskWebView.visibility = View.GONE
        binding.btnCloseDemo.visibility = View.GONE
        binding.btnDemoLocalHtml.requestFocus()
    }

    private fun observeRenderer() {
        lifecycleScope.launch {
            binding.kioskWebView.state.collectLatest { state ->
                when (state) {
                    is RendererState.Ready -> {
                        binding.txtFeedback.apply {
                            visibility = View.VISIBLE
                            text = "Renderer Ready: ${state.url} (${state.title ?: "Untitled"})"
                        }
                    }
                    is RendererState.MainFrameError -> {
                        binding.txtFeedback.apply {
                            visibility = View.VISIBLE
                            text = "Renderer Error [${state.errorCode}]: ${state.description}"
                        }
                    }
                    is RendererState.TlsError -> {
                        binding.txtFeedback.apply {
                            visibility = View.VISIBLE
                            text = "TLS Error: ${state.errorDescription}"
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
                        binding.txtFeedback.apply {
                            visibility = View.VISIBLE
                            text = "Blocked Navigation: ${event.targetUrl}"
                        }
                    }
                    is RendererEvent.ExternalAppLaunchBlocked -> {
                        binding.txtFeedback.apply {
                            visibility = View.VISIBLE
                            text = "Blocked External App: ${event.targetUrl}"
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun ensureSampleLocalHtmlExists(): LocalHtmlMetadata {
        val storageDir = File(filesDir, "imported_html/sample_demo")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val file = File(storageDir, "index.html")
        if (!file.exists()) {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <title>ScreenSmithy Kiosk</title>
                  <style>
                    body {
                      background: #090d16;
                      color: #f1f5f9;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                      display: flex;
                      flex-direction: column;
                      align-items: center;
                      justify-content: center;
                      height: 100vh;
                      margin: 0;
                      text-align: center;
                    }
                    .badge {
                      background: #0284c7;
                      color: white;
                      padding: 6px 14px;
                      border-radius: 9999px;
                      font-size: 13px;
                      font-weight: 600;
                      letter-spacing: 0.5px;
                      margin-bottom: 16px;
                      display: inline-block;
                    }
                    h1 { font-size: 38px; margin: 0 0 12px 0; color: #38bdf8; }
                    p { font-size: 18px; color: #94a3b8; max-width: 600px; margin: 0 auto 24px auto; }
                    .card {
                      background: #1e293b;
                      border: 1px solid #334155;
                      border-radius: 16px;
                      padding: 36px 48px;
                      box-shadow: 0 20px 40px rgba(0,0,0,0.6);
                    }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="badge">SECURE LOCAL CONTENT RENDERER</div>
                    <h1>ScreenSmithy TV Display</h1>
                    <p>This self-contained HTML page is served securely via AndroidX WebViewAssetLoader from app-private storage. Direct file access is strictly blocked.</p>
                  </div>
                </body>
                </html>
            """.trimIndent()
            file.writeText(html)
        }

        return LocalHtmlMetadata(
            documentId = "sample_demo",
            displayName = "index.html",
            relativePath = "imported_html/sample_demo/index.html",
            sizeBytes = file.length(),
            importedAtEpochMs = System.currentTimeMillis()
        )
    }

    override fun onPause() {
        super.onPause()
        sequenceDetector.reset()
        sessionManager.endSession()
        binding.kioskWebView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        binding.kioskWebView.resumeTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.kioskWebView.destroyRenderer()
    }
}
