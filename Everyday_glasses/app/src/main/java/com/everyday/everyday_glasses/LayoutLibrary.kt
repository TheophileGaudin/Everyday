package com.everyday.everyday_glasses

/**
 * Owns the named layouts: the dashboards the user has deliberately filed under a name
 * and can load again.
 *
 * A layout holds the same [WidgetDocument.PersistedDashboard] as the active dashboard;
 * the name and its timestamps are the only difference. This class is the authority on
 * which names exist — [WidgetDocument.PersistedDashboard.activeLayoutName] only records
 * which one the live dashboard came from.
 *
 * Every operation here is an explicit user action, so unlike [ActiveDashboardStore]
 * there is nothing to debounce: writes happen immediately.
 */
class LayoutLibrary(private val context: android.content.Context) {

    fun list(): List<WidgetDocument.PersistedDashboardLayoutRecord> =
        WidgetPersistence.listDashboardLayouts(context)

    fun names(): List<String> = list().map { it.name }

    fun save(name: String, dashboard: WidgetDocument.PersistedDashboard): Boolean =
        WidgetPersistence.saveDashboardLayout(context, name, dashboard)

    fun load(name: String): WidgetDocument.PersistedDashboard? =
        WidgetPersistence.loadDashboardLayout(context, name)

    /**
     * Deleting a layout can orphan the images it referenced, so this is one of the two
     * moments image storage is collected. The other is startup.
     */
    fun delete(name: String): Boolean {
        if (!WidgetPersistence.deleteLayout(context, name)) return false
        WidgetPersistence.pruneUnreferencedImages(context)
        return true
    }
}
