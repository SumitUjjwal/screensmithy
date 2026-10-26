package com.screensmithy.app.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.screensmithy.app.domain.auth.HashedPin
import com.screensmithy.app.domain.auth.PinSecurity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAdminAuthRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var testFile: File
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreAdminAuthRepository

    @Before
    fun setUp() {
        testFile = tempFolder.newFile("test_admin_auth.preferences_pb")
        testDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { testFile }
        )
        repository = DataStoreAdminAuthRepository(testDataStore)
    }

    @Test
    fun `getHashedPin returns null when unconfigured`() = testScope.runTest {
        assertNull(repository.getHashedPin())
        assertFalse(repository.isPinConfigured())
    }

    @Test
    fun `savePin persists HashedPin and resets penalties`() = testScope.runTest {
        val hashed = PinSecurity.hashPin("1234".toCharArray())
        repository.recordFailedAttempt(1000L) // Record an initial attempt

        repository.savePin(hashed)

        val retrieved = repository.getHashedPin()
        assertNotNull(retrieved)
        assertEquals(hashed.hashHex, retrieved!!.hashHex)
        assertEquals(hashed.saltHex, retrieved.saltHex)
        assertEquals(hashed.iterations, retrieved.iterations)
        assertTrue(repository.isPinConfigured())

        // Saving a PIN must reset previous failed attempts
        assertEquals(0, repository.getFailedAttempts())
        val lockout = repository.getLockoutState(1000L)
        assertFalse(lockout.isLockedOut)
    }

    @Test
    fun `clearPin removes credentials and resets penalties`() = testScope.runTest {
        val hashed = PinSecurity.hashPin("9876".toCharArray())
        repository.savePin(hashed)
        assertTrue(repository.isPinConfigured())

        repository.clearPin()

        assertNull(repository.getHashedPin())
        assertFalse(repository.isPinConfigured())
        assertEquals(0, repository.getFailedAttempts())
    }

    @Test
    fun `recordFailedAttempt increments count and enforces progressive lockout`() = testScope.runTest {
        val now = 10_000L

        // Attempt 1: no lockout
        val state1 = repository.recordFailedAttempt(now)
        assertEquals(1, state1.failedAttempts)
        assertFalse(state1.isLockedOut)

        // Attempt 2: no lockout
        val state2 = repository.recordFailedAttempt(now)
        assertEquals(2, state2.failedAttempts)
        assertFalse(state2.isLockedOut)

        // Attempt 3: 30s lockout
        val state3 = repository.recordFailedAttempt(now)
        assertEquals(3, state3.failedAttempts)
        assertTrue(state3.isLockedOut)
        assertEquals(30L, state3.remainingSeconds)

        // Check getLockoutState after 10 seconds: 20 seconds remaining
        val checkMid = repository.getLockoutState(now + 10_000L)
        assertTrue(checkMid.isLockedOut)
        assertEquals(20L, checkMid.remainingSeconds)

        // Check getLockoutState after 30 seconds: lockout expired
        val checkExpired = repository.getLockoutState(now + 30_001L)
        assertFalse(checkExpired.isLockedOut)
        assertEquals(0L, checkExpired.remainingSeconds)
    }

    @Test
    fun `resetFailedAttempts clears failure counter and lockout deadline`() = testScope.runTest {
        val now = 10_000L
        repository.recordFailedAttempt(now)
        repository.recordFailedAttempt(now)
        repository.recordFailedAttempt(now) // 3 attempts -> lockout

        assertTrue(repository.getLockoutState(now).isLockedOut)

        repository.resetFailedAttempts()

        val state = repository.getLockoutState(now)
        assertFalse(state.isLockedOut)
        assertEquals(0, state.failedAttempts)
    }

    @Test
    fun `lockout state persists across repository re-instantiation simulating process restart`() = testScope.runTest {
        val now = 50_000L
        // 3 failed attempts: locked out for 30s (until 80_000)
        repository.recordFailedAttempt(now)
        repository.recordFailedAttempt(now)
        repository.recordFailedAttempt(now)

        // Simulate app kill and recreation with a fresh repository instance reading the persisted store
        val recreatedRepo = DataStoreAdminAuthRepository(testDataStore)

        val stateAtRestart = recreatedRepo.getLockoutState(now + 5_000L)
        assertTrue("Lockout must survive process restart", stateAtRestart.isLockedOut)
        assertEquals(25L, stateAtRestart.remainingSeconds)
        assertEquals(3, stateAtRestart.failedAttempts)
    }
}
