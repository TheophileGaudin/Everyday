package com.everyday.everyday_glasses.persistence

import android.graphics.Canvas
import android.util.Log
import com.everyday.everyday_glasses.interaction.user_input.TextZoneHost
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.WidgetLifecycleController
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts
import com.everyday.everyday_glasses.ui.components.LayoutDeletionHandler
import com.everyday.everyday_glasses.ui.components.LayoutNameHandler
import com.everyday.everyday_glasses.ui.components.WidgetLayoutDeleteConfirmationPopup
import com.everyday.everyday_glasses.ui.components.WidgetLayoutNamePrompt

/**
 * Owns the dashboard's layouts: saving it under a name (with the name prompt), deleting a saved
 * layout (with its confirmation popup), and replacing the whole dashboard with a built-in, saved
 * or restored one.
 */
class WidgetLayoutManager(
    private val layouts: LayoutLibrary,
    private val services: WidgetContext,
    private val lifecycle: WidgetLifecycleController,
    private val host: Host,
) {
    /** What replacing the dashboard needs from the container that shows it. */
    interface Host {
        /** The dashboard as it would be saved, without the layout name. */
        fun captureDashboard(): WidgetDocument.PersistedDashboard
        fun notifyPersistentChange()
        /** A stored dashboard was applied: refresh everything, but don't save it again. */
        fun notifyRestored()
        /** Tells the phone whether something still takes the keyboard, without a redraw. */
        fun reportKeyFocus()
        fun dismissMenus()
        /** Closes every menu and popup and cancels any gesture in progress. */
        fun dismissTransientOverlays()
        fun <T> suppressingChanges(action: () -> T): T
        /** Closes every widget, with the container's own cleanup. */
        fun clearWidgets()
        fun applyHoverControls(placements: List<DashboardPersistence.HoverControlPlacementState>?)
        fun resetHoverControls()
        fun restoreLayoutLock(locked: Boolean)
        fun onLayoutSwitch()
    }

    companion object {
        private const val TAG = "WidgetLayoutManager"
    }

    var activeLayoutName: String? = null

    /**
     * True between learning that a saved dashboard exists and actually applying it. While set,
     * nothing may save: the not-yet-restored dashboard would overwrite the stored one.
     */
    var isAwaitingRestore = false
        private set

    /** Cleared once a dashboard is applied or a saved one is expected: no default layout then. */
    private var createsDefaultLayout = true

    private var namePrompt: WidgetLayoutNamePrompt? = null
    private var deleteConfirmation: WidgetLayoutDeleteConfirmationPopup? = null

    // Set when a layout action already reported its change, so the tap behind it doesn't report a second one.
    private var notificationEmitted = false
    private var saveInProgress = false
    private var deleteInProgress = false

    val isNamePromptVisible: Boolean get() = namePrompt?.isVisible == true
    val isDeleteConfirmationVisible: Boolean get() = deleteConfirmation?.isVisible == true

    // ==================== Name prompt and delete confirmation ====================

    /** Builds the name prompt and the delete confirmation for a [width] by [height] screen. */
    fun createOverlays(width: Float, height: Float, textHost: TextZoneHost) {
        deleteConfirmation = WidgetLayoutDeleteConfirmationPopup(width, height).apply {
            handler = object : LayoutDeletionHandler {
                override fun deleteLayout(name: String) = deleting { deleteLayoutByName(name) }
                override fun onPopupDismissed() {
                    if (!deleteInProgress) notifyTransient()
                }
            }
        }
        namePrompt = WidgetLayoutNamePrompt(width, height).apply {
            textZoneHost = textHost
            handler = object : LayoutNameHandler {
                override fun submitLayoutName(name: String) = saving { saveLayoutWithName(name) }
                override fun onPromptDismissed() {
                    // While saving, the save reports the change; only the keyboard state is passed on.
                    host.reportKeyFocus()
                    if (!saveInProgress) notifyTransient()
                }
            }
        }
    }

    /** A tap while the name prompt or the delete confirmation is open; null when neither is. */
    fun onOverlayTap(x: Float, y: Float): Boolean? {
        namePrompt?.takeIf { it.isVisible }?.let { prompt -> return reportedOnce { prompt.onTap(x, y) } }
        deleteConfirmation?.takeIf { it.isVisible }?.let { popup -> return reportedOnce { deleting { popup.onTap(x, y) } } }
        return null
    }

    fun updateOverlayHover(x: Float, y: Float) {
        deleteConfirmation?.updateHover(x, y)
        namePrompt?.updateHover(x, y)
    }

    fun drawDeleteConfirmation(canvas: Canvas) { deleteConfirmation?.draw(canvas) }
    fun drawNamePrompt(canvas: Canvas) { namePrompt?.draw(canvas) }
    fun dismissNamePrompt() { namePrompt?.dismiss() }
    fun dismissOverlays() { deleteConfirmation?.dismiss(); namePrompt?.dismiss() }

    // ==================== Saving and deleting layouts ====================

    private fun isDeliveredWithoutSavedOverride(name: String): Boolean = BuiltInLayouts.isDelivered(name)
    fun requestDeleteLayout(name: String): Boolean {
        if (isDeliveredWithoutSavedOverride(name)) {
            notifyTransient()
            return false
        }
        deleteConfirmation?.show(name)
        notifyTransient()
        return true
    }

    fun capturePersistedDashboard(): WidgetDocument.PersistedDashboard =
        host.captureDashboard().copy(activeLayoutName = activeLayoutName?.trim()?.takeIf { it.isNotBlank() })

    private fun saveLayoutWithName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            namePrompt?.showError("Name required")
            notifyTransient()
            return
        }
        if (BuiltInLayouts.isDelivered(trimmedName)) {
            namePrompt?.showError("Delivered layout names are protected")
            notifyTransient()
            return
        }
        val saved = layouts.save(trimmedName, capturePersistedDashboard())
        if (saved) {
            activeLayoutName = trimmedName
            namePrompt?.dismiss()
            Log.d(TAG, "Saved widget layout '$trimmedName'")
        } else {
            namePrompt?.showError("Could not save layout")
            notifyTransient()
            return
        }
        notifyPersistent()
    }

    private fun deleteLayoutByName(name: String) {
        if (layouts.delete(name)) {
            if (activeLayoutName.equals(name.trim(), ignoreCase = true)) {
                activeLayoutName = null
            }
            Log.d(TAG, "Deleted widget layout '$name'")
            notifyPersistent()
        } else {
            notifyTransient()
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
        host.dismissMenus()
        namePrompt?.show(suggestedName)
        notifyTransient()
    }

    // ==================== Replacing the dashboard ====================

    /** A saved dashboard is about to be restored: no default layout, and no saving until it lands. */
    fun expectSavedDashboard() {
        createsDefaultLayout = false
        isAwaitingRestore = true
    }

    /** On first run only, fills the dashboard with the built-in Standard layout. */
    fun createDefaultLayoutIfFirstRun() {
        if (!createsDefaultLayout) return
        val dashboard = BuiltInLayouts.buildDashboard(
            DashboardPersistence.DEFAULT_LAYOUT_NAME,
            services.viewportWidth.toFloat(),
            services.viewportHeight.toFloat()
        ) ?: return
        host.resetHoverControls()
        replaceDashboard(dashboard)
    }

    /** Loads the built-in or saved layout called [name]; false before the dashboard is sized or when there's none. */
    fun loadLayout(name: String): Boolean {
        if (!isReady()) return false
        val dashboard = BuiltInLayouts.buildDashboard(name, services.viewportWidth.toFloat(), services.viewportHeight.toFloat())
            ?: layouts.load(name)
            ?: return false
        host.onLayoutSwitch()
        replaceDashboard(dashboard, notifyRestored = false)
        activeLayoutName = name
        host.notifyPersistentChange()
        return true
    }

    /**
     * Replaces every widget with [dashboard]'s. If that fails partway, the previous dashboard is put
     * back and the failure rethrown. Pass [notifyRestored] = false when the caller reports the change.
     */
    fun replaceDashboard(dashboard: WidgetDocument.PersistedDashboard, notifyRestored: Boolean = true) {
        if (!isReady()) return

        val previousDashboard = host.captureDashboard().copy(activeLayoutName = activeLayoutName)
        host.suppressingChanges {
            try {
                host.dismissTransientOverlays()
                host.clearWidgets()
                applyContents(dashboard)
            } catch (failure: Throwable) {
                runCatching {
                    host.clearWidgets()
                    applyContents(previousDashboard)
                }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        // The dashboard now reflects a stored one, so saving is safe again.
        isAwaitingRestore = false
        if (notifyRestored) host.notifyRestored()
    }

    private fun applyContents(dashboard: WidgetDocument.PersistedDashboard) {
        createsDefaultLayout = false
        lifecycle.replaceClosedTemplates(dashboard.closedTemplates)

        dashboard.widgets.forEach { record ->
            runCatching { lifecycle.restore(record) }
                .onFailure { Log.w(TAG, "Skipping invalid persisted ${record.type.persistedId} widget", it) }
        }
        host.applyHoverControls(dashboard.hoverControls)
        host.restoreLayoutLock(dashboard.isLayoutLocked)

        lifecycle.arrangeMinimizedWidgets()
        activeLayoutName = dashboard.activeLayoutName
    }

    private fun isReady(): Boolean = services.viewportWidth > 0 && services.viewportHeight > 0

    // ==================== Change reports ====================

    private fun notifyTransient() {
        notificationEmitted = true
        services.notifyTransientChange()
    }

    private fun notifyPersistent() {
        notificationEmitted = true
        host.notifyPersistentChange()
    }

    /** Runs one tap on an overlay and reports it once, unless the action it triggered already did. */
    private inline fun reportedOnce(tap: () -> Boolean): Boolean {
        notificationEmitted = false
        val handled = tap()
        if (handled && !notificationEmitted) services.notifyTransientChange()
        notificationEmitted = false
        return handled
    }

    private inline fun <T> saving(action: () -> T): T {
        saveInProgress = true
        try {
            return action()
        } finally {
            saveInProgress = false
        }
    }

    private inline fun <T> deleting(action: () -> T): T {
        deleteInProgress = true
        try {
            return action()
        } finally {
            deleteInProgress = false
        }
    }
}