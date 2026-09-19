package com.everyday.everyday_glasses.contracts

import com.everyday.shared.command.CommandWidgetId

enum class WidgetTypeId(val persistedId: String) {
    STATUS("status"),
    LOCATION("location"),
    MAP("map"),
    CALENDAR("calendar"),
    FINANCE("finance"),
    NEWS("news"),
    NOTIFICATIONS("notifications"),
    SPEEDOMETER("speedometer"),
    SUBTITLE("subtitle"),
    MIRROR("mirror"),
    BROWSER("browser"),
    TEXT("text"),
    IMAGE("image"),
    VIDEO("video"),
    PDF("pdf"),
    FILES("files")
}

enum class WidgetInstancePolicy { SINGLETON, MULTIPLE }

/** Stable, UI-independent metadata for one widget type. */
data class WidgetDescriptor(
    val type: WidgetTypeId,
    val label: String,
    val policy: WidgetInstancePolicy,
    val order: Int,
    val category: String,
    val commandId: CommandWidgetId? = null,
    val storesClosedTemplate: Boolean = false,
    val menuOrder: Int = order
)

/** The sole catalog used by lifecycle, registry ordering, commands, and menus. */
object WidgetDescriptors {
    val all: List<WidgetDescriptor> = listOf(
        WidgetDescriptor(WidgetTypeId.STATUS, "Status", WidgetInstancePolicy.SINGLETON, 0, "information", CommandWidgetId.STATUS, true),
        WidgetDescriptor(WidgetTypeId.LOCATION, "Location", WidgetInstancePolicy.SINGLETON, 1, "information", CommandWidgetId.LOCATION, true),
        WidgetDescriptor(WidgetTypeId.MAP, "Map", WidgetInstancePolicy.SINGLETON, 2, "information", CommandWidgetId.MAP, true),
        WidgetDescriptor(WidgetTypeId.CALENDAR, "Calendar", WidgetInstancePolicy.SINGLETON, 3, "information", CommandWidgetId.CALENDAR, true),
        WidgetDescriptor(WidgetTypeId.FINANCE, "Finance", WidgetInstancePolicy.SINGLETON, 4, "information", CommandWidgetId.FINANCE, true, 5),
        WidgetDescriptor(WidgetTypeId.NEWS, "News", WidgetInstancePolicy.SINGLETON, 5, "information", CommandWidgetId.NEWS, true, 6),
        WidgetDescriptor(WidgetTypeId.NOTIFICATIONS, "Notifications", WidgetInstancePolicy.SINGLETON, 6, "information", CommandWidgetId.NOTIFICATIONS, true, 7),
        WidgetDescriptor(WidgetTypeId.SPEEDOMETER, "Speedometer", WidgetInstancePolicy.SINGLETON, 7, "information", CommandWidgetId.SPEEDOMETER, true, 4),
        WidgetDescriptor(WidgetTypeId.SUBTITLE, "Subtitles", WidgetInstancePolicy.SINGLETON, 8, "media", CommandWidgetId.SUBTITLE, true),
        WidgetDescriptor(WidgetTypeId.MIRROR, "Screen mirror", WidgetInstancePolicy.SINGLETON, 9, "media", CommandWidgetId.MIRROR, true),
        WidgetDescriptor(WidgetTypeId.BROWSER, "Browser", WidgetInstancePolicy.MULTIPLE, 10, "content", storesClosedTemplate = true),
        WidgetDescriptor(WidgetTypeId.TEXT, "Text", WidgetInstancePolicy.MULTIPLE, 11, "content", storesClosedTemplate = true),
        WidgetDescriptor(WidgetTypeId.IMAGE, "Image", WidgetInstancePolicy.MULTIPLE, 12, "content"),
        WidgetDescriptor(WidgetTypeId.VIDEO, "Video", WidgetInstancePolicy.MULTIPLE, 13, "media"),
        WidgetDescriptor(WidgetTypeId.PDF, "PDF", WidgetInstancePolicy.MULTIPLE, 14, "content"),
        WidgetDescriptor(WidgetTypeId.FILES, "Files", WidgetInstancePolicy.MULTIPLE, 15, "content")
    )

    private val byType = all.associateBy(WidgetDescriptor::type)
    private val byCommand = all.mapNotNull { descriptor -> descriptor.commandId?.let { it to descriptor } }.toMap()

    operator fun get(type: WidgetTypeId): WidgetDescriptor = requireNotNull(byType[type])
    fun forCommand(commandId: CommandWidgetId): WidgetDescriptor = requireNotNull(byCommand[commandId])
}
