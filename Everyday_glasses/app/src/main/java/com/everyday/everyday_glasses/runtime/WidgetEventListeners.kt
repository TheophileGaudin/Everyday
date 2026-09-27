package com.everyday.everyday_glasses.runtime

import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.NavigationOperationResult
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidget
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.SubtitleControl

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

/**
 * Small platform-neutral dispatcher used to keep cumulative notification semantics explicit.
 * A persistent change includes the repaint and transient work exactly once before its save hook.
 */
class WidgetChangeDispatcher(
    private val repaint: () -> Unit,
    private val transient: () -> Unit,
    private val persistent: () -> Unit
) {
    fun requestRepaint() = repaint()

    fun notifyTransientStateChanged() {
        repaint()
        transient()
    }

    fun notifyPersistentStateChanged() {
        notifyTransientStateChanged()
        persistent()
    }
}

/** Browser events besides change notifications. */
interface BrowserWidgetListener {
    fun onFocusChanged(focused: Boolean) = Unit
    fun onKeyboardRequested(show: Boolean) = Unit
}

interface FileBrowserWidgetListener {
    fun onFileSelected(entry: FileBrowserWidget.FileEntry) = Unit
}

interface MapWidgetListener {
    fun onMapDataRequested(point: MapGeoPoint, config: PoiDisplayConfig) = Unit
    fun onAddressFocusChanged(focused: Boolean) = Unit
    fun onNavigationResult(result: NavigationOperationResult) = Unit
    fun onStopNavigationRequested() = Unit
}

interface FinanceWidgetListener {
    fun onDataRequested(
        visibleConfigs: List<FinanceDashboardTileConfig>,
        refreshTileIds: List<String>,
        force: Boolean,
        reason: String
    ) = Unit
}

interface NewsWidgetListener {
    fun onDataRequested(countryCode: String, force: Boolean, reason: String) = Unit
    fun onSourceDataRequested(
        sourceSpec: NewsSourceSpec,
        countryCode: String,
        force: Boolean,
        reason: String,
        completion: (Result<NewsSnapshot>) -> Unit
    ) = Unit
}

interface SubtitleWidgetListener {
    fun onSubtitleControlRequested(control: SubtitleControl) = Unit
}

interface VideoWidgetListener {
    fun onPlaybackVisibilityChanged(active: Boolean) = Unit
}

interface GeminiSettingsListener {
    fun onPasteGeminiKey() = Unit
    fun onRemoveGeminiKey() = Unit
    fun onSelectGeminiModel(model: String?) = Unit
}