package com.everyday.everyday_glasses

import com.everyday.everyday_glasses._threedof.ThreeDofCoordinateMath
import org.junit.Assert
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ThreeDofCoordinateMathTest {
    @Test
    fun `translation without roll is removed from screen point`() {
        val content = ThreeDofCoordinateMath.screenToContent(
            screenX = 400f,
            screenY = 200f,
            viewportWidth = 640f,
            viewportHeight = 480f,
            offsetX = 40f,
            offsetY = -20f,
            rollDeg = 0f,
        )

        assertPoint(expectedX = 360f, expectedY = 220f, actual = content)
    }

    @Test
    fun `visible screen centre maps to offset adjusted rotation pivot`() {
        val content = ThreeDofCoordinateMath.screenToContent(
            screenX = 320f,
            screenY = 240f,
            viewportWidth = 640f,
            viewportHeight = 480f,
            offsetX = 40f,
            offsetY = -20f,
            rollDeg = 30f,
        )

        assertPoint(expectedX = 280f, expectedY = 260f, actual = content)
    }

    @Test
    fun `roll without translation is inverted around screen centre`() {
        val content = ThreeDofCoordinateMath.screenToContent(
            screenX = 320f,
            screenY = 140f,
            viewportWidth = 640f,
            viewportHeight = 480f,
            offsetX = 0f,
            offsetY = 0f,
            rollDeg = 90f,
        )

        assertPoint(expectedX = 420f, expectedY = 240f, actual = content)
    }

    @Test
    fun `combined Canvas transform and inverse return original content points`() {
        val viewportWidth = 640f
        val viewportHeight = 480f
        val offsetX = 73f
        val offsetY = -41f
        val rollDeg = 27f
        val contentPoints = listOf(
            0f to 0f,
            320f to 240f,
            117f to 389f,
            639f to 479f,
        )

        contentPoints.forEach { content ->
            val screen = contentToScreenLikeCanvas(
                contentX = content.first,
                contentY = content.second,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                offsetX = offsetX,
                offsetY = offsetY,
                rollDeg = rollDeg,
            )
            val restored = ThreeDofCoordinateMath.screenToContent(
                screenX = screen.first,
                screenY = screen.second,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                offsetX = offsetX,
                offsetY = offsetY,
                rollDeg = rollDeg,
            )

            assertPoint(content.first, content.second, restored)
        }
    }

    /** Matches Canvas.translate(offset), followed by Canvas.rotate(-roll, pivot). */
    private fun contentToScreenLikeCanvas(
        contentX: Float,
        contentY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        offsetX: Float,
        offsetY: Float,
        rollDeg: Float,
    ): Pair<Float, Float> {
        val pivotX = viewportWidth / 2f - offsetX
        val pivotY = viewportHeight / 2f - offsetY
        val angle = Math.toRadians(-rollDeg.toDouble())
        val cosAngle = cos(angle).toFloat()
        val sinAngle = sin(angle).toFloat()
        val dx = contentX - pivotX
        val dy = contentY - pivotY
        val rotatedX = pivotX + dx * cosAngle - dy * sinAngle
        val rotatedY = pivotY + dx * sinAngle + dy * cosAngle
        return Pair(rotatedX + offsetX, rotatedY + offsetY)
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        Assert.assertEquals(expectedX, actual.first, TOLERANCE)
        Assert.assertEquals(expectedY, actual.second, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}