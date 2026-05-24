package com.everyday.everyday_glasses

import android.content.Context
import android.util.Log

class WidgetLayoutManager(
    private val context: Context,
    private val persistenceManager: PersistenceManager,
    private val canApplyLayout: () -> Boolean,
    private val captureState: () -> WidgetPersistence.PersistedState,
    private val applyState: (WidgetPersistence.PersistedState) -> Unit,
    private val buildDeliveredLayout: (String) -> WidgetPersistence.PersistedState?,
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
        val savedLayouts = persistenceManager.listLayouts()
            .filterNot { WidgetPersistence.isDefaultLayoutName(it.name) }
            .map { layout ->
                ContextMenu.SubMenuItem("layout_load:${layout.name}", layout.name)
            }
        val savedNames = savedLayouts.map { it.label }
        val deliveredLayouts = BuiltInLayouts.DELIVERED_LAYOUTS
            .filterNot { deliveredName ->
                savedNames.any { it.equals(deliveredName, ignoreCase = true) }
            }
            .map { name -> ContextMenu.SubMenuItem("layout_load:$name", name) }
        val loadItems = deliveredLayouts + savedLayouts
        return actions + ContextMenu.SubMenuItem("layout_load_menu", "Load", submenu = loadItems)
    }

    private fun loadLayoutByNameOrDelivered(name: String): WidgetPersistence.PersistedState? {
        val saved = persistenceManager.loadLayout(name)
        if (saved != null) return saved
        return buildDeliveredLayout(name)
    }

    private fun isDeliveredWithoutSavedOverride(name: String): Boolean {
        if (!BuiltInLayouts.isDelivered(name)) return false
        return persistenceManager.loadLayout(name) == null
    }

    private fun canonicalLoadName(name: String): String {
        val trimmed = name.trim()
        return BuiltInLayouts.DELIVERED_LAYOUTS.firstOrNull { it.equals(trimmed, ignoreCase = true) }
            ?: trimmed
    }

    fun handleSubmenuItemSelected(subItem: ContextMenu.SubMenuItem): Boolean {
        when (subItem.id) {
            "layout_save" -> saveCurrentLayout()
            "layout_save_as" -> showSaveLayoutAsPrompt()
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
        val name = subItem.id.removePrefix("layout_load:")
        if (isDeliveredWithoutSavedOverride(name)) {
            return false
        }
        showDeleteConfirmation(name)
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
            showNameError("${WidgetPersistence.DEFAULT_LAYOUT_NAME} is protected")
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

    private fun loadLayoutByName(name: String) {
        if (!canApplyLayout()) return
        val state = loadLayoutByNameOrDelivered(name) ?: return
        val loadedName = canonicalLoadName(name)
        Log.d(TAG, "Loading widget layout '$loadedName'")
        applyState(state)
        activeLayoutName = loadedName
    }
}
