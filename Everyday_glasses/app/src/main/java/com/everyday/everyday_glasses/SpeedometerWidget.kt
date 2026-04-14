package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import java.util.Locale
import kotlin.math.abs

/**
 * Speedometer widget that displays movement speed from local location updates.
 *
 * Behavior:
 * - Uses BaseWidget for move/resize/pin/minimize/fullscreen behavior.
 * - Hides speed content when not moving, but still supports hover border/buttons.
 * - Supports in-widget unit toggle (km/h <-> mph) via a unit chip tap target.
 */
class SpeedometerWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(x, y, width, height) {

    companion object {
        private const val KMH_TO_MPH = 0.621371f
    }

    enum class SpeedUnit(val code: String, val label: String) {
        KMH("kmh", "km/h"),
        MPH("mph", "mph");

        companion object {
            fun fromCode(code: String?): SpeedUnit {
                return values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: KMH
            }
        }
    }

    override val minimizeLabel: String = "V"
    override val minWidth: Float = 180f
    override val minHeight: Float = 90f

    private var smoothedSpeedKmh = 0f
    private var hasReliableSpeed = false
    private var isMoving = false
    private var forceVisibleWhenIdle = false
    private var speedUnit = SpeedUnit.KMH

    var onUnitChanged: ((SpeedUnit) -> Unit)? = null

    private val speedTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 52f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val unitTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C6D0DA")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val unitChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#335060")
        style = Paint.Style.FILL
    }

    private val unitChipHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4480A0")
        style = Paint.Style.FILL
    }

    private val unitChipTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val unitChipBounds = RectF()
    private var unitChipHovered = false

    fun setUnit(unit: SpeedUnit) {
        if (speedUnit == unit) return
        speedUnit = unit
    }

    fun getUnit(): SpeedUnit = speedUnit

    fun setForceVisibleWhenIdle(forceVisible: Boolean): Boolean {
        if (forceVisibleWhenIdle == forceVisible) return false
        forceVisibleWhenIdle = forceVisible
        return true
    }

    /**
     * Update speed state.
     *
     * @return true when visible state changed and container should redraw.
     */
    fun setSpeedData(kmh: Float, qualityOk: Boolean, moving: Boolean): Boolean {
        val newKmh = kmh.coerceAtLeast(0f)
        val changed = abs(smoothedSpeedKmh - newKmh) > 0.01f ||
                hasReliableSpeed != qualityOk ||
                isMoving != moving
        smoothedSpeedKmh = newKmh
        hasReliableSpeed = qualityOk
        isMoving = moving
        return changed
    }

    fun setPermissionDenied() {
        smoothedSpeedKmh = 0f
        hasReliableSpeed = false
        isMoving = false
    }

    fun onTap(px: Float, py: Float): Boolean {
        val canToggleUnit = forceVisibleWhenIdle || (isMoving && hasReliableSpeed)
        val canShowUnitChip = canToggleUnit && isHovering()
        if (canShowUnitChip && unitChipBounds.contains(px, py)) {
            speedUnit = if (speedUnit == SpeedUnit.KMH) SpeedUnit.MPH else SpeedUnit.KMH
            onUnitChanged?.invoke(speedUnit)
            return true
        }
        return false
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
        val canToggleUnit = forceVisibleWhenIdle || (isMoving && hasReliableSpeed)
        val canShowUnitChip = canToggleUnit && isHovering()
        unitChipHovered = canShowUnitChip && unitChipBounds.contains(px, py)
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Always draw shell so hover border/buttons still work when content is hidden.
        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)

        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        }
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }

        val shouldShowContent = forceVisibleWhenIdle || (isMoving && hasReliableSpeed)
        if (!shouldShowContent) {
            unitChipBounds.setEmpty()
            unitChipHovered = false
            return
        }

        val baseSpeedKmh = if (hasReliableSpeed) smoothedSpeedKmh else 0f
        val displaySpeed = if (speedUnit == SpeedUnit.KMH) baseSpeedKmh else baseSpeedKmh * KMH_TO_MPH
        val speedText = if (displaySpeed >= 10f) {
            String.format(Locale.ENGLISH, "%.0f", displaySpeed)
        } else {
            String.format(Locale.ENGLISH, "%.1f", displaySpeed)
        }

        val horizontalPadding = 20f
        val verticalPadding = 14f
        val shouldShowUnitChip = isHovering()
        if (shouldShowUnitChip) {
            val chipWidth = 78f
            val chipHeight = 32f
            val chipLeft = widgetBounds.right - horizontalPadding - chipWidth
            val chipTop = widgetBounds.top + verticalPadding
            unitChipBounds.set(chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight)

            canvas.drawRoundRect(
                unitChipBounds,
                10f,
                10f,
                if (unitChipHovered) unitChipHoverPaint else unitChipPaint
            )

            val chipBaseline = unitChipBounds.centerY() - (unitChipTextPaint.descent() + unitChipTextPaint.ascent()) * 0.5f
            canvas.drawText(speedUnit.label, unitChipBounds.centerX(), chipBaseline, unitChipTextPaint)
        } else {
            unitChipBounds.setEmpty()
            unitChipHovered = false
        }

        val maxWidth = (widgetWidth - (horizontalPadding * 2f)).coerceAtLeast(80f)
        val maxHeight = (widgetHeight * 0.44f).coerceIn(24f, 72f)
        speedTextPaint.textSize = maxHeight
        while (speedTextPaint.measureText(speedText) > maxWidth && speedTextPaint.textSize > 22f) {
            speedTextPaint.textSize -= 2f
        }

        val speedCenterY = widgetBounds.centerY() + 8f
        val speedBaseline = speedCenterY - (speedTextPaint.descent() + speedTextPaint.ascent()) * 0.5f
        canvas.drawText(speedText, widgetBounds.centerX(), speedBaseline, speedTextPaint)

        val unitBaseline = speedBaseline + speedTextPaint.textSize * 0.62f
        canvas.drawText(speedUnit.label, widgetBounds.centerX(), unitBaseline, unitTextPaint)
    }
}
