package com.everyday.everyday_glasses

import android.content.Context
import android.os.Handler
import android.util.Log

/**
 * Owns the active dashboard: the unnamed widget state that is restored when the app
 * reopens.
 *
 * This class decides *when* that state is written. The bytes themselves are
 * [WidgetPersistence]'s job, and named layouts are [LayoutLibrary]'s.
 *
 * Writes are debounced because the dashboard changes continuously while the user
 * drags and resizes widgets: a change schedules a write [debounceMs] later, and any
 * further change reschedules it, so a burst of edits costs one write. [saveNow] is
 * the escape hatch for shutdown, where there is no later.
 */
class ActiveDashboardStore(
    private val context: Context,
    private val handler: Handler,
    private val captureDashboard: () -> WidgetDocument.PersistedDashboard,
    /**
     * Guards the stored dashboard against being overwritten by a container that has
     * not loaded it yet. Restoring happens after layout, so between process start and
     * the first layout pass the container is legitimately empty — writing that out
     * would discard the user's dashboard.
     */
    private val canSave: () -> Boolean = { true },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    companion object {
        private const val TAG = "ActiveDashboardStore"
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }

    private var pendingSave: Runnable? = null
    private var dirty = false

    fun load(): WidgetDocument.PersistedDashboard = WidgetPersistence.loadDashboard(context)

    /** Writes immediately, cancelling any pending debounced write. */
    fun saveNow(): Boolean {
        cancelPendingSave()
        if (!dirty) return true
        if (!canSave()) {
            Log.d(TAG, "Skipping save: dashboard has not been restored yet")
            return false
        }
        val saved = WidgetPersistence.saveDashboard(context, captureDashboard())
        if (saved) dirty = false
        return saved
    }

    /** Writes once the dashboard has been quiet for [debounceMs]. */
    fun saveDebounced() {
        dirty = true
        cancelPendingSave()
        val save = Runnable {
            pendingSave = null
            // Re-checked here, not at scheduling time: a save queued during startup
            // must still be dropped if the restore has not landed by the time it fires.
            if (!canSave()) {
                Log.d(TAG, "Skipping debounced save: dashboard has not been restored yet")
                return@Runnable
            }
            if (WidgetPersistence.saveDashboard(context, captureDashboard())) {
                dirty = false
            }
        }
        pendingSave = save
        handler.postDelayed(save, debounceMs)
    }

    fun cancelPendingSave() {
        pendingSave?.let(handler::removeCallbacks)
        pendingSave = null
    }
}
