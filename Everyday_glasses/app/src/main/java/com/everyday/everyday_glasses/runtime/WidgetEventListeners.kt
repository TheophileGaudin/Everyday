package com.everyday.everyday_glasses.runtime

import com.everyday.everyday_glasses.map.MapGeoPoint
import com.everyday.everyday_glasses.map.NavigationOperationResult
import com.everyday.everyday_glasses.map.PoiDisplayConfig
import com.everyday.everyday_glasses.ui.widgets.BaseWidget
import com.everyday.everyday_glasses.ui.widgets.BrowserWidget
import com.everyday.everyday_glasses.ui.widgets.FileBrowserWidget
import com.everyday.everyday_glasses.ui.widgets.TextBoxWidget
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.SubtitleControl

/**
 * Cumulative change notifications emitted by widgets.
 *
 * Repaint is local visual feedback, transient is visible runtime state that must be mirrored,
 * and persistent includes the transient work plus a debounced dashboard save.  The default
 * implementations keep existing listener implementations source-compatible while callers
 * migrate to the more precise signal.
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

interface BrowserWidgetListener : WidgetChangeListener {
    fun onStateChanged(state: BrowserWidget.State) = onTransientStateChanged()
    fun onFocusChanged(focused: Boolean) = Unit
    fun onKeyboardRequested(show: Boolean) = Unit
}

interface FileBrowserWidgetListener : WidgetChangeListener {
    fun onStateChanged(state: FileBrowserWidget.State) = onTransientStateChanged()
    fun onFileSelected(entry: FileBrowserWidget.FileEntry) = Unit
}

interface MapWidgetListener : WidgetChangeListener {
    fun onMapDataRequested(point: MapGeoPoint, config: PoiDisplayConfig) = Unit
    fun onAddressFocusChanged(focused: Boolean) = Unit
    fun onNavigationResult(result: NavigationOperationResult) = Unit
    fun onStopNavigationRequested() = Unit
}

interface FinanceWidgetListener : WidgetChangeListener {
    fun onStateChanged() = onTransientStateChanged()
    fun onDataRequested(
        visibleConfigs: List<FinanceDashboardTileConfig>,
        refreshTileIds: List<String>,
        force: Boolean,
        reason: String
    ) = Unit
}

interface NewsWidgetListener : WidgetChangeListener {
    fun onStateChanged() = onTransientStateChanged()
    fun onDataRequested(countryCode: String, force: Boolean, reason: String) = Unit
    fun onSourceDataRequested(
        sourceSpec: NewsSourceSpec,
        countryCode: String,
        force: Boolean,
        reason: String,
        completion: (Result<NewsSnapshot>) -> Unit
    ) = Unit
    fun onSourceInputFocusChanged(focused: Boolean) = Unit
}

interface SubtitleWidgetListener : WidgetChangeListener {
    fun onSubtitleControlRequested(control: SubtitleControl) = Unit
    fun onStateChanged() = onTransientStateChanged()
}

interface TextBoxWidgetListener : WidgetChangeListener {
    fun onFocusChanged(focused: Boolean) = Unit
    fun onStateChanged(state: TextBoxWidget.State) = onTransientStateChanged()
}

interface VideoWidgetListener : WidgetChangeListener {
    fun onStateChanged() = onTransientStateChanged()
    fun onPlaybackVisibilityChanged(active: Boolean) = Unit
}

interface WidgetPersistenceListener : WidgetChangeListener {
    fun onWidgetFocusChanged(widget: BaseWidget?, focused: Boolean) = Unit
    fun onWidgetStateChanged() = onTransientStateChanged()
    fun onBrowserKeyboardRequested(show: Boolean) = Unit
}

interface GeminiSettingsListener {
    fun onPasteGeminiKey() = Unit
    fun onRemoveGeminiKey() = Unit
    fun onSelectGeminiModel(model: String?) = Unit
}
