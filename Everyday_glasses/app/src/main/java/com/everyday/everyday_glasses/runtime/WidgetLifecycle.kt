package com.everyday.everyday_glasses.runtime

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.settings.UnitSettings
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.shared.sync.SubtitleControl
import com.everyday.everyday_glasses.interaction.commands.AppCommandDefinition
import com.everyday.everyday_glasses.widgets._common.PresentationSpec
import com.everyday.shared.command.AppCommandArguments

/**
 * Cumulative change notifications emitted by widgets.
 *
 * Every widget reports through one instance of this interface, installed on
 * [BaseWidget.changeListener] by [WidgetLifecycleController] when the widget joins the
 * dashboard. Transient is visible runtime state that must be redrawn and mirrored; persistent
 * includes the transient work plus a debounced dashboard save.
 */
interface WidgetChangeListener {
    fun onTransientStateChanged() = Unit
    fun onPersistentStateChanged() = Unit
}

/** What every widget hears of the app's own lifecycle (the activity resuming, pausing, ending; the display). */
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

    fun runCommand(commandId: String, arguments: AppCommandArguments): Boolean = false

    /** The dashboard is going away: release what the plugin itself holds (threads, files). Its widgets are closed separately. */
    fun release() = Unit
}

/**
 * A panel a plugin shows over the dashboard (the speedometer's settings), registered with
 * [WidgetContext.addOverlay]. While it's visible, it's modal: it takes every press, move and tap.
 */
interface DashboardOverlay {
    val isVisible: Boolean
    fun draw(canvas: Canvas)
    fun onDown(x: Float, y: Float): Boolean
    fun onMove(x: Float, y: Float): Boolean
    fun onUp(): Boolean
    fun onTap(x: Float, y: Float): Boolean
    fun dismiss()
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

    /** The app commands this type adds: its menu entries, shortcuts and assistant commands. Its plugin runs them. */
    val commands: List<WidgetCommand> get() = emptyList()
}

/** Where a widget command is listed in the context menu. */
enum class WidgetMenu {
    /** *Widgets*: shows or hides a single-instance type's widget. The dashboard runs these itself. */
    TOGGLE,
    /** *Create*: opens a new widget. */
    CREATE,
    /** *Files*. */
    FILES,
}

/** An app command a widget type adds; [menu] lists it in the context menu, under [menuLabel]. */
data class WidgetCommand(
    val definition: AppCommandDefinition,
    val menu: WidgetMenu? = null,
    val menuLabel: String = definition.shortLabel,
)

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

    /** Where the user asked for a new widget (the context menu's spot); null when there's none, so the plugin picks its default place. */
    val creationPosition: PointF?

    /** Ends the assistant's presentation of [widget], if that widget is the one being presented. */
    fun dismissPresentationOf(widget: BaseWidget)

    /**
     * Presents [widget] for the assistant, as [spec] says (where, for how long, what comes after);
     * false when it can't start. [createdByPresentation]: the widget was opened for this
     * presentation, so the presentation's end may close it.
     */
    fun present(widget: BaseWidget, spec: PresentationSpec, createdByPresentation: Boolean = false): Boolean

    /** Closes [widget] without reporting it as a dashboard change (a presentation that couldn't start). */
    fun closeUnreported(widget: BaseWidget)

    /** Shows the single-instance [type]'s widget, reopening or creating it; the widget, or null. */
    fun show(type: WidgetTypeId): BaseWidget?

    /** Brings [widget] in front of the others, its native view (a web page) included. */
    fun bringToFront(widget: BaseWidget)

    /** Takes the keyboard from whatever has it, the phone's keyboard included. */
    fun clearKeyFocus()

    /** A change that is saved: the dashboard is written soon after. */
    fun notifyPersistentChange()

    /** Shows [overlay] over the dashboard whenever it's visible; see [DashboardOverlay]. */
    fun addOverlay(overlay: DashboardOverlay)

    /** Another type's plugin, for a family of types that work together (the file picker opens images, videos and PDFs). */
    fun plugin(type: WidgetTypeId): WidgetPlugin

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
    /** Sets the speed below which the speedometer hides; the app saves it and publishes it on [AppChannels.speedVisibilityThreshold]. */
    fun setSpeedVisibilityThreshold(thresholdKmh: Float)
}