package com.everyday.everyday_glasses.assistant

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.everyday.shared.transport.AssistantSessionState

/** Keeps the glasses network and CPU available for the duration of one voice request. */
class AssistantNetworkLease(context: Context) : AutoCloseable {
    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiLock = wifiManager.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "Everyday_glasses::AssistantWifi"
    ).apply { setReferenceCounted(false) }
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "Everyday_glasses::AssistantCpu"
    ).apply { setReferenceCounted(false) }

    fun update(state: AssistantSessionState, hasActiveSession: Boolean) {
        val active = hasActiveSession &&
            (state == AssistantSessionState.LISTENING || state == AssistantSessionState.PROCESSING)
        if (active) acquire() else release()
    }

    private fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire(MAX_LEASE_MS)
        if (!wifiLock.isHeld) wifiLock.acquire()
        Log.d(TAG, "Assistant network lease acquired wifiEnabled=${wifiManager.isWifiEnabled}")
    }

    private fun release() {
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun close() = release()

    companion object {
        private const val TAG = "AssistantNetwork"
        private const val MAX_LEASE_MS = 2 * 60_000L
    }
}
