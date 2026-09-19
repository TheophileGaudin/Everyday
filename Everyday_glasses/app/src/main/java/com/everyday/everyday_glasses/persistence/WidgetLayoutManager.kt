package com.everyday.everyday_glasses.persistence

import android.util.Log
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts

class WidgetLayoutManager(
    private val layouts: LayoutLibrary,
    private val captureDashboard: () -> WidgetDocument.PersistedDashboard,
    private val showNamePrompt: (String) -> Unit,
    private val showNameError: (String) -> Unit,
    private val dismissNamePrompt: () -> Unit,
    private val showDeleteConfirmation: (String) -> Unit,
    private val notifyTransientContentChanged: () -> Unit,
    private val notifyPersistentContentChanged: () -> Unit
) {
    companion object {
        private const val TAG = "WidgetLayoutManager"
    }

    var activeLayoutName: String? = null
    private fun isDeliveredWithoutSavedOverride(name: String): Boolean = BuiltInLayouts.isDelivered(name)
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

}