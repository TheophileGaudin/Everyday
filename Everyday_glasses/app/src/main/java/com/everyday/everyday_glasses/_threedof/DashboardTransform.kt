package com.everyday.everyday_glasses._threedof

import android.graphics.Canvas
import kotlin.math.cos
import kotlin.math.sin

/** Owns the 3DOF dashboard offset/roll and creates geometry for one viewport frame. */
internal class DashboardTransform {
    private var offsetX = 0f
    private var offsetY = 0f
    private var rollDeg = 0f

    fun setOffset(x: Float, y: Float) {
        offsetX = x
        offsetY = y
    }

    fun setRoll(degrees: Float) {
        rollDeg = degrees
    }

    fun snapshot(
        viewportWidth: Float,
        viewportHeight: Float,
        applies: Boolean,
    ): Snapshot = Snapshot(
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        offsetX = offsetX,
        offsetY = offsetY,
        rollDeg = rollDeg,
        applies = applies,
    )

    /** Immutable geometry used by one draw or input operation. */
    internal data class Snapshot(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val offsetX: Float,
        val offsetY: Float,
        val rollDeg: Float,
        val applies: Boolean,
    ) {
        private val pivotX: Float get() = viewportWidth / 2f - offsetX
        private val pivotY: Float get() = viewportHeight / 2f - offsetY

        /** Applies the same translate-then-rotate operation formerly written in WidgetContainer. */
        fun applyTo(canvas: Canvas) {
            if (!applies) return
            canvas.translate(offsetX, offsetY)
            canvas.rotate(-rollDeg, pivotX, pivotY)
        }

        /** Converts an untransformed widget/content point to the point visible on the screen. */
        fun contentToScreen(contentX: Float, contentY: Float): Pair<Float, Float> {
            if (!applies) return Pair(contentX, contentY)
            val angle = Math.toRadians(-rollDeg.toDouble())
            val cosAngle = cos(angle).toFloat()
            val sinAngle = sin(angle).toFloat()
            val dx = contentX - pivotX
            val dy = contentY - pivotY
            return Pair(
                pivotX + dx * cosAngle - dy * sinAngle + offsetX,
                pivotY + dx * sinAngle + dy * cosAngle + offsetY,
            )
        }

        /** Reverses the drawing transform so screen input can target untransformed widgets. */
        fun screenToContent(screenX: Float, screenY: Float): Pair<Float, Float> {
            if (!applies) return Pair(screenX, screenY)

            // Undo the translation first, then undo Canvas's -roll rotation with +roll.
            val untranslatedX = screenX - offsetX
            val untranslatedY = screenY - offsetY
            val angle = Math.toRadians(rollDeg.toDouble())
            val cosAngle = cos(angle).toFloat()
            val sinAngle = sin(angle).toFloat()
            val dx = untranslatedX - pivotX
            val dy = untranslatedY - pivotY
            return Pair(
                pivotX + dx * cosAngle - dy * sinAngle,
                pivotY + dx * sinAngle + dy * cosAngle,
            )
        }
    }
}