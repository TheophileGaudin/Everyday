package com.everyday.everyday_glasses

import android.content.res.Resources
import android.graphics.Canvas
import android.util.Log

class SettingsController(
    private val resources: Resources,
    private val defaultSpeedVisibilityThresholdKmh: Float,
    private val invalidateView: () -> Unit,
    private val notifyContentChanged: () -> Unit,
    private val setSpeedometerForceVisibleWhenIdle: (Boolean) -> Boolean,
    private val isSmartAlignmentEnabled: () -> Boolean,
    private val setSmartAlignmentEnabled: (Boolean) -> Unit,
    private val notifyBrightnessChanged: (Float) -> Unit,
    private val notifyAdaptiveBrightnessChanged: (Boolean) -> Unit,
    private val notifyHeadUpTimeChanged: (Long) -> Unit,
    private val notifyWakeDurationChanged: (Long) -> Unit,
    private val notifyAngleThresholdChanged: (Float) -> Unit,
    private val notifyHeadsUpEnabledChanged: (Boolean) -> Unit,
    private val notifySmartAlignmentChanged: (Boolean) -> Unit,
    private val notifySpeedVisibilityThresholdChanged: (Float) -> Unit,
    private val connectGoogle: () -> Unit,
    private val grantCalendarAccess: () -> Unit,
    private val disconnectGoogle: () -> Unit,
    private val retryGoogleAuth: () -> Unit,
    private val shortcutsProvider: () -> List<ShortcutAction> = { ShortcutAction.DEFAULT_LEMON_SHORTCUTS },
    private val onShortcutsChanged: (List<ShortcutAction>) -> Unit = {},
    private val savedLayoutNamesProvider: () -> List<String> = { emptyList() }
) {
    companion object {
        private const val TAG = "SettingsController"
    }

    private var dashboardSettings: DashboardSettings? = null
    private var speedometerSettingsMenu: SpeedometerSettingsMenu? = null
    private var hoverControlsLayoutEditor: HoverControlsLayoutEditor? = null
    private var hoverControlPlacements: List<WidgetPersistence.HoverControlPlacementState>? = null
    private var hasHoverControlPlacements = false
    private var shortcutsSettingsMenu: ShortcutsSettingsMenu? = null
    private var lastScreenWidth: Float = 0f
    private var lastScreenHeight: Float = 0f

    private var brightnessValue = 1.0f
    private var adaptiveBrightnessEnabled = true
    private var headUpTimeMs = HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS
    private var wakeDurationMs = HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS
    private var angleThresholdDegrees = HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD
    private var headsUpEnabled = true
    private var googleAuthState = GoogleAuthState.signedOut()
    private var speedVisibilityThresholdKmh = defaultSpeedVisibilityThresholdKmh

    fun createHoverControlsLayoutEditor(
        screenWidth: Float,
        screenHeight: Float,
        controls: List<BaseHoverControl>
    ) {
        hoverControlsLayoutEditor = HoverControlsLayoutEditor(screenWidth, screenHeight, controls).apply {
            onLayoutChanged = {
                hoverControlPlacements = exportPlacements()
                hasHoverControlPlacements = true
                invalidateView()
                notifyContentChanged()
            }
            onDismissed = {
                invalidateView()
                notifyContentChanged()
            }
            if (hasHoverControlPlacements) {
                applyPersistedPlacements(hoverControlPlacements)
            }
        }
    }

    fun isHoverControlsLayoutEditorVisible(): Boolean =
        hoverControlsLayoutEditor?.isVisible == true

    fun showHoverControlsLayoutEditor() {
        val editor = hoverControlsLayoutEditor ?: return
        editor.show()
        invalidateView()
        notifyContentChanged()
    }

    fun getHoverControlPlacements(): List<WidgetPersistence.HoverControlPlacementState>? =
        hoverControlsLayoutEditor?.exportPlacements()
            ?: hoverControlPlacements.takeIf { hasHoverControlPlacements }

    fun applyHoverControlPlacements(placements: List<WidgetPersistence.HoverControlPlacementState>?) {
        hoverControlPlacements = placements
        hasHoverControlPlacements = true
        hoverControlsLayoutEditor?.applyPersistedPlacements(placements)
        invalidateView()
        notifyContentChanged()
    }

    fun resetHoverControlPlacementsToDefault() {
        hoverControlPlacements = null
        hasHoverControlPlacements = false
        hoverControlsLayoutEditor?.resetPlacementsToDefault()
        invalidateView()
        notifyContentChanged()
    }

    fun drawHoverControlsLayoutEditor(canvas: Canvas) {
        hoverControlsLayoutEditor?.draw(canvas)
    }

    fun handleHoverControlsLayoutEditorDown(x: Float, y: Float): Boolean {
        val editor = hoverControlsLayoutEditor ?: return false
        if (!editor.isVisible) return false
        editor.onDown(x, y)
        notifyContentChanged()
        return true
    }

    fun handleHoverControlsLayoutEditorMove(x: Float, y: Float): Boolean {
        val editor = hoverControlsLayoutEditor ?: return false
        if (!editor.isVisible) return false
        editor.onMove(x, y)
        notifyContentChanged()
        return true
    }

    fun handleHoverControlsLayoutEditorUp() {
        val editor = hoverControlsLayoutEditor ?: return
        if (!editor.isVisible) return
        editor.onUp()
        notifyContentChanged()
    }

    fun handleHoverControlsLayoutEditorTap(x: Float, y: Float): Boolean? {
        val editor = hoverControlsLayoutEditor ?: return null
        if (!editor.isVisible) return null
        val consumed = editor.onTap(x, y)
        if (consumed) notifyContentChanged()
        return true
    }

    fun handleHoverControlsLayoutEditorDoubleTap(x: Float, y: Float): Boolean? {
        val editor = hoverControlsLayoutEditor ?: return null
        if (!editor.isVisible) return null
        val consumed = editor.onDoubleTap(x, y)
        if (consumed) notifyContentChanged()
        return true
    }

    fun createSpeedometerSettingsMenu(screenWidth: Float, screenHeight: Float) {
        lastScreenWidth = screenWidth
        lastScreenHeight = screenHeight
        speedometerSettingsMenu = SpeedometerSettingsMenu(screenWidth, screenHeight).apply {
            setThreshold(speedVisibilityThresholdKmh, notify = false)
            onThresholdChanged = { threshold ->
                if (speedVisibilityThresholdKmh != threshold) {
                    speedVisibilityThresholdKmh = threshold
                    setSpeedometerForceVisibleWhenIdle(speedVisibilityThresholdKmh <= 0f)
                    notifySpeedVisibilityThresholdChanged(threshold)
                }
                invalidateView()
            }
            onDismissed = { notifyContentChanged() }
        }
    }

    fun toggleDashboardSettings(screenWidth: Float, screenHeight: Float) {
        if (dashboardSettings != null) {
            dashboardSettings = null
            Log.d(TAG, "DashboardSettings removed")
        } else {
            createDashboardSettings(screenWidth, screenHeight)
            Log.d(TAG, "DashboardSettings created")
        }
    }

    fun showSpeedometerSettingsMenu() {
        speedometerSettingsMenu?.let { menu ->
            menu.setThreshold(speedVisibilityThresholdKmh, notify = false)
            menu.show()
            notifyContentChanged()
        }
    }

    fun dismissSpeedometerSettingsMenu() {
        speedometerSettingsMenu?.dismiss()
    }

    fun dismissAll() {
        dashboardSettings?.dismiss()
        speedometerSettingsMenu?.dismiss()
        hoverControlsLayoutEditor?.dismiss()
        shortcutsSettingsMenu?.dismiss()
    }

    fun draw(canvas: Canvas) {
        dashboardSettings?.draw(canvas)
        speedometerSettingsMenu?.draw(canvas)
        shortcutsSettingsMenu?.draw(canvas)
    }

    // ==================== Shortcuts menu ====================

    fun isShortcutsSettingsMenuVisible(): Boolean = shortcutsSettingsMenu?.isVisible == true

    fun isDashboardSettingsVisible(): Boolean = dashboardSettings?.isVisible == true

    fun isSpeedometerSettingsMenuVisible(): Boolean = speedometerSettingsMenu?.isVisible == true

    fun showShortcutsSettingsMenu() {
        val screenWidth = lastScreenWidth.takeIf { it > 0f } ?: return
        val screenHeight = lastScreenHeight.takeIf { it > 0f } ?: return
        val menu = ShortcutsSettingsMenu(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            savedLayoutsProvider = savedLayoutNamesProvider,
            initialShortcuts = shortcutsProvider()
        ).apply {
            onChanged = { actions ->
                onShortcutsChanged(actions)
                invalidateView()
                notifyContentChanged()
            }
            onDismissed = {
                shortcutsSettingsMenu = null
                invalidateView()
                notifyContentChanged()
            }
            show()
        }
        shortcutsSettingsMenu = menu
        invalidateView()
        notifyContentChanged()
    }

    fun handleShortcutsSettingsMenuDown(x: Float, y: Float): Boolean {
        val menu = shortcutsSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onDown(x, y)
        notifyContentChanged()
        return true
    }

    fun handleShortcutsSettingsMenuMove(x: Float, y: Float): Boolean {
        val menu = shortcutsSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onMove(x, y)
        notifyContentChanged()
        return true
    }

    fun handleShortcutsSettingsMenuUp() {
        val menu = shortcutsSettingsMenu ?: return
        if (!menu.isVisible) return
        menu.onUp()
        notifyContentChanged()
    }

    fun handleShortcutsSettingsMenuTap(x: Float, y: Float): Boolean? {
        val menu = shortcutsSettingsMenu ?: return null
        if (!menu.isVisible) return null
        val consumed = menu.onTap(x, y)
        if (consumed) notifyContentChanged()
        return true
    }


    fun handleSpeedometerDown(x: Float, y: Float): Boolean {
        val menu = speedometerSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onDown(x, y)
        notifyContentChanged()
        return true
    }

    fun handleSpeedometerMove(x: Float, y: Float): Boolean {
        val menu = speedometerSettingsMenu ?: return false
        if (!menu.isVisible) return false
        menu.onMove(x, y)
        notifyContentChanged()
        return true
    }

    fun handleSpeedometerUp() {
        val menu = speedometerSettingsMenu ?: return
        if (!menu.isVisible) return
        menu.onUp()
        notifyContentChanged()
    }

    fun handleSpeedometerTap(x: Float, y: Float): Boolean? {
        val menu = speedometerSettingsMenu ?: return null
        if (!menu.isVisible) return null
        return menu.onTap(x, y)
    }

    fun handleDashboardDown(x: Float, y: Float): Boolean {
        val settings = dashboardSettings ?: return false
        if (!settings.isVisible) return false
        settings.onDown(x, y)
        notifyContentChanged()
        return true
    }

    fun handleDashboardMove(x: Float, y: Float): Boolean {
        val settings = dashboardSettings ?: return false
        if (!settings.isVisible) return false
        settings.onMove(x, y)
        notifyContentChanged()
        return true
    }

    fun handleDashboardUp() {
        val settings = dashboardSettings ?: return
        if (!settings.isVisible) return
        settings.onUp()
        notifyContentChanged()
    }

    fun handleDashboardTap(x: Float, y: Float): Boolean? {
        val settings = dashboardSettings ?: return null
        if (!settings.isVisible) return null
        val consumed = settings.onTap(x, y)
        if (consumed) notifyContentChanged()
        return true
    }

    fun setSpeedVisibilityThreshold(thresholdKmh: Float): Boolean {
        val clamped = thresholdKmh.coerceIn(0f, 1f)
        val forceVisibleChanged = setSpeedometerForceVisibleWhenIdle(clamped <= 0f)
        if (speedVisibilityThresholdKmh == clamped) {
            speedometerSettingsMenu?.setThreshold(clamped, notify = false)
            return forceVisibleChanged
        }
        speedVisibilityThresholdKmh = clamped
        speedometerSettingsMenu?.setThreshold(clamped, notify = false)
        notifySpeedVisibilityThresholdChanged(clamped)
        return forceVisibleChanged
    }

    fun getSpeedVisibilityThreshold(): Float = speedVisibilityThresholdKmh

    fun setBrightness(value: Float) {
        brightnessValue = value.coerceIn(0f, 1f)
        notifyBrightnessChanged(brightnessValue)
    }

    fun getBrightness(): Float = brightnessValue

    fun setAdaptiveBrightnessEnabled(enabled: Boolean) {
        adaptiveBrightnessEnabled = enabled
        notifyAdaptiveBrightnessChanged(adaptiveBrightnessEnabled)
    }

    fun getAdaptiveBrightnessEnabled(): Boolean = adaptiveBrightnessEnabled

    fun setHeadUpTime(ms: Long) {
        headUpTimeMs = ms.coerceIn(
            HeadUpWakeManager.MIN_HEAD_UP_HOLD_TIME_MS,
            HeadUpWakeManager.MAX_HEAD_UP_HOLD_TIME_MS
        )
        notifyHeadUpTimeChanged(headUpTimeMs)
    }

    fun getHeadUpTime(): Long = headUpTimeMs

    fun setWakeDuration(ms: Long) {
        wakeDurationMs = ms.coerceIn(
            HeadUpWakeManager.MIN_WAKE_DURATION_MS,
            HeadUpWakeManager.MAX_WAKE_DURATION_MS
        )
        notifyWakeDurationChanged(wakeDurationMs)
    }

    fun getWakeDuration(): Long = wakeDurationMs

    fun setAngleThreshold(degrees: Float) {
        angleThresholdDegrees = degrees.coerceIn(
            DashboardSettings.MIN_ANGLE_THRESHOLD,
            DashboardSettings.MAX_ANGLE_THRESHOLD
        )
        notifyAngleThresholdChanged(angleThresholdDegrees)
    }

    fun getAngleThreshold(): Float = angleThresholdDegrees

    fun setHeadsUpEnabled(enabled: Boolean) {
        headsUpEnabled = enabled
        notifyHeadsUpEnabledChanged(headsUpEnabled)
    }

    fun getHeadsUpEnabled(): Boolean = headsUpEnabled

    fun setGoogleAuthState(state: GoogleAuthState) {
        googleAuthState = state
        dashboardSettings?.googleAuthState = state
        invalidateView()
        notifyContentChanged()
    }

    private fun createDashboardSettings(screenWidth: Float, screenHeight: Float) {
        dashboardSettings = DashboardSettings(resources, screenWidth, screenHeight).apply {
            brightnessValue = this@SettingsController.brightnessValue
            adaptiveBrightnessEnabled = this@SettingsController.adaptiveBrightnessEnabled
            setHeadUpTimeMs(this@SettingsController.headUpTimeMs)
            setWakeDurationMs(this@SettingsController.wakeDurationMs)
            setAngleThresholdDegrees(this@SettingsController.angleThresholdDegrees)
            headsUpEnabled = this@SettingsController.headsUpEnabled
            smartAlignmentEnabled = isSmartAlignmentEnabled()
            googleAuthState = this@SettingsController.googleAuthState

            onBrightnessChanged = { value ->
                this@SettingsController.brightnessValue = value
                notifyBrightnessChanged(value)
                invalidateView()
            }

            onAdaptiveBrightnessChanged = { enabled ->
                this@SettingsController.adaptiveBrightnessEnabled = enabled
                notifyAdaptiveBrightnessChanged(enabled)
                invalidateView()
            }

            onHeadUpTimeChanged = { value ->
                val ms = headUpTimeValueToMs(value)
                this@SettingsController.headUpTimeMs = ms
                notifyHeadUpTimeChanged(ms)
                invalidateView()
            }

            onWakeDurationChanged = { value ->
                val ms = wakeDurationValueToMs(value)
                this@SettingsController.wakeDurationMs = ms
                notifyWakeDurationChanged(ms)
                invalidateView()
            }

            onAngleThresholdChanged = { degrees ->
                this@SettingsController.angleThresholdDegrees = degrees
                notifyAngleThresholdChanged(degrees)
                invalidateView()
            }

            onHeadsUpEnabledChanged = { enabled ->
                this@SettingsController.headsUpEnabled = enabled
                notifyHeadsUpEnabledChanged(enabled)
                invalidateView()
            }

            onSmartAlignmentChanged = { enabled ->
                setSmartAlignmentEnabled(enabled)
                notifySmartAlignmentChanged(enabled)
                invalidateView()
            }

            onConnectGoogle = {
                connectGoogle()
                invalidateView()
            }

            onGrantCalendarAccess = {
                grantCalendarAccess()
                invalidateView()
            }

            onDisconnectGoogle = {
                disconnectGoogle()
                invalidateView()
            }

            onRetryGoogleAuth = {
                retryGoogleAuth()
                invalidateView()
            }

            onAppearanceRequested = {
                showHoverControlsLayoutEditor()
            }

            onShortcutsRequested = {
                showShortcutsSettingsMenu()
            }

            onDismissed = {
                dashboardSettings = null
                Log.d(TAG, "DashboardSettings removed")
            }

            show()
        }
        notifyContentChanged()
    }
}
