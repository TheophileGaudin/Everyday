package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import com.everyday.shared.sync.NewsArticle
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.NewsSnapshot

/**
 * Google News RSS widget.
 *
 * Behavior:
 * - Fetches national + world headlines (country-aware) from Google News RSS
 * - Shows title in windowed mode
 * - Opens fullscreen on tap with title + scrollable content
 * - Auto-refreshes list every 60s
 * - Auto-switches current displayed item every 60s (paused in fullscreen)
 * - Left/right overlay arrows on hover for previous/next item navigation
 * - Header and status chrome only appear on hover
 * - Double-tap opens the font-size popup in windowed mode, and exits fullscreen when expanded
 */
class NewsWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(x, y, width, height) {

    companion object {
        private const val TAG = "NewsWidget"

        const val DEFAULT_WIDTH = 420f
        const val DEFAULT_HEIGHT = 250f

        private const val AUTO_INTERVAL_MS = 60_000L
        private const val WINDOW_PADDING = 16f
        private const val FULLSCREEN_PADDING = 26f
        private const val CONTENT_GAP = 10f
        private const val MIN_TITLE_LINES_WINDOW = 3
        private const val BASE_TITLE_WINDOW_SIZE = 24f
        private const val BASE_TITLE_FULLSCREEN_SIZE = 40f
        private const val BASE_BODY_FULLSCREEN_SIZE = 28f
        private const val BASE_CENTER_INFO_SIZE = 24f
        private const val FONT_SCALE_MIN = 0.75f
        private const val FONT_SCALE_MAX = 1.55f
        private const val FONT_SCALE_STEP = 0.10f
        private const val FONT_MENU_WIDTH = 178f
        private const val FONT_MENU_HEIGHT = 58f
        private const val FONT_MENU_BUTTON_SIZE = 40f
    }

    private data class FullscreenLayout(
        val titleLines: List<String>,
        val bodyLines: List<String>,
        val bodyViewport: RectF,
        val bodyLineHeight: Float,
        val maxScroll: Float
    )

    private enum class ArrowDirection { LEFT, RIGHT }
    private enum class FontMenuAction { DECREASE, INCREASE }

    // ==================== Widget State ====================

    override val minimizeLabel: String = "N"
    override val minWidth: Float = 260f
    override val minHeight: Float = 140f

    private val newsItems = mutableListOf<NewsArticle>()
    private var currentIndex = 0

    private var currentCountryCode = NewsDataProvider.defaultCountryCode()
    private val dataState = WidgetDataState(
        loadingText = "Loading news...",
        emptyText = "No news"
    )
    private var fontScale = 1f

    // Content scroll (fullscreen)
    private var contentScrollOffset = 0f

    // Timer
    private val handler = Handler(Looper.getMainLooper())

    var onStateChanged: (() -> Unit)? = null
    var onDataRequested: ((countryCode: String, force: Boolean, reason: String) -> Unit)? = null

    // Overlay arrows
    private val leftArrowBounds = RectF()
    private val rightArrowBounds = RectF()
    private var hoveredArrow: ArrowDirection? = null
    private var showArrowOverlay = false
    private val fontMenuBounds = RectF()
    private val decreaseFontBounds = RectF()
    private val increaseFontBounds = RectF()
    private var showFontMenu = false
    private var hoveredFontMenuAction: FontMenuAction? = null

