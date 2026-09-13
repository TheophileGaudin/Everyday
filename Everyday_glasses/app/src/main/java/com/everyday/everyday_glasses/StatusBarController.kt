package com.everyday.everyday_glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Complete data snapshot displayed by StatusBarWidget. */
data class StatusBarDisplayState(
    val isPhoneConnected: Boolean = false,
    val phoneBatteryPercent: Int = -1,
    val glassesBatteryPercent: Int = -1,
    val time: String = "--:--",
    val date: String = "-- --- ----"
)

/** Application reactions required by StatusBarController. */
interface StatusBarControllerCallbacks {
    /** Sends a complete presentation snapshot to WidgetContainer. */
    fun applyStatusBarState(state: StatusBarDisplayState)

    /**
     * Reports charging state for brightness and binocular rendering behavior.
     */
    fun onChargingChanged(charging: Boolean)
}

/**
 * Owns status-bar data and the Android sources that update it.
 *
 * Widget placement, drawing, persistence, and visibility options remain in
 * StatusBarWidget and WidgetContainer.
 */
class StatusBarController(
    context: Context,
    private val callbacks: StatusBarControllerCallbacks
) {

    companion object {
        private const val TAG = "StatusBarController"
        private const val BATTERY_UPDATE_INTERVAL_MS = 30_000L
        private const val TIME_UPDATE_INTERVAL_MS = 1_000L
    }

    // ---------------------------------------------------------------------
    // Owned Android objects and presentation state
    // ---------------------------------------------------------------------

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var state = StatusBarDisplayState()
    private var batteryStateReceiver: BroadcastReceiver? = null
    private var glassesBatteryUpdateRunnable: Runnable? = null
    private var timeUpdateRunnable: Runnable? = null

    private var started = false
    private var released = false

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    /** Registers system sources and begins periodic status updates. */
    fun start() {
        if (started || released) return
        started = true

        registerBatteryStateReceiver()
        refreshClock()
        refreshGlassesBattery()
        publishState()

        startGlassesBatteryMonitoring()
        startTimeUpdates()
    }

    /** Stops all callbacks and unregisters the battery receiver. */
    fun release() {
        if (released) return
        released = true

        glassesBatteryUpdateRunnable?.let(handler::removeCallbacks)
        glassesBatteryUpdateRunnable = null

        timeUpdateRunnable?.let(handler::removeCallbacks)
        timeUpdateRunnable = null

        batteryStateReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to unregister battery receiver", error)
                }
        }
        batteryStateReceiver = null
    }

    // ---------------------------------------------------------------------
    // Phone-driven updates
    // ---------------------------------------------------------------------

    /** Call only for a real phone connection transition. */
    fun onPhoneConnectionChanged(
        connected: Boolean,
        phoneBatteryPercent: Int
    ) {
        state = state.copy(
            isPhoneConnected = connected,
            phoneBatteryPercent = if (connected) phoneBatteryPercent else -1
        )
        publishState()
    }

    /**
     * Call for the phone's periodic battery message. This must not be treated
     * as a reconnection event.
     */
    fun onPhoneBatteryChanged(phoneBatteryPercent: Int) {
        state = state.copy(
            isPhoneConnected = true,
            phoneBatteryPercent = phoneBatteryPercent
        )
        publishState()
    }

    /**
     * Republishes the latest snapshot. Useful immediately after dashboard
     * restoration or status-widget creation.
     */
    fun refreshNow() {
        if (released) return
        refreshClock()
        refreshGlassesBattery()
        publishState()
    }

    // ---------------------------------------------------------------------
    // Periodic updates
    // ---------------------------------------------------------------------

    private fun startGlassesBatteryMonitoring() {
        glassesBatteryUpdateRunnable = object : Runnable {
            override fun run() {
                if (released) return

                refreshGlassesBattery()
                publishState()
                handler.postDelayed(this, BATTERY_UPDATE_INTERVAL_MS)
            }
        }

        handler.postDelayed(
            glassesBatteryUpdateRunnable!!,
            BATTERY_UPDATE_INTERVAL_MS
        )
    }

    private fun startTimeUpdates() {
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                if (released) return

                refreshClock()
                publishState()
                handler.postDelayed(this, TIME_UPDATE_INTERVAL_MS)
            }
        }

        handler.postDelayed(timeUpdateRunnable!!, TIME_UPDATE_INTERVAL_MS)
    }

    private fun refreshClock() {
        state = state.copy(
            time = getCurrentTime(),
            date = getCurrentDate()
        )
    }

    private fun refreshGlassesBattery() {
        state = state.copy(
            glassesBatteryPercent = getGlassesBatteryLevel()
        )
    }

    private fun publishState() {
        if (released) return
        callbacks.applyStatusBarState(state)
    }

    // ---------------------------------------------------------------------
    // Time and battery sources
    // ---------------------------------------------------------------------

    private fun getCurrentTime(): String {
        val format = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        return format.format(Date())
    }

    private fun getCurrentDate(): String {
        val format = SimpleDateFormat("EEE dd MMM yyyy", Locale.ENGLISH)
        return format.format(Date())
    }

    private fun getGlassesBatteryLevel(): Int {
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        return batteryPercentFromIntent(batteryIntent)
    }

    private fun registerBatteryStateReceiver() {
        val stickyBatteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        updateBatteryStateFromIntent(stickyBatteryIntent)

        batteryStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateBatteryStateFromIntent(intent)
            }
        }

        appContext.registerReceiver(
            batteryStateReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    private fun updateBatteryStateFromIntent(intent: Intent?) {
        callbacks.onChargingChanged(isChargingFromIntent(intent))

        val batteryPercent = batteryPercentFromIntent(intent)
        if (batteryPercent in 0..100) {
            state = state.copy(glassesBatteryPercent = batteryPercent)
            publishState()
        }
    }

    private fun batteryPercentFromIntent(intent: Intent?): Int {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level >= 0 && scale > 0) {
            level * 100 / scale
        } else {
            -1
        }
    }

    private fun isChargingFromIntent(intent: Intent?): Boolean {
        val status =
            intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged =
            intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0
    }
}