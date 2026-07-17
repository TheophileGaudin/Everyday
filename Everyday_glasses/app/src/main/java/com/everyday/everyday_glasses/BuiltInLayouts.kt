package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.WidgetPersistence.PersistedState

/**
 * Programmatic app-delivered layouts. They compose a [PersistedState] sized to the current
 * screen so [WidgetContainer.applyPersistedStateReplacingCurrent] can install them the same
 * way a user-saved layout would be restored.
 */
object BuiltInLayouts {
    val DELIVERED_LAYOUTS: List<String> = listOf(
        ShortcutAction.BUILTIN_LAYOUT_STANDARD,
        ShortcutAction.BUILTIN_LAYOUT_GROCERIES,
        ShortcutAction.BUILTIN_LAYOUT_SUBTITLES,
        ShortcutAction.BUILTIN_LAYOUT_MIRROR
    )

    /**
     * Builds a layout by name. Returns null when the name is not one of the recognised
     * built-ins; callers should fall back to a user-saved layout in that case.
     */
    fun build(name: String, screenWidth: Float, screenHeight: Float): PersistedState? {
        if (screenWidth <= 0f || screenHeight <= 0f) return null
        return when (canonicalBuiltinName(name)) {
            ShortcutAction.BUILTIN_LAYOUT_STANDARD -> standardLayout(screenWidth, screenHeight)
            ShortcutAction.BUILTIN_LAYOUT_GROCERIES -> groceriesLayout(screenWidth, screenHeight)
            ShortcutAction.BUILTIN_LAYOUT_SUBTITLES -> subtitlesLayout(screenWidth, screenHeight)
            ShortcutAction.BUILTIN_LAYOUT_MIRROR -> mirrorLayout(screenWidth, screenHeight)
            else -> null
        }
    }

    fun isDelivered(name: String): Boolean =
        DELIVERED_LAYOUTS.any { it.equals(name.trim(), ignoreCase = true) }

    private fun canonicalBuiltinName(name: String): String? {
        val trimmed = name.trim()
        return ShortcutAction.BUILTIN_LAYOUTS.firstOrNull { it.equals(trimmed, ignoreCase = true) }
    }

    private fun emptyState(name: String): PersistedState = PersistedState(
        text = emptyList(),
        browser = emptyList(),
        image = emptyList(),
        status = null,
        location = null,
        map = null,
        calendar = null,
        finance = null,
        news = null,
        notifications = null,
        speedometer = null,
        subtitle = null,
        mirror = null,
        closedTemplates = WidgetPersistence.ClosedWidgetTemplates(),
        hoverControls = null,
        isFirstRun = false,
        activeLayoutName = name
    )

    // -----------------------------------------------------------------------------------
    // Standard: 3×2 grid of dashboard widgets.
    //   row 0: system status (top-left)   | speedometer (top-center) | location/weather (top-right)
    //   row 1: calendar (middle, centered) (spans width below row 0)
    //   bottom: news (bottom-left) | finance (bottom-right)
    // -----------------------------------------------------------------------------------
    private fun standardLayout(w: Float, h: Float): PersistedState {
        val sx = w / 640f
        val sy = h / 480f
        val status = WidgetPersistence.StatusBarState(
            x = 0f, y = 41f * sy,
            showTime = true,
            showDate = true,
            showPhoneBattery = true,
            showGlassesBattery = true,
            isPinned = true
        )
        val map = WidgetPersistence.MapWidgetState(
            x = 405.5f * sx, y = 41f * sy,
            width = 181.95f * sx, height = 160.05f * sy,
            mode = MapViewMode.TOP_DOWN.name, zoom = MapZoom.MEDIUM.name,
            showLocation = true, showCountry = true, isPinned = true
        )
        val speedometer = WidgetPersistence.SpeedometerWidgetState(
            x = 229.9f * sx, y = 38f * sy,
            width = 134.9f * sx, height = 92.7f * sy,
            isPinned = true
        )
        val location = WidgetPersistence.LocationWidgetState(
            x = 392.55f * sx, y = 0f,
            width = 194.9f * sx, height = 76f * sy,
            showWeather = true,
            showTemperature = true,
            showLocation = false,
            showCountry = false,
            isPinned = true
        )
        val calendar = WidgetPersistence.CalendarWidgetState(
            x = 0f, y = 201.05f * sy,
            width = 612f * sx, height = 90.9f * sy,
            fontScale = 1f,
            isPinned = true
        )
        val news = WidgetPersistence.NewsWidgetState(
            x = 0f, y = 301.95f * sy,
            width = 352.2f * sx, height = 134.4f * sy,
            fontScale = 0.75f,
            isPinned = true
        )
        val finance = WidgetPersistence.FinanceWidgetState(
            x = 364.8f * sx, y = 301.95f * sy,
            width = 202.2f * sx, height = 134.4f * sy,
            isPinned = true
        )

        return emptyState(ShortcutAction.BUILTIN_LAYOUT_STANDARD).copy(
            status = status,
            speedometer = speedometer,
            location = location,
            map = map,
            calendar = calendar,
            news = news,
            finance = finance
        )
    }

