package com.screensmithy.app.data.diagnostics

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.screensmithy.app.domain.admin.DeviceManagementInfo
import com.screensmithy.app.domain.admin.DeviceManagementInfoProvider
import com.screensmithy.app.domain.diagnostics.DefaultLauncherStatus
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.RuntimeStatus
import com.screensmithy.app.domain.repository.ConfigurationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidSystemDiagnosticInspectorTest {

    private val context = mockk<Context>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val configRepository = mockk<ConfigurationRepository>()
    private val deviceManagementInfoProvider = mockk<DeviceManagementInfoProvider>()

    private val fakeConfig = KioskConfiguration(
        isSetupCompleted = true,
        contentSource = ContentSource.RemoteUrl("https://kiosk.example.com/?token=secret123&page=1")
    )

    private val fakeRuntime = RuntimeStatus(
        lastLoadAttemptEpochMs = 1700000000000L,
        lastLoadSuccess = false,
        lastErrorMessage = "Connection failed Authorization: Bearer secret_token_xyz"
    )

    @Before
    fun setUp() {
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.screensmithy.app"
        every { configRepository.configurationFlow } returns flowOf(fakeConfig)
        coEvery { configRepository.getConfiguration() } returns fakeConfig
    }

    @Test
    fun inspect_standardMode_sanitizesUrlsAndErrors() = runTest {
        every { deviceManagementInfoProvider.getInfo() } returns DeviceManagementInfo(
            isDeviceOwner = false,
            isProfileOwner = false,
            isLockTaskPermitted = false,
            packageName = "com.screensmithy.app"
        )

        val inspector = AndroidSystemDiagnosticInspector(
            context = context,
            configRepository = configRepository,
            deviceManagementInfoProvider = deviceManagementInfoProvider,
            runtimeStatusProvider = { fakeRuntime }
        )

        val snapshot = inspector.inspect()

        assertEquals("Standard Normal Mode", snapshot.operatingMode)
        assertFalse(snapshot.isDeviceOwner)
        assertFalse(snapshot.isProfileOwner)
        assertEquals("https://kiosk.example.com/?token=REDACTED&page=1", snapshot.sanitizedContentIdentifier)
        assertEquals("Connection failed Authorization: REDACTED", snapshot.lastErrorMessage)
        assertEquals(fakeRuntime.lastLoadAttemptEpochMs, snapshot.lastLoadAttemptEpochMs)
        assertEquals(false, snapshot.lastLoadSuccess)
    }

    @Test
    fun inspect_deviceOwnerMode_reflectsDeviceOwner() = runTest {
        every { deviceManagementInfoProvider.getInfo() } returns DeviceManagementInfo(
            isDeviceOwner = true,
            isProfileOwner = false,
            isLockTaskPermitted = true,
            packageName = "com.screensmithy.app"
        )

        val inspector = AndroidSystemDiagnosticInspector(
            context = context,
            configRepository = configRepository,
            deviceManagementInfoProvider = deviceManagementInfoProvider,
            runtimeStatusProvider = { fakeRuntime }
        )

        val snapshot = inspector.inspect()

        assertEquals("Device Owner Mode", snapshot.operatingMode)
        assertTrue(snapshot.isDeviceOwner)
    }

    @Test
    fun inspect_whenScreenSmithyIsDefaultLauncher_reportsYes() = runTest {
        every { deviceManagementInfoProvider.getInfo() } returns DeviceManagementInfo(
            isDeviceOwner = false,
            isProfileOwner = false,
            isLockTaskPermitted = false,
            packageName = "com.screensmithy.app"
        )

        val inspector = AndroidSystemDiagnosticInspector(
            context = context,
            configRepository = configRepository,
            deviceManagementInfoProvider = deviceManagementInfoProvider,
            runtimeStatusProvider = { fakeRuntime },
            launcherChecker = { DefaultLauncherStatus.Yes }
        )

        val snapshot = inspector.inspect()
        assertEquals(DefaultLauncherStatus.Yes, snapshot.defaultHomeLauncher)
    }

    @Test
    fun inspect_whenOtherLauncherIsDefault_reportsNoWithPackageName() = runTest {
        every { deviceManagementInfoProvider.getInfo() } returns DeviceManagementInfo(
            isDeviceOwner = false,
            isProfileOwner = false,
            isLockTaskPermitted = false,
            packageName = "com.screensmithy.app"
        )

        val inspector = AndroidSystemDiagnosticInspector(
            context = context,
            configRepository = configRepository,
            deviceManagementInfoProvider = deviceManagementInfoProvider,
            runtimeStatusProvider = { fakeRuntime },
            launcherChecker = { DefaultLauncherStatus.No("com.google.android.apps.tv.launcherx") }
        )

        val snapshot = inspector.inspect()
        assertTrue(snapshot.defaultHomeLauncher is DefaultLauncherStatus.No)
        val noStatus = snapshot.defaultHomeLauncher as DefaultLauncherStatus.No
        assertEquals("com.google.android.apps.tv.launcherx", noStatus.currentDefaultPackage)
    }
}
