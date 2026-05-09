package com.everyday.everyday_glasses

import android.content.Context
import android.os.Handler
import android.util.Log

class PersistenceManager(
    private val context: Context,
    private val handler: Handler,
    private val captureState: () -> WidgetPersistence.PersistedState,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    companion object {
        private const val TAG = "PersistenceManager"
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }

    private var saveRunnable: Runnable? = null

    fun loadState(): WidgetPersistence.PersistedState =
        WidgetPersistence.loadAll(context)

    fun saveNow(): Boolean {
        cancelPendingSave()
        return saveState(captureState())
    }

    fun saveDebounced() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        saveRunnable = Runnable {
            saveRunnable = null
            saveState(captureState())
        }
        handler.postDelayed(saveRunnable!!, debounceMs)
    }

    fun listLayouts(): List<WidgetPersistence.WidgetLayoutRecord> =
        WidgetPersistence.listLayouts(context)

    fun saveLayout(name: String, state: WidgetPersistence.PersistedState): Boolean =
        WidgetPersistence.saveLayout(context, name, state)

    fun loadLayout(name: String): WidgetPersistence.PersistedState? =
        WidgetPersistence.loadLayout(context, name)

    fun deleteLayout(name: String): Boolean =
        WidgetPersistence.deleteLayout(context, name)

    fun cancelPendingSave() {
        saveRunnable?.let { handler.removeCallbacks(it) }
        saveRunnable = null
    }

    private fun saveState(state: WidgetPersistence.PersistedState): Boolean {
        val saved = WidgetPersistence.saveState(context, state)
        if (saved) {
            pruneUnreferencedImages(state)
        }
        return saved
    }

    private fun pruneUnreferencedImages(state: WidgetPersistence.PersistedState) {
        val layoutImagePaths = listLayouts()
            .flatMap { it.state.image.map { image -> image.imagePath } }
        ImageWidgetStorage.pruneUnreferenced(
            context,
            state.image.map { it.imagePath } + layoutImagePaths
        )
        Log.d(TAG, "Persisted widget state")
    }
}
