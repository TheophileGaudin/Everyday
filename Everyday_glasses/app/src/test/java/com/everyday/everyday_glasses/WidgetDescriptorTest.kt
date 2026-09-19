package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.shared.command.CommandWidgetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDescriptorTest {
    @Test
    fun `catalog contains every stable widget type exactly once`() {
        assertEquals(WidgetTypeId.entries.toSet(), WidgetDescriptors.all.map { it.type }.toSet())
        assertEquals(WidgetTypeId.entries.size, WidgetDescriptors.all.size)
        assertEquals(WidgetDescriptors.all.indices.toList(), WidgetDescriptors.all.map { it.order })
        assertFalse(WidgetDescriptors.all.any { it.label.isBlank() || it.category.isBlank() })
    }

    @Test
    fun `every command widget resolves to a singleton descriptor`() {
        CommandWidgetId.entries.forEach { commandId ->
            val descriptor = WidgetDescriptors.forCommand(commandId)
            assertEquals(WidgetInstancePolicy.SINGLETON, descriptor.policy)
            assertEquals(commandId, descriptor.commandId)
            assertTrue(descriptor.storesClosedTemplate)
        }
    }

    @Test
    fun `resource and document widgets retain multiple instance policy`() {
        val multiple = WidgetDescriptors.all
            .filter { it.policy == WidgetInstancePolicy.MULTIPLE }
            .map { it.type }
            .toSet()
        assertEquals(
            setOf(
                WidgetTypeId.BROWSER,
                WidgetTypeId.TEXT,
                WidgetTypeId.IMAGE,
                WidgetTypeId.VIDEO,
                WidgetTypeId.PDF,
                WidgetTypeId.FILES
            ),
            multiple
        )
    }
}
