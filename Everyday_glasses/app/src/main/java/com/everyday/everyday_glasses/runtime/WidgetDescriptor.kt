package com.everyday.everyday_glasses.runtime

import com.everyday.everyday_glasses.widgets.WidgetPlugins

/**
 * A widget type's id, which is also its name in saved dashboards ("map", "text", …). Each plugin
 * declares its own, as `TYPE` in its companion object.
 */
@JvmInline
value class WidgetTypeId(val persistedId: String) {
    override fun toString(): String = persistedId
}

enum class WidgetInstancePolicy { SINGLETON, MULTIPLE }

/** Stable, UI-independent metadata for one widget type. Each plugin's companion object declares its own. */
data class WidgetDescriptor(
    val type: WidgetTypeId,
    val label: String,
    val policy: WidgetInstancePolicy,
    val order: Int,
    val category: String,
    val storesClosedTemplate: Boolean = false,
    val menuOrder: Int = order
)

/** Every widget type's descriptor, from [WidgetPlugins], in [WidgetDescriptor.order]. Used by lifecycle, registry ordering, commands, and menus. */
object WidgetDescriptors {
    val all: List<WidgetDescriptor> = WidgetPlugins.all.map(WidgetType::descriptor).sortedBy(WidgetDescriptor::order)

    private val byType = all.associateBy(WidgetDescriptor::type)

    operator fun get(type: WidgetTypeId): WidgetDescriptor = requireNotNull(byType[type]) { "Unknown widget type $type" }
}