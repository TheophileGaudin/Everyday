package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.interaction.user_input.KeyTarget
import com.everyday.everyday_glasses.interaction.user_input.TextEditingController
import com.everyday.everyday_glasses.interaction.user_input.TextZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFocusTest {
    private var savedChanges = 0
    private val controller = TextEditingController(
        toContentSpace = { x, y -> x to y },
        requestPhoneClipboard = {},
        notifyFocusChanged = {},
        notifyTransientChange = {},
        notificationGeneration = { 0L },
        runClassifiedInput = { action, changed -> action().also { if (changed()) savedChanges++ } },
    )

    @Test
    fun `focusing a page takes the keyboard from the previous one`() {
        val first = FakePage()
        val second = FakePage()

        controller.focusPage(first)
        controller.focusPage(second)

        assertSame(second, controller.focusedPage)
        assertEquals(1, first.focusLost)
        assertEquals(0, second.focusLost)
    }

    @Test
    fun `keys go to the focused page, and a navigation is saved`() {
        val page = FakePage()
        controller.focusPage(page)

        controller.typeKey("a")
        controller.typeKey("ENTER")

        assertEquals(listOf("a", "ENTER"), page.keys)
        assertEquals(1, savedChanges)
    }

    @Test
    fun `clearing all focus releases the page`() {
        val page = FakePage()
        controller.focusPage(page)
        assertTrue(controller.hasKeyFocus)

        controller.clearAllFocus()

        assertNull(controller.focusedPage)
        assertEquals(1, page.focusLost)
        assertFalse(controller.hasKeyFocus)
    }

    private class FakePage : KeyTarget {
        val keys = mutableListOf<String>()
        var address = "home"
        var focusLost = 0

        override val ownField: TextZone? = null
        override fun onPageKey(key: String) {
            keys += key
            if (key == "ENTER") address = "search"
        }
        override fun savedState(): Any = address
        override fun onKeyFocusLost() {
            focusLost++
        }
    }
}