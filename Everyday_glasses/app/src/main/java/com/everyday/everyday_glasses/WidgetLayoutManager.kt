package com.everyday.everyday_glasses

import android.content.Context
import android.util.Log

class WidgetLayoutManager(
    private val context: Context,
    private val layouts: LayoutLibrary,
    private val canApplyLayout: () -> Boolean,
    private val captureDashboard: () -> WidgetDocument.PersistedDashboard,
    private val applyDashboard: (WidgetDocument.PersistedDashboard) -> Unit,
    private val buildDeliveredLayout: (String) -> WidgetDocument.PersistedDashboard?,
    private val showNamePrompt: (String) -> Unit,
    private val showNameError: (String) -> Unit,
    private val dismissNamePrompt: () -> Unit,
    private val showDeleteConfirmation: (String) -> Unit,
    private val beforeApplyLayout: () -> Unit,
    private val notifyTransientContentChanged: () -> Unit,
    private val notifyPersistentContentChanged: () -> Unit
) {
    companion object {
        private const val TAG = "WidgetLayoutManager"
    }

    var activeLayoutName: String? = null

    private fun loadLayoutByNameOrDelivered(name: String): WidgetDocument.PersistedDashboard? {
        return buildDeliveredLayout(name) ?: layouts.load(name)
    }

    private fun isDeliveredWithoutSavedOverride(name: String): Boolean = BuiltInLayouts.isDelivered(name)

    private fun canonicalLoadName(name: String): String {
        val trimmed = name.trim()
        return BuiltInLayouts.DELIVERED_LAYOUTS.firstOrNull { it.equals(trimmed, ignoreCase = true) }
            ?: trimmed
    }

    fun requestDeleteLayout(name: String): Boolean {
        if (isDeliveredWithoutSavedOverride(name)) {
            notifyTransientContentChanged()
            return false
        }
        showDeleteConfirmation(name)
        notifyTransientContentChanged()
        return true
    }

    fun capturePersistedDashboard(): WidgetDocument.PersistedDashboard =
        captureDashboard().copy(activeLayoutName = activeLayoutName?.trim()?.takeIf { it.isNotBlank() })

    fun saveLayoutWithName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            showNameError("Name required")
            notifyTransientContentChanged()
            return
        }
        if (BuiltInLayouts.isDelivered(trimmedName)) {
            showNameError("Delivered layout names are protected")
            notifyTransientContentChanged()
            return
        }
        val saved = layouts.save(trimmedName, capturePersistedDashboard())
        if (saved) {
            activeLayoutName = trimmedName
            dismissNamePrompt()
            Log.d(TAG, "Saved widget layout '$trimmedName'")
        } else {
            showNameError("Could not save layout")
            notifyTransientContentChanged()
            return
        }
        notifyPersistentContentChanged()
    }

    fun deleteLayoutByName(name: String) {
        if (layouts.delete(name)) {
            if (activeLayoutName.equals(name.trim(), ignoreCase = true)) {
                activeLayoutName = null
            }
            Log.d(TAG, "Deleted widget layout '$name'")
            notifyPersistentContentChanged()
        } else {
            notifyTransientContentChanged()
        }
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
        notifyTransientContentChanged()
    }

    private fun loadLayoutByName(name: String) {
        if (!canApplyLayout()) return
        val state = loadLayoutByNameOrDelivered(name) ?: return
        val loadedName = canonicalLoadName(name)
        Log.d(TAG, "Loading widget layout '$loadedName'")
        beforeApplyLayout()
        applyDashboard(state)
        activeLayoutName = loadedName
        // Loading a layout is a user mutation.  The dashboard restore itself suppresses
        // notifications, so emit the single persistent signal after the active name changes.
        notifyPersistentContentChanged()
    }
}
