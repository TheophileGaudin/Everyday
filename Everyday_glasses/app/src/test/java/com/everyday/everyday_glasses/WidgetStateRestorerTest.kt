package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetStateRestorerTest {
    @Test
    fun `restore delegates to callback exactly once`() {
        val restored = mutableListOf<String>()
        val restorer = WidgetStateRestorer(
            WidgetDescriptors[WidgetTypeId.STATUS],
            String::class,
            restored::add
        )

        restorer.restore("saved state")

        assertEquals(listOf("saved state"), restored)
    }

    @Test
    fun `restore rejects a state of the wrong type`() {
        val restorer = WidgetStateRestorer(
            WidgetDescriptors[WidgetTypeId.STATUS],
            String::class
        ) { }

        assertThrows(IllegalArgumentException::class.java) {
            restorer.restore(42)
        }
    }
}
