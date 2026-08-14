package com.everyday.everyday_glasses

import org.json.JSONObject

/**
 * The only entry point for unversioned v1 documents.
 *
 * Keeping this adapter separate makes the compatibility boundary explicit: all
 * normal saves use [WidgetPersistenceV2], and this code can be deleted once
 * the app's upgrade-support window ends.
 */
object LegacyWidgetStateV1Migrator {
    fun migrateDashboard(root: JSONObject): WidgetPersistenceV2.PersistedDashboard {
        require(WidgetPersistenceV2.isLegacySchema(root)) { "Expected an unversioned v1 document" }
        return WidgetPersistenceV2.fromLegacy(WidgetPersistence.stateFromJson(root, isFirstRun = false))
    }

    fun migrateLayoutRecords(root: JSONObject): List<WidgetPersistence.WidgetLayoutRecord> {
        require(WidgetPersistenceV2.isLegacySchema(root)) { "Expected an unversioned v1 layout document" }
        return WidgetPersistence.legacyLayoutRecordsFromJson(root)
    }
}
