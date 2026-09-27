package com.everyday.everyday_glasses.runtime

import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.widgets._common.BaseWidget

interface WidgetLifecycle {
    fun onResume() {}
    fun onPause() {}
    fun onDestroy() {}
    fun onDisplayVisibilityChanged(visible: Boolean) {}
}

/** Receives user-driven close, fullscreen, and minimize changes from a [BaseWidget]. */
interface WidgetLifecycleListener {
    fun onCloseRequested(widget: BaseWidget) = Unit
    fun onFullscreenToggled(widget: BaseWidget, isFullscreen: Boolean) = Unit
    fun onMinimizeToggled(widget: BaseWidget, isMinimized: Boolean) = Unit
}

/**
 * What the dashboard offers every widget module: its size, change notifications and the
 * saved-state helpers. WidgetContainer implements it once and hands it to each module.
 */
interface DashboardServices {
    /** Dashboard size in pixels: where a new widget is placed, and what a restored one is fitted to. */
    val viewportWidth: Int
    val viewportHeight: Int

    /** True while the glasses display is off; data updates then don't ask for a redraw. */
    val isDisplayOff: Boolean

    /** Reads and restores the saved state of each widget type. */
    val captureRestore: WidgetCaptureRestore

    /** A visible change that doesn't need saving. */
    fun notifyTransientChange()

    /** Runs [action] and reports the changes it makes as one notification. */
    fun <T> withTransientChanges(action: () -> T): T

    /**
     * Runs one input with the widgets' own notifications muted, then reports it once: saved when
     * [changed] is true, transient otherwise. Returns what [action] returned.
     */
    fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean): Boolean

    /**
     * Runs [action] with the widgets' own notifications muted, then saves the dashboard when
     * [changed] says its result changed something.
     */
    fun <T> runPersistentMutation(changed: (T) -> Boolean = { true }, action: () -> T): T

    /** Adds a new widget of a multiple-instance [type]; null when [create] gives none. */
    fun <W : BaseWidget> open(type: WidgetTypeId, create: () -> W?): W?

    /** Reopens the last closed widget of [type] from its saved template, if there is one. */
    fun openClosedTemplate(type: WidgetTypeId): BaseWidget?

    /** Closes [widget], with its type's cleanup. */
    fun close(widget: BaseWidget)
}
