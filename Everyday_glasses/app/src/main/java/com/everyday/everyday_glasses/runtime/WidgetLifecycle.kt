package com.everyday.everyday_glasses.runtime

import android.content.Context
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.settings.SettingsController
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
 * One widget type's plugin: how its widgets are built, restored, saved and fed. Every plugin is built
 * from a [WidgetContext] only, and listed once, in `widgets/WidgetPlugins.kt`.
 */
interface WidgetPlugin {
    /** How the dashboard opens, restores, saves and closes this type's widgets. */
    val lifecycle: WidgetLifecycleRegistration<out BaseWidget, out Any>
}

/**
 * A widget type, as the app knows it before any widget exists: its [descriptor], how its widgets
 * are saved ([codec]), and how to build its plugin. Each plugin's companion object is one, and
 * `widgets/WidgetPlugins.kt` lists them.
 */
interface WidgetType {
    val descriptor: WidgetDescriptor
    val codec: WidgetDocument.WidgetStateCodec
    fun create(context: WidgetContext): WidgetPlugin
}

/**
 * What the dashboard offers every widget plugin, named by capability, never by widget: its size and
 * views, change notifications, the app's data, the saved-state helpers, and the requests a widget can
 * make. WidgetContainer implements it once and hands it to each plugin.
 */
interface WidgetContext {
    /** Dashboard size in pixels: where a new widget is placed, and what a restored one is fitted to. */
    val viewportWidth: Int
    val viewportHeight: Int

    /** The Android context, for widgets that load resources or files. */
    val androidContext: Context

    /** The view group that holds the dashboard and the native views (web pages, video); null until the dashboard is on screen. */
    val rootLayout: ViewGroup?

    /** The dashboard's own view, which native views are placed relative to. */
    val dashboardView: View

    /** Runs code on the main thread, from the assistant's worker thread for example. */
    val ui: UiThreadDispatcher

    /** The settings screens. For now only the speedometer uses it, for its settings menu (WP11 moves that into its package). */
    val settings: SettingsController

    /** The widget of the single-instance [type] on the dashboard, if there is one. */
    fun singleton(type: WidgetTypeId): BaseWidget?

    /** The widgets of [type] on the dashboard. */
    fun widgets(type: WidgetTypeId): List<BaseWidget>

    /** [widget]'s durable id, which the assistant and presentations use. */
    fun stableId(widget: BaseWidget): String

    /** Redraws the dashboard for a change that's only visual (no change report). */
    fun requestRepaint()

    /** A media widget drew a new frame; the second eye's display follows it. */
    fun notifyMediaFrame(sourceId: String)

    /** A media widget started or stopped playing (brightness cap, second eye). */
    fun setMediaSourceActive(sourceId: String, active: Boolean)

    /** Re-evaluates the brightness rules the widgets ask for (media cap, browser minimum). */
    fun refreshBrightnessRules()

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