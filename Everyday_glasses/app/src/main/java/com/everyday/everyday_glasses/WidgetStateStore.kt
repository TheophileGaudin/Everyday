package com.everyday.everyday_glasses

import android.content.Context
import android.os.Handler
import android.util.Log

/** Debounced current-state storage and named-layout orchestration. */
class WidgetStateStore(
    private val context: Context,
    private val handler: Handler,
    private val captureDashboard: () -> WidgetPersistenceV2.PersistedDashboard,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    companion object {
        private const val TAG = "WidgetStateStore"
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }

    private var saveRunnable: Runnable? = null

    fun loadDashboard(): WidgetPersistenceV2.PersistedDashboard =
        WidgetPersistence.loadDashboard(context)

    fun saveNow(): Boolean {
        cancelPendingSave()
        return saveDashboard(captureDashboard())
    }

    fun saveDebounced() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        saveRunnable = Runnable {
            saveRunnable = null
            saveDashboard(captureDashboard())
        }
        handler.postDelayed(saveRunnable!!, debounceMs)
    }

    fun listLayouts(): List<WidgetPersistence.PersistedDashboardLayoutRecord> =
        WidgetPersistence.listDashboardLayouts(context)

    fun saveLayout(name: String, dashboard: WidgetPersistenceV2.PersistedDashboard): Boolean =
        WidgetPersistence.saveDashboardLayout(context, name, dashboard)

    fun loadLayout(name: String): WidgetPersistenceV2.PersistedDashboard? =
        WidgetPersistence.loadDashboardLayout(context, name)

    fun deleteLayout(name: String): Boolean =
        WidgetPersistence.deleteLayout(context, name)

    fun cancelPendingSave() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        saveRunnable = null
    }

    private fun saveDashboard(dashboard: WidgetPersistenceV2.PersistedDashboard): Boolean {
        val saved = WidgetPersistence.saveDashboard(context, dashboard)
        if (saved) {
            pruneUnreferencedImages(dashboard)
        }
        return saved
    }

    private fun pruneUnreferencedImages(dashboard: WidgetPersistenceV2.PersistedDashboard) {
        val layoutImagePaths = listLayouts()
            .flatMap { imagePaths(it.dashboard) }
        ImageWidgetStorage.pruneUnreferenced(
            context,
            imagePaths(dashboard) + layoutImagePaths
        )
        Log.d(TAG, "Persisted widget state")
    }

    private fun imagePaths(dashboard: WidgetPersistenceV2.PersistedDashboard): List<String> =
        dashboard.widgets.mapNotNull { record ->
            if (record.type != WidgetTypeId.IMAGE) return@mapNotNull null
            (WidgetPersistenceV2.codecs[WidgetTypeId.IMAGE]?.decode(record.state)
                as? WidgetPersistence.ImageWidgetState)
                ?.imagePath
        }
}
