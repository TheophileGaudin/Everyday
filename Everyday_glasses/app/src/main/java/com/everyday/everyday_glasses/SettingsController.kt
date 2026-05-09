package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.util.Log

class SettingsController(
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
    private val retryGoogleAuth: () -> Unit
) {
    companion object {
        private const val TAG = "SettingsController"
    }

    private var dashboardSettings: DashboardSettings? = null
    private var speedometerSettingsMenu: SpeedometerSettingsMenu? = null

    private var brightnessValue = 1.0f
    private var adaptiveBrightnessEnabled = true
    private var headUpTimeMs = HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS
    private var wakeDurationMs = HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS
    private var angleThresholdDegrees = HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD
    private var headsUpEnabled = true
    private var googleAuthState = GoogleAuthState.signedOut()
    private var speedVisibilityThresholdKmh = defaultSpeedVisibilityThresholdKmh

    fun createSpeedometerSettingsMenu(screenWidth: Float, screenHeight: Float) {
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
    }

    fun draw(canvas: Canvas) {
        dashboardSettings?.draw(canvas)
        speedometerSettingsMenu?.draw(canvas)
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
        dashboardSettings = DashboardSettings(screenWidth, screenHeight).apply {
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

            onDismissed = {
                dashboardSettings = null
                Log.d(TAG, "DashboardSettings removed")
            }

            show()
        }
        notifyContentChanged()
    }
}
