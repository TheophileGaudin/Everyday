package com.everyday.everyday_glasses.bindings

import com.everyday.everyday_glasses.binocular.BinocularContentClass
import com.everyday.everyday_glasses.map.MapGeoPoint
import com.everyday.everyday_glasses.map.PoiDisplayConfig
import com.everyday.everyday_glasses.ui.widgets.ScreenMirrorWidget
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.FinanceDashboardTileConfig

/** Observes user-visible content changes produced by [com.everyday.everyday_glasses.WidgetContainer]. */
interface WidgetContentObserver {
    fun onFocusChanged(focused: Boolean)
    fun onPersistentContentChanged()
    fun onTransientContentChanged()
    /** Emits the one structural binocular refresh that completes a suppressed restore. */
    fun onDashboardRestored() = Unit
    fun onMediaFrame(sourceId: String)
    fun onBinocularContentSourceChanged(
        sourceId: String,
        contentClass: BinocularContentClass,
        active: Boolean
    )
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
    fun requestSubtitleControl(control: SubtitleControl)
    fun toggleAssistantVoice()
}

/** Applies display and wake-related settings selected inside the widget UI. */
interface DisplaySettingsHandler {
    fun applyBrightness(brightness: Float)
    fun applyAdaptiveBrightness(enabled: Boolean)
    fun applyMediaBrightnessCap(active: Boolean)
    fun applyBrowserMinimumBrightness(active: Boolean)
    fun applyLayoutSwitchBrightness()
    fun applyHeadUpTime(durationMs: Long)
    fun applyWakeDuration(durationMs: Long)
    fun applyHeadUpAngleThreshold(degrees: Float)
    fun applyHeadsUpEnabled(enabled: Boolean)
    fun applySmartAlignment(enabled: Boolean)
    fun applySpeedVisibilityThreshold(threshold: Float)
}

/** Handles Google-account actions initiated by settings hosted in [com.everyday.everyday_glasses.WidgetContainer]. */
interface GoogleAccountHandler {
    fun connectGoogleAccount()
    fun grantCalendarAccess()
    fun disconnectGoogleAccount()
    fun retryGoogleAuthentication()
    fun onGoogleAuthBrowserClosed()
}

/** Supplies remotely synchronized data requested by widgets. */
interface WidgetDataProvider {
    fun requestFinanceData(
        visibleConfigs: List<FinanceDashboardTileConfig>,
        refreshTileIds: List<String>,
        force: Boolean,
        reason: String
    )

    fun onFinanceVisibilityChanged(visible: Boolean)
    fun requestNewsData(countryCode: String, force: Boolean, reason: String)
    fun requestNewsSourceData(
        sourceSpec: NewsSourceSpec,
        countryCode: String,
        force: Boolean,
        reason: String,
        completion: (Result<NewsSnapshot>) -> Unit
    )

    fun requestMapData(point: MapGeoPoint, config: PoiDisplayConfig)
}

interface MirrorWidgetListener : WidgetChangeListener {
    fun onStateChanged(state: ScreenMirrorWidget.State)
    fun onStreamStateChanged(state: ScreenMirrorWidget.StreamState)
    fun onFrameReady()
    fun requestMirror(start: Boolean)
    fun onMediaStateChanged(active: Boolean)
}
