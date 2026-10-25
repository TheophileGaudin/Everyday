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

/**
 * Stable, UI-independent metadata for one widget type. Each plugin's companion object declares its own.
 *
 * @property label The type's name on the *Widgets* page and for the assistant ("Show: Map").
 * @property policy One widget at most, or several.
 * @property order Sorts the types ([WidgetDescriptors.all]) and the dashboard's widgets by type. Each
 * type has its own; a new type takes the next free number.
 * @property storesClosedTemplate A closed widget's state is kept, so showing it again brings it back
 * as it was. Every single-instance type keeps one.
 * @property menuOrder Sorts the *Widgets* page and the *Create* and *Files* commands, when that differs
 * from [order].
 */
data class WidgetDescriptor(
    val type: WidgetTypeId,
    val label: String,
    val policy: WidgetInstancePolicy,
    val order: Int,
    val storesClosedTemplate: Boolean = false,
    val menuOrder: Int = order
)

/** Every widget type's descriptor, from [WidgetPlugins], in [WidgetDescriptor.order]. Used by lifecycle, registry ordering, commands, and menus. */
object WidgetDescriptors {
    val all: List<WidgetDescriptor> = WidgetPlugins.all.map(WidgetType::descriptor).sortedBy(WidgetDescriptor::order)

    private val byType = all.associateBy(WidgetDescriptor::type)

    operator fun get(type: WidgetTypeId): WidgetDescriptor = requireNotNull(byType[type]) { "Unknown widget type $type" }
}