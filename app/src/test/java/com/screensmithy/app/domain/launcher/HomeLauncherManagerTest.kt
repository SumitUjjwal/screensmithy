package com.screensmithy.app.domain.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import com.screensmithy.app.domain.diagnostics.DefaultLauncherStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeLauncherManagerTest {

    private lateinit var packageManager: PackageManager
    private val testPackageName = "com.screensmithy.app"
    private lateinit var launcherManager: HomeLauncherManager

    @Before
    fun setUp() {
        packageManager = mockk(relaxed = true)
        launcherManager = HomeLauncherManager(
            packageManager = packageManager,
            packageName = testPackageName,
            intentFactory = { action ->
                mockk<Intent>(relaxed = true).apply {
                    every { this@apply.action } returns action
                }
            }
        )
    }

    @Test
    fun `resolveDefaultHomeLauncher returns Yes when ScreenSmithy is default home`() {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = testPackageName
            }
        }
        every {
            packageManager.resolveActivity(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } returns resolveInfo

        val status = launcherManager.resolveDefaultHomeLauncher()
        assertEquals(DefaultLauncherStatus.Yes, status)
    }

    @Test
    fun `resolveDefaultHomeLauncher returns No with package name when another launcher is default`() {
        val otherPackage = "com.google.android.apps.tv.launcherx"
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = otherPackage
            }
        }
        every {
            packageManager.resolveActivity(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } returns resolveInfo

        val status = launcherManager.resolveDefaultHomeLauncher()
        assertTrue(status is DefaultLauncherStatus.No)
        assertEquals(otherPackage, (status as DefaultLauncherStatus.No).currentDefaultPackage)
    }

    @Test
    fun `resolveDefaultHomeLauncher returns No with null package when system resolver is returned`() {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "android"
            }
        }
        every {
            packageManager.resolveActivity(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } returns resolveInfo

        val status = launcherManager.resolveDefaultHomeLauncher()
        assertTrue(status is DefaultLauncherStatus.No)
        assertEquals(null, (status as DefaultLauncherStatus.No).currentDefaultPackage)
    }

    @Test
    fun `resolveDefaultHomeLauncher returns Unknown when resolution returns null`() {
        every {
            packageManager.resolveActivity(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } returns null

        val status = launcherManager.resolveDefaultHomeLauncher()
        assertEquals(DefaultLauncherStatus.Unknown, status)
    }

    @Test
    fun `resolveDefaultHomeLauncher returns Unknown when packageManager throws exception`() {
        every {
            packageManager.resolveActivity(any(), PackageManager.MATCH_DEFAULT_ONLY)
        } throws RuntimeException("DeadSystemException")

        val status = launcherManager.resolveDefaultHomeLauncher()
        assertEquals(DefaultLauncherStatus.Unknown, status)
    }

    @Test
    fun `createHomeSettingsIntent returns ACTION_HOME_SETTINGS when resolvable`() {
        val manager = HomeLauncherManager(
            packageManager = packageManager,
            packageName = testPackageName,
            intentResolver = { intent -> intent.action == Settings.ACTION_HOME_SETTINGS },
            intentFactory = { action ->
                mockk<Intent>(relaxed = true).apply {
                    every { this@apply.action } returns action
                }
            }
        )

        val intent = manager.createHomeSettingsIntent()
        assertEquals(Settings.ACTION_HOME_SETTINGS, intent.action)
    }

    @Test
    fun `createHomeSettingsIntent falls back to ACTION_SETTINGS when home settings is not resolvable`() {
        val manager = HomeLauncherManager(
            packageManager = packageManager,
            packageName = testPackageName,
            intentResolver = { intent -> intent.action == Settings.ACTION_SETTINGS },
            intentFactory = { action ->
                mockk<Intent>(relaxed = true).apply {
                    every { this@apply.action } returns action
                }
            }
        )

        val intent = manager.createHomeSettingsIntent()
        assertEquals(Settings.ACTION_SETTINGS, intent.action)
    }

    @Test
    fun `createHomeSettingsIntent falls back to CATEGORY_HOME when no settings intents are resolvable`() {
        val manager = HomeLauncherManager(
            packageManager = packageManager,
            packageName = testPackageName,
            intentResolver = { false },
            intentFactory = { action ->
                mockk<Intent>(relaxed = true).apply {
                    every { this@apply.action } returns action
                }
            }
        )

        val intent = manager.createHomeSettingsIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
    }

    @Test
    fun `getLauncherGuidance returns appropriate instructions for all states`() {
        val guidanceYes = launcherManager.getLauncherGuidance(DefaultLauncherStatus.Yes)
        assertTrue(guidanceYes.contains("ScreenSmithy is configured as your default Home launcher"))

        val guidanceNo = launcherManager.getLauncherGuidance(DefaultLauncherStatus.No("com.tv.launcher"))
        assertTrue(guidanceNo.contains("com.tv.launcher"))
        assertTrue(guidanceNo.contains("Normal Mode"))

        val guidanceUnknown = launcherManager.getLauncherGuidance(DefaultLauncherStatus.Unknown)
        assertTrue(guidanceUnknown.contains("could not be determined"))
    }

    @Test
    fun `getNormalModeLimitations returns truthful non-speculative constraints`() {
        val limitations = launcherManager.getNormalModeLimitations()
        assertTrue(limitations.size >= 4)
        assertTrue(limitations.any { it.contains("Device Owner", ignoreCase = true) })
        assertTrue(limitations.any { it.contains("system settings", ignoreCase = true) })
        assertTrue(limitations.any { it.contains("OEM-branded", ignoreCase = true) })
        assertTrue(limitations.any { it.contains("user confirmation", ignoreCase = true) })
    }
}
