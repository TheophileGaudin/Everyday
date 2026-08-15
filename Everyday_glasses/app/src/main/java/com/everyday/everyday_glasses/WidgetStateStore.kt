package com.everyday.everyday_glasses

import android.content.Context
import android.os.Handler
import android.util.Log

/** Debounced current-state storage and named-layout orchestration. */
class WidgetStateStore(
    private val context: Context,
    private val handler: Handler,
    private val captureDashboard: () -> WidgetDocument.PersistedDashboard,
    /**
     * Guards the saved document against being overwritten by a container that has not
     * loaded it yet. Restoring happens after layout, so between process start and the
     * first layout pass the container is legitimately empty — writing that out would
     * discard the user's dashboard.
     */
    private val canSave: () -> Boolean = { true },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    companion object {
        private const val TAG = "WidgetStateStore"
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }

    private var saveRunnable: Runnable? = null

    fun loadDashboard(): WidgetDocument.PersistedDashboard =
        WidgetPersistence.loadDashboard(context)

    fun saveNow(): Boolean {
        cancelPendingSave()
        if (!canSave()) {
            Log.d(TAG, "Skipping save: dashboard has not been restored yet")
            return false
        }
        return saveDashboard(captureDashboard())
    }

    fun saveDebounced() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        saveRunnable = Runnable {
            saveRunnable = null
            // Re-checked here, not at scheduling time: a save queued during startup
            // must still be dropped if the restore has not landed by the time it fires.
            if (!canSave()) {
                Log.d(TAG, "Skipping debounced save: dashboard has not been restored yet")
                return@Runnable
            }
            saveDashboard(captureDashboard())
        }
        handler.postDelayed(saveRunnable!!, debounceMs)
    }

    fun listLayouts(): List<WidgetDocument.PersistedDashboardLayoutRecord> =
        WidgetPersistence.listDashboardLayouts(context)

    fun saveLayout(name: String, dashboard: WidgetDocument.PersistedDashboard): Boolean =
        WidgetPersistence.saveDashboardLayout(context, name, dashboard)

    fun loadLayout(name: String): WidgetDocument.PersistedDashboard? =
        WidgetPersistence.loadDashboardLayout(context, name)

    fun deleteLayout(name: String): Boolean =
        WidgetPersistence.deleteLayout(context, name)

    fun cancelPendingSave() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        saveRunnable = null
    }

    private fun saveDashboard(dashboard: WidgetDocument.PersistedDashboard): Boolean {
        val saved = WidgetPersistence.saveDashboard(context, dashboard)
        if (saved) {
            pruneUnreferencedImages(dashboard)
        }
        return saved
    }

    private fun pruneUnreferencedImages(dashboard: WidgetDocument.PersistedDashboard) {
        val layoutImagePaths = listLayouts()
            .flatMap { imagePaths(it.dashboard) }
        ImageWidgetStorage.pruneUnreferenced(
            context,
            imagePaths(dashboard) + layoutImagePaths
        )
        Log.d(TAG, "Persisted widget state")
    }

    private fun imagePaths(dashboard: WidgetDocument.PersistedDashboard): List<String> =
        dashboard.widgets.mapNotNull { record ->
            if (record.type != WidgetTypeId.IMAGE) return@mapNotNull null
            (WidgetDocument.codecs[WidgetTypeId.IMAGE]?.decode(record.state)
                as? WidgetPersistence.ImageWidgetState)
                ?.imagePath
        }
}
