package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale

/**
 * Modal settings menu for the speedometer widget.
 * Provides a single slider controlling the minimum velocity threshold (0.0..1.0 km/h).
 */
class SpeedometerSettingsMenu(
    private val screenWidth: Float,
    private val screenHeight: Float
) {

    companion object {
        private const val MENU_WIDTH = 360f
        private const val MENU_HEIGHT = 190f
        private const val CORNER_RADIUS = 12f
        private const val CLOSE_BUTTON_SIZE = 38f
        private const val CLOSE_BUTTON_MARGIN = 10f
        private const val TRACK_MARGIN_X = 28f
        private const val THUMB_RADIUS = 12f
        private const val TRACK_STROKE = 5f
        private const val DEFAULT_THRESHOLD = 0.5f
    }

    var isVisible = false
        private set

    var onThresholdChanged: ((Float) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    private var thresholdValue = DEFAULT_THRESHOLD
    private var sliderDragging = false

    private val menuRect = RectF()
    private val closeButtonRect = RectF()
    private val sliderTrackRect = RectF()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE1F1F1F")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        textSize = 18f
        textAlign = Paint.Align.LEFT
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.RIGHT
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        strokeWidth = TRACK_STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90D9")
        strokeWidth = TRACK_STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }

    private val closeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        updateLayout()
    }

    fun show() {
        isVisible = true
        sliderDragging = false
    }

    fun dismiss() {
        if (!isVisible) return
        isVisible = false
        sliderDragging = false
        onDismissed?.invoke()
    }

    fun getThreshold(): Float = thresholdValue

    fun setThreshold(value: Float, notify: Boolean = true) {
        val clamped = value.coerceIn(0f, 1f)
        if (thresholdValue == clamped) return
        thresholdValue = clamped
        if (notify) onThresholdChanged?.invoke(thresholdValue)
    }

    fun draw(canvas: Canvas) {
        if (!isVisible) return

        canvas.drawRoundRect(menuRect, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(menuRect, CORNER_RADIUS, CORNER_RADIUS, borderPaint)

        canvas.drawRoundRect(closeButtonRect, 8f, 8f, closeButtonPaint)
        val closeCx = closeButtonRect.centerX()
        val closeCy = closeButtonRect.centerY()
        val closeOffset = CLOSE_BUTTON_SIZE * 0.25f
        canvas.drawLine(closeCx - closeOffset, closeCy - closeOffset, closeCx + closeOffset, closeCy + closeOffset, closeIconPaint)
        canvas.drawLine(closeCx + closeOffset, closeCy - closeOffset, closeCx - closeOffset, closeCy + closeOffset, closeIconPaint)

        canvas.drawText("Speedometer", menuRect.centerX(), menuRect.top + 44f, titlePaint)

        val trackLeft = sliderTrackRect.left
        val trackRight = sliderTrackRect.right
        val trackY = sliderTrackRect.centerY()
        val thumbX = trackLeft + (trackRight - trackLeft) * thresholdValue

        canvas.drawText("Min Velocity", trackLeft, trackY - 26f, labelPaint)
        canvas.drawText(formatValueText(), trackRight, trackY - 26f, valuePaint)

        canvas.drawLine(trackLeft, trackY, trackRight, trackY, trackPaint)
        canvas.drawLine(trackLeft, trackY, thumbX, trackY, activeTrackPaint)
        canvas.drawCircle(thumbX, trackY, THUMB_RADIUS, thumbPaint)
    }

    fun onDown(x: Float, y: Float): Boolean {
        if (!isVisible) return false
        if (closeButtonRect.contains(x, y)) return true
        if (expandedSliderRect().contains(x, y)) {
            sliderDragging = true
            updateThresholdFromTouch(x)
            return true
        }
        return menuRect.contains(x, y)
    }

    fun onMove(x: Float, y: Float): Boolean {
        if (!isVisible) return false
        if (sliderDragging) {
            updateThresholdFromTouch(x)
            return true
        }
        return menuRect.contains(x, y)
    }

    fun onUp(): Boolean {
        if (!isVisible) return false
        if (!sliderDragging) return false
        sliderDragging = false
        return true
    }

    fun onTap(x: Float, y: Float): Boolean {
        if (!isVisible) return false

        if (closeButtonRect.contains(x, y)) {
            dismiss()
            return true
        }

        if (expandedSliderRect().contains(x, y)) {
            updateThresholdFromTouch(x)
            return true
        }

        if (menuRect.contains(x, y)) {
            return true
        }

        dismiss()
        return true
    }

    private fun updateLayout() {
        val left = (screenWidth - MENU_WIDTH) / 2f
        val top = (screenHeight - MENU_HEIGHT) / 2f
        menuRect.set(left, top, left + MENU_WIDTH, top + MENU_HEIGHT)

        val closeLeft = menuRect.right - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN
        val closeTop = menuRect.top + CLOSE_BUTTON_MARGIN
        closeButtonRect.set(closeLeft, closeTop, closeLeft + CLOSE_BUTTON_SIZE, closeTop + CLOSE_BUTTON_SIZE)

        val sliderCenterY = menuRect.top + 124f
        val trackLeft = menuRect.left + TRACK_MARGIN_X
        val trackRight = menuRect.right - TRACK_MARGIN_X
        sliderTrackRect.set(trackLeft, sliderCenterY - 10f, trackRight, sliderCenterY + 10f)
    }

    private fun expandedSliderRect(): RectF {
        return RectF(
            sliderTrackRect.left - 10f,
            sliderTrackRect.top - 20f,
            sliderTrackRect.right + 10f,
            sliderTrackRect.bottom + 20f
        )
    }

    private fun updateThresholdFromTouch(touchX: Float) {
        val trackWidth = sliderTrackRect.width()
        if (trackWidth <= 0f) return
        val value = ((touchX - sliderTrackRect.left) / trackWidth).coerceIn(0f, 1f)
        setThreshold(value, notify = true)
    }

    private fun formatValueText(): String {
        return if (thresholdValue <= 0f) {
            "Always visible"
        } else {
            String.format(Locale.ENGLISH, "%.2f km/h", thresholdValue)
        }
    }
}
