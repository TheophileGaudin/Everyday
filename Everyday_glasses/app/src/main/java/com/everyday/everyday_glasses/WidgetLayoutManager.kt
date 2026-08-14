package com.everyday.everyday_glasses

import android.content.Context
import android.util.Log

class WidgetLayoutManager(
    private val context: Context,
    private val stateStore: WidgetStateStore,
    private val canApplyLayout: () -> Boolean,
    private val captureDashboard: () -> WidgetPersistenceV2.PersistedDashboard,
    private val applyDashboard: (WidgetPersistenceV2.PersistedDashboard) -> Unit,
    private val buildDeliveredLayout: (String) -> WidgetPersistenceV2.PersistedDashboard?,
    private val showNamePrompt: (String) -> Unit,
    private val showNameError: (String) -> Unit,
    private val dismissNamePrompt: () -> Unit,
    private val showDeleteConfirmation: (String) -> Unit,
    private val beforeApplyLayout: () -> Unit,
    private val notifyContentChanged: () -> Unit
) {
    companion object {
        private const val TAG = "WidgetLayoutManager"
    }

    var activeLayoutName: String? = null

    private fun loadLayoutByNameOrDelivered(name: String): WidgetPersistenceV2.PersistedDashboard? {
        return buildDeliveredLayout(name) ?: stateStore.loadLayout(name)
    }

    private fun isDeliveredWithoutSavedOverride(name: String): Boolean = BuiltInLayouts.isDelivered(name)

    private fun canonicalLoadName(name: String): String {
        val trimmed = name.trim()
        return BuiltInLayouts.DELIVERED_LAYOUTS.firstOrNull { it.equals(trimmed, ignoreCase = true) }
            ?: trimmed
    }

    fun requestDeleteLayout(name: String): Boolean {
        if (isDeliveredWithoutSavedOverride(name)) {
            return false
        }
        showDeleteConfirmation(name)
        notifyContentChanged()
        return true
    }

    fun capturePersistedDashboard(): WidgetPersistenceV2.PersistedDashboard =
        captureDashboard().copy(activeLayoutName = activeLayoutName?.trim()?.takeIf { it.isNotBlank() })

    fun saveLayoutWithName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            showNameError("Name required")
            notifyContentChanged()
            return
        }
        if (BuiltInLayouts.isDelivered(trimmedName)) {
            showNameError("Delivered layout names are protected")
            notifyContentChanged()
            return
        }
        val saved = stateStore.saveLayout(trimmedName, capturePersistedDashboard())
        if (saved) {
            activeLayoutName = trimmedName
            dismissNamePrompt()
            Log.d(TAG, "Saved widget layout '$trimmedName'")
        } else {
            showNameError("Could not save layout")
        }
        notifyContentChanged()
    }

    fun deleteLayoutByName(name: String) {
        if (stateStore.deleteLayout(name)) {
            if (activeLayoutName.equals(name.trim(), ignoreCase = true)) {
                activeLayoutName = null
            }
            Log.d(TAG, "Deleted widget layout '$name'")
        }
        notifyContentChanged()
    }

    fun saveCurrentLayout() {
        val layoutName = activeLayoutName?.trim().orEmpty()
        if (layoutName.isBlank() || BuiltInLayouts.isDelivered(layoutName)) {
            showSaveLayoutAsPrompt(initialName = "")
            return
        }
        saveLayoutWithName(layoutName)
    }

    fun showSaveLayoutAsPrompt(initialName: String? = null) {
        val suggestedName = initialName
            ?: activeLayoutName
                ?.takeUnless { BuiltInLayouts.isDelivered(it) }
                .orEmpty()
        showNamePrompt(suggestedName)
        notifyContentChanged()
    }

    private fun loadLayoutByName(name: String) {
        if (!canApplyLayout()) return
        val state = loadLayoutByNameOrDelivered(name) ?: return
        val loadedName = canonicalLoadName(name)
        Log.d(TAG, "Loading widget layout '$loadedName'")
        beforeApplyLayout()
        applyDashboard(state)
        activeLayoutName = loadedName
    }
}
