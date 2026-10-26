package com.screensmithy.app.ui.display

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.screensmithy.app.domain.model.DisplayMargins
import kotlin.math.roundToInt

/**
 * Shared authoritative applicator for ScreenSmithy global display margins.
 *
 * Translates administrator-configured viewport percentage margins (0..20%) into
 * pixel insets, handling physical TV overscan, Android window insets, and base padding.
 *
 * Guarantees that margins are applied consistently across all application-owned screens
 * (kiosk presentation, wizard, error screens, and dialogs) without double-applying
 * system bar insets.
 */
object DisplayMarginApplicator {

    data class InsetPixels(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /**
     * Pure calculation of pixel insets from viewport dimensions and percentage margins.
     */
    fun calculateMarginPixels(
        viewportWidth: Int,
        viewportHeight: Int,
        margins: DisplayMargins
    ): InsetPixels {
        if (margins.isZero || viewportWidth <= 0 || viewportHeight <= 0) {
            return InsetPixels(0, 0, 0, 0)
        }

        val leftPx = ((viewportWidth * margins.leftPercent) / 100f).roundToInt()
        val topPx = ((viewportHeight * margins.topPercent) / 100f).roundToInt()
        val rightPx = ((viewportWidth * margins.rightPercent) / 100f).roundToInt()
        val bottomPx = ((viewportHeight * margins.bottomPercent) / 100f).roundToInt()

        return InsetPixels(left = leftPx, top = topPx, right = rightPx, bottom = bottomPx)
    }

    /**
     * Computes the combined insets taking the maximum of system bar insets and configured margins,
     * preventing double-application of system bar padding.
     */
    fun computeEffectiveInsets(
        marginPixels: InsetPixels,
        systemInsets: InsetPixels,
        basePaddingPx: Int = 0
    ): InsetPixels {
        return InsetPixels(
            left = basePaddingPx + maxOf(systemInsets.left, marginPixels.left),
            top = basePaddingPx + maxOf(systemInsets.top, marginPixels.top),
            right = basePaddingPx + maxOf(systemInsets.right, marginPixels.right),
            bottom = basePaddingPx + maxOf(systemInsets.bottom, marginPixels.bottom)
        )
    }

    /**
     * Applies display margins to the given [view].
     *
     * @param view The root container view to apply margins to.
     * @param margins The authoritative [DisplayMargins] to apply.
     * @param basePaddingDp Optional base padding in dp (e.g. 24dp for dialog cards).
     */
    fun applyMargins(
        view: View,
        margins: DisplayMargins,
        basePaddingDp: Int = 0,
        insets: WindowInsetsCompat? = null
    ) {
        val displayMetrics = view.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val basePaddingPx = (basePaddingDp * displayMetrics.density).roundToInt()
        val marginPixels = calculateMarginPixels(screenWidth, screenHeight, margins)

        // Query active window insets to prevent double-applying system bars
        val rootWindowInsets = insets ?: ViewCompat.getRootWindowInsets(view)
        val systemInsets = if (rootWindowInsets != null) {
            val bars = rootWindowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            InsetPixels(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
        } else {
            InsetPixels(0, 0, 0, 0)
        }

        val effective = computeEffectiveInsets(marginPixels, systemInsets, basePaddingPx)

        view.setPadding(
            effective.left,
            effective.top,
            effective.right,
            effective.bottom
        )
    }
}
