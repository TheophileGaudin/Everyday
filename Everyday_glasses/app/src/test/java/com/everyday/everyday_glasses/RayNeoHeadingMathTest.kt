package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.widgets.map.backend.headingFromRayNeoOpticalForward
import com.everyday.everyday_glasses.widgets.map.compassRelativeBearingDeg
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RayNeoHeadingMathTest {
    @Test
    fun horizontalHeadingDoesNotChangeWithOrdinaryPitch() {
        for (pitch in listOf(-50f, -25f, 0f, 25f, 50f)) {
            val heading = headingFromRayNeoOpticalForward(matrixFor(123f, pitch), fallback = null)
            assertEquals(123f, heading!!, 0.01f)
        }
    }

    @Test
    fun steepVerticalPoseHoldsLastReliableHeading() {
        assertEquals(123f, headingFromRayNeoOpticalForward(matrixFor(300f, 75f), 123f)!!, 0.01f)
        assertEquals(123f, headingFromRayNeoOpticalForward(matrixFor(40f, -75f), 123f)!!, 0.01f)
    }

    @Test
    fun compassMapIsIndependentOfSensorSessionStartDirection() {
        // A road due north has the same screen position for a given absolute
        // heading; no startup-relative yaw baseline participates in this result.
        assertEquals(270f, compassRelativeBearingDeg(0f, 90f), 0.001f)
        assertEquals(270f, compassRelativeBearingDeg(360f, 90f), 0.001f)
    }

    @Test
    fun northIndicatorMovesAroundRimLikeAHorizontalCompass() {
        assertEquals(0f, compassRelativeBearingDeg(0f, 0f), 0.001f)
        assertEquals(270f, compassRelativeBearingDeg(0f, 90f), 0.001f)
        assertEquals(180f, compassRelativeBearingDeg(0f, 180f), 0.001f)
        assertEquals(90f, compassRelativeBearingDeg(0f, 270f), 0.001f)
    }

    private fun matrixFor(headingDeg: Float, pitchDeg: Float): FloatArray {
        val heading = Math.toRadians(headingDeg.toDouble())
        val horizontal = cos(Math.toRadians(pitchDeg.toDouble())).toFloat()
        return FloatArray(9).apply {
            this[2] = -sin(heading).toFloat() * horizontal
            this[5] = -cos(heading).toFloat() * horizontal
        }
    }
}
