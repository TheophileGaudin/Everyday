package com.everyday.everyday_phone

import android.util.DisplayMetrics
import com.everyday.shared.transport.TransportMessage

/**
 * Owns how the mirrored screen reaches the glasses. The phone always offers its own Wi-Fi Direct
 * group, a direct link that needs no router: the glasses join it with the credentials sent over
 * Bluetooth. When the phone is also on a regular Wi-Fi network, that address goes along as a
 * fallback. Main thread.
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

    /**
     * Starts the stream server (screen capture must be granted already), then tells the glasses where
     * to connect once the link is ready. [onFailed] runs when there is no way to reach the glasses.
     */
    fun startMirror(metrics: DisplayMetrics, onFailed: () -> Unit) {
        val server = screenStreamServerProvider() ?: return onFailed()
        server.start(metrics)
        mirrorActive = true
        val lanIp = server.getLanIp()
        val wifiDirect = wifiDirectManagerProvider()
        if (wifiDirect == null) {
            useLanOnly(lanIp, onFailed)
            return
        }
        log("Mirror: preparing the Wi-Fi Direct group (LAN fallback: $lanIp)")
        wifiDirect.ensureMirrorGroup { group ->
            if (!mirrorActive) return@ensureMirrorGroup
            if (group == null) {
                useLanOnly(lanIp, onFailed)
                return@ensureMirrorGroup
            }
            sendMirrorState(
                TransportMessage.MirrorState(
                    enabled = true,
                    ip = group.ownerIp,
                    port = ScreenStreamServer.STREAM_PORT,
                    wifiDirect = true,
                    networkName = group.networkName,
                    passphrase = group.passphrase,
                    lanIp = lanIp,
                )
            )
            log("Mirror started over Wi-Fi Direct: ${group.networkName} at ${group.ownerIp}")
        }
    }

    private fun useLanOnly(lanIp: String?, onFailed: () -> Unit) {
        if (lanIp == null) {
            log("Mirror: no Wi-Fi Direct group and no Wi-Fi network")
            onFailed()
            return
        }
        sendMirrorState(TransportMessage.MirrorState(enabled = true, ip = lanIp, port = ScreenStreamServer.STREAM_PORT))
        log("Mirror started over Wi-Fi: $lanIp")
    }

    /** Sends the current mirror state again, for glasses that just reconnected. */
    fun resendMirrorState() {
        if (mirrorActive) lastMirrorState?.let(::sendMirrorState)
    }

    fun stopMirror() {
        mirrorActive = false
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
