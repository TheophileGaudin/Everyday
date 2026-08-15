package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.WidgetDocument.PersistedDashboard
import com.everyday.everyday_glasses.WidgetDocument.PersistedWidgetRecord

/**
 * Programmatic app-delivered layouts. They compose a [PersistedDashboard] sized to the current
 * screen so [WidgetContainer.applyPersistedDashboardReplacingCurrent] can install them the same
 * way a user-saved layout would be restored.
 */
object BuiltInLayouts {
    const val STANDARD = "Standard"
    const val GROCERIES = "Groceries"
    const val SUBTITLES = "Subtitles"
    const val MIRROR = "Mirror"
    val NAMES: List<String> = listOf(STANDARD, GROCERIES, SUBTITLES, MIRROR)
    val DELIVERED_LAYOUTS: List<String> = NAMES

    /**
     * Order in which records are written. Widget order is not semantically meaningful,
     * but keeping it stable keeps saved documents diffable and the golden layouts fixed.
     */
    private val SINGLETON_ORDER: List<WidgetTypeId> = listOf(
        WidgetTypeId.STATUS,
        WidgetTypeId.LOCATION,
        WidgetTypeId.MAP,
        WidgetTypeId.CALENDAR,
        WidgetTypeId.FINANCE,
        WidgetTypeId.NEWS,
        WidgetTypeId.NOTIFICATIONS,
        WidgetTypeId.SPEEDOMETER,
        WidgetTypeId.SUBTITLE,
        WidgetTypeId.MIRROR
    )

    private val MULTIPLE_ORDER: List<WidgetTypeId> = listOf(
        WidgetTypeId.BROWSER,
        WidgetTypeId.TEXT,
        WidgetTypeId.IMAGE,
        WidgetTypeId.VIDEO,
        WidgetTypeId.PDF
    )

    /**
     * Builds a layout by name. Returns null when the name is not one of the recognised
     * built-ins; callers should fall back to a user-saved layout in that case.
     */
    fun buildDashboard(name: String, screenWidth: Float, screenHeight: Float): PersistedDashboard? {
        if (screenWidth <= 0f || screenHeight <= 0f) return null
        return when (canonicalBuiltinName(name)) {
            STANDARD -> standardLayout(screenWidth, screenHeight)
            GROCERIES -> groceriesLayout(screenWidth, screenHeight)
            SUBTITLES -> subtitlesLayout(screenWidth, screenHeight)
            MIRROR -> mirrorLayout(screenWidth, screenHeight)
            else -> null
        }
    }

    fun isDelivered(name: String): Boolean =
        DELIVERED_LAYOUTS.any { it.equals(name.trim(), ignoreCase = true) }

    private fun canonicalBuiltinName(name: String): String? {
        val trimmed = name.trim()
        return NAMES.firstOrNull { it.equals(trimmed, ignoreCase = true) }
    }

    /**
     * Encodes the widget states of one delivered layout into a dashboard.
     * Singletons take the widget type as their instance id; repeatable widgets are
     * numbered from one, matching the ids the runtime assigns to restored widgets.
     */
    private fun dashboard(
        name: String,
        singletons: Map<WidgetTypeId, Any>,
        multiples: Map<WidgetTypeId, List<Any>> = emptyMap()
    ): PersistedDashboard {
        val widgets = buildList {
            SINGLETON_ORDER.forEach { type ->
                singletons[type]?.let { state -> add(record(type, state, type.persistedId)) }
            }
            MULTIPLE_ORDER.forEach { type ->
                multiples[type]?.forEachIndexed { index, state ->
                    add(record(type, state, "${type.persistedId}#${index + 1}"))
                }
            }
        }
        return PersistedDashboard(widgets = widgets, activeLayoutName = name)
    }

    private fun record(type: WidgetTypeId, state: Any, instanceId: String): PersistedWidgetRecord {
        val codec = requireNotNull(WidgetDocument.codecs[type]) {
            "No persistence codec for ${type.persistedId}"
        }
        return PersistedWidgetRecord(type, instanceId, codec.encode(state))
    }

