package com.everyday.everyday_glasses

import android.graphics.Canvas
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseWidgetInputTest {
    @Test
    fun `the persistent signature covers the common saved fields and the widget's own`() {
        val widget = FakeWidget()
        val initial = widget.persistentSignature()

        widget.isPinned = !widget.isPinned
        val pinned = widget.persistentSignature()
        assertNotEquals(initial, pinned)

        widget.savedValue = 2
        assertNotEquals(pinned, widget.persistentSignature())
    }

    @Test
    fun `a tap reaches the widget's own controls first and is always consumed`() {
        val widget = FakeWidget()

        assertTrue(widget.onTap(5f, 5f))
        assertEquals(1, widget.contentTaps)
    }

    @Test
    fun `the default scroll goes to onScroll and counts as used`() {
        val widget = FakeWidget()

        assertTrue(widget.onScrollAt(5f, 5f, 12f))
        assertEquals(listOf(12f), widget.scrolls)
    }

    @Test
    fun `a cancelled press ends like a release by default`() {
        val widget = FakeWidget()

        widget.onPressCancel(3f, 4f)
        assertEquals(listOf(3f to 4f), widget.releases)
    }

    private class FakeWidget : BaseWidget(TextBoxPlugin.TYPE, 0f, 0f, 10f, 10f) {
        var savedValue = 1
        var contentTaps = 0
        val scrolls = mutableListOf<Float>()
        val releases = mutableListOf<Pair<Float, Float>>()

        override fun draw(canvas: Canvas) = Unit
        override fun contentSignature(): List<Any?> = listOf(savedValue)
        override fun onContentTap(px: Float, py: Float): Boolean {
            contentTaps++
            return true
        }
        override fun onScroll(dy: Float) {
            scrolls += dy
        }
        override fun onPressRelease(px: Float, py: Float) {
            releases += px to py
        }
    }
}