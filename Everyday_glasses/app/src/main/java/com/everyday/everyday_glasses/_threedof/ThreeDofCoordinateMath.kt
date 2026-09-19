package com.everyday.everyday_glasses._threedof

import kotlin.math.cos
import kotlin.math.sin

/** Pure coordinate calculations matching WidgetContainer's 3DOF Canvas operations. */
internal object ThreeDofCoordinateMath {
    fun screenToContent(
        screenX: Float,
        screenY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        offsetX: Float,
        offsetY: Float,
        rollDeg: Float,
    ): Pair<Float, Float> {
        // WidgetContainer translates first, then asks Canvas to rotate. The rotation pivot is
        // expressed before that translation, so subtract the offset from the screen center.
        val pivotX = viewportWidth / 2f - offsetX
        val pivotY = viewportHeight / 2f - offsetY

        // Undo the Canvas translation first.
        val untranslatedX = screenX - offsetX
        val untranslatedY = screenY - offsetY

        // Canvas draws with -rollDeg, so the inverse uses +rollDeg around the same pivot.
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