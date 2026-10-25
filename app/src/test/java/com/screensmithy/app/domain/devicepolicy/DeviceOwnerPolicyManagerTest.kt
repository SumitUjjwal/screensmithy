package com.screensmithy.app.domain.devicepolicy

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.screensmithy.app.data.devicepolicy.AndroidDeviceOwnerPolicyManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceOwnerPolicyManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val dpm = mockk<DevicePolicyManager>(relaxed = true)
    private val am = mockk<ActivityManager>(relaxed = true)
    private val activity = mockk<Activity>(relaxed = true)
    private val adminComponent = ComponentName("com.screensmithy.app", "com.screensmithy.app.receiver.KioskDeviceAdminReceiver")
    private val packageName = "com.screensmithy.app"

    private lateinit var policyManager: AndroidDeviceOwnerPolicyManager

    @Before
    fun setup() {
        every { context.packageName } returns packageName
        policyManager = AndroidDeviceOwnerPolicyManager(
            context = context,
            dpmProvider = { dpm },
            amProvider = { am },
            adminComponent = adminComponent,
            targetPackageName = packageName
        )
    }

    @Test
    fun isDeviceOwner_returnsFalse_whenDpmReportsFalse() {
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        assertFalse(policyManager.isDeviceOwner)
    }

    @Test
    fun isDeviceOwner_returnsTrue_whenDpmReportsTrue() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        assertTrue(policyManager.isDeviceOwner)
    }

    @Test
    fun canEnterLockTask_returnsTrue_onlyWhenDeviceOwnerAndLockTaskPermitted() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        every { dpm.isLockTaskPermitted(packageName) } returns true
        assertTrue(policyManager.canEnterLockTask())
    }

    @Test
    fun canEnterLockTask_returnsFalse_whenDeviceOwnerFalse() {
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        every { dpm.isLockTaskPermitted(packageName) } returns true
        assertFalse(policyManager.canEnterLockTask())
    }

    @Test
    fun canEnterLockTask_returnsFalse_whenLockTaskPermittedFalse() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        every { dpm.isLockTaskPermitted(packageName) } returns false
        assertFalse(policyManager.canEnterLockTask())
    }

    @Test
    fun lockTaskStatus_returnsLocked_whenAmReportsLocked() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_LOCKED
        assertEquals(LockTaskStatus.Locked, policyManager.lockTaskStatus)
        assertTrue(policyManager.lockTaskStatus.isLocked)
    }

    @Test
    fun lockTaskStatus_returnsPinned_whenAmReportsPinned() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_PINNED
        assertEquals(LockTaskStatus.Pinned, policyManager.lockTaskStatus)
        assertFalse(policyManager.lockTaskStatus.isLocked)
    }

    @Test
    fun lockTaskStatus_returnsInactive_whenAmNoneAndEligible() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_NONE
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        every { dpm.isLockTaskPermitted(packageName) } returns true
        assertEquals(LockTaskStatus.Inactive, policyManager.lockTaskStatus)
    }

    @Test
    fun lockTaskStatus_returnsUnavailable_whenAmNoneAndNotEligible() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_NONE
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        val status = policyManager.lockTaskStatus
        assertTrue(status is LockTaskStatus.Unavailable)
    }

    @Test
    fun allowlistLockTaskPackages_returnsNotEligible_whenNotDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        val result = policyManager.allowlistLockTaskPackages(true)
        assertTrue(result is PolicyExecutionResult.NotEligible)
    }

    @Test
    fun allowlistLockTaskPackages_returnsSuccess_whenDeviceOwnerAndVerified() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        every { dpm.isLockTaskPermitted(packageName) } returns true

        val result = policyManager.allowlistLockTaskPackages(true)
        assertTrue(result is PolicyExecutionResult.Success)
        verify { dpm.setLockTaskPackages(adminComponent, arrayOf(packageName)) }
    }

    @Test
    fun allowlistLockTaskPackages_returnsFailure_whenVerificationMismatches() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        // Set to allow, but verify reports false
        every { dpm.isLockTaskPermitted(packageName) } returns false

        val result = policyManager.allowlistLockTaskPackages(true)
        assertTrue(result is PolicyExecutionResult.Failure)
    }

    @Test
    fun configureKioskLockTaskFeatures_returnsNotEligible_whenNotDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        val result = policyManager.configureKioskLockTaskFeatures(false)
        assertTrue(result is PolicyExecutionResult.NotEligible)
    }

    @Test
    fun configureKioskLockTaskFeatures_returnsSuccess_whenDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        val result = policyManager.configureKioskLockTaskFeatures(false)
        assertTrue(result is PolicyExecutionResult.Success)
    }

    @Test
    fun setKeyguardAndStatusBarDisabled_returnsNotEligible_whenNotDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        val result = policyManager.setKeyguardAndStatusBarDisabled(true)
        assertTrue(result is PolicyExecutionResult.NotEligible)
    }

    @Test
    fun setKeyguardAndStatusBarDisabled_returnsSuccess_whenDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        val result = policyManager.setKeyguardAndStatusBarDisabled(true)
        assertTrue(result is PolicyExecutionResult.Success)
        verify { dpm.setKeyguardDisabled(adminComponent, true) }
        verify { dpm.setStatusBarDisabled(adminComponent, true) }
    }

    @Test
    fun setPersistentPreferredHome_returnsNotEligible_whenNotDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        val result = policyManager.setPersistentPreferredHome(true)
        assertTrue(result is PolicyExecutionResult.NotEligible)
    }

    @Test
    fun setPersistentPreferredHome_registersActivity_whenEnabled() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        val result = policyManager.setPersistentPreferredHome(true)
        assertTrue(result is PolicyExecutionResult.Success)
        verify { dpm.addPersistentPreferredActivity(adminComponent, any(), any()) }
    }

    @Test
    fun setPersistentPreferredHome_clearsActivity_whenDisabled() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        val result = policyManager.setPersistentPreferredHome(false)
        assertTrue(result is PolicyExecutionResult.Success)
        verify { dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName) }
    }

    @Test
    fun applyManagedKioskPolicies_returnsNotEligible_whenNotDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns false
        val result = policyManager.applyManagedKioskPolicies()
        assertTrue(result is PolicyExecutionResult.NotEligible)
    }

    @Test
    fun applyManagedKioskPolicies_orchestratesAllPolicies_whenDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        every { dpm.isLockTaskPermitted(packageName) } returns true

        val result = policyManager.applyManagedKioskPolicies()
        assertTrue(result is PolicyExecutionResult.Success)
        verify { dpm.setLockTaskPackages(adminComponent, arrayOf(packageName)) }
        verify { dpm.setKeyguardDisabled(adminComponent, true) }
        verify { dpm.setStatusBarDisabled(adminComponent, true) }
        verify { dpm.addPersistentPreferredActivity(adminComponent, any(), any()) }
    }

    @Test
    fun clearManagedKioskPolicies_returnsSuccess_withoutRemovingDeviceOwner() {
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        every { dpm.isLockTaskPermitted(packageName) } returns false

        val result = policyManager.clearManagedKioskPolicies()
        assertTrue(result is PolicyExecutionResult.Success)
        verify { dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName) }
        verify { dpm.setKeyguardDisabled(adminComponent, false) }
        verify { dpm.setStatusBarDisabled(adminComponent, false) }
        verify { dpm.setLockTaskPackages(adminComponent, emptyArray()) }
    }

    @Test
    fun enterLockTask_returnsSuccessImmediately_whenAlreadyLocked() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_LOCKED
        val result = policyManager.enterLockTask(activity)
        assertTrue(result is PolicyExecutionResult.Success)
        verify(exactly = 0) { activity.startLockTask() }
    }

    @Test
    fun enterLockTask_returnsNotEligible_whenCannotEnterLockTask() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_NONE
        every { dpm.isDeviceOwnerApp(packageName) } returns false

        val result = policyManager.enterLockTask(activity)
        assertTrue(result is PolicyExecutionResult.NotEligible)
        verify(exactly = 0) { activity.startLockTask() }
    }

    @Test
    fun enterLockTask_startsLockTask_whenEligible() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_NONE
        every { dpm.isDeviceOwnerApp(packageName) } returns true
        every { dpm.isLockTaskPermitted(packageName) } returns true

        val result = policyManager.enterLockTask(activity)
        assertTrue(result is PolicyExecutionResult.Success)
        verify(exactly = 1) { activity.startLockTask() }
    }

    @Test
    fun exitLockTask_returnsSuccessImmediately_whenNotLocked() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_NONE
        val result = policyManager.exitLockTask(activity)
        assertTrue(result is PolicyExecutionResult.Success)
        verify(exactly = 0) { activity.stopLockTask() }
    }

    @Test
    fun exitLockTask_stopsLockTask_whenLocked() {
        every { am.lockTaskModeState } returns ActivityManager.LOCK_TASK_MODE_LOCKED
        val result = policyManager.exitLockTask(activity)
        assertTrue(result is PolicyExecutionResult.Success)
        verify(exactly = 1) { activity.stopLockTask() }
    }
}
