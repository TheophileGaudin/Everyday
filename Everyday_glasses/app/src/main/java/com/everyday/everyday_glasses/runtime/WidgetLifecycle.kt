package com.everyday.everyday_glasses.runtime

import android.graphics.PointF
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.settings.UnitSettings
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.shared.sync.SubtitleControl

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

    /** The app's data (phone, GPS, status monitor): the latest values, and subscriptions to new ones. */
    val appData: AppData

    /** The app's unit choices: read them, or toggle one from a widget. */
    val units: UnitSettings

    /** What the activity does for widgets (phone keyboard, mirroring, subtitles, cursor); null until it's plugged in. */
    val host: WidgetHost?

    /** Where the user asked for a new widget (the context menu's spot); null when there's none, so the module picks its default place. */
    val creationPosition: PointF?

    /** Ends the assistant's presentation of [widget], if that widget is the one being presented. */
    fun dismissPresentationOf(widget: BaseWidget)

    /** [widget] took or gave up the keyboard by itself (the map's address search): bring it forward and tell the phone. */
    fun onKeyFocusChanged(widget: BaseWidget, focused: Boolean)

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

/** Capabilities the activity supplies to [com.everyday.everyday_glasses.WidgetContainer]. */
interface WidgetHost {
    fun requestClose()
    fun requestMirror(start: Boolean)
    fun requestBrowserKeyboard(show: Boolean)
    fun requestClipboard()
    fun requestFilePicker()
    fun requestStoragePermission()
    fun requestCursorCenter()
    /** False when the phone didn't take the request. */
    fun requestSubtitleControl(control: SubtitleControl): Boolean
    fun toggleAssistantVoice()
}