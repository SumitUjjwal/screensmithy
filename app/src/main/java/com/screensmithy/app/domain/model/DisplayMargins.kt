package com.screensmithy.app.domain.model

/**
 * Immutable administrator-configurable display margins (overscan protection)
 * expressed as percentages of the visible display viewport (0..20% per edge).
 *
 * Designed to adjust the visible ScreenSmithy application UI to prevent cropping
 * on physical televisions and commercial monitors with edge overscan.
 */
data class DisplayMargins(
    val topPercent: Int = 0,
    val bottomPercent: Int = 0,
    val leftPercent: Int = 0,
    val rightPercent: Int = 0
) {
    init {
        require(topPercent in MIN_PERCENT..MAX_PERCENT) {
            "topPercent must be between $MIN_PERCENT% and $MAX_PERCENT%, got $topPercent%"
        }
        require(bottomPercent in MIN_PERCENT..MAX_PERCENT) {
            "bottomPercent must be between $MIN_PERCENT% and $MAX_PERCENT%, got $bottomPercent%"
        }
        require(leftPercent in MIN_PERCENT..MAX_PERCENT) {
            "leftPercent must be between $MIN_PERCENT% and $MAX_PERCENT%, got $leftPercent%"
        }
        require(rightPercent in MIN_PERCENT..MAX_PERCENT) {
            "rightPercent must be between $MIN_PERCENT% and $MAX_PERCENT%, got $rightPercent%"
        }
        require(topPercent + bottomPercent <= MAX_COMBINED_VERTICAL_PERCENT) {
            "Combined vertical margins (${topPercent + bottomPercent}%) cannot exceed $MAX_COMBINED_VERTICAL_PERCENT%"
        }
        require(leftPercent + rightPercent <= MAX_COMBINED_HORIZONTAL_PERCENT) {
            "Combined horizontal margins (${leftPercent + rightPercent}%) cannot exceed $MAX_COMBINED_HORIZONTAL_PERCENT%"
        }
    }

    val isZero: Boolean
        get() = topPercent == 0 && bottomPercent == 0 && leftPercent == 0 && rightPercent == 0

    companion object {
        const val MIN_PERCENT = 0
        const val MAX_PERCENT = 20
        const val MAX_COMBINED_VERTICAL_PERCENT = 40
        const val MAX_COMBINED_HORIZONTAL_PERCENT = 40
        const val STEP_PERCENT = 1

        val ZERO = DisplayMargins(0, 0, 0, 0)
    }
}
