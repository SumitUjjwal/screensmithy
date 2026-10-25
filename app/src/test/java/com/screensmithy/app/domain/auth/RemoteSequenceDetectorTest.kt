package com.screensmithy.app.domain.auth

import android.view.KeyEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteSequenceDetectorTest {

    private var sequenceCompletedCount = 0
    private lateinit var detector: RemoteSequenceDetector

    @Before
    fun setUp() {
        sequenceCompletedCount = 0
        detector = RemoteSequenceDetector(
            onSequenceCompleted = { sequenceCompletedCount++ }
        )
    }

    @Test
    fun `full Konami sequence triggers onSequenceCompleted and consumes final key`() {
        var time = 1000L

        // UP
        assertFalse("Intermediate step 1 must not consume event", detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = time))
        assertEquals(1, detector.activeStep)
        time += 500

        // UP
        assertFalse(detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = time))
        assertEquals(2, detector.activeStep)
        time += 500

        // DOWN
        assertFalse(detector.onKey(KeyEvent.KEYCODE_DPAD_DOWN, currentTimeMs = time))
        assertEquals(3, detector.activeStep)
        time += 500

        // DOWN
        assertFalse(detector.onKey(KeyEvent.KEYCODE_DPAD_DOWN, currentTimeMs = time))
        assertEquals(4, detector.activeStep)
        time += 500

        // LEFT
        assertFalse(detector.onKey(KeyEvent.KEYCODE_DPAD_LEFT, currentTimeMs = time))
        assertEquals(5, detector.activeStep)
        time += 500

        // RIGHT
        assertFalse(detector.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, currentTimeMs = time))
        assertEquals(6, detector.activeStep)
        time += 500

        // LEFT
        assertFalse(detector.onKey(KeyEvent.KEYCODE_DPAD_LEFT, currentTimeMs = time))
        assertEquals(7, detector.activeStep)
        time += 500

        // RIGHT
        assertFalse(detector.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, currentTimeMs = time))
        assertEquals(8, detector.activeStep)
        time += 500

        // OK (KEYCODE_DPAD_CENTER)
        val consumed = detector.onKey(KeyEvent.KEYCODE_DPAD_CENTER, currentTimeMs = time)
        assertTrue("Final OK event must be consumed", consumed)
        assertEquals(1, sequenceCompletedCount)
        assertEquals("Detector must reset after sequence completion", 0, detector.activeStep)
    }

    @Test
    fun `onKeyEvent with mocked Android KeyEvent dispatches correctly`() {
        val event = mockk<KeyEvent>()
        every { event.action } returns KeyEvent.ACTION_DOWN
        every { event.keyCode } returns KeyEvent.KEYCODE_DPAD_UP
        every { event.repeatCount } returns 0

        assertFalse(detector.onKeyEvent(event, 1000L))
        assertEquals(1, detector.activeStep)
    }

    @Test
    fun `alternative OK keycode KEYCODE_ENTER completes sequence`() {
        var time = 1000L
        val keys = listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )

        keys.forEach { code ->
            detector.onKey(code, currentTimeMs = time)
            time += 200
        }

        val consumed = detector.onKey(KeyEvent.KEYCODE_ENTER, currentTimeMs = time)
        assertTrue("KEYCODE_ENTER must be accepted as OK", consumed)
        assertEquals(1, sequenceCompletedCount)
    }

    @Test
    fun `key repeat events are ignored and do not advance state machine`() {
        val time = 1000L

        // Primary press
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, repeatCount = 0, currentTimeMs = time)
        assertEquals(1, detector.activeStep)

        // Repeat events from held button must be ignored
        val consumed = detector.onKey(KeyEvent.KEYCODE_DPAD_UP, repeatCount = 1, currentTimeMs = time + 100)
        assertFalse(consumed)
        assertEquals("Repeat count > 0 must not advance step", 1, detector.activeStep)
    }

    @Test
    fun `key release ACTION_UP events are ignored`() {
        val time = 1000L
        val consumed = detector.onKey(KeyEvent.KEYCODE_DPAD_UP, action = KeyEvent.ACTION_UP, currentTimeMs = time)
        assertFalse(consumed)
        assertEquals(0, detector.activeStep)
    }

    @Test
    fun `sequence times out after 10 seconds and resets to step 0`() {
        val startTime = 1000L

        // Enter partial sequence: UP -> UP -> DOWN
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = startTime)
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = startTime + 1000)
        detector.onKey(KeyEvent.KEYCODE_DPAD_DOWN, currentTimeMs = startTime + 2000)
        assertEquals(3, detector.activeStep)

        // Next key pressed after 10 seconds (> 10,000ms elapsed)
        val timeoutTime = startTime + 10_001L
        // Pressed RIGHT instead of DOWN (timeout should reset before evaluation)
        detector.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, currentTimeMs = timeoutTime)
        assertEquals("Detector must reset after 10-second timeout", 0, detector.activeStep)
    }

    @Test
    fun `mismatched key resets sequence`() {
        val time = 1000L

        // UP -> UP
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = time)
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = time + 100)
        assertEquals(2, detector.activeStep)

        // Wrong key: LEFT instead of DOWN
        detector.onKey(KeyEvent.KEYCODE_DPAD_LEFT, currentTimeMs = time + 200)
        assertEquals("Mismatched key must reset sequence to 0", 0, detector.activeStep)
        assertEquals(0, sequenceCompletedCount)
    }

    @Test
    fun `mismatched key that is UP immediately starts new sequence at step 1`() {
        val time = 1000L

        // UP -> UP -> DOWN
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = time)
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = time + 100)
        detector.onKey(KeyEvent.KEYCODE_DPAD_DOWN, currentTimeMs = time + 200)
        assertEquals(3, detector.activeStep)

        // Mismatched key is UP (instead of second DOWN)
        // It resets but recognizes UP as step 1 of a new attempt
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = time + 300)
        assertEquals(1, detector.activeStep)
    }

    @Test
    fun `explicit reset clears active step`() {
        detector.onKey(KeyEvent.KEYCODE_DPAD_UP, currentTimeMs = 1000L)
        assertTrue(detector.isSequenceActive)

        detector.reset()

        assertFalse(detector.isSequenceActive)
        assertEquals(0, detector.activeStep)
    }
}
