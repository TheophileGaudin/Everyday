package com.everyday.everyday_glasses.ui.widgets.common

import android.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * Shared widget visual constants.
 *
 * Keep widget chrome values here so interaction affordances stay consistent
 * across individual widgets.
 */
object WidgetTheme {
    object Size {
        const val BORDER_WIDTH = 3f
        const val FULLSCREEN_BUTTON = 28f
        const val MINIMIZE_BUTTON = 28f
        const val PIN_BUTTON = 28f
        const val CLOSE_BUTTON = 28f
        const val RESIZE_HANDLE = 40f
        const val BORDER_HIT_AREA = 12f
        const val BUTTON_SPACING = 6f
    }

    object ColorValue {
        val hoverBorder: Int = "#6666AA".toColorInt()
        val primaryButton: Int = "#4488AA".toColorInt()
        val pinIdleButton: Int = "#666688".toColorInt()
        val pinnedButton: Int = "#44AA66".toColorInt()
        val resizeHandle: Int = "#888899".toColorInt()
        val dangerButton: Int = "#AA4444".toColorInt()
        val focusedBorder: Int = "#4444AA".toColorInt()
        val selectionHighlight: Int = "#806699FF".toColorInt()
        val scrollbarTrack: Int = "#22666688".toColorInt()
        val scrollbarThumb: Int = "#66888899".toColorInt()
        val streamErrorBorder: Int = dangerButton
        val streamErrorText: Int = "#FF6666".toColorInt()
        val stopButton: Int = "#D32F2F".toColorInt()
        val warningText: Int = "#FFAA00".toColorInt()
    }
}