    // -----------------------------------------------------------------------------------
    // Groceries: top row mirrors Standard's top row; the rest of the screen is one big
    // text widget configured with 3 columns and a small font.
    // -----------------------------------------------------------------------------------
    private fun groceriesLayout(w: Float, h: Float): PersistedState {
        val margin = w * 0.025f
        val rowHeight = (h * 0.18f).coerceAtLeast(110f)
        val colWidth = (w - 4f * margin) / 3f

        val status = WidgetPersistence.StatusBarState(x = margin, y = margin, isPinned = true)
        val speedW = colWidth.coerceAtMost(260f)
        val speedometer = WidgetPersistence.SpeedometerWidgetState(
            x = (w - speedW) / 2f,
            y = margin,
            width = speedW,
            height = rowHeight.coerceAtMost(140f),
            isPinned = true
        )
        val locW = colWidth.coerceAtMost(320f)
        val location = WidgetPersistence.LocationWidgetState(
            x = w - locW - margin,
            y = margin,
            width = locW,
            height = rowHeight.coerceAtMost(140f),
            isPinned = true
        )

        val textTop = margin + rowHeight + margin
        val textW = w - 2f * margin
        val textH = h - textTop - margin
        val textWidget = WidgetPersistence.TextWidgetState(
            x = margin,
            y = textTop,
            width = textW,
            height = textH,
            text = "",
            fontSize = 16f,
            isTextWrap = true,
            columnCount = 3,
            isPinned = true
        )

        return emptyState(ShortcutAction.BUILTIN_LAYOUT_GROCERIES).copy(
            text = listOf(textWidget),
            status = status,
            speedometer = speedometer,
            location = location
        )
    }

    // -----------------------------------------------------------------------------------
    // Subtitles only.
    // -----------------------------------------------------------------------------------
    private fun subtitlesLayout(w: Float, h: Float): PersistedState {
        val subW = (w * 0.85f).coerceAtMost(SubtitleWidget.DEFAULT_WIDTH * 1.6f)
        val subH = (h * 0.32f).coerceAtLeast(SubtitleWidget.DEFAULT_HEIGHT)
        val subtitle = WidgetPersistence.SubtitleWidgetState(
            x = (w - subW) / 2f,
            y = (h - subH) / 2f,
            width = subW,
            height = subH,
            phonePlaybackEnabled = true,
            microphoneEnabled = false,
            translationEnabled = false,
            isPinned = true
        )
        return emptyState(ShortcutAction.BUILTIN_LAYOUT_SUBTITLES).copy(subtitle = subtitle)
    }

    // -----------------------------------------------------------------------------------
    // Screen mirror only, fullscreen.
    // -----------------------------------------------------------------------------------
    private fun mirrorLayout(w: Float, h: Float): PersistedState {
        val mirror = WidgetPersistence.MirrorWidgetState(
            x = 0f,
            y = 0f,
            width = w,
            height = h,
            isFullscreen = true,
            isPinned = true
        )
        return emptyState(ShortcutAction.BUILTIN_LAYOUT_MIRROR).copy(mirror = mirror)
    }
}
