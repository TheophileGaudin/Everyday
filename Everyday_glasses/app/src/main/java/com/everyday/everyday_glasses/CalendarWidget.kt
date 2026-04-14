package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class CalendarWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(x, y, width, height) {

    companion object {
        const val DEFAULT_WIDTH = 320f
        const val DEFAULT_HEIGHT = 150f
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val WINDOW_PADDING = 16f
        private const val FULLSCREEN_PADDING = 26f
        private const val CONTENT_GAP = 10f
        private const val EVENT_GAP = 18f
        private const val ITEM_TITLE_MAX_LINES = 2
        private const val BASE_TITLE_WINDOW_SIZE = 24f
        private const val BASE_DETAIL_WINDOW_SIZE = 18f
        private const val BASE_TITLE_FULLSCREEN_SIZE = 26f
        private const val BASE_DETAIL_FULLSCREEN_SIZE = 18f
        private const val BASE_CENTER_INFO_SIZE = 22f
        private const val FONT_SCALE_MIN = 0.75f
        private const val FONT_SCALE_MAX = 1.55f
        private const val FONT_SCALE_STEP = 0.10f
        private const val FONT_MENU_WIDTH = 194f
        private const val FONT_MENU_HEIGHT = 82f
        private const val FONT_MENU_BUTTON_SIZE = 40f
    }

    private enum class FontMenuAction { DECREASE, INCREASE }

    private data class ParsedEventTime(val startMs: Long, val isAllDay: Boolean)

    private data class ParsedEvent(
        val event: GoogleCalendarEvent,
        val startMs: Long,
        val isAllDay: Boolean
    )

    private data class DisplayState(
        val generation: Long,
        val events: List<DisplayEvent>,
        val emptyStateLine: String,
        val freshnessLabel: String,
        val nextRefreshAtMs: Long
    )

    private data class DisplayEvent(
        val title: String,
        val relativeLabel: String,
        val scheduleLabel: String,
        val startMs: Long,
        val isAllDay: Boolean
    )

    private data class WindowedLayout(
        val displayGeneration: Long,
        val geometryVersion: Int,
        val event: DisplayEvent?,
        val titleLines: List<String>
    )

    private data class EventLayout(
        val eventIndex: Int,
        val titleLines: List<String>,
        val top: Float,
        val bottom: Float
    )

    private data class FullscreenLayout(
        val displayGeneration: Long,
        val geometryVersion: Int,
        val events: List<EventLayout>,
        val viewport: RectF,
        val maxScroll: Float
    )

    override val minimizeLabel: String = "C"
    override val minWidth: Float = 250f
    override val minHeight: Float = 130f

    var onStateChanged: (() -> Unit)? = null

    private val titlePaintWindow = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BASE_TITLE_WINDOW_SIZE
    }
    private val detailPaintWindow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6E3F8")
        textSize = BASE_DETAIL_WINDOW_SIZE
    }
    private val titlePaintFullscreen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BASE_TITLE_FULLSCREEN_SIZE
    }
    private val detailPaintFullscreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6E3F8")
        textSize = BASE_DETAIL_FULLSCREEN_SIZE
    }
    private val metaPaintFullscreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA1B7")
        textSize = BASE_DETAIL_FULLSCREEN_SIZE * 0.92f
    }
    private val centerInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#96A7BD")
        textSize = BASE_CENTER_INFO_SIZE
        textAlign = Paint.Align.CENTER
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22415A74")
        strokeWidth = 2f
    }
    private val scrollbarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33445566")
        style = Paint.Style.FILL
    }
    private val scrollbarThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88AECBFF")
        style = Paint.Style.FILL
    }
    private val fontMenuBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD111827")
        style = Paint.Style.FILL
    }
    private val fontMenuBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#667FA1C4")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val fontMenuButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334A6178")
        style = Paint.Style.FILL
    }
    private val fontMenuButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5578A7D5")
        style = Paint.Style.FILL
    }
    private val fontMenuLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.CENTER
    }
    private val fontMenuStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9FB2C9")
        textSize = 13f
        textAlign = Paint.Align.CENTER
    }
    private val fontMenuButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val iconBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4285F4") }
    private val iconBlueDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B6BCB") }
    private val iconWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconYellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FBBC04") }
    private val iconGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34A853") }
    private val iconGreenDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E8E3E") }
    private val iconRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EA4335") }
    private val iconFoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val iconNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A73E8")
        textAlign = Paint.Align.CENTER
    }

    private var snapshot: GoogleCalendarSnapshot? = null
    private var parsedEvents: List<ParsedEvent> = emptyList()
    private var fontScale = 1f
    private var contentScrollOffset = 0f
    private var showFontMenu = false
    private var hoveredFontMenuAction: FontMenuAction? = null
    private var geometryVersion = 0
    private var displayGeneration = 0L
    private var displayStateCache: DisplayState? = null
    private var windowedLayoutCache: WindowedLayout? = null
    private var fullscreenLayoutCache: FullscreenLayout? = null

    private val fontMenuBounds = RectF()
    private val decreaseFontBounds = RectF()
    private val increaseFontBounds = RectF()
    private val windowIconBounds = RectF()
    private val allDayParser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val localDayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val scheduleDateFormatter = SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    private val scheduleTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    private val dateTimeParsers = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mmXXX"
    ).map { pattern ->
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    init {
        applyFontScale()
        updateInternalBounds()
    }

    fun setSnapshot(snapshot: GoogleCalendarSnapshot?) {
        this.snapshot = snapshot
        parsedEvents = snapshot?.events
            ?.mapNotNull { event ->
                parseEventTime(event.startIso)?.let { parsed ->
                    ParsedEvent(event, parsed.startMs, parsed.isAllDay)
                }
            }
            ?.sortedBy { it.startMs }
            ?: emptyList()
        displayStateCache = null
        onStateChanged?.invoke()
    }

    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        if (abs(clamped - fontScale) < 0.001f) return
        fontScale = clamped
        applyFontScale()
        geometryVersion += 1
        windowedLayoutCache = null
        fullscreenLayoutCache = null
        onStateChanged?.invoke()
    }

    fun getFontScale(): Float = fontScale

    fun onTap(px: Float, py: Float): Boolean {
        if (handleFontMenuTapOrDismiss(px, py)) return true
        if (!isFullscreen && contentBounds.contains(px, py)) {
            toggleFullscreen()
            return true
        }
        return false
    }

    fun onDoubleTap(px: Float, py: Float): Boolean {
        if (!containsPoint(px, py)) return false
        if (isFullscreen) {
            toggleFullscreen()
            return true
        }
        showFontMenu = !showFontMenu
        hoveredFontMenuAction = null
        onStateChanged?.invoke()
        return true
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        updateInternalBounds()
        geometryVersion += 1
        windowedLayoutCache = null
        fullscreenLayoutCache = null
    }

    override fun enterFullscreen() {
        super.enterFullscreen()
        contentScrollOffset = 0f
        showFontMenu = false
        onStateChanged?.invoke()
    }

    override fun exitFullscreen() {
        super.exitFullscreen()
        contentScrollOffset = 0f
        showFontMenu = false
        onStateChanged?.invoke()
    }

    override fun onScroll(dy: Float) {
        if (!isFullscreen) return
        val layout = ensureFullscreenLayout(ensureDisplayState())
        if (layout.maxScroll <= 0f) {
            contentScrollOffset = 0f
            return
        }
        contentScrollOffset = (contentScrollOffset + dy).coerceIn(0f, layout.maxScroll)
        onStateChanged?.invoke()
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
        hoveredFontMenuAction = when {
            !showFontMenu -> null
            decreaseFontBounds.contains(px, py) -> FontMenuAction.DECREASE
            increaseFontBounds.contains(px, py) -> FontMenuAction.INCREASE
            else -> null
        }
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }
        val displayState = ensureDisplayState()
        canvas.drawRoundRect(widgetBounds, 10f, 10f, backgroundPaint)
        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 10f, 10f, hoverBorderPaint)
        }
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }
        if (isFullscreen) drawFullscreenContent(canvas, displayState) else drawWindowedContent(canvas, displayState)
        if (showFontMenu) drawFontMenu(canvas, displayState)
    }

    private fun drawWindowedContent(canvas: Canvas, displayState: DisplayState) {
        drawCalendarIcon(canvas, windowIconBounds)

        val top = contentBounds.top + WINDOW_PADDING

        var y = top
        val layout = ensureWindowedLayout(displayState)
        val textLeft = windowIconBounds.right + 16f
        val event = layout.event
        if (event == null) {
            y += lineHeight(titlePaintWindow)
            canvas.drawText("No upcoming event", textLeft, y, titlePaintWindow)
            y += lineHeight(detailPaintWindow)
            canvas.drawText(displayState.emptyStateLine, textLeft, y, detailPaintWindow)
            return
        }

        for (line in layout.titleLines) {
            y += lineHeight(titlePaintWindow)
            canvas.drawText(line, textLeft, y, titlePaintWindow)
        }

        y += CONTENT_GAP
        canvas.drawText(event.relativeLabel, textLeft, y + detailPaintWindow.textSize, detailPaintWindow)
        y += lineHeight(detailPaintWindow)
        canvas.drawText(event.scheduleLabel, textLeft, y + detailPaintWindow.textSize, detailPaintWindow)
    }

    private fun drawFullscreenContent(canvas: Canvas, displayState: DisplayState) {
        val left = contentBounds.left + FULLSCREEN_PADDING
        val headerTop = contentBounds.top + FULLSCREEN_PADDING
        val headerIconSize = 48f * fontScale.coerceIn(0.9f, 1.2f)
        val headerIconBounds = RectF(left, headerTop, left + headerIconSize, headerTop + headerIconSize)
        drawCalendarIcon(canvas, headerIconBounds)

        val titleX = headerIconBounds.right + 16f
        val titleY = headerIconBounds.centerY() - (titlePaintWindow.descent() + titlePaintWindow.ascent()) / 2f
        canvas.drawText("Calendar", titleX, titleY, titlePaintWindow)

        val layout = ensureFullscreenLayout(displayState)
        if (layout.events.isEmpty()) {
            val emptyY = layout.viewport.centerY() - (centerInfoPaint.descent() + centerInfoPaint.ascent()) / 2f
            canvas.drawText(displayState.emptyStateLine, layout.viewport.centerX(), emptyY, centerInfoPaint)
            return
        }

        val clampedScroll = contentScrollOffset.coerceIn(0f, layout.maxScroll)
        if (clampedScroll != contentScrollOffset) contentScrollOffset = clampedScroll

        canvas.save()
        canvas.clipRect(layout.viewport)

        val titleLineHeight = lineHeight(titlePaintFullscreen)
        val detailLineHeight = lineHeight(detailPaintFullscreen)
        for (item in layout.events) {
            val event = displayState.events.getOrNull(item.eventIndex) ?: continue
            val itemTop = item.top - clampedScroll
            val itemBottom = item.bottom - clampedScroll
            if (itemBottom < layout.viewport.top || itemTop > layout.viewport.bottom) continue

            var y = itemTop + titleLineHeight
            for (line in item.titleLines) {
                canvas.drawText(line, layout.viewport.left, y, titlePaintFullscreen)
                y += titleLineHeight
            }

            canvas.drawText(event.relativeLabel, layout.viewport.left, y + 2f, detailPaintFullscreen)
            y += detailLineHeight
            canvas.drawText(event.scheduleLabel, layout.viewport.left, y + 2f, metaPaintFullscreen)

            val separatorY = itemBottom + EVENT_GAP * 0.5f
            canvas.drawLine(layout.viewport.left, separatorY, layout.viewport.right - 14f, separatorY, separatorPaint)
        }

        canvas.restore()
        drawScrollbar(canvas, layout)
    }

    private fun drawFontMenu(canvas: Canvas, displayState: DisplayState) {
        canvas.drawRoundRect(fontMenuBounds, 12f, 12f, fontMenuBackgroundPaint)
        canvas.drawRoundRect(fontMenuBounds, 12f, 12f, fontMenuBorderPaint)

        canvas.drawText(displayState.freshnessLabel, fontMenuBounds.centerX(), fontMenuBounds.top + 18f, fontMenuStatusPaint)
        canvas.drawText("Text ${(fontScale * 100f).toInt()}%", fontMenuBounds.centerX(), fontMenuBounds.top + 44f, fontMenuLabelPaint)

        val decreasePaint = if (hoveredFontMenuAction == FontMenuAction.DECREASE) fontMenuButtonHoverPaint else fontMenuButtonPaint
        val increasePaint = if (hoveredFontMenuAction == FontMenuAction.INCREASE) fontMenuButtonHoverPaint else fontMenuButtonPaint
        canvas.drawRoundRect(decreaseFontBounds, 10f, 10f, decreasePaint)
        canvas.drawRoundRect(increaseFontBounds, 10f, 10f, increasePaint)

        val baseline = decreaseFontBounds.centerY() - (fontMenuButtonTextPaint.descent() + fontMenuButtonTextPaint.ascent()) / 2f
        canvas.drawText("-", decreaseFontBounds.centerX(), baseline, fontMenuButtonTextPaint)
        canvas.drawText("+", increaseFontBounds.centerX(), baseline, fontMenuButtonTextPaint)
    }

    private fun drawScrollbar(canvas: Canvas, layout: FullscreenLayout) {
        if (layout.maxScroll <= 0f) return

        val trackWidth = 10f
        val track = RectF(layout.viewport.right - trackWidth, layout.viewport.top, layout.viewport.right, layout.viewport.bottom)
        canvas.drawRoundRect(track, 4f, 4f, scrollbarTrackPaint)

        val visibleHeight = layout.viewport.height()
        val contentHeight = visibleHeight + layout.maxScroll
        val thumbHeight = (visibleHeight / contentHeight * visibleHeight).coerceAtLeast(34f)
        val thumbTravel = (visibleHeight - thumbHeight).coerceAtLeast(1f)
        val thumbTop = track.top + (contentScrollOffset / layout.maxScroll) * thumbTravel
        val thumb = RectF(track.left, thumbTop, track.right, thumbTop + thumbHeight)
        canvas.drawRoundRect(thumb, 4f, 4f, scrollbarThumbPaint)
    }

    private fun drawCalendarIcon(canvas: Canvas, bounds: RectF) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return

        val radius = min(bounds.width(), bounds.height()) * 0.22f
        val clipPath = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(bounds, iconBluePaint)

        val topBandHeight = bounds.height() * 0.24f
        val leftBandWidth = bounds.width() * 0.24f
        val rightBandStart = bounds.left + bounds.width() * 0.76f
        val bottomBandStart = bounds.top + bounds.height() * 0.76f

        canvas.drawRect(bounds.centerX(), bounds.top, bounds.right, bounds.top + topBandHeight, iconBlueDarkPaint)

        val whiteRect = RectF(bounds.left + leftBandWidth, bounds.top + topBandHeight, rightBandStart, bottomBandStart)
        canvas.drawRect(whiteRect, iconWhitePaint)
        canvas.drawRect(rightBandStart, bounds.top + topBandHeight, bounds.right, bottomBandStart, iconYellowPaint)
        canvas.drawRect(bounds.left, bottomBandStart, bounds.left + leftBandWidth, bounds.bottom, iconGreenDarkPaint)
        canvas.drawRect(bounds.left + leftBandWidth, bottomBandStart, rightBandStart, bounds.bottom, iconGreenPaint)
        canvas.drawRect(rightBandStart, bottomBandStart, bounds.right, bounds.bottom, iconRedPaint)

        val foldPath = Path().apply {
            moveTo(bounds.right, bounds.bottom)
            lineTo(bounds.right - bounds.width() * 0.12f, bounds.bottom)
            lineTo(bounds.right, bounds.bottom - bounds.height() * 0.12f)
            close()
        }
        canvas.drawPath(foldPath, iconFoldPaint)
        canvas.restore()

        iconNumberPaint.textSize = bounds.height() * 0.42f
        val baseline = whiteRect.centerY() - (iconNumberPaint.descent() + iconNumberPaint.ascent()) / 2f
        canvas.drawText("31", whiteRect.centerX(), baseline, iconNumberPaint)
    }

    private fun ensureWindowedLayout(displayState: DisplayState): WindowedLayout {
        val cached = windowedLayoutCache
        if (
            cached != null &&
            cached.displayGeneration == displayState.generation &&
            cached.geometryVersion == geometryVersion
        ) {
            return cached
        }

        val event = displayState.events.firstOrNull()
        val layout = if (event == null) {
            WindowedLayout(
                displayGeneration = displayState.generation,
                geometryVersion = geometryVersion,
                event = null,
                titleLines = emptyList()
            )
        } else {
            val textLeft = windowIconBounds.right + 16f
            val textRight = contentBounds.right - WINDOW_PADDING
            val maxWidth = (textRight - textLeft).coerceAtLeast(1f)
            val reserveHeight =
                lineHeight(detailPaintWindow) +
                    lineHeight(detailPaintWindow) +
                    CONTENT_GAP
            val titleAvailableHeight = (
                contentBounds.bottom - WINDOW_PADDING - reserveHeight - (contentBounds.top + WINDOW_PADDING)
            ).coerceAtLeast(lineHeight(titlePaintWindow))
            val maxTitleLines = max(1, (titleAvailableHeight / lineHeight(titlePaintWindow)).toInt())
            WindowedLayout(
                displayGeneration = displayState.generation,
                geometryVersion = geometryVersion,
                event = event,
                titleLines = fitLines(
                    toWrappedLines(event.title, titlePaintWindow, maxWidth),
                    maxTitleLines,
                    titlePaintWindow,
                    maxWidth
                )
            )
        }

        windowedLayoutCache = layout
        return layout
    }

    private fun ensureFullscreenLayout(displayState: DisplayState): FullscreenLayout {
        val cached = fullscreenLayoutCache
        if (
            cached != null &&
            cached.displayGeneration == displayState.generation &&
            cached.geometryVersion == geometryVersion
        ) {
            return cached
        }

        val headerHeight = 48f * fontScale.coerceIn(0.9f, 1.2f)
        val viewport = RectF(
            contentBounds.left + FULLSCREEN_PADDING,
            contentBounds.top + FULLSCREEN_PADDING + headerHeight + 20f,
            contentBounds.right - FULLSCREEN_PADDING,
            contentBounds.bottom - FULLSCREEN_PADDING
        )
        if (displayState.events.isEmpty()) {
            val emptyLayout = FullscreenLayout(
                displayGeneration = displayState.generation,
                geometryVersion = geometryVersion,
                events = emptyList(),
                viewport = viewport,
                maxScroll = 0f
            )
            fullscreenLayoutCache = emptyLayout
            return emptyLayout
        }

        val titleLineHeight = lineHeight(titlePaintFullscreen)
        val detailLineHeight = lineHeight(detailPaintFullscreen)
        val maxWidth = (viewport.width() - 18f).coerceAtLeast(1f)
        val layouts = mutableListOf<EventLayout>()
        var cursorY = viewport.top
        displayState.events.forEachIndexed { index, event ->
            val titleLines = fitLines(
                toWrappedLines(event.title, titlePaintFullscreen, maxWidth),
                ITEM_TITLE_MAX_LINES,
                titlePaintFullscreen,
                maxWidth
            )
            val itemHeight = titleLines.size * titleLineHeight + detailLineHeight + detailLineHeight + CONTENT_GAP
            val bottom = cursorY + itemHeight
            layouts.add(EventLayout(index, titleLines, cursorY, bottom))
            cursorY = bottom + EVENT_GAP
        }

        val contentHeight = max(0f, cursorY - viewport.top - EVENT_GAP)
        val layout = FullscreenLayout(
            displayGeneration = displayState.generation,
            geometryVersion = geometryVersion,
            events = layouts,
            viewport = viewport,
            maxScroll = (contentHeight - viewport.height()).coerceAtLeast(0f)
        )
        fullscreenLayoutCache = layout
        return layout
    }

    private fun ensureDisplayState(nowMs: Long = System.currentTimeMillis()): DisplayState {
        val cached = displayStateCache
        if (cached != null && nowMs < cached.nextRefreshAtMs) {
            return cached
        }

        val currentSnapshot = snapshot
        val staleBoundary = snapshotFreshnessBoundary(currentSnapshot, nowMs)

        if (currentSnapshot == null) {
            val state = DisplayState(
                generation = nextDisplayGeneration(),
                events = emptyList(),
                emptyStateLine = "No calendar snapshot yet",
                freshnessLabel = "waiting",
                nextRefreshAtMs = staleBoundary
            )
            displayStateCache = state
            return state
        }

        val upcoming = mutableListOf<DisplayEvent>()
        var nextRefreshAtMs = staleBoundary
        for (event in parsedEvents) {
            if (event.isAllDay) {
                val dayDiff = dayDifference(nowMs, event.startMs)
                if (dayDiff < 0L) continue
            } else if (event.startMs < nowMs) {
                continue
            }

            upcoming += DisplayEvent(
                title = event.event.summary.ifBlank { "(No title)" },
                relativeLabel = formatRelative(event.startMs, event.isAllDay, nowMs),
                scheduleLabel = formatSchedule(event.startMs, event.isAllDay, nowMs),
                startMs = event.startMs,
                isAllDay = event.isAllDay
            )
            nextRefreshAtMs = min(nextRefreshAtMs, nextDisplayRefreshBoundary(event, nowMs))
        }

        val emptyStateLine = when {
            upcoming.isNotEmpty() -> ""
            currentSnapshot.events.isEmpty() -> "No upcoming events found"
            parsedEvents.isEmpty() -> "Could not parse upcoming events"
            else -> "No upcoming events found"
        }
        val state = DisplayState(
            generation = nextDisplayGeneration(),
            events = upcoming,
            emptyStateLine = emptyStateLine,
            freshnessLabel = if (isSnapshotStale(currentSnapshot, nowMs)) "cached" else "synced",
            nextRefreshAtMs = nextRefreshAtMs
        )
        displayStateCache = state
        return state
    }

    private fun nextDisplayGeneration(): Long {
        displayGeneration += 1
        return displayGeneration
    }

    private fun nextDisplayRefreshBoundary(event: ParsedEvent, nowMs: Long): Long {
        val nextMidnight = nextLocalMidnightAfter(nowMs)
        return if (event.isAllDay) {
            nextMidnight
        } else {
            val diffMs = event.startMs - nowMs
            if (diffMs <= MINUTE_MS) {
                (event.startMs + 1L).coerceAtLeast(nowMs + 1L)
            } else if (diffMs < DAY_MS) {
                val totalMinutes = max(1L, diffMs / MINUTE_MS)
                min(
                    (event.startMs - totalMinutes * MINUTE_MS + 1L).coerceAtLeast(nowMs + 1L),
                    event.startMs + 1L
                )
            } else {
                min(
                    min(nextMidnight, (event.startMs - DAY_MS + 1L).coerceAtLeast(nowMs + 1L)),
                    event.startMs + 1L
                )
            }
        }
    }

    fun handleFontMenuTapOrDismiss(px: Float, py: Float): Boolean {
        if (!showFontMenu) return false
        when {
            decreaseFontBounds.contains(px, py) -> setFontScale(fontScale - FONT_SCALE_STEP)
            increaseFontBounds.contains(px, py) -> setFontScale(fontScale + FONT_SCALE_STEP)
            fontMenuBounds.contains(px, py) -> return true
            else -> {
                showFontMenu = false
                hoveredFontMenuAction = null
                onStateChanged?.invoke()
            }
        }
        return true
    }

    private fun snapshotFreshnessBoundary(snapshot: GoogleCalendarSnapshot?, nowMs: Long): Long {
        if (snapshot == null || snapshot.staleAfterMs <= 0L) return Long.MAX_VALUE
        val staleAtMs = snapshot.fetchedAtMs + snapshot.staleAfterMs
        return if (nowMs <= staleAtMs) staleAtMs + 1L else Long.MAX_VALUE
    }

    private fun isSnapshotStale(snapshot: GoogleCalendarSnapshot, nowMs: Long): Boolean {
        return snapshot.staleAfterMs > 0L && nowMs > snapshot.fetchedAtMs + snapshot.staleAfterMs
    }

    private fun formatSchedule(startMs: Long, isAllDay: Boolean, nowMs: Long): String {
        val dayDiff = dayDifference(nowMs, startMs)
        val dateText = when (dayDiff) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> scheduleDateFormatter.format(Date(startMs))
        }
        return if (isAllDay) "$dateText - All day" else "$dateText, ${scheduleTimeFormatter.format(Date(startMs))}"
    }

    private fun formatRelative(startMs: Long, isAllDay: Boolean, nowMs: Long): String {
        if (isAllDay) {
            val dayDiff = dayDifference(nowMs, startMs)
            return when {
                dayDiff <= 0L -> "Today"
                dayDiff < 30L -> "${pluralize(dayDiff, "day")} left"
                dayDiff < 365L -> "${pluralize(ceil(dayDiff / 30.0).toLong(), "month")} left"
                else -> "${pluralize(ceil(dayDiff / 365.0).toLong(), "year")} left"
            }
        }

        val diffMs = startMs - nowMs
        if (diffMs <= 60_000L) return "Starting now"
        if (diffMs < 24L * 60L * 60L * 1000L) {
            val totalMinutes = max(1L, diffMs / 60_000L)
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return when {
                hours <= 0L -> "${minutes}m left"
                minutes == 0L -> "${hours}h left"
                else -> "${hours}h ${minutes}m left"
            }
        }

        val dayDiff = dayDifference(nowMs, startMs).coerceAtLeast(1L)
        return when {
            dayDiff < 30L -> "${pluralize(dayDiff, "day")} left"
            dayDiff < 365L -> "${pluralize(ceil(dayDiff / 30.0).toLong(), "month")} left"
            else -> "${pluralize(ceil(dayDiff / 365.0).toLong(), "year")} left"
        }
    }

    private fun pluralize(value: Long, unit: String): String =
        "$value $unit" + if (value == 1L) "" else "s"

    private fun parseEventTime(startIso: String): ParsedEventTime? {
        if (startIso.isBlank()) return null

        val dateOnly = runCatching { allDayParser.parse(startIso) }.getOrNull()
        if (dateOnly != null && startIso.length == 10) {
            return ParsedEventTime(dateOnly.time, true)
        }

        for (parser in dateTimeParsers) {
            val parsed = runCatching { parser.parse(startIso) }.getOrNull()
            if (parsed != null) return ParsedEventTime(parsed.time, false)
        }
        return null
    }

    private fun dayDifference(fromMs: Long, toMs: Long): Long {
        val fromMidnight = runCatching {
            localDayFormatter.parse(localDayFormatter.format(Date(fromMs)))?.time
        }.getOrNull() ?: return 0L
        val toMidnight = runCatching {
            localDayFormatter.parse(localDayFormatter.format(Date(toMs)))?.time
        }.getOrNull() ?: return 0L
        return (toMidnight - fromMidnight) / DAY_MS
    }

    private fun nextLocalMidnightAfter(nowMs: Long): Long {
        val midnightMs = runCatching {
            localDayFormatter.parse(localDayFormatter.format(Date(nowMs)))?.time
        }.getOrNull() ?: return nowMs + DAY_MS
        return midnightMs + DAY_MS
    }

    private fun toWrappedLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        val paragraphs = text.replace("\r", "").split('\n')
        for ((index, paragraph) in paragraphs.withIndex()) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) {
                lines.add("")
                continue
            }

            var start = 0
            while (start < trimmed.length) {
                val count = paint.breakText(trimmed, start, trimmed.length, true, maxWidth, null)
                if (count <= 0) break
                var end = (start + count).coerceAtMost(trimmed.length)
                if (end < trimmed.length) {
                    val split = trimmed.lastIndexOf(' ', end - 1)
                    if (split > start) end = split + 1
                }
                val line = trimmed.substring(start, end).trim()
                if (line.isNotEmpty()) lines.add(line)
                start = end
            }
            if (index != paragraphs.lastIndex) lines.add("")
        }
        return lines
    }

    private fun fitLines(lines: List<String>, maxLines: Int, paint: TextPaint, maxWidth: Float): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (lines.size <= maxLines) return lines
        val head = lines.take(maxLines - 1)
        return head + TextUtils.ellipsize(lines[maxLines - 1], paint, maxWidth, TextUtils.TruncateAt.END).toString()
    }

    private fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * 1.08f
    }

    private fun applyFontScale() {
        titlePaintWindow.textSize = BASE_TITLE_WINDOW_SIZE * fontScale
        detailPaintWindow.textSize = BASE_DETAIL_WINDOW_SIZE * fontScale
        titlePaintFullscreen.textSize = BASE_TITLE_FULLSCREEN_SIZE * fontScale
        detailPaintFullscreen.textSize = BASE_DETAIL_FULLSCREEN_SIZE * fontScale
        metaPaintFullscreen.textSize = BASE_DETAIL_FULLSCREEN_SIZE * 0.92f * fontScale
        centerInfoPaint.textSize = BASE_CENTER_INFO_SIZE * fontScale
    }

    private fun updateInternalBounds() {
        val iconSize = min(72f, (contentBounds.height() - WINDOW_PADDING * 2).coerceAtLeast(40f))
        val iconTop = contentBounds.centerY() - iconSize / 2f
        windowIconBounds.set(
            contentBounds.left + WINDOW_PADDING,
            iconTop,
            contentBounds.left + WINDOW_PADDING + iconSize,
            iconTop + iconSize
        )

        val menuLeft = contentBounds.centerX() - FONT_MENU_WIDTH / 2f
        val menuTop = contentBounds.top + WINDOW_PADDING + 6f
        fontMenuBounds.set(menuLeft, menuTop, menuLeft + FONT_MENU_WIDTH, menuTop + FONT_MENU_HEIGHT)

        val buttonTop = fontMenuBounds.bottom - FONT_MENU_BUTTON_SIZE - 10f
        decreaseFontBounds.set(
            fontMenuBounds.left + 10f,
            buttonTop,
            fontMenuBounds.left + 10f + FONT_MENU_BUTTON_SIZE,
            buttonTop + FONT_MENU_BUTTON_SIZE
        )
        increaseFontBounds.set(
            fontMenuBounds.right - 10f - FONT_MENU_BUTTON_SIZE,
            buttonTop,
            fontMenuBounds.right - 10f,
            buttonTop + FONT_MENU_BUTTON_SIZE
        )
    }
}
