package com.everyday.everyday_glasses.widgets.news

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
import com.everyday.everyday_glasses.interaction.user_input.TextEditAction
import com.everyday.everyday_glasses.interaction.user_input.TextZone
import com.everyday.everyday_glasses.interaction.user_input.TextZoneKind
import com.everyday.everyday_glasses.runtime.NewsWidgetListener
import com.everyday.everyday_glasses.widgets._common.WidgetDataState
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.shared.sync.NewsArticle
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceKind
import com.everyday.shared.sync.NewsSourceSpec
import java.util.Locale
import kotlin.math.abs
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import com.everyday.everyday_glasses.widgets._common.BaseWidget

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
 * - Double-tap opens the combined font/source options panel in windowed mode, and exits
 *   fullscreen when expanded
 */
class NewsWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(WidgetTypeId.NEWS, x, y, width, height) {

    companion object {
        private const val TAG = "NewsWidget"

        const val DEFAULT_WIDTH = 420f
        const val DEFAULT_HEIGHT = 250f

        private const val AUTO_INTERVAL_MS = 60_000L
        private const val WINDOW_PADDING = 16f
        private const val FULLSCREEN_PADDING = 26f
        private const val CONTENT_GAP = 10f
        private const val BORDER_BADGE_GAP = 6f
        private const val BORDER_BADGE_HEIGHT = 26f
        private const val BASE_TITLE_WINDOW_SIZE = 24f
        private const val BASE_TITLE_FULLSCREEN_SIZE = 40f
        private const val BASE_BODY_FULLSCREEN_SIZE = 28f
        private const val BASE_CENTER_INFO_SIZE = 24f
        private const val FONT_SCALE_MIN = 0.75f
        private const val FONT_SCALE_MAX = 1.55f
        private const val FONT_SCALE_STEP = 0.10f
        private const val FONT_MENU_WIDTH = 178f
        private const val OPTIONS_PANEL_WIDTH = 390f
        private const val OPTIONS_PANEL_MIN_HEIGHT = 246f
        private const val OPTIONS_PANEL_MAX_HEIGHT = 300f
        private const val OPTIONS_ROW_HEIGHT = 34f
        private const val OPTIONS_BUTTON_HEIGHT = 28f
        private const val SOURCE_INPUT_MAX_LENGTH = 512
        private const val FULLSCREEN_OPTIONS_SIZE = 44f
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
    private enum class SourcePanelField { QUERY, CUSTOM_RSS }

    // ==================== Widget State ====================

    override val minimizeLabel: String = "N"
    override val minWidth: Float = MIN_WIDGET_SIZE
    override val minHeight: Float = MIN_WIDGET_SIZE

    private val newsItems = mutableListOf<NewsArticle>()
    private var currentIndex = 0

    private var currentCountryCode = NewsDataProvider.defaultCountryCode()
    private var sourceSpec: NewsSourceSpec = NewsSourceSpec.DEFAULT_LOCATION
    private var pendingSourceSpec: NewsSourceSpec? = null
    private var pendingSourceCompletion: ((Result<NewsSnapshot>) -> Unit)? = null
    private val dataState = WidgetDataState(
        loadingText = "Loading news...",
        emptyText = "No news"
    )
    private var fontScale = 1f

    // Content scroll (fullscreen)
    private var contentScrollOffset = 0f

    // Timer
    private val handler = Handler(Looper.getMainLooper())

    var listener: NewsWidgetListener? = null
    private val onStateChanged: (() -> Unit)? get() = listener?.let { value -> { value.onStateChanged() } }

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
    private val sourcePanelBounds = RectF()
    private val defaultSourceBounds = RectF()
    private val querySourceBounds = RectF()
    private val customRssSourceBounds = RectF()
    private val applySourceBounds = RectF()
    private val cancelSourceBounds = RectF()
    private val fullscreenOptionsBounds = RectF()
    private var showSourcePanel = false
    private var sourcePanelField: SourcePanelField? = null
    private var sourcePanelQuery = ""
    private var sourcePanelCustomRss = ""
    private var sourcePanelError: String? = null

    /** A source panel input; typing, selection and clipboard are handled by the text editing controller. */
    private inner class SourceFieldZone(val field: SourcePanelField, private val row: RectF) : TextZone {
        private val textBounds = RectF()
        override val kind = TextZoneKind.FIELD
        override val bounds: RectF
            get() = textBounds.apply { set(row.left + 88f, row.top, row.right - 6f, row.bottom) }
        override val fieldTextSize: Float get() = sourcePanelSecondaryPaint.textSize
        override fun content(): CharSequence = when (field) {
            SourcePanelField.QUERY -> sourcePanelQuery
            SourcePanelField.CUSTOM_RSS -> sourcePanelCustomRss
        }
        override fun onTextEditAction(action: TextEditAction) {
            when (action) {
                is TextEditAction.Changed -> {
                    val value = action.content.toString().take(SOURCE_INPUT_MAX_LENGTH)
                    when (field) {
                        SourcePanelField.QUERY -> sourcePanelQuery = value
                        SourcePanelField.CUSTOM_RSS -> sourcePanelCustomRss = value
                    }
                    sourcePanelError = null
                }
                is TextEditAction.Submitted -> submitSourcePanelField()
                TextEditAction.Released -> if (sourcePanelField == field) sourcePanelField = null
                TextEditAction.LayoutChanged -> Unit
            }
            onStateChanged?.invoke()
        }
    }

    private val queryFieldZone = SourceFieldZone(SourcePanelField.QUERY, querySourceBounds)
    private val customRssFieldZone = SourceFieldZone(SourcePanelField.CUSTOM_RSS, customRssSourceBounds)

    private fun zoneFor(field: SourcePanelField): TextZone = when (field) {
        SourcePanelField.QUERY -> queryFieldZone
        SourcePanelField.CUSTOM_RSS -> customRssFieldZone
    }

    // ==================== Paints ====================

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#B0BEC5".toColorInt()
        textSize = 14f
    }

