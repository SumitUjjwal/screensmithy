package com.screensmithy.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayMarginsTest {

    @Test
    fun defaultMargins_hasZeroValuesAndIsZero() {
        val margins = DisplayMargins.ZERO
        assertEquals(0, margins.topPercent)
        assertEquals(0, margins.bottomPercent)
        assertEquals(0, margins.leftPercent)
        assertEquals(0, margins.rightPercent)
        assertTrue(margins.isZero)
    }

    @Test
    fun validIndependentMargins_accepted() {
        val margins = DisplayMargins(topPercent = 5, bottomPercent = 3, leftPercent = 2, rightPercent = 4)
        assertEquals(5, margins.topPercent)
        assertEquals(3, margins.bottomPercent)
        assertEquals(2, margins.leftPercent)
        assertEquals(4, margins.rightPercent)
        assertFalse(margins.isZero)
    }

    @Test
    fun maximumAllowedMargins_accepted() {
        val margins = DisplayMargins(topPercent = 20, bottomPercent = 20, leftPercent = 20, rightPercent = 20)
        assertEquals(20, margins.topPercent)
        assertEquals(20, margins.bottomPercent)
        assertEquals(20, margins.leftPercent)
        assertEquals(20, margins.rightPercent)
        assertFalse(margins.isZero)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeTopPercent() {
        DisplayMargins(topPercent = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeBottomPercent() {
        DisplayMargins(bottomPercent = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLeftPercent() {
        DisplayMargins(leftPercent = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeRightPercent() {
        DisplayMargins(rightPercent = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTopPercentExceedingMax() {
        DisplayMargins(topPercent = 21)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBottomPercentExceedingMax() {
        DisplayMargins(bottomPercent = 21)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLeftPercentExceedingMax() {
        DisplayMargins(leftPercent = 21)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRightPercentExceedingMax() {
        DisplayMargins(rightPercent = 21)
    }

    @Test
    fun combinedMarginsWithinLimit_accepted() {
        val margins = DisplayMargins(topPercent = 20, bottomPercent = 20, leftPercent = 15, rightPercent = 20)
        assertEquals(40, margins.topPercent + margins.bottomPercent)
        assertEquals(35, margins.leftPercent + margins.rightPercent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun combinedVerticalMarginsExceedingLimit_rejected() {
        // Even if individual percent <= 20%, combined cannot exceed 40%
        DisplayMargins(topPercent = 20, bottomPercent = 21)
    }

    @Test(expected = IllegalArgumentException::class)
    fun combinedHorizontalMarginsExceedingLimit_rejected() {
        DisplayMargins(leftPercent = 20, rightPercent = 21)
    }

    @Test
    fun displayPreferences_defaultsToZeroMargins() {
        val prefs = DisplayPreferences()
        assertTrue(prefs.displayMargins.isZero)
        assertEquals(DisplayMargins.ZERO, prefs.displayMargins)
    }
}
