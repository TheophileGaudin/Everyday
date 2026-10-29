package com.everyday.everyday_glasses.interaction.assistant

import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import kotlin.math.roundToInt

internal class AssistantUiContextCapture(
    private val registry: WidgetRegistry,
    private val screenSize: () -> Pair<Int, Int>,
    private val activeLayoutName: () -> String?,
    private val focusedWidget: () -> BaseWidget?,
    private val factsOf: (BaseWidget, String) -> List<AssistantSemanticFact>, /** What the widget's plugin says about it ([AssistantContribution.assistantFacts]). */
) {
    /** Capture only runtime layout metadata for the next assistant request. */
    fun capture(): AssistantUiContext {
        val entries = mutableListOf<Triple<String, WidgetDescriptor, BaseWidget>>()
        WidgetDescriptors.all.forEach { descriptor ->
            registry.widgets(descriptor.type).forEach { widget ->
                val id = registry.stableId(widget)
                entries += Triple(id, descriptor, widget)
            }
        }

        val focused = focusedWidget()
        val focusedId = entries.firstOrNull { it.third === focused }?.first
        val widgets = entries.map { (id, descriptor, widget) ->
            val bounds = widget.getBounds()
            val facts = mutableListOf(
                AssistantSemanticFact("widget_type", descriptor.type.persistedId),
                AssistantSemanticFact("widget_label", descriptor.label)
            )
            facts += factsOf(widget, id)
            AssistantWidgetContext(
                id = id,
                left = bounds.left.roundToInt(),
                top = bounds.top.roundToInt(),
                right = bounds.right.roundToInt(),
                bottom = bounds.bottom.roundToInt(),
                zOrder = widget.zOrder,
                minimized = widget.isMinimized,
                fullscreen = widget.isFullscreen,
                pinned = widget.isPinned,
                stableId = id,
                facts = facts
            )
        }
        val (screenWidth, screenHeight) = screenSize()

        return AssistantUiContext(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            layoutName = activeLayoutName(),
            focusedWidgetId = focusedId,
            widgets = widgets
        )
    }
}