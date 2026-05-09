package com.everyday.everyday_glasses

import android.graphics.Color

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
        val hoverBorder: Int = Color.parseColor("#6666AA")
        val primaryButton: Int = Color.parseColor("#4488AA")
        val pinIdleButton: Int = Color.parseColor("#666688")
        val pinnedButton: Int = Color.parseColor("#44AA66")
        val resizeHandle: Int = Color.parseColor("#888899")
        val dangerButton: Int = Color.parseColor("#AA4444")
        val focusedBorder: Int = Color.parseColor("#4444AA")
        val selectionHighlight: Int = Color.parseColor("#806699FF")
        val scrollbarTrack: Int = Color.parseColor("#22666688")
        val scrollbarThumb: Int = Color.parseColor("#66888899")
        val streamOkBorder: Int = Color.parseColor("#44AA44")
        val streamErrorBorder: Int = dangerButton
        val streamErrorText: Int = Color.parseColor("#FF6666")
        val stopButton: Int = Color.parseColor("#D32F2F")
        val warningText: Int = Color.parseColor("#FFAA00")
    }
}
