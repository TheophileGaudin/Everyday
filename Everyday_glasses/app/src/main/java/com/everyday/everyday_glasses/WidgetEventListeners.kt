package com.everyday.everyday_glasses

import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.SubtitleControl

interface BrowserWidgetListener {
    fun onStateChanged(state: BrowserWidget.State) = Unit
    fun onFocusChanged(focused: Boolean) = Unit
    fun onKeyboardRequested(show: Boolean) = Unit
}

interface FileBrowserWidgetListener {
    fun onStateChanged(state: FileBrowserWidget.State) = Unit
    fun onFileSelected(entry: FileBrowserWidget.FileEntry) = Unit
}

interface MapWidgetListener {
    fun onMapDataRequested(point: MapGeoPoint, config: PoiDisplayConfig) = Unit
    fun onAddressFocusChanged(focused: Boolean) = Unit
    fun onNavigationResult(result: NavigationOperationResult) = Unit
    fun onStopNavigationRequested() = Unit
}

interface FinanceWidgetListener {
    fun onStateChanged() = Unit
    fun onDataRequested(
        visibleConfigs: List<FinanceDashboardTileConfig>,
        refreshTileIds: List<String>,
        force: Boolean,
        reason: String
    ) = Unit
}

interface NewsWidgetListener {
    fun onStateChanged() = Unit
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

interface SubtitleWidgetListener {
    fun onSubtitleControlRequested(control: SubtitleControl) = Unit
    fun onStateChanged() = Unit
}

interface TextBoxWidgetListener {
    fun onFocusChanged(focused: Boolean) = Unit
    fun onStateChanged(state: TextBoxWidget.State) = Unit
}

interface VideoWidgetListener {
    fun onStateChanged() = Unit
    fun onPlaybackVisibilityChanged(active: Boolean) = Unit
}

interface WidgetPersistenceListener {
    fun onWidgetFocusChanged(widget: BaseWidget?, focused: Boolean) = Unit
    fun onTextWidgetDeleted(widget: TextBoxWidget) = Unit
    fun onBrowserWidgetClosed(widget: BrowserWidget) = Unit
    fun onWidgetStateChanged() = Unit
    fun onTextWidgetFullscreenToggled(widget: TextBoxWidget, isFullscreen: Boolean) = Unit
    fun onTextWidgetMinimizeToggled(widget: TextBoxWidget, isMinimized: Boolean) = Unit
    fun onBrowserWidgetFullscreenToggled(widget: BrowserWidget, isFullscreen: Boolean) = Unit
    fun onBrowserWidgetMinimizeToggled(widget: BrowserWidget, isMinimized: Boolean) = Unit
    fun onBrowserKeyboardRequested(show: Boolean) = Unit
}

interface GeminiSettingsListener {
    fun onPasteGeminiKey() = Unit
    fun onRemoveGeminiKey() = Unit
    fun onSelectGeminiModel(model: String?) = Unit
}