    // ==================== Paints ====================

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 14f
    }

    private val titlePaintWindow = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BASE_TITLE_WINDOW_SIZE
    }

    private val titlePaintFullscreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BASE_TITLE_FULLSCREEN_SIZE
    }

    private val bodyPaintFullscreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = BASE_BODY_FULLSCREEN_SIZE
    }

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE")
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val centerInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = BASE_CENTER_INFO_SIZE
        textAlign = Paint.Align.CENTER
    }

    private val arrowBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0000000")
        style = Paint.Style.FILL
    }

    private val arrowBackgroundHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4488AA")
        style = Paint.Style.FILL
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

    private val fontMenuButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    // ==================== Periodic Tasks ====================

    private val switchRunnable = object : Runnable {
        override fun run() {
            if (!isFullscreen) {
                showNextItem()
            }
            handler.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    init {
        applyFontScale()
        updateInternalBounds()
        startTimers()
    }

    // ==================== Public API ====================

    fun setCountryCode(code: String) {
        val normalized = NewsDataProvider.normalizeCountryCode(code) ?: return
        if (normalized == currentCountryCode) return

        currentCountryCode = normalized
        currentIndex = 0
        contentScrollOffset = 0f
        dataState.clearError()
        onStateChanged?.invoke()
        requestData(force = true, reason = "country_changed")
        restartSwitchTimer()
        Log.d(TAG, "News country updated: $currentCountryCode")
    }

    fun getCountryCode(): String = currentCountryCode

    fun setSelectedIndex(index: Int) {
        if (newsItems.isEmpty()) {
            currentIndex = 0
            return
        }
        val newIndex = index.coerceIn(0, newsItems.lastIndex)
        if (newIndex != currentIndex) {
            currentIndex = newIndex
            contentScrollOffset = 0f
            onStateChanged?.invoke()
        }
    }

    fun getSelectedIndex(): Int = currentIndex

    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        if (kotlin.math.abs(clamped - fontScale) < 0.001f) return
        fontScale = clamped
        applyFontScale()
        onStateChanged?.invoke()
    }

    fun getFontScale(): Float = fontScale

    /**
     * On head-up wake, rotate to the next cached story immediately so the
     * user sees fresh content even before network refresh completes.
     */
    fun showNextItemOnHeadUpWake() {
        showNextItem()
        restartSwitchTimer()
    }

    override fun onPause() {
        stopTimers()
    }

    override fun onResume() {
        startTimers()
    }

    override fun onDestroy() {
        stopTimers()
    }

    fun release() = onDestroy()

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

    fun onTap(px: Float, py: Float): Boolean {
        if (handleFontMenuTapOrDismiss(px, py)) return true

        if (newsItems.isNotEmpty()) {
            when {
                leftArrowBounds.contains(px, py) -> {
                    showPreviousItem()
                    restartSwitchTimer()
                    return true
                }
                rightArrowBounds.contains(px, py) -> {
                    showNextItem()
                    restartSwitchTimer()
                    return true
                }
            }
        }

        if (!isFullscreen && contentBounds.contains(px, py) && newsItems.isNotEmpty()) {
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

    // ==================== Base Overrides ====================

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        updateInternalBounds()
    }

    override fun enterFullscreen() {
        super.enterFullscreen()
        contentScrollOffset = 0f
        showFontMenu = false
        restartSwitchTimer()
    }

    override fun exitFullscreen() {
        super.exitFullscreen()
        contentScrollOffset = 0f
        showFontMenu = false
        restartSwitchTimer()
    }

    override fun onScroll(dy: Float) {
        if (!isFullscreen) return
        val layout = buildFullscreenLayout()
        if (layout.maxScroll <= 0f) {
            contentScrollOffset = 0f
            return
        }
        contentScrollOffset = (contentScrollOffset + dy).coerceIn(0f, layout.maxScroll)
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)

        val hoveringWidget = baseState != BaseState.IDLE
        showArrowOverlay = hoveringWidget && newsItems.isNotEmpty()
        hoveredArrow = when {
            !showArrowOverlay -> null
            leftArrowBounds.contains(px, py) -> ArrowDirection.LEFT
            rightArrowBounds.contains(px, py) -> ArrowDirection.RIGHT
            else -> null
        }
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

        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)

        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        }
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }

        if (shouldShowChrome()) {
            drawHeader(canvas)
        }

        if (newsItems.isEmpty()) {
            drawCenteredState(canvas)
        } else if (isFullscreen) {
            drawFullscreenContent(canvas)
        } else {
            drawWindowedTitle(canvas)
        }

        if (showArrowOverlay && newsItems.isNotEmpty()) {
            drawNavigationArrows(canvas)
        }

        if (showFontMenu) {
            drawFontMenu(canvas)
        }
    }

    // ==================== Drawing Helpers ====================

    private fun drawHeader(canvas: Canvas) {
        val headline = buildString {
            append("News ")
            append(currentCountryCode)
            append(" ")
            append(if (dataState.isLoading) "- Refreshing" else "- Live")
        }
        val x = contentBounds.left + WINDOW_PADDING
        val y = contentBounds.top + WINDOW_PADDING + headerPaint.textSize
        canvas.drawText(headline, x, y, headerPaint)
    }

    private fun drawCenteredState(canvas: Canvas) {
        val text = dataState.displayText()
        val y = widgetBounds.centerY() - (centerInfoPaint.descent() + centerInfoPaint.ascent()) / 2f
        canvas.drawText(text, widgetBounds.centerX(), y, centerInfoPaint)
    }

    private fun drawWindowedTitle(canvas: Canvas) {
        val item = newsItems.getOrNull(currentIndex) ?: return
        val showChrome = shouldShowChrome()

        val top = contentBounds.top + WINDOW_PADDING + reservedHeaderHeight()
        val bottom = contentBounds.bottom - WINDOW_PADDING - reservedFooterHeight()
        val left = contentBounds.left + WINDOW_PADDING
        val right = contentBounds.right - WINDOW_PADDING
        val maxWidth = (right - left).coerceAtLeast(1f)
        val availableHeight = (bottom - top).coerceAtLeast(1f)

        val lineHeight = lineHeight(titlePaintWindow)
        val maxLinesByHeight = (availableHeight / lineHeight).toInt().coerceAtLeast(MIN_TITLE_LINES_WINDOW)
        val titleLines = toWrappedLines(item.title, titlePaintWindow, maxWidth)

        val drawLines = if (titleLines.size > maxLinesByHeight) {
            titleLines.take(maxLinesByHeight - 1) + ellipsizeLine(titleLines[maxLinesByHeight - 1], titlePaintWindow, maxWidth)
        } else {
            titleLines
        }

        var y = top + lineHeight
        for (line in drawLines) {
            canvas.drawText(line, left, y, titlePaintWindow)
            y += lineHeight
        }

        if (showChrome) {
            val status = "${currentIndex + 1}/${newsItems.size}"
            val statusY = contentBounds.bottom - WINDOW_PADDING
            canvas.drawText(status, right, statusY, statusPaint)
        }
    }

    private fun drawFullscreenContent(canvas: Canvas) {
        val item = newsItems.getOrNull(currentIndex) ?: return
        val layout = buildFullscreenLayout()

        val left = contentBounds.left + FULLSCREEN_PADDING
        val top = contentBounds.top + FULLSCREEN_PADDING + reservedHeaderHeight()
        val titleLineHeight = lineHeight(titlePaintFullscreen)

        var y = top + titleLineHeight
        for (line in layout.titleLines) {
            canvas.drawText(line, left, y, titlePaintFullscreen)
            y += titleLineHeight
        }

        // Body (scrollable)
        canvas.save()
        canvas.clipRect(layout.bodyViewport)

        var bodyY = layout.bodyViewport.top + layout.bodyLineHeight - contentScrollOffset
        for (line in layout.bodyLines) {
            canvas.drawText(line, left, bodyY, bodyPaintFullscreen)
            bodyY += layout.bodyLineHeight
        }
        canvas.restore()

        if (shouldShowChrome()) {
            drawFullscreenFooter(canvas, item)
        }
        drawBodyScrollbar(canvas, layout)
    }

    private fun drawFullscreenFooter(canvas: Canvas, item: NewsArticle) {
        val right = contentBounds.right - FULLSCREEN_PADDING
        val bottom = contentBounds.bottom - FULLSCREEN_PADDING

        val sourceParts = mutableListOf<String>()
        if (item.source.isNotBlank()) sourceParts.add(item.source)
        if (item.publishedAt.isNotBlank()) sourceParts.add(item.publishedAt)
        val sourceText = sourceParts.joinToString(" - ")

        if (sourceText.isNotBlank()) {
            val truncated = TextUtils.ellipsize(sourceText, statusPaint, contentBounds.width() * 0.65f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(truncated, contentBounds.left + FULLSCREEN_PADDING, bottom, headerPaint)
        }

        val status = "${currentIndex + 1}/${newsItems.size}"
        canvas.drawText(status, right, bottom, statusPaint)
    }

    private fun drawBodyScrollbar(canvas: Canvas, layout: FullscreenLayout) {
        if (layout.maxScroll <= 0f) return

        val trackWidth = 10f
        val track = RectF(
            layout.bodyViewport.right - trackWidth,
            layout.bodyViewport.top,
            layout.bodyViewport.right,
            layout.bodyViewport.bottom
        )
        canvas.drawRoundRect(track, 4f, 4f, scrollbarTrackPaint)

        val visibleHeight = layout.bodyViewport.height()
        val contentHeight = visibleHeight + layout.maxScroll
        val thumbHeight = (visibleHeight / contentHeight * visibleHeight).coerceAtLeast(34f)
        val thumbTravel = (visibleHeight - thumbHeight).coerceAtLeast(1f)
        val thumbTop = track.top + (contentScrollOffset / layout.maxScroll) * thumbTravel

        val thumb = RectF(track.left, thumbTop, track.right, thumbTop + thumbHeight)
        canvas.drawRoundRect(thumb, 4f, 4f, scrollbarThumbPaint)
    }

    private fun drawNavigationArrows(canvas: Canvas) {
        drawArrow(canvas, leftArrowBounds, ArrowDirection.LEFT, hoveredArrow == ArrowDirection.LEFT)
        drawArrow(canvas, rightArrowBounds, ArrowDirection.RIGHT, hoveredArrow == ArrowDirection.RIGHT)
    }

    private fun drawFontMenu(canvas: Canvas) {
        canvas.drawRoundRect(fontMenuBounds, 12f, 12f, fontMenuBackgroundPaint)
        canvas.drawRoundRect(fontMenuBounds, 12f, 12f, fontMenuBorderPaint)

        val decreasePaint = if (hoveredFontMenuAction == FontMenuAction.DECREASE) fontMenuButtonHoverPaint else fontMenuButtonPaint
        val increasePaint = if (hoveredFontMenuAction == FontMenuAction.INCREASE) fontMenuButtonHoverPaint else fontMenuButtonPaint
        canvas.drawRoundRect(decreaseFontBounds, 10f, 10f, decreasePaint)
        canvas.drawRoundRect(increaseFontBounds, 10f, 10f, increasePaint)

        val labelY = fontMenuBounds.centerY() - (fontMenuLabelPaint.descent() + fontMenuLabelPaint.ascent()) / 2f
        canvas.drawText("Text ${(fontScale * 100f).toInt()}%", fontMenuBounds.centerX(), labelY, fontMenuLabelPaint)

        val decreaseY = decreaseFontBounds.centerY() - (fontMenuButtonTextPaint.descent() + fontMenuButtonTextPaint.ascent()) / 2f
        val increaseY = increaseFontBounds.centerY() - (fontMenuButtonTextPaint.descent() + fontMenuButtonTextPaint.ascent()) / 2f
        canvas.drawText("-", decreaseFontBounds.centerX(), decreaseY, fontMenuButtonTextPaint)
        canvas.drawText("+", increaseFontBounds.centerX(), increaseY, fontMenuButtonTextPaint)
    }

    private fun drawArrow(canvas: Canvas, bounds: RectF, direction: ArrowDirection, hovered: Boolean) {
        canvas.drawOval(bounds, if (hovered) arrowBackgroundHoverPaint else arrowBackgroundPaint)

        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = bounds.width() * 0.22f
        val path = Path()

        if (direction == ArrowDirection.LEFT) {
            path.moveTo(cx + size, cy - size)
            path.lineTo(cx - size, cy)
            path.lineTo(cx + size, cy + size)
        } else {
            path.moveTo(cx - size, cy - size)
            path.lineTo(cx + size, cy)
            path.lineTo(cx - size, cy + size)
        }
        canvas.drawPath(path, arrowStrokePaint)
    }

    // ==================== Data Application ====================

    fun requestData(force: Boolean, reason: String) {
        dataState.startLoading(clearError = newsItems.isEmpty())
        onStateChanged?.invoke()
        onDataRequested?.invoke(currentCountryCode, force, reason)
    }

    fun applySnapshot(snapshot: NewsSnapshot) {
        if (snapshot.countryCode != currentCountryCode) {
            return
        }
        val previousLink = newsItems.getOrNull(currentIndex)?.link
        newsItems.clear()
        newsItems.addAll(snapshot.items)
        currentIndex = resolveCurrentIndex(previousLink)
        contentScrollOffset = 0f
        dataState.markLoaded()
        Log.d(TAG, "News snapshot applied: items=${newsItems.size} country=$currentCountryCode")
        onStateChanged?.invoke()
    }

    fun applyError(message: String = "No news available") {
        dataState.markError(message, showError = newsItems.isEmpty())
        onStateChanged?.invoke()
    }

    // ==================== Layout Helpers ====================

    private fun updateInternalBounds() {
        val arrowSize = (widgetHeight * 0.22f).coerceIn(44f, 92f)
        val cy = widgetBounds.centerY()
        val leftCx = widgetBounds.left + arrowSize * 0.72f
        val rightCx = widgetBounds.right - arrowSize * 0.72f

        leftArrowBounds.set(
            leftCx - arrowSize / 2f,
            cy - arrowSize / 2f,
            leftCx + arrowSize / 2f,
            cy + arrowSize / 2f
        )
        rightArrowBounds.set(
            rightCx - arrowSize / 2f,
            cy - arrowSize / 2f,
            rightCx + arrowSize / 2f,
            cy + arrowSize / 2f
        )

        val menuLeft = contentBounds.centerX() - FONT_MENU_WIDTH / 2f
        val menuTop = contentBounds.top + WINDOW_PADDING + 6f
        fontMenuBounds.set(
            menuLeft,
            menuTop,
            menuLeft + FONT_MENU_WIDTH,
            menuTop + FONT_MENU_HEIGHT
        )

        val buttonTop = fontMenuBounds.centerY() - FONT_MENU_BUTTON_SIZE / 2f
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

    private fun buildFullscreenLayout(): FullscreenLayout {
        val item = newsItems.getOrNull(currentIndex)
        if (item == null) {
            return FullscreenLayout(
                titleLines = emptyList(),
                bodyLines = emptyList(),
                bodyViewport = RectF(contentBounds),
                bodyLineHeight = lineHeight(bodyPaintFullscreen),
                maxScroll = 0f
            )
        }

        val left = contentBounds.left + FULLSCREEN_PADDING
        val right = contentBounds.right - FULLSCREEN_PADDING
        val top = contentBounds.top + FULLSCREEN_PADDING + reservedHeaderHeight()
        val bottom = contentBounds.bottom - FULLSCREEN_PADDING - reservedFooterHeight()
        val maxWidth = (right - left).coerceAtLeast(1f)

        val titleLines = toWrappedLines(item.title, titlePaintFullscreen, maxWidth).take(5)
        val titleHeight = titleLines.size * lineHeight(titlePaintFullscreen)

        val bodyTop = (top + titleHeight + CONTENT_GAP * 2f).coerceAtMost(bottom)
        val bodyViewport = RectF(left, bodyTop, right, bottom)

        val bodyText = buildString {
            if (item.source.isNotBlank()) append(item.source)
            if (item.publishedAt.isNotBlank()) {
                if (isNotEmpty()) append(" - ")
                append(item.publishedAt)
            }
            if (isNotEmpty()) append("\n\n")
            append(item.content)
        }

        val bodyLines = toWrappedLines(bodyText, bodyPaintFullscreen, maxWidth - 14f)
        val bodyLineHeight = lineHeight(bodyPaintFullscreen)
        val bodyHeight = bodyLines.size * bodyLineHeight
        val maxScroll = (bodyHeight - bodyViewport.height()).coerceAtLeast(0f)
        contentScrollOffset = contentScrollOffset.coerceIn(0f, maxScroll)

        return FullscreenLayout(
            titleLines = titleLines,
            bodyLines = bodyLines,
            bodyViewport = bodyViewport,
            bodyLineHeight = bodyLineHeight,
            maxScroll = maxScroll
        )
    }

    private fun toWrappedLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = mutableListOf<String>()

        val paragraphs = text.replace("\r", "").split('\n')
        for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
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
                    if (split > start) {
                        end = split + 1
                    }
                }
                val line = trimmed.substring(start, end).trim()
                if (line.isNotEmpty()) {
                    lines.add(line)
                }
                start = end
            }

            if (paragraphIndex != paragraphs.lastIndex) {
                lines.add("")
            }
        }
        return lines
    }

    private fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * 1.08f
    }

    private fun applyFontScale() {
        titlePaintWindow.textSize = BASE_TITLE_WINDOW_SIZE * fontScale
        titlePaintFullscreen.textSize = BASE_TITLE_FULLSCREEN_SIZE * fontScale
        bodyPaintFullscreen.textSize = BASE_BODY_FULLSCREEN_SIZE * fontScale
        centerInfoPaint.textSize = BASE_CENTER_INFO_SIZE * fontScale
    }

    private fun reservedHeaderHeight(): Float = headerPaint.textSize + CONTENT_GAP

    private fun reservedFooterHeight(): Float = statusPaint.textSize + CONTENT_GAP

    private fun shouldShowChrome(): Boolean = baseState != BaseState.IDLE

    private fun ellipsizeLine(text: String, paint: TextPaint, width: Float): String {
        return TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
    }

    // ==================== Timer Helpers ====================

    private fun startTimers() {
        handler.removeCallbacks(switchRunnable)
        handler.postDelayed(switchRunnable, AUTO_INTERVAL_MS)
    }

    private fun stopTimers() {
        handler.removeCallbacks(switchRunnable)
    }

    private fun restartSwitchTimer() {
        handler.removeCallbacks(switchRunnable)
        handler.postDelayed(switchRunnable, AUTO_INTERVAL_MS)
    }

    private fun showNextItem() {
        if (newsItems.size < 2) return
        currentIndex = (currentIndex + 1) % newsItems.size
        contentScrollOffset = 0f
        onStateChanged?.invoke()
    }

    private fun showPreviousItem() {
        if (newsItems.size < 2) return
        currentIndex = if (currentIndex - 1 < 0) newsItems.lastIndex else currentIndex - 1
        contentScrollOffset = 0f
        onStateChanged?.invoke()
    }

    private fun resolveCurrentIndex(previousLink: String?): Int {
        if (newsItems.isEmpty()) return 0
        if (!previousLink.isNullOrBlank()) {
            val same = newsItems.indexOfFirst { it.link == previousLink }
            if (same >= 0) return same
        }
        return currentIndex.coerceIn(0, newsItems.lastIndex)
    }

}
