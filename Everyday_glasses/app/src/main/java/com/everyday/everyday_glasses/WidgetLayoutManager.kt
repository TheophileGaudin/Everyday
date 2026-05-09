package com.everyday.everyday_glasses

import android.content.Context
import android.util.Log

class WidgetLayoutManager(
    private val context: Context,
    private val persistenceManager: PersistenceManager,
    private val canApplyLayout: () -> Boolean,
    private val captureState: () -> WidgetPersistence.PersistedState,
    private val applyState: (WidgetPersistence.PersistedState) -> Unit,
    private val applyDefaultLayout: () -> Unit,
    private val showNamePrompt: (String) -> Unit,
    private val showNameError: (String) -> Unit,
    private val dismissNamePrompt: () -> Unit,
    private val showDeleteConfirmation: (String) -> Unit,
    private val notifyContentChanged: () -> Unit
) {
    companion object {
        private const val TAG = "WidgetLayoutManager"
    }

    var activeLayoutName: String? = null

    fun layoutSubmenuItems(): List<ContextMenu.SubMenuItem> {
        val actions = listOf(
            ContextMenu.SubMenuItem("layout_save", "Save"),
            ContextMenu.SubMenuItem("layout_save_as", "Save As...")
        )
        val defaultLayout = ContextMenu.SubMenuItem(
            "layout_load_default",
            WidgetPersistence.DEFAULT_LAYOUT_NAME
        )
        val layouts = persistenceManager.listLayouts()
            .filterNot { WidgetPersistence.isDefaultLayoutName(it.name) }
            .map { layout ->
                ContextMenu.SubMenuItem("layout_load:${layout.name}", layout.name)
            }
        val loadItems = if (layouts.isEmpty()) {
            listOf(defaultLayout)
        } else {
            listOf(defaultLayout) + layouts
        }
        return actions + ContextMenu.SubMenuItem("layout_load_menu", "Load", submenu = loadItems)
    }

    fun handleSubmenuItemSelected(subItem: ContextMenu.SubMenuItem): Boolean {
        when (subItem.id) {
            "layout_save" -> saveCurrentLayout()
            "layout_save_as" -> showSaveLayoutAsPrompt()
            "layout_load_default" -> loadDefaultLayout()
            else -> {
                if (subItem.id.startsWith("layout_load:")) {
                    loadLayoutByName(subItem.id.removePrefix("layout_load:"))
                } else {
                    return false
                }
            }
        }
        return true
    }

    fun handleSubmenuItemDoubleTapped(subItem: ContextMenu.SubMenuItem): Boolean {
        if (!subItem.id.startsWith("layout_load:")) {
            return false
        }
        showDeleteConfirmation(subItem.id.removePrefix("layout_load:"))
        notifyContentChanged()
        return true
    }

    fun capturePersistedState(): WidgetPersistence.PersistedState =
        captureState().copy(activeLayoutName = activeLayoutName?.trim()?.takeIf { it.isNotBlank() })

    fun saveLayoutWithName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            showNameError("Name required")
            notifyContentChanged()
            return
        }
        if (WidgetPersistence.isDefaultLayoutName(trimmedName)) {
            showNameError("Default is protected")
            notifyContentChanged()
            return
        }
        val saved = persistenceManager.saveLayout(trimmedName, capturePersistedState())
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
        if (persistenceManager.deleteLayout(name)) {
            if (activeLayoutName.equals(name.trim(), ignoreCase = true)) {
                activeLayoutName = null
            }
            Log.d(TAG, "Deleted widget layout '$name'")
        }
        notifyContentChanged()
    }

    private fun saveCurrentLayout() {
        val layoutName = activeLayoutName?.trim().orEmpty()
        if (layoutName.isBlank() || WidgetPersistence.isDefaultLayoutName(layoutName)) {
            showSaveLayoutAsPrompt(initialName = "")
            return
        }
        saveLayoutWithName(layoutName)
    }

    private fun showSaveLayoutAsPrompt(initialName: String? = null) {
        val suggestedName = initialName
            ?: activeLayoutName
                ?.takeUnless { WidgetPersistence.isDefaultLayoutName(it) }
                .orEmpty()
        showNamePrompt(suggestedName)
        notifyContentChanged()
    }

    private fun loadDefaultLayout() {
        if (!canApplyLayout()) return
        Log.d(TAG, "Loading built-in default widget layout")
        applyDefaultLayout()
        activeLayoutName = WidgetPersistence.DEFAULT_LAYOUT_NAME
    }

    private fun loadLayoutByName(name: String) {
        if (!canApplyLayout()) return
        val state = persistenceManager.loadLayout(name) ?: return
        Log.d(TAG, "Loading widget layout '$name'")
        applyState(state)
        activeLayoutName = name.trim()
    }
}