    private val borderBadgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val borderBadgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#667FA1C4".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val borderBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#D6E3F8".toColorInt()
        textSize = 13f
        textAlign = Paint.Align.CENTER
    }

    private val borderFlagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
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
        color = "#E0E0E0".toColorInt()
        textSize = BASE_BODY_FULLSCREEN_SIZE
    }

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#90A4AE".toColorInt()
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val centerInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#9E9E9E".toColorInt()
        textSize = BASE_CENTER_INFO_SIZE
        textAlign = Paint.Align.CENTER
    }

    private val arrowBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#A0000000".toColorInt()
        style = Paint.Style.FILL
    }

    private val arrowBackgroundHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CC4488AA".toColorInt()
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
        color = "#33445566".toColorInt()
        style = Paint.Style.FILL
    }

    private val scrollbarThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#88AECBFF".toColorInt()
        style = Paint.Style.FILL
    }

    private val fontMenuBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#DD111827".toColorInt()
        style = Paint.Style.FILL
    }

    private val fontMenuBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#667FA1C4".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val fontMenuButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#334A6178".toColorInt()
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

    private val sourcePanelTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f
        textAlign = Paint.Align.CENTER
    }

    private val sourcePanelRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#334A6178".toColorInt()
        style = Paint.Style.FILL
    }

    private val sourcePanelRowSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#5578A7D5".toColorInt()
        style = Paint.Style.FILL
    }

    private val sourcePanelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f
    }

    private val sourcePanelSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#B0BEC5".toColorInt()
        textSize = 12f
    }

    private val sourcePanelErrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFB4AB".toColorInt()
        textSize = 12f
    }

    private val sourcePanelButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#405D7A96".toColorInt()
        style = Paint.Style.FILL
    }

    private val sourcePanelButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f
        textAlign = Paint.Align.CENTER
    }

    private val fullscreenOptionsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CC111827".toColorInt()
        style = Paint.Style.FILL
    }

    private val fullscreenOptionsBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#99AECBFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val fullscreenOptionsIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
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

    fun getSourceSpec(): NewsSourceSpec = sourceSpec

    /** Restore a persisted source without performing a network request. */
    fun setSourceSpec(spec: NewsSourceSpec) {
        pendingSourceCompletion?.invoke(
            Result.failure(IllegalStateException("News source request was replaced"))
        )
        pendingSourceCompletion = null
        pendingSourceSpec = null
        sourceSpec = spec
        sourcePanelError = null
        onStateChanged?.invoke()
    }

    /** A compact description used by assistant context and diagnostics. */
    fun getSourceDescription(): String = sourceSpec.description

    /**
     * Ask the data coordinator to validate and fetch a new source. The current source remains
     * active until a non-empty RSS/Atom snapshot is returned successfully.
     */
    fun requestSource(spec: NewsSourceSpec, completion: (Result<NewsSnapshot>) -> Unit) {
        if (spec == sourceSpec && pendingSourceSpec == null) {
            completion(Result.success(NewsSnapshot(currentCountryCode, newsItems.toList(), sourceSpec = sourceSpec)))
            if (showSourcePanel) closeSourcePanel()
            return
        }

        pendingSourceCompletion?.invoke(
            Result.failure(IllegalStateException("News source request was replaced"))
        )
        pendingSourceSpec = spec
        pendingSourceCompletion = completion
        sourcePanelError = null
        dataState.startLoading(clearError = false)
        onStateChanged?.invoke()

        val callback: (Result<NewsSnapshot>) -> Unit = { result ->
            handler.post { finishSourceRequest(spec, result) }
        }
        val request = listener
        if (request == null) {
            callback(Result.failure(IllegalStateException("News data service is unavailable")))
        } else {
            runCatching {
                request.onSourceDataRequested(spec, currentCountryCode, true, "news_source_changed", callback)
            }.onFailure { callback(Result.failure(it)) }
        }
    }

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
        if (abs(clamped - fontScale) < 0.001f) return
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
        releaseSourcePanelField()
    }

    fun release() = onDestroy()

    fun handleFontMenuTapOrDismiss(px: Float, py: Float): Boolean =
        handleNewsOptionsTapOrDismiss(px, py)

    fun handleNewsOptionsTapOrDismiss(px: Float, py: Float): Boolean {
        if (!showSourcePanel) return false

        when {
            decreaseFontBounds.contains(px, py) -> setFontScale(fontScale - FONT_SCALE_STEP)
            increaseFontBounds.contains(px, py) -> setFontScale(fontScale + FONT_SCALE_STEP)
            defaultSourceBounds.contains(px, py) -> requestSourceFromPanel(NewsSourceSpec.DEFAULT_LOCATION)
            querySourceBounds.contains(px, py) -> selectSourcePanelField(SourcePanelField.QUERY, px, py)
            customRssSourceBounds.contains(px, py) -> selectSourcePanelField(SourcePanelField.CUSTOM_RSS, px, py)
            applySourceBounds.contains(px, py) -> submitSourcePanelField()
            cancelSourceBounds.contains(px, py) -> closeSourcePanel()
            sourcePanelBounds.contains(px, py) -> return true
            else -> closeSourcePanel()
        }
        return true
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (handleNewsOptionsTapOrDismiss(px, py)) return true

        if (NewsInteractionPolicy.isFullscreenOptionsHit(
                isFullscreen,
                px,
                py,
                fullscreenOptionsBounds.left,
                fullscreenOptionsBounds.top,
                fullscreenOptionsBounds.right,
                fullscreenOptionsBounds.bottom
            )
        ) {
            openSourcePanel()
            return true
        }

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
        when (NewsInteractionPolicy.doubleTapAction(isFullscreen, showSourcePanel)) {
            NewsDoubleTapAction.EXIT_FULLSCREEN -> toggleFullscreen()
            NewsDoubleTapAction.CLOSE_SOURCE_PANEL -> closeSourcePanel()
            NewsDoubleTapAction.OPEN_SOURCE_PANEL -> openSourcePanel()
        }
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
        closeSourcePanel()
        restartSwitchTimer()
    }

    override fun exitFullscreen() {
        super.exitFullscreen()
        contentScrollOffset = 0f
        closeSourcePanel()
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
            !showSourcePanel -> null
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

        if (newsItems.isEmpty()) {
            drawCenteredState(canvas)
            if (isFullscreen) drawFullscreenOptionsButton(canvas)
        } else if (isFullscreen) {
            drawFullscreenContent(canvas)
        } else {
            drawWindowedTitle(canvas)
        }

        if (shouldShowChrome()) {
            drawBorderIndicators(canvas)
        }

        if (showArrowOverlay && newsItems.isNotEmpty()) {
            drawNavigationArrows(canvas)
        }

        if (showSourcePanel) {
            drawSourcePanel(canvas)
        }
    }

    // ==================== Drawing Helpers ====================

    private fun drawCenteredState(canvas: Canvas) {
        val text = dataState.displayText()
        val y = widgetBounds.centerY() - (centerInfoPaint.descent() + centerInfoPaint.ascent()) / 2f
        canvas.drawText(text, widgetBounds.centerX(), y, centerInfoPaint)
    }

    private fun drawWindowedTitle(canvas: Canvas) {
        val item = newsItems.getOrNull(currentIndex) ?: return
        val showChrome = shouldShowChrome()

        val maxWidth = (contentBounds.width() - WINDOW_PADDING * 2f).coerceAtLeast(1f)
        val availableHeight = (contentBounds.height() - WINDOW_PADDING * 2f).coerceAtLeast(1f)

        val lineHeight = lineHeight(titlePaintWindow)
        val maxLinesByHeight = maxOf(1, (availableHeight / lineHeight).toInt())
        val titleLines = toWrappedLines(item.title, titlePaintWindow, maxWidth)

        val drawLines = if (titleLines.size > maxLinesByHeight) {
            titleLines.take(maxLinesByHeight - 1) + ellipsizeLine(titleLines[maxLinesByHeight - 1], titlePaintWindow, maxWidth)
        } else {
            titleLines
        }

        val textBlockWidth = maxMeasuredWidth(drawLines, titlePaintWindow).coerceAtMost(maxWidth)
        val textBlockHeight = drawLines.size * lineHeight
        val left = contentBounds.left + ((contentBounds.width() - textBlockWidth) / 2f).coerceAtLeast(WINDOW_PADDING)
        val top = contentBounds.top + ((contentBounds.height() - textBlockHeight) / 2f).coerceAtLeast(WINDOW_PADDING)

        var y = top + lineHeight
        for (line in drawLines) {
            canvas.drawText(line, left, y, titlePaintWindow)
            y += lineHeight
        }

        if (showChrome) {
            val status = "${currentIndex + 1}/${newsItems.size}"
            val statusY = contentBounds.bottom - WINDOW_PADDING
            val right = contentBounds.right - WINDOW_PADDING
            canvas.drawText(status, right, statusY, statusPaint)
        }
    }

    private fun drawFullscreenContent(canvas: Canvas) {
        val item = newsItems.getOrNull(currentIndex) ?: return
        val layout = buildFullscreenLayout()

        drawFullscreenOptionsButton(canvas)

        val left = contentBounds.left + FULLSCREEN_PADDING
        val top = contentBounds.top + FULLSCREEN_PADDING
        val titleLineHeight = lineHeight(titlePaintFullscreen)

        var y = top + titleLineHeight
        for (line in layout.titleLines) {
            canvas.drawText(line, left, y, titlePaintFullscreen)
            y += titleLineHeight
        }

        // Body (scrollable)
        canvas.withClip(layout.bodyViewport) {
            var bodyY = layout.bodyViewport.top + layout.bodyLineHeight - contentScrollOffset
            for (line in layout.bodyLines) {
                drawText(line, left, bodyY, bodyPaintFullscreen)
                bodyY += layout.bodyLineHeight
            }
        }

        if (shouldShowChrome()) {
            drawFullscreenFooter(canvas, item)
        }
        drawBodyScrollbar(canvas, layout)
    }

    private fun drawFullscreenOptionsButton(canvas: Canvas) {
        canvas.drawRoundRect(fullscreenOptionsBounds, 10f, 10f, fullscreenOptionsPaint)
        canvas.drawRoundRect(fullscreenOptionsBounds, 10f, 10f, fullscreenOptionsBorderPaint)
        val centerX = fullscreenOptionsBounds.centerX()
        val centerY = fullscreenOptionsBounds.centerY()
        val dotSpacing = 8f
        canvas.drawCircle(centerX - dotSpacing, centerY, 3f, fullscreenOptionsIconPaint)
        canvas.drawCircle(centerX, centerY, 3f, fullscreenOptionsIconPaint)
        canvas.drawCircle(centerX + dotSpacing, centerY, 3f, fullscreenOptionsIconPaint)
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

    private fun drawBorderIndicators(canvas: Canvas) {
        val flag = countryFlagEmoji(currentCountryCode)
        val status = newsStatusLabel()
        val badgeTop = widgetBounds.top - BORDER_BADGE_HEIGHT / 2f
        val badgeBottom = widgetBounds.top + BORDER_BADGE_HEIGHT / 2f
        var left = pinButtonBounds.right + BORDER_BADGE_GAP

        val flagWidth = 32f
        val flagBounds = RectF(left, badgeTop, left + flagWidth, badgeBottom)
        canvas.drawRoundRect(flagBounds, 8f, 8f, borderBadgeBackgroundPaint)
        canvas.drawRoundRect(flagBounds, 8f, 8f, borderBadgeBorderPaint)
        val flagY = flagBounds.centerY() - (borderFlagPaint.descent() + borderFlagPaint.ascent()) / 2f
        canvas.drawText(flag, flagBounds.centerX(), flagY, borderFlagPaint)

        left = flagBounds.right + BORDER_BADGE_GAP
        val statusWidth = (borderBadgeTextPaint.measureText(status) + 18f).coerceAtLeast(46f)
        val statusBounds = RectF(left, badgeTop, left + statusWidth, badgeBottom)
        canvas.drawRoundRect(statusBounds, 7f, 7f, borderBadgeBackgroundPaint)
        canvas.drawRoundRect(statusBounds, 7f, 7f, borderBadgeBorderPaint)
        val statusY = statusBounds.centerY() - (borderBadgeTextPaint.descent() + borderBadgeTextPaint.ascent()) / 2f
        canvas.drawText(status, statusBounds.centerX(), statusY, borderBadgeTextPaint)
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

    private fun drawSourcePanel(canvas: Canvas) {
        canvas.drawRoundRect(sourcePanelBounds, 12f, 12f, fontMenuBackgroundPaint)
        canvas.drawRoundRect(sourcePanelBounds, 12f, 12f, fontMenuBorderPaint)

        val titleY = sourcePanelBounds.top + 22f
        canvas.drawText("News options", sourcePanelBounds.centerX(), titleY, sourcePanelTitlePaint)

        val fontLabelY = fontMenuBounds.centerY() -
            (fontMenuLabelPaint.descent() + fontMenuLabelPaint.ascent()) / 2f
        canvas.drawText("Text ${(fontScale * 100f).toInt()}%", fontMenuBounds.centerX(), fontLabelY, fontMenuLabelPaint)
        canvas.drawRoundRect(decreaseFontBounds, 8f, 8f, fontMenuButtonPaint)
        canvas.drawRoundRect(increaseFontBounds, 8f, 8f, fontMenuButtonPaint)
        val decreaseY = decreaseFontBounds.centerY() -
            (fontMenuButtonTextPaint.descent() + fontMenuButtonTextPaint.ascent()) / 2f
        val increaseY = increaseFontBounds.centerY() -
            (fontMenuButtonTextPaint.descent() + fontMenuButtonTextPaint.ascent()) / 2f
        canvas.drawText("-", decreaseFontBounds.centerX(), decreaseY, fontMenuButtonTextPaint)
        canvas.drawText("+", increaseFontBounds.centerX(), increaseY, fontMenuButtonTextPaint)

        drawSourcePanelRow(
            canvas,
            defaultSourceBounds,
            "Default",
            "Location national + world",
            sourceSpec.kind == NewsSourceKind.DEFAULT_LOCATION && sourcePanelField == null
        )
        drawSourcePanelRow(
            canvas,
            querySourceBounds,
            "Query",
            sourcePanelQuery.ifBlank { "Tap, then type an intent" },
            sourcePanelField == SourcePanelField.QUERY ||
                (sourceSpec.kind == NewsSourceKind.QUERY && sourcePanelField == null),
            queryFieldZone
        )
        drawSourcePanelRow(
            canvas,
            customRssSourceBounds,
            "RSS URL",
            sourcePanelCustomRss.ifBlank { "Tap, then type an RSS/Atom URL" },
            sourcePanelField == SourcePanelField.CUSTOM_RSS ||
                (sourceSpec.kind == NewsSourceKind.CUSTOM_RSS && sourcePanelField == null),
            customRssFieldZone
        )

        sourcePanelError?.takeIf { it.isNotBlank() }?.let { error ->
            canvas.drawText(
                truncateSourceText(error, sourcePanelBounds.width() - 24f, sourcePanelErrorPaint),
                sourcePanelBounds.left + 12f,
                applySourceBounds.top - 8f,
                sourcePanelErrorPaint
            )
        }

        canvas.drawRoundRect(applySourceBounds, 8f, 8f, sourcePanelButtonPaint)
        canvas.drawRoundRect(cancelSourceBounds, 8f, 8f, sourcePanelButtonPaint)
        val buttonY = applySourceBounds.centerY() -
            (sourcePanelButtonTextPaint.descent() + sourcePanelButtonTextPaint.ascent()) / 2f
        canvas.drawText("Apply", applySourceBounds.centerX(), buttonY, sourcePanelButtonTextPaint)
        canvas.drawText("Cancel", cancelSourceBounds.centerX(), buttonY, sourcePanelButtonTextPaint)
    }

    private fun drawSourcePanelRow(
        canvas: Canvas,
        bounds: RectF,
        label: String,
        value: String,
        selected: Boolean,
        zone: TextZone? = null
    ) {
        canvas.drawRoundRect(
            bounds,
            8f,
            8f,
            if (selected) sourcePanelRowSelectedPaint else sourcePanelRowPaint
        )
        val labelY = bounds.centerY() - 3f
        canvas.drawText(label, bounds.left + 10f, labelY, sourcePanelTextPaint)
        if (zone != null && textZoneHost?.isFocused(zone) == true) {
            canvas.withClip(zone.bounds) { textZoneHost?.drawZone(this, zone) }
            return
        }
        canvas.drawText(
            truncateSourceText(value, bounds.width() - 94f, sourcePanelSecondaryPaint),
            bounds.left + 88f,
            labelY,
            sourcePanelSecondaryPaint
        )
    }

    private fun truncateSourceText(value: String, width: Float, paint: Paint): String {
        if (paint.measureText(value) <= width) return value
        var result = value
        while (result.length > 1 && paint.measureText("$result...") > width) {
            result = result.dropLast(1)
        }
        return "$result..."
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

    private fun finishSourceRequest(
        requestedSpec: NewsSourceSpec,
        result: Result<NewsSnapshot>
    ) {
        if (pendingSourceSpec != requestedSpec) return

        val completion = pendingSourceCompletion
        pendingSourceSpec = null
        pendingSourceCompletion = null

        result.fold(
            onSuccess = { snapshot ->
                if (snapshot.sourceSpec != requestedSpec ||
                    snapshot.countryCode != currentCountryCode ||
                    snapshot.items.isEmpty()
                ) {
                    val failure = IllegalStateException("Feed did not return usable news")
                    sourcePanelError = failure.message
                    dataState.markError(failure.message ?: "News source failed", showError = false)
                    onStateChanged?.invoke()
                    completion?.invoke(Result.failure(failure))
                } else {
                    sourceSpec = requestedSpec
                    sourcePanelError = null
                    // A successful source switch changes persisted configuration and the
                    // visible feed together. Let the cumulative persistent callback cover
                    // both paths without first emitting a redundant transient update.
                    applySnapshotInternal(snapshot, notify = false)
                    closeSourcePanel(notify = false)
                    listener?.onPersistentStateChanged()
                    completion?.invoke(Result.success(snapshot))
                }
            },
            onFailure = { failure ->
                sourcePanelError = failure.message ?: "News source validation failed"
                dataState.markError(sourcePanelError ?: "News source failed", showError = false)
                onStateChanged?.invoke()
                completion?.invoke(Result.failure(failure))
            }
        )
    }

    private fun openSourcePanel() {
        showFontMenu = false
        showSourcePanel = true
        sourcePanelField = null
        sourcePanelError = null
        sourcePanelQuery = sourceSpec.intent.orEmpty()
        sourcePanelCustomRss = sourceSpec.url.orEmpty()
        onStateChanged?.invoke()
    }

    private fun closeSourcePanel(notify: Boolean = true) {
        val wasOpen = showSourcePanel || sourcePanelField != null
        showSourcePanel = false
        releaseSourcePanelField()
        sourcePanelError = null
        if (wasOpen && notify) onStateChanged?.invoke()
    }

    private fun selectSourcePanelField(field: SourcePanelField, px: Float, py: Float) {
        sourcePanelField = field
        sourcePanelError = null
        textZoneHost?.tap(zoneFor(field), px, py)
        onStateChanged?.invoke()
    }

    private fun releaseSourcePanelField() {
        val field = sourcePanelField ?: return
        sourcePanelField = null
        textZoneHost?.release(zoneFor(field))
    }

    private fun requestSourceFromPanel(spec: NewsSourceSpec) {
        releaseSourcePanelField()
        sourcePanelError = null
        requestSource(spec) { /* The widget owns the result and keeps failures non-destructive. */ }
    }

    private fun submitSourcePanelField() {
        val spec = runCatching {
            when (sourcePanelField) {
                SourcePanelField.QUERY -> NewsSourceSpec.QUERY(sourcePanelQuery)
                SourcePanelField.CUSTOM_RSS -> NewsSourceSpec.CUSTOM_RSS(sourcePanelCustomRss)
                null -> throw IllegalArgumentException("Choose Query or RSS URL first")
            }
        }.getOrElse { failure ->
            sourcePanelError = failure.message ?: "Enter a news source"
            onStateChanged?.invoke()
            return
        }
        requestSourceFromPanel(spec)
    }

    fun requestData(force: Boolean, reason: String) {
        dataState.startLoading(clearError = newsItems.isEmpty())
        onStateChanged?.invoke()
        listener?.onDataRequested(currentCountryCode, force, reason)
    }

    fun applySnapshot(snapshot: NewsSnapshot) {
        if (snapshot.countryCode != currentCountryCode) {
            return
        }
        if (snapshot.sourceSpec != sourceSpec) {
            return
        }
        applySnapshotInternal(snapshot)
    }

    private fun applySnapshotInternal(snapshot: NewsSnapshot, notify: Boolean = true) {
        val previousLink = newsItems.getOrNull(currentIndex)?.link
        newsItems.clear()
        newsItems.addAll(snapshot.items)
        currentIndex = resolveCurrentIndex(previousLink)
        contentScrollOffset = 0f
        dataState.markLoaded()
        Log.d(TAG, "News snapshot applied: items=${newsItems.size} country=$currentCountryCode")
        if (notify) onStateChanged?.invoke()
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

        val panelWidth = minOf(
            OPTIONS_PANEL_WIDTH,
            (contentBounds.width() - 8f).coerceAtLeast(160f)
        )
        val panelHeight = minOf(
            OPTIONS_PANEL_MAX_HEIGHT,
            maxOf(OPTIONS_PANEL_MIN_HEIGHT, contentBounds.height() - 4f)
        )
        val panelLeft = contentBounds.centerX() - panelWidth / 2f
        val panelTop = contentBounds.centerY() - panelHeight / 2f
        sourcePanelBounds.set(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight)

        val menuWidth = minOf(FONT_MENU_WIDTH, panelWidth - 20f)
        val menuLeft = sourcePanelBounds.centerX() - menuWidth / 2f
        val menuTop = sourcePanelBounds.top + 31f
        fontMenuBounds.set(
            menuLeft,
            menuTop,
            menuLeft + menuWidth,
            menuTop + OPTIONS_BUTTON_HEIGHT
        )

        val buttonSize = (OPTIONS_BUTTON_HEIGHT - 4f).coerceAtLeast(20f)
        val buttonTop = fontMenuBounds.centerY() - buttonSize / 2f
        decreaseFontBounds.set(
            fontMenuBounds.left + 6f,
            buttonTop,
            fontMenuBounds.left + 6f + buttonSize,
            buttonTop + buttonSize
        )
        increaseFontBounds.set(
            fontMenuBounds.right - 6f - buttonSize,
            buttonTop,
            fontMenuBounds.right - 6f,
            buttonTop + buttonSize
        )

        val rowLeft = sourcePanelBounds.left + 10f
        val rowRight = sourcePanelBounds.right - 10f
        val firstRowTop = fontMenuBounds.bottom + 8f
        defaultSourceBounds.set(rowLeft, firstRowTop, rowRight, firstRowTop + OPTIONS_ROW_HEIGHT)
        querySourceBounds.set(
            rowLeft,
            defaultSourceBounds.bottom + 4f,
            rowRight,
            defaultSourceBounds.bottom + 4f + OPTIONS_ROW_HEIGHT
        )
        customRssSourceBounds.set(
            rowLeft,
            querySourceBounds.bottom + 4f,
            rowRight,
            querySourceBounds.bottom + 4f + OPTIONS_ROW_HEIGHT
        )

        val buttonTopSource = sourcePanelBounds.bottom - OPTIONS_BUTTON_HEIGHT - 10f
        val buttonWidth = 86f.coerceAtMost((panelWidth - 30f) / 2f)
        applySourceBounds.set(
            sourcePanelBounds.right - buttonWidth - 10f,
            buttonTopSource,
            sourcePanelBounds.right - 10f,
            buttonTopSource + OPTIONS_BUTTON_HEIGHT
        )
        cancelSourceBounds.set(
            applySourceBounds.left - 8f - buttonWidth,
            buttonTopSource,
            applySourceBounds.left - 8f,
            buttonTopSource + OPTIONS_BUTTON_HEIGHT
        )

        fullscreenOptionsBounds.set(
            contentBounds.right - FULLSCREEN_OPTIONS_SIZE - 8f,
            contentBounds.top + 8f,
            contentBounds.right - 8f,
            contentBounds.top + 8f + FULLSCREEN_OPTIONS_SIZE
        )
    }

    private fun buildFullscreenLayout(): FullscreenLayout {
        val item = newsItems.getOrNull(currentIndex) ?: return FullscreenLayout(
            titleLines = emptyList(),
            bodyLines = emptyList(),
            bodyViewport = RectF(contentBounds),
            bodyLineHeight = lineHeight(bodyPaintFullscreen),
            maxScroll = 0f
        )

        val left = contentBounds.left + FULLSCREEN_PADDING
        val right = contentBounds.right - FULLSCREEN_PADDING
        val top = contentBounds.top + FULLSCREEN_PADDING
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

    private fun reservedFooterHeight(): Float = statusPaint.textSize + CONTENT_GAP

    private fun shouldShowChrome(): Boolean = baseState != BaseState.IDLE

    private fun ellipsizeLine(text: String, paint: TextPaint, width: Float): String {
        return TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
    }

    private fun maxMeasuredWidth(lines: List<String>, paint: Paint): Float =
        lines.maxOfOrNull { paint.measureText(it) } ?: 0f

    private fun newsStatusLabel(): String {
        return when {
            dataState.isLoading && newsItems.isEmpty() -> "Loading"
            dataState.isLoading -> "Refreshing"
            dataState.error != null && newsItems.isNotEmpty() -> "Cached"
            else -> "Live"
        }
    }

    private fun countryFlagEmoji(countryCode: String): String {
        val normalized = countryCode.uppercase(Locale.US).filter { it in 'A'..'Z' }.take(2)
        if (normalized.length != 2) return normalized.ifBlank { "?" }
        val first = String(Character.toChars(0x1F1E6 + (normalized[0] - 'A')))
        val second = String(Character.toChars(0x1F1E6 + (normalized[1] - 'A')))
        return first + second
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
