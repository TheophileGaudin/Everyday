package com.everyday.everyday_phone

import android.util.DisplayMetrics
import com.everyday.shared.transport.TransportMessage

/**
 * Owns how the mirrored screen reaches the glasses. The phone always offers its own Wi-Fi Direct
 * group, a direct link that needs no router: the glasses join it with the credentials sent over
 * Bluetooth. When the phone is also on a regular Wi-Fi network, that address goes along as a
 * fallback. The same stream is also served over Bluetooth, slower, for when either device's Wi-Fi
 * is off; with the phone's off it is the only way, until its Wi-Fi comes back. Main thread.
 */
class TransportManager(
    private val rfcommServerProvider: () -> RfcommServer?,
    private val screenStreamServerProvider: () -> ScreenStreamServer?,
    private val wifiDirectManagerProvider: () -> WifiDirectManager?,
    private val log: (String) -> Unit
) {

    /** True from [startMirror] until [stopMirror]: a late group callback is ignored after a stop. */
    private var mirrorActive = false

    /** The last mirror state sent, so it can be sent again when the glasses reconnect. */
    private var lastMirrorState: TransportMessage.MirrorState? = null

    /** The mirror runs over Bluetooth only because the phone had no Wi-Fi: retry Wi-Fi Direct when it has. */
    private var bluetoothOnly = false

    /**
     * Starts the stream server (screen capture must be granted already), then tells the glasses where
     * to connect once the link is ready. [onFailed] runs when the stream server is unavailable.
     */
    fun startMirror(metrics: DisplayMetrics, onFailed: () -> Unit) {
        val server = screenStreamServerProvider() ?: return onFailed()
        server.start(metrics)
        mirrorActive = true
        offerWifiDirect(server)
    }

    /** Offers the Wi-Fi Direct group, else the shared Wi-Fi network, else Bluetooth alone. */
    private fun offerWifiDirect(server: ScreenStreamServer) {
        val lanIp = server.getLanIp()
        val wifiDirect = wifiDirectManagerProvider()
        if (wifiDirect == null) {
            useLanOnly(lanIp)
            return
        }
        log("Mirror: preparing the Wi-Fi Direct group (LAN fallback: $lanIp)")
        wifiDirect.ensureMirrorGroup { group ->
            if (!mirrorActive) return@ensureMirrorGroup
            if (group == null) {
                useLanOnly(lanIp)
                return@ensureMirrorGroup
            }
            bluetoothOnly = false
            sendMirrorState(
                TransportMessage.MirrorState(
                    enabled = true,
                    ip = group.ownerIp,
                    port = ScreenStreamServer.STREAM_PORT,
                    wifiDirect = true,
                    networkName = group.networkName,
                    passphrase = group.passphrase,
                    lanIp = lanIp,
                    bluetooth = true,
                )
            )
            log("Mirror started over Wi-Fi Direct: ${group.networkName} at ${group.ownerIp}")
        }
    }

    private fun useLanOnly(lanIp: String?) {
        if (lanIp == null) {
            bluetoothOnly = true
            sendMirrorState(TransportMessage.MirrorState(enabled = true, bluetooth = true))
            log("Mirror started over Bluetooth only: the phone's Wi-Fi is off")
            return
        }
        sendMirrorState(TransportMessage.MirrorState(enabled = true, ip = lanIp, port = ScreenStreamServer.STREAM_PORT, bluetooth = true))
        log("Mirror started over Wi-Fi: $lanIp")
    }

    /** The phone's Wi-Fi came back during a Bluetooth-only mirror: move it to Wi-Fi Direct. */
    fun onWifiDirectStateChanged(enabled: Boolean) {
        if (!enabled || !mirrorActive || !bluetoothOnly) return
        val server = screenStreamServerProvider() ?: return
        log("Mirror: the phone's Wi-Fi is back, offering Wi-Fi Direct")
        offerWifiDirect(server)
    }

    /** Sends the current mirror state again, for glasses that just reconnected. */
    fun resendMirrorState() {
        if (mirrorActive) lastMirrorState?.let(::sendMirrorState)
    }

    fun stopMirror() {
        mirrorActive = false
        bluetoothOnly = false
        lastMirrorState = null
        rfcommServerProvider()?.takeIf { it.isConnected() }?.sendMirrorState(TransportMessage.MirrorState(enabled = false))
        screenStreamServerProvider()?.stop()
        wifiDirectManagerProvider()?.removeMirrorGroup()
    }

    private fun sendMirrorState(state: TransportMessage.MirrorState) {
        lastMirrorState = state
        val server = rfcommServerProvider() ?: return
        if (server.isConnected()) server.sendMirrorState(state)
    }
}
