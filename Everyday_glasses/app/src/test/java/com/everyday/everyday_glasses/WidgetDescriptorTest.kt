package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.interaction.commands.AppCommandRegistry
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.widgets.WidgetPlugins
import com.everyday.everyday_glasses.widgets.browser.BrowserPlugin
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserPlugin
import com.everyday.everyday_glasses.widgets.file_browser.ImagePlugin
import com.everyday.everyday_glasses.widgets.file_browser.PdfPlugin
import com.everyday.everyday_glasses.widgets.file_browser.VideoPlugin
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDescriptorTest {
    @Test
    fun `catalog contains every widget type exactly once, each with its own order`() {
        assertEquals(WidgetPlugins.all.size, WidgetDescriptors.all.map { it.type }.toSet().size)
        assertEquals(WidgetPlugins.all.size, WidgetDescriptors.all.map { it.order }.toSet().size)
        assertFalse(WidgetDescriptors.all.any { it.label.isBlank() || it.type.persistedId.isBlank() })
    }

    @Test
    fun `every singleton keeps a closed template and has its toggle command`() {
        val commands = AppCommandRegistry()
        WidgetDescriptors.all.filter { it.policy == WidgetInstancePolicy.SINGLETON }.forEach { descriptor ->
            assertTrue(descriptor.storesClosedTemplate)
            assertEquals("toggle:${descriptor.type.persistedId}", commands.widgetDefinition(descriptor.type).id)
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
                BrowserPlugin.TYPE,
                TextBoxPlugin.TYPE,
                ImagePlugin.TYPE,
                VideoPlugin.TYPE,
                PdfPlugin.TYPE,
                FileBrowserPlugin.TYPE
            ),
            multiple
        )
    }
}