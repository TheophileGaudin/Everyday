package com.everyday.everyday_phone

import android.util.DisplayMetrics

/** Owns screen-mirroring route selection and Wi-Fi/Wi-Fi Direct arbitration. */
class TransportManager(
    private val rfcommServerProvider: () -> RfcommServer?,
    private val screenStreamServerProvider: () -> ScreenStreamServer?,
    private val wifiDirectManagerProvider: () -> WifiDirectManager?,
    private val log: (String) -> Unit
) {

    enum class MirrorStartAction {
        REQUEST_PROJECTION,
        WAIT_FOR_WIFI_DIRECT,
        NO_NETWORK
    }

    private enum class MirrorRoute {
        WIFI,
        WIFI_DIRECT
    }

    private var pendingMirrorRoute: MirrorRoute? = null

    fun prepareMirrorStart(): MirrorStartAction {
        val bestIp = screenStreamServerProvider()?.getBestStreamingIp()
        return when {
            bestIp != null -> {
                pendingMirrorRoute = when (bestIp.second) {
                    ScreenStreamServer.ConnectionMode.WIFI_DIRECT -> MirrorRoute.WIFI_DIRECT
                    else -> MirrorRoute.WIFI
                }
                log("Starting mirror with ${bestIp.second}: ${bestIp.first}")
                MirrorStartAction.REQUEST_PROJECTION
            }

            wifiDirectManagerProvider()?.isWifiDirectEnabled == true -> {
                pendingMirrorRoute = MirrorRoute.WIFI_DIRECT
                log("No network IP, creating WiFi Direct group...")
                wifiDirectManagerProvider()?.createGroup()
                MirrorStartAction.WAIT_FOR_WIFI_DIRECT
            }

            else -> {
                pendingMirrorRoute = null
                log("No WiFi or WiFi Direct available")
                MirrorStartAction.NO_NETWORK
            }
        }
    }

    fun onWifiDirectGroupFormed(ownerIp: String, isMirroringEnabled: Boolean): MirrorStartAction? {
        if (pendingMirrorRoute != MirrorRoute.WIFI_DIRECT) {
            return null
        }
        if (!isMirroringEnabled) {
            return MirrorStartAction.REQUEST_PROJECTION
        }
        if (screenStreamServerProvider()?.isRunning() == true) {
            sendMirrorControl(enabled = true, ip = ownerIp, isWifiDirect = true)
        }
        return null
    }

    fun onWifiDirectError() {
        if (pendingMirrorRoute == MirrorRoute.WIFI_DIRECT) {
            pendingMirrorRoute = null
        }
    }

    fun startMirrorAfterPermission(metrics: DisplayMetrics): Boolean {
        val bestIp = screenStreamServerProvider()?.getBestStreamingIp() ?: return false
        pendingMirrorRoute = when (bestIp.second) {
            ScreenStreamServer.ConnectionMode.WIFI_DIRECT -> MirrorRoute.WIFI_DIRECT
            else -> MirrorRoute.WIFI
        }

        screenStreamServerProvider()?.start(metrics)
        val isWifiDirect = pendingMirrorRoute == MirrorRoute.WIFI_DIRECT
        sendMirrorControl(enabled = true, ip = bestIp.first, isWifiDirect = isWifiDirect)
        log("Mirror started, IP: ${bestIp.first}, WiFi Direct: $isWifiDirect")
        return true
    }

    fun stopMirror() {
        pendingMirrorRoute = null
        sendMirrorControl(enabled = false)
        screenStreamServerProvider()?.stop()
    }

    private fun sendMirrorControl(enabled: Boolean, ip: String? = null, isWifiDirect: Boolean = false): Boolean {
        val server = rfcommServerProvider() ?: return false
        if (!server.isConnected()) return false
        server.sendMirrorControl(enabled, ip, isWifiDirect)
        return true
    }
}
