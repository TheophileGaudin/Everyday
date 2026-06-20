package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.everyday.shared.sync.PhoneNotificationItem
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class NotificationsWidget(
    private val context: Context,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    private var screenHeight: Float
) : BaseWidget(x, y, width, height) {

    companion object {
        const val DEFAULT_WIDTH = 420f
        const val DEFAULT_HEIGHT = 220f

        private const val PADDING = 14f
        private const val ROW_GAP = 8f
        private const val FONT_SCALE_MIN = 0.75f
        private const val FONT_SCALE_MAX = 1.6f
        private const val FONT_SCALE_STEP = 0.1f
        private const val BASE_TITLE_SIZE = 20f
        private const val BASE_BODY_SIZE = 16f
        private const val BASE_META_SIZE = 13f
        private const val BASE_TOAST_TITLE_SIZE = 26f
        private const val BASE_TOAST_BODY_SIZE = 22f
        private const val FONT_MENU_WIDTH = 260f
        private const val FONT_MENU_HEIGHT = 116f
        private const val FONT_BUTTON_SIZE = 34f
        private const val WIDGET_SCROLL_STEP_THRESHOLD = 2f

        fun computeDisplayCount(widgetHeight: Float, screenHeight: Float): Int {
            val third = (screenHeight / 3f).coerceAtLeast(1f)
            return ceil(widgetHeight / third).toInt().coerceIn(1, 3)
        }
    }

    private enum class FontAction { WIDGET_DECREASE, WIDGET_INCREASE, TOAST_DECREASE, TOAST_INCREASE }

    override val minimizeLabel: String = "!"
    override val minWidth: Float = 260f
    override val minHeight: Float = 120f

    private val notifications = mutableListOf<PhoneNotificationItem>()
    private var phoneNotificationAccessEnabled = false
    private var firstVisibleIndex = 0
    private var widgetFontScale = 1f
    private var toastFontScale = 1f
    private var selectedToastKey: String? = null
    private var toastScrollOffset = 0f
    private var widgetScrollAccumulator = 0f
    private var showFontMenu = false
    private var hoveredFontAction: FontAction? = null

    private val rowBounds = mutableListOf<RectF>()
    private val toastBounds = RectF()
    private val toastViewport = RectF()
    private val fontMenuBounds = RectF()
    private val widgetMinusBounds = RectF()
    private val widgetPlusBounds = RectF()
    private val toastMinusBounds = RectF()
    private val toastPlusBounds = RectF()

    var onStateChanged: (() -> Unit)? = null

    private val rowBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141A22")
        style = Paint.Style.FILL
    }
    private val rowBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334A6178")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BASE_TITLE_SIZE
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D5DCE5")
        textSize = BASE_BODY_SIZE
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8EA0B5")
        textSize = BASE_META_SIZE
    }
    private val centerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA6B2")
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val toastBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE0D1117")
        style = Paint.Style.FILL
    }
    private val toastBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88AECBFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val toastTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BASE_TOAST_TITLE_SIZE
    }
    private val toastBodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5EAF0")
        textSize = BASE_TOAST_BODY_SIZE
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
        color = Color.parseColor("#EE111827")
        style = Paint.Style.FILL
    }
    private val fontMenuBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#667FA1C4")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val fontLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f
        textAlign = Paint.Align.CENTER
    }
    private val fontButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334A6178")
        style = Paint.Style.FILL
    }
    private val fontButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5578A7D5")
        style = Paint.Style.FILL
    }
    private val fontButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    init {
        applyFontScales()
        updateInternalBounds()
    }

    fun setScreenHeight(value: Float) {
        screenHeight = value.coerceAtLeast(1f)
        clampScrollIndex()
        updateInternalBounds()
    }

    fun setNotificationsSnapshot(snapshot: PhoneNotificationsSnapshot) {
        phoneNotificationAccessEnabled = snapshot.listenerEnabled
        setNotifications(snapshot.items)
    }

    fun setNotifications(items: List<PhoneNotificationItem>) {
        notifications.clear()
        notifications.addAll(items)
        if (selectedToastKey != null && notifications.none { it.key == selectedToastKey }) {
            selectedToastKey = null
            toastScrollOffset = 0f
        }
        widgetScrollAccumulator = 0f
        clampScrollIndex()
    }

    fun setScrollIndex(index: Int) {
        firstVisibleIndex = index
        widgetScrollAccumulator = 0f
        clampScrollIndex()
    }

    fun getScrollIndex(): Int = firstVisibleIndex

    fun setWidgetFontScale(scale: Float) {
        val clamped = scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        if (kotlin.math.abs(clamped - widgetFontScale) < 0.001f) return
        widgetFontScale = clamped
        applyFontScales()
        onStateChanged?.invoke()
    }

    fun getWidgetFontScale(): Float = widgetFontScale

    fun setToastFontScale(scale: Float) {
        val clamped = scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        if (kotlin.math.abs(clamped - toastFontScale) < 0.001f) return
        toastFontScale = clamped
        applyFontScales()
        toastScrollOffset = 0f
        onStateChanged?.invoke()
    }

    fun getToastFontScale(): Float = toastFontScale

    fun onTap(px: Float, py: Float): Boolean {
        if (handleFontMenuTapOrDismiss(px, py)) return true
        if (selectedToastKey != null) return toastBounds.contains(px, py)
        rowBounds.forEachIndexed { index, bounds ->
            if (bounds.contains(px, py)) {
                val item = notifications.getOrNull(firstVisibleIndex + index) ?: return true
                selectedToastKey = item.key
                toastScrollOffset = 0f
                showFontMenu = false
                onStateChanged?.invoke()
                return true
            }
        }
        return false
    }

    fun onDoubleTap(px: Float, py: Float): Boolean {
        if (!containsPoint(px, py)) return false
        if (selectedToastKey != null) {
            selectedToastKey = null
            toastScrollOffset = 0f
            onStateChanged?.invoke()
            return true
        }
        showFontMenu = !showFontMenu
        hoveredFontAction = null
        onStateChanged?.invoke()
        return true
    }

    fun handleFontMenuTapOrDismiss(px: Float, py: Float): Boolean {
        if (!showFontMenu) return false
        when {
            widgetMinusBounds.contains(px, py) -> setWidgetFontScale(widgetFontScale - FONT_SCALE_STEP)
            widgetPlusBounds.contains(px, py) -> setWidgetFontScale(widgetFontScale + FONT_SCALE_STEP)
            toastMinusBounds.contains(px, py) -> setToastFontScale(toastFontScale - FONT_SCALE_STEP)
            toastPlusBounds.contains(px, py) -> setToastFontScale(toastFontScale + FONT_SCALE_STEP)
            fontMenuBounds.contains(px, py) -> return true
            else -> {
                showFontMenu = false
                hoveredFontAction = null
                onStateChanged?.invoke()
            }
        }
        return true
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        updateInternalBounds()
        clampScrollIndex()
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
        hoveredFontAction = when {
            !showFontMenu -> null
            widgetMinusBounds.contains(px, py) -> FontAction.WIDGET_DECREASE
            widgetPlusBounds.contains(px, py) -> FontAction.WIDGET_INCREASE
            toastMinusBounds.contains(px, py) -> FontAction.TOAST_DECREASE
            toastPlusBounds.contains(px, py) -> FontAction.TOAST_INCREASE
            else -> null
        }
    }

    override fun onScroll(dy: Float) {
        if (selectedToastKey != null) {
            val maxScroll = buildToastLines().maxScroll
            toastScrollOffset = (toastScrollOffset + dy).coerceIn(0f, maxScroll)
            onStateChanged?.invoke()
            return
        }
        if (notifications.isEmpty()) return
        val direction = if (dy > 0f) 1 else -1
        if (widgetScrollAccumulator.signOrZero() != direction) {
            widgetScrollAccumulator = 0f
        }
        widgetScrollAccumulator += direction.toFloat()
        if (kotlin.math.abs(widgetScrollAccumulator) < WIDGET_SCROLL_STEP_THRESHOLD) return
        firstVisibleIndex += direction
        widgetScrollAccumulator = 0f
        clampScrollIndex()
        onStateChanged?.invoke()
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

        drawContent(canvas)

        if (selectedToastKey != null) {
            drawToast(canvas)
        }
        if (showFontMenu) {
            drawFontMenu(canvas)
        }
    }

    private fun drawContent(canvas: Canvas) {
        if (!phoneNotificationAccessEnabled) {
            drawCentered(canvas, "Phone notification access needed")
            return
        }
        if (notifications.isEmpty()) {
            drawCentered(canvas, "No notifications")
            return
        }
        rowBounds.forEachIndexed { index, bounds ->
            val item = notifications.getOrNull(firstVisibleIndex + index) ?: return@forEachIndexed
            drawRow(canvas, bounds, item)
        }
    }

    private fun drawRow(canvas: Canvas, bounds: RectF, item: PhoneNotificationItem) {
        canvas.drawRoundRect(bounds, 7f, 7f, rowBackgroundPaint)
        canvas.drawRoundRect(bounds, 7f, 7f, rowBorderPaint)

        val left = bounds.left + PADDING
        val right = bounds.right - PADDING
        val maxWidth = (right - left).coerceAtLeast(1f)
        var y = bounds.top + PADDING + lineHeight(metaPaint) * 0.8f
        val meta = "${item.appLabel} - ${formatTime(item.postTime)}"
        canvas.drawText(ellipsize(meta, metaPaint, maxWidth), left, y, metaPaint)

        y += lineHeight(titlePaint)
        if (item.title.isNotBlank()) {
            canvas.drawText(ellipsize(item.title, titlePaint, maxWidth), left, y, titlePaint)
            y += lineHeight(bodyPaint)
        }

        val remainingHeight = bounds.bottom - PADDING - y
        val maxBodyLines = (remainingHeight / lineHeight(bodyPaint)).toInt().coerceAtLeast(1)
        val lines = toWrappedLines(item.text.ifBlank { item.subText }, bodyPaint, maxWidth).take(maxBodyLines)
        lines.forEachIndexed { i, line ->
            val drawLine = if (i == lines.lastIndex && toWrappedLines(item.text.ifBlank { item.subText }, bodyPaint, maxWidth).size > maxBodyLines) {
                ellipsize(line, bodyPaint, maxWidth)
            } else {
                line
            }
            canvas.drawText(drawLine, left, y, bodyPaint)
            y += lineHeight(bodyPaint)
        }
    }

    private data class ToastLayout(
        val lines: List<String>,
        val lineHeight: Float,
        val maxScroll: Float
    )

    private fun drawToast(canvas: Canvas) {
        val item = notifications.firstOrNull { it.key == selectedToastKey } ?: return
        val layout = buildToastLines()
        canvas.drawRoundRect(toastBounds, 10f, 10f, toastBackgroundPaint)
        canvas.drawRoundRect(toastBounds, 10f, 10f, toastBorderPaint)

        val left = toastViewport.left
        var y = toastViewport.top + lineHeight(toastTitlePaint)
        canvas.drawText(ellipsize(item.appLabel, metaPaint, toastViewport.width()), left, y, metaPaint)
        y += lineHeight(toastTitlePaint)
        if (item.title.isNotBlank()) {
            canvas.drawText(ellipsize(item.title, toastTitlePaint, toastViewport.width()), left, y, toastTitlePaint)
        }

        canvas.save()
        canvas.clipRect(toastViewport)
        var bodyY = toastViewport.top + lineHeight(metaPaint) + lineHeight(toastTitlePaint) * 2f - toastScrollOffset
        layout.lines.forEach { line ->
            canvas.drawText(line, left, bodyY, toastBodyPaint)
            bodyY += layout.lineHeight
        }
        canvas.restore()
        drawToastScrollbar(canvas, layout)
    }

    private fun drawFontMenu(canvas: Canvas) {
        canvas.drawRoundRect(fontMenuBounds, 10f, 10f, fontMenuBackgroundPaint)
        canvas.drawRoundRect(fontMenuBounds, 10f, 10f, fontMenuBorderPaint)
        drawFontRow(canvas, "Widget ${(widgetFontScale * 100f).toInt()}%", widgetMinusBounds, widgetPlusBounds, FontAction.WIDGET_DECREASE, FontAction.WIDGET_INCREASE)
        drawFontRow(canvas, "Toast ${(toastFontScale * 100f).toInt()}%", toastMinusBounds, toastPlusBounds, FontAction.TOAST_DECREASE, FontAction.TOAST_INCREASE)
    }

    private fun drawFontRow(
        canvas: Canvas,
        label: String,
        minus: RectF,
        plus: RectF,
        minusAction: FontAction,
        plusAction: FontAction
    ) {
        val cy = minus.centerY()
        canvas.drawText(label, fontMenuBounds.centerX(), cy - (fontLabelPaint.descent() + fontLabelPaint.ascent()) / 2f, fontLabelPaint)
        drawFontButton(canvas, minus, "-", hoveredFontAction == minusAction)
        drawFontButton(canvas, plus, "+", hoveredFontAction == plusAction)
    }

    private fun drawFontButton(canvas: Canvas, bounds: RectF, label: String, hovered: Boolean) {
        canvas.drawRoundRect(bounds, 8f, 8f, if (hovered) fontButtonHoverPaint else fontButtonPaint)
        val y = bounds.centerY() - (fontButtonTextPaint.descent() + fontButtonTextPaint.ascent()) / 2f
        canvas.drawText(label, bounds.centerX(), y, fontButtonTextPaint)
    }

    private fun drawToastScrollbar(canvas: Canvas, layout: ToastLayout) {
        if (layout.maxScroll <= 0f) return
        val trackWidth = 8f
        val track = RectF(
            toastViewport.right - trackWidth,
            toastViewport.top,
            toastViewport.right,
            toastViewport.bottom
        )
        canvas.drawRoundRect(track, 4f, 4f, scrollbarTrackPaint)
        val contentHeight = toastViewport.height() + layout.maxScroll
        val thumbHeight = (toastViewport.height() / contentHeight * toastViewport.height()).coerceAtLeast(28f)
        val travel = (toastViewport.height() - thumbHeight).coerceAtLeast(1f)
        val top = track.top + (toastScrollOffset / layout.maxScroll) * travel
        canvas.drawRoundRect(RectF(track.left, top, track.right, top + thumbHeight), 4f, 4f, scrollbarThumbPaint)
    }

    private fun drawCentered(canvas: Canvas, text: String) {
        val y = widgetBounds.centerY() - (centerPaint.descent() + centerPaint.ascent()) / 2f
        canvas.drawText(text, widgetBounds.centerX(), y, centerPaint)
    }

    private fun buildToastLines(): ToastLayout {
        val item = notifications.firstOrNull { it.key == selectedToastKey }
        val lineHeight = lineHeight(toastBodyPaint)
        if (item == null) return ToastLayout(emptyList(), lineHeight, 0f)
        val body = buildString {
            if (item.text.isNotBlank()) append(item.text)
            if (item.subText.isNotBlank() && item.subText != item.text) {
                if (isNotEmpty()) append("\n\n")
                append(item.subText)
            }
        }
        val lines = toWrappedLines(body, toastBodyPaint, toastViewport.width() - 12f)
        val reservedHeader = lineHeight(metaPaint) + lineHeight(toastTitlePaint) * 2f
        val maxScroll = ((lines.size * lineHeight) + reservedHeader - toastViewport.height()).coerceAtLeast(0f)
        toastScrollOffset = toastScrollOffset.coerceIn(0f, maxScroll)
        return ToastLayout(lines, lineHeight, maxScroll)
    }

    private fun updateInternalBounds() {
        rowBounds.clear()
        val count = computeDisplayCount(widgetHeight, screenHeight)
            .coerceAtMost(notifications.size.coerceAtLeast(1))
        val totalGap = ROW_GAP * (count - 1).coerceAtLeast(0)
        val rowHeight = ((contentBounds.height() - PADDING * 2f - totalGap) / count)
            .coerceAtLeast(1f)
            .coerceAtMost(screenHeight / 3f)
        var top = contentBounds.top + PADDING
        repeat(count) {
            rowBounds.add(RectF(contentBounds.left + PADDING, top, contentBounds.right - PADDING, top + rowHeight))
            top += rowHeight + ROW_GAP
        }

        toastBounds.set(
            contentBounds.left + PADDING,
            contentBounds.top + PADDING,
            contentBounds.right - PADDING,
            contentBounds.bottom - PADDING
        )
        toastViewport.set(
            toastBounds.left + PADDING,
            toastBounds.top + PADDING,
            toastBounds.right - PADDING,
            toastBounds.bottom - PADDING
        )

        val menuLeft = contentBounds.centerX() - FONT_MENU_WIDTH / 2f
        val menuTop = contentBounds.top + PADDING
        fontMenuBounds.set(menuLeft, menuTop, menuLeft + FONT_MENU_WIDTH, menuTop + FONT_MENU_HEIGHT)
        val firstCy = fontMenuBounds.top + 32f
        val secondCy = fontMenuBounds.top + 84f
        widgetMinusBounds.set(fontMenuBounds.left + 10f, firstCy - FONT_BUTTON_SIZE / 2f, fontMenuBounds.left + 10f + FONT_BUTTON_SIZE, firstCy + FONT_BUTTON_SIZE / 2f)
        widgetPlusBounds.set(fontMenuBounds.right - 10f - FONT_BUTTON_SIZE, firstCy - FONT_BUTTON_SIZE / 2f, fontMenuBounds.right - 10f, firstCy + FONT_BUTTON_SIZE / 2f)
        toastMinusBounds.set(fontMenuBounds.left + 10f, secondCy - FONT_BUTTON_SIZE / 2f, fontMenuBounds.left + 10f + FONT_BUTTON_SIZE, secondCy + FONT_BUTTON_SIZE / 2f)
        toastPlusBounds.set(fontMenuBounds.right - 10f - FONT_BUTTON_SIZE, secondCy - FONT_BUTTON_SIZE / 2f, fontMenuBounds.right - 10f, secondCy + FONT_BUTTON_SIZE / 2f)
    }

    private fun clampScrollIndex() {
        val maxIndex = (notifications.size - computeDisplayCount(widgetHeight, screenHeight)).coerceAtLeast(0)
        firstVisibleIndex = firstVisibleIndex.coerceIn(0, maxIndex)
    }

    private fun applyFontScales() {
        titlePaint.textSize = BASE_TITLE_SIZE * widgetFontScale
        bodyPaint.textSize = BASE_BODY_SIZE * widgetFontScale
        metaPaint.textSize = BASE_META_SIZE * widgetFontScale
        toastTitlePaint.textSize = BASE_TOAST_TITLE_SIZE * toastFontScale
        toastBodyPaint.textSize = BASE_TOAST_BODY_SIZE * toastFontScale
    }

    private fun toWrappedLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        text.replace("\r", "").split('\n').forEachIndexed { paragraphIndex, raw ->
            val paragraph = raw.trim()
            if (paragraph.isEmpty()) {
                lines.add("")
            } else {
                var start = 0
                while (start < paragraph.length) {
                    val count = paint.breakText(paragraph, start, paragraph.length, true, maxWidth, null)
                    if (count <= 0) break
                    var end = (start + count).coerceAtMost(paragraph.length)
                    if (end < paragraph.length) {
                        val split = paragraph.lastIndexOf(' ', end - 1)
                        if (split > start) end = split + 1
                    }
                    val line = paragraph.substring(start, end).trim()
                    if (line.isNotEmpty()) lines.add(line)
                    start = end
                }
            }
            if (paragraphIndex != text.split('\n').lastIndex) lines.add("")
        }
        return lines
    }

    private fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * 1.08f
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String =
        TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()

    private fun formatTime(postTime: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(postTime))

    private fun Float.signOrZero(): Int =
        when {
            this > 0f -> 1
            this < 0f -> -1
            else -> 0
        }
}
