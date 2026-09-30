package com.everyday.everyday_glasses.runtime

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
data class StatusSnapshot(
    val isPhoneConnected: Boolean = false,
    val phoneBatteryPercent: Int = -1,
    val glassesBatteryPercent: Int = -1,
    val time: String = "--:--",
    val date: String = "-- --- ----"
)

internal fun delayUntilNextMinute(nowMs: Long): Long {
    val remainder = Math.floorMod(nowMs, 60_000L)
    return if (remainder == 0L) 60_000L else 60_000L - remainder
}

/** Application reactions required by StatusController. */
interface StatusControllerCallbacks {
    /** Sends a complete presentation snapshot to WidgetContainer. */
    fun onStatusChanged(state: StatusSnapshot)

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
class StatusController(
    context: Context,
    private val callbacks: StatusControllerCallbacks
) {

    companion object {
        private const val TAG = "StatusController"
    }

    // ---------------------------------------------------------------------
    // Owned Android objects and presentation state
    // ---------------------------------------------------------------------

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var state = StatusSnapshot()
    private var batteryStateReceiver: BroadcastReceiver? = null
    private var timeUpdateRunnable: Runnable? = null
    private var lastChargingState: Boolean? = null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.ENGLISH)
    private val dateFormat = SimpleDateFormat("EEE dd MMM yyyy", Locale.ENGLISH)

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
        publishState()

        startTimeUpdates()
    }

    /** Stops all callbacks and unregisters the battery receiver. */
    fun release() {
        if (released) return
        released = true

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
        if (!state.isPhoneConnected) return
        state = state.copy(
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
        updateBatteryStateFromIntent(readStickyBatteryIntent(), publish = false)
        publishState()
    }

    // ---------------------------------------------------------------------
    // Periodic updates
    // ---------------------------------------------------------------------

    private fun startTimeUpdates() {
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                if (released) return

                refreshClock()
                publishState()
                handler.postDelayed(this, delayUntilNextMinute(System.currentTimeMillis()))
            }
        }

        handler.postDelayed(
            timeUpdateRunnable!!,
            delayUntilNextMinute(System.currentTimeMillis())
        )
    }

    private fun refreshClock() {
        state = state.copy(
            time = getCurrentTime(),
            date = getCurrentDate()
        )
    }

    private fun publishState() {
        if (released) return
        callbacks.onStatusChanged(state)
    }

    // ---------------------------------------------------------------------
    // Time and battery sources
    // ---------------------------------------------------------------------

    private fun getCurrentTime(): String {
        return timeFormat.format(Date())
    }

    private fun getCurrentDate(): String {
        return dateFormat.format(Date())
    }

    private fun readStickyBatteryIntent(): Intent? =
        appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

    private fun registerBatteryStateReceiver() {
        updateBatteryStateFromIntent(readStickyBatteryIntent(), publish = false)

        batteryStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateBatteryStateFromIntent(intent, publish = true)
            }
        }

        appContext.registerReceiver(
            batteryStateReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    private fun updateBatteryStateFromIntent(intent: Intent?, publish: Boolean) {
        val charging = isChargingFromIntent(intent)
        if (lastChargingState != charging) {
            lastChargingState = charging
            callbacks.onChargingChanged(charging)
        }

        val batteryPercent = batteryPercentFromIntent(intent)
        if (batteryPercent in 0..100 && batteryPercent != state.glassesBatteryPercent) {
            state = state.copy(glassesBatteryPercent = batteryPercent)
            if (publish) publishState()
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