    // -----------------------------------------------------------------------------------
    // Standard: 3×2 grid of dashboard widgets.
    //   row 0: system status (top-left)   | speedometer (top-center) | location/weather (top-right)
    //   row 1: calendar (middle, centered) (spans width below row 0)
    //   bottom: news (bottom-left) | finance (bottom-right)
    // -----------------------------------------------------------------------------------
    private fun standardLayout(w: Float, h: Float): PersistedDashboard {
        val sx = w / 640f
        val sy = h / 480f
        return dashboard(
            STANDARD,
            mapOf(
                WidgetTypeId.STATUS to WidgetPersistence.StatusBarState(
                    x = 0f, y = 41f * sy,
                    showTime = true,
                    showDate = true,
                    showPhoneBattery = true,
                    showGlassesBattery = true,
                    isPinned = true
                ),
                WidgetTypeId.LOCATION to WidgetPersistence.LocationWidgetState(
                    x = 392.55f * sx, y = 0f,
                    width = 194.9f * sx, height = 76f * sy,
                    showWeather = true,
                    showTemperature = true,
                    showLocation = false,
                    showCountry = false,
                    isPinned = true
                ),
                WidgetTypeId.MAP to WidgetPersistence.MapWidgetState(
                    x = 405.5f * sx, y = 41f * sy,
                    width = 181.95f * sx, height = 160.05f * sy,
                    mode = MapViewMode.TOP_DOWN.name, zoom = MapZoom.MEDIUM.name,
                    showLocation = true, showCountry = true, isPinned = true
                ),
                WidgetTypeId.CALENDAR to WidgetPersistence.CalendarWidgetState(
                    x = 0f, y = 201.05f * sy,
                    width = 612f * sx, height = 90.9f * sy,
                    fontScale = 1f,
                    isPinned = true
                ),
                WidgetTypeId.FINANCE to WidgetPersistence.FinanceWidgetState(
                    x = 364.8f * sx, y = 301.95f * sy,
                    width = 202.2f * sx, height = 134.4f * sy,
                    isPinned = true
                ),
                WidgetTypeId.NEWS to WidgetPersistence.NewsWidgetState(
                    x = 0f, y = 301.95f * sy,
                    width = 352.2f * sx, height = 134.4f * sy,
                    fontScale = 0.75f,
                    isPinned = true
                ),
                WidgetTypeId.SPEEDOMETER to WidgetPersistence.SpeedometerWidgetState(
                    x = 229.9f * sx, y = 38f * sy,
                    width = 134.9f * sx, height = 92.7f * sy,
                    isPinned = true
                )
            )
        )
    }

    // -----------------------------------------------------------------------------------
    // Groceries: top row mirrors Standard's top row; the rest of the screen is one big
    // text widget configured with 3 columns and a small font.
    // -----------------------------------------------------------------------------------
    private fun groceriesLayout(w: Float, h: Float): PersistedDashboard {
        val margin = w * 0.025f
        val rowHeight = (h * 0.18f).coerceAtLeast(110f)
        val colWidth = (w - 4f * margin) / 3f
        val speedW = colWidth.coerceAtMost(260f)
        val locW = colWidth.coerceAtMost(320f)
        val textTop = margin + rowHeight + margin

        return dashboard(
            GROCERIES,
            mapOf(
                WidgetTypeId.STATUS to WidgetPersistence.StatusBarState(
                    x = margin, y = margin, isPinned = true
                ),
                WidgetTypeId.LOCATION to WidgetPersistence.LocationWidgetState(
                    x = w - locW - margin,
                    y = margin,
                    width = locW,
                    height = rowHeight.coerceAtMost(140f),
                    isPinned = true
                ),
                WidgetTypeId.SPEEDOMETER to WidgetPersistence.SpeedometerWidgetState(
                    x = (w - speedW) / 2f,
                    y = margin,
                    width = speedW,
                    height = rowHeight.coerceAtMost(140f),
                    isPinned = true
                )
            ),
            mapOf(
                WidgetTypeId.TEXT to listOf(
                    WidgetPersistence.TextWidgetState(
                        x = margin,
                        y = textTop,
                        width = w - 2f * margin,
                        height = h - textTop - margin,
                        text = "",
                        fontSize = 16f,
                        isTextWrap = true,
                        columnCount = 3,
                        isPinned = true
                    )
                )
            )
        )
    }

    // -----------------------------------------------------------------------------------
    // Subtitles only.
    // -----------------------------------------------------------------------------------
    private fun subtitlesLayout(w: Float, h: Float): PersistedDashboard {
        val subW = (w * 0.85f).coerceAtMost(SubtitleWidget.DEFAULT_WIDTH * 1.6f)
        val subH = (h * 0.32f).coerceAtLeast(SubtitleWidget.DEFAULT_HEIGHT)
        return dashboard(
            SUBTITLES,
            mapOf(
                WidgetTypeId.SUBTITLE to WidgetPersistence.SubtitleWidgetState(
                    x = (w - subW) / 2f,
                    y = (h - subH) / 2f,
                    width = subW,
                    height = subH,
                    phonePlaybackEnabled = true,
                    microphoneEnabled = false,
                    translationEnabled = false,
                    isPinned = true
                )
            )
        )
    }

    // -----------------------------------------------------------------------------------
    // Screen mirror only, fullscreen.
    // -----------------------------------------------------------------------------------
    private fun mirrorLayout(w: Float, h: Float): PersistedDashboard = dashboard(
        MIRROR,
        mapOf(
            WidgetTypeId.MIRROR to WidgetPersistence.MirrorWidgetState(
                x = 0f,
                y = 0f,
                width = w,
                height = h,
                isFullscreen = true,
                isPinned = true
            )
        )
    )
}
