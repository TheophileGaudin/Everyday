package com.everyday.everyday_glasses

import com.everyday.everyday_glasses._threedof.DashboardTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTransformTest {
    @Test
    fun `inactive transform leaves coordinates unchanged`() {
        val transform = DashboardTransform().apply {
            setOffset(40f, -20f)
            setRoll(30f)
        }.snapshot(640f, 480f, applies = false)

        assertPoint(400f, 200f, transform.contentToScreen(400f, 200f))
        assertPoint(400f, 200f, transform.screenToContent(400f, 200f))
    }

    @Test
    fun `translation without roll is reversed for targeting`() {
        val transform = DashboardTransform().apply {
            setOffset(40f, -20f)
        }.snapshot(640f, 480f, applies = true)

        assertPoint(360f, 220f, transform.screenToContent(400f, 200f))
    }

    @Test
    fun `visible screen centre maps to offset adjusted pivot`() {
        val transform = DashboardTransform().apply {
            setOffset(40f, -20f)
            setRoll(30f)
        }.snapshot(640f, 480f, applies = true)

        assertPoint(280f, 260f, transform.screenToContent(320f, 240f))
    }

    @Test
    fun `roll without translation is reversed around screen centre`() {
        val transform = DashboardTransform().apply {
            setRoll(90f)
        }.snapshot(640f, 480f, applies = true)

        assertPoint(420f, 240f, transform.screenToContent(320f, 140f))
    }

    @Test
    fun `combined forward and inverse transforms return original points`() {
        val transform = DashboardTransform().apply {
            setOffset(73f, -41f)
            setRoll(27f)
        }.snapshot(640f, 480f, applies = true)

        listOf(0f to 0f, 320f to 240f, 117f to 389f, 639f to 479f).forEach { point ->
            val screen = transform.contentToScreen(point.first, point.second)
            assertPoint(point.first, point.second, transform.screenToContent(screen.first, screen.second))
        }
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedX, actual.first, TOLERANCE)
        assertEquals(expectedY, actual.second, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}