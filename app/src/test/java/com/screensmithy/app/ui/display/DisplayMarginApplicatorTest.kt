package com.screensmithy.app.ui.display

import com.screensmithy.app.domain.model.DisplayMargins
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayMarginApplicatorTest {

    @Test
    fun zeroMargins_producesZeroPixelInsets() {
        val insets = DisplayMarginApplicator.calculateMarginPixels(
            viewportWidth = 1920,
            viewportHeight = 1080,
            margins = DisplayMargins.ZERO
        )
        assertEquals(0, insets.left)
        assertEquals(0, insets.top)
        assertEquals(0, insets.right)
        assertEquals(0, insets.bottom)
    }

    @Test
    fun standard1080p_calculatesExactPixelInsets() {
        val margins = DisplayMargins(topPercent = 5, bottomPercent = 10, leftPercent = 5, rightPercent = 5)
        val insets = DisplayMarginApplicator.calculateMarginPixels(
            viewportWidth = 1920,
            viewportHeight = 1080,
            margins = margins
        )
        assertEquals(96, insets.left)
        assertEquals(54, insets.top)
        assertEquals(96, insets.right)
        assertEquals(108, insets.bottom)
    }

    @Test
    fun standard720p_calculatesExactPixelInsets() {
        val margins = DisplayMargins(topPercent = 5, bottomPercent = 5, leftPercent = 5, rightPercent = 5)
        val insets = DisplayMarginApplicator.calculateMarginPixels(
            viewportWidth = 1280,
            viewportHeight = 720,
            margins = margins
        )
        assertEquals(64, insets.left)
        assertEquals(36, insets.top)
        assertEquals(64, insets.right)
        assertEquals(36, insets.bottom)
    }

    @Test
    fun nonZeroDimensions_handlesZeroOrNegativeGracefully() {
        val margins = DisplayMargins(topPercent = 5, bottomPercent = 5, leftPercent = 5, rightPercent = 5)
        val insetsZero = DisplayMarginApplicator.calculateMarginPixels(0, 0, margins)
        assertEquals(0, insetsZero.left)
        assertEquals(0, insetsZero.top)

        val insetsNegative = DisplayMarginApplicator.calculateMarginPixels(-100, -100, margins)
        assertEquals(0, insetsNegative.left)
        assertEquals(0, insetsNegative.top)
    }

    @Test
    fun computeEffectiveInsets_preventsDoubleApplicationOfSystemInsets() {
        val marginPixels = DisplayMarginApplicator.InsetPixels(left = 96, top = 54, right = 96, bottom = 54)
        // System status bar is 48px, which is smaller than 54px margin
        val systemInsets = DisplayMarginApplicator.InsetPixels(left = 0, top = 48, right = 0, bottom = 0)

        val effective = DisplayMarginApplicator.computeEffectiveInsets(marginPixels, systemInsets, basePaddingPx = 0)
        // maxOf(48, 54) = 54 (margin covers status bar without double-adding to 102)
        assertEquals(54, effective.top)
        assertEquals(96, effective.left)
        assertEquals(96, effective.right)
        assertEquals(54, effective.bottom)
    }

    @Test
    fun computeEffectiveInsets_takesSystemInsetsWhenLarger() {
        val marginPixels = DisplayMarginApplicator.InsetPixels(left = 20, top = 20, right = 20, bottom = 20)
        // System status bar is 60px, which is larger than 20px margin
        val systemInsets = DisplayMarginApplicator.InsetPixels(left = 0, top = 60, right = 0, bottom = 0)

        val effective = DisplayMarginApplicator.computeEffectiveInsets(marginPixels, systemInsets, basePaddingPx = 0)
        // maxOf(60, 20) = 60
        assertEquals(60, effective.top)
        assertEquals(20, effective.left)
    }

    @Test
    fun computeEffectiveInsets_addsBasePaddingCleanly() {
        val marginPixels = DisplayMarginApplicator.InsetPixels(left = 50, top = 30, right = 50, bottom = 30)
        val systemInsets = DisplayMarginApplicator.InsetPixels(left = 0, top = 0, right = 0, bottom = 0)
        val basePaddingPx = 24

        val effective = DisplayMarginApplicator.computeEffectiveInsets(marginPixels, systemInsets, basePaddingPx = basePaddingPx)
        assertEquals(74, effective.left) // 24 + 50
        assertEquals(54, effective.top)  // 24 + 30
        assertEquals(74, effective.right)
        assertEquals(54, effective.bottom)
    }

    @Test
    fun asymmetricMargins_calculatesExactPixelInsets() {
        val margins = DisplayMargins(topPercent = 2, bottomPercent = 8, leftPercent = 4, rightPercent = 12)
        val insets = DisplayMarginApplicator.calculateMarginPixels(
            viewportWidth = 1920,
            viewportHeight = 1080,
            margins = margins
        )
        assertEquals(77, insets.left)   // 1920 * 0.04 = 76.8 -> 77
        assertEquals(22, insets.top)    // 1080 * 0.02 = 21.6 -> 22
        assertEquals(230, insets.right) // 1920 * 0.12 = 230.4 -> 230
        assertEquals(86, insets.bottom) // 1080 * 0.08 = 86.4 -> 86
    }

    @Test
    fun uhd4k_calculatesExactPixelInsets() {
        val margins = DisplayMargins(topPercent = 5, bottomPercent = 5, leftPercent = 5, rightPercent = 5)
        val insets = DisplayMarginApplicator.calculateMarginPixels(
            viewportWidth = 3840,
            viewportHeight = 2160,
            margins = margins
        )
        assertEquals(192, insets.left)
        assertEquals(108, insets.top)
        assertEquals(192, insets.right)
        assertEquals(108, insets.bottom)
    }

    @Test
    fun usableContentBounds_remainsPositiveEvenAtMaxMargins_on720p() {
        val maxMargins = DisplayMargins(topPercent = 20, bottomPercent = 20, leftPercent = 20, rightPercent = 20)
        val insets = DisplayMarginApplicator.calculateMarginPixels(1280, 720, maxMargins)
        val usableWidth = 1280 - insets.left - insets.right
        val usableHeight = 720 - insets.top - insets.bottom
        org.junit.Assert.assertTrue(usableWidth > 0)
        org.junit.Assert.assertTrue(usableHeight > 0)
        assertEquals(768, usableWidth)
        assertEquals(432, usableHeight)
    }
}
