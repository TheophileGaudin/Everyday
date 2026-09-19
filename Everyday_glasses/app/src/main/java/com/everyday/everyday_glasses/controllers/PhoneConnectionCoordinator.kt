package com.everyday.everyday_glasses.controllers

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import com.everyday.everyday_glasses.bindings.AssistantControlListener
import com.everyday.everyday_glasses.bindings.DestinationSearchMessageListener
import com.everyday.everyday_glasses.google.GoogleAccountSummary
import com.everyday.everyday_glasses.bindings.MirrorSessionListener
import com.everyday.everyday_glasses.bindings.RemoteAuthListener
import com.everyday.everyday_glasses.bindings.RemoteContentListener
import com.everyday.everyday_glasses.bindings.RemoteInputListener
import com.everyday.everyday_glasses.adapters.RfcommClient
import com.everyday.everyday_glasses.bindings.RouteMessageListener
import com.everyday.everyday_glasses.bindings.SubtitleMessageListener
import com.everyday.everyday_glasses.bindings.SyncMessageListener
import com.everyday.everyday_glasses.bindings.TransportConnectionListener
import com.everyday.everyday_glasses.adapters.WifiDirectClient
import com.everyday.everyday_glasses.bindings.WifiDirectConnectionListener
import com.everyday.everyday_glasses.google.GooglePhoneAuthStatus
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.transport.AssistantControl
import com.everyday.shared.transport.AssistantStatus
import com.everyday.shared.transport.DestinationSearchCancellation
import com.everyday.shared.transport.DestinationSearchError
import com.everyday.shared.transport.DestinationSearchRequest
import com.everyday.shared.transport.DestinationSearchResult
import com.everyday.shared.transport.RouteTransportCancellation
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportRequest
import com.everyday.shared.transport.RouteTransportResult
import com.everyday.shared.transport.SyncMessenger
import com.everyday.shared.transport.TransportMessage

/**
 * Application-level reactions to messages arriving from the paired phone.
 *
 * RfcommClient and WifiDirectClient already deliver their callbacks on the main
 * thread. Implementations may therefore update views directly.
 */
interface PhoneConnectionCallbacks {
    fun onConnectionStatusChanged(status: String)
    fun onConnectionChanged(connected: Boolean, phoneBatteryPercent: Int)

    fun onPointerReceived(pointer: TransportMessage.Pointer)
    fun onKeyReceived(key: String)
    fun onLongPressWithClipboard(clipboardContent: String?)
    fun onClipboardReceived(clipboardContent: String)

    fun onImageReceived(imageData: ByteArray, fileName: String)
    fun onOpenBrowserRequested(address: String)
    fun onMirroringChanged(enabled: Boolean, ip: String?)

    fun onGoogleAuthStateReceived(
        status: GooglePhoneAuthStatus,
        account: GoogleAccountSummary?,
        detail: String?
    )

    fun onSyncSnapshotReceived(snapshot: SyncSnapshot)
    fun onSyncErrorReceived(error: SyncError)

    fun onRouteResultReceived(result: RouteTransportResult)
    fun onRouteErrorReceived(error: RouteTransportError)
    fun onDestinationSearchResultReceived(result: DestinationSearchResult)
    fun onDestinationSearchErrorReceived(error: DestinationSearchError)

    fun onSubtitleStatusReceived(status: SubtitleStatus)
    fun onSubtitleTranscriptReceived(transcript: SubtitleTranscript)
    fun onAssistantControlReceived(control: AssistantControl)

    fun onPhoneBatteryChanged(phoneBatteryPercent: Int)

    /** Requests the feature-level Wi-Fi Direct permission from the Activity. */
    fun onWifiDirectPermissionRequired() {}
}

/**
 * Owns communication with the paired phone.
 *
 * This class owns transport mechanics and connection state. It forwards
 * application behavior to PhoneConnectionCallbacks.
 */
class PhoneConnectionCoordinator(
    context: Context,
    private val callbacks: PhoneConnectionCallbacks,
    private val preferredPhoneAddress: String? = null,
    private val preferredWifiDirectDeviceAddress: String? = null
) : SyncMessenger, GlassesRouteTransport, GlassesDestinationSearchTransport {

    companion object {
        private const val TAG = "PhoneConnectionCoordinator"

        /** Selects a P2P peer only when its identity is unambiguous. */
        internal fun selectWifiDirectPeerIndex(
            addresses: List<String>,
            preferredAddress: String?
        ): Int {
            preferredAddress?.let { address ->
                addresses.indexOfFirst { it.equals(address, ignoreCase = true) }
                    .takeIf { it >= 0 }
                    ?.let { return it }
            }
            return if (addresses.size == 1) 0 else -1
        }

        internal fun selectWifiDirectPeer(
            peers: List<WifiP2pDevice>,
            preferredAddress: String?
        ): WifiP2pDevice? {
            return selectWifiDirectPeerIndex(peers.map { it.deviceAddress }, preferredAddress)
                .takeIf { it >= 0 }
                ?.let(peers::get)
        }
    }

    // ---------------------------------------------------------------------
    // Owned transport objects and transport-related state
    // ---------------------------------------------------------------------

    // Neither transport needs to retain MainActivity itself.
    private val appContext = context.applicationContext
    private val rfcommClient = RfcommClient(appContext, preferredPhoneAddress)
    private var wifiDirectClient: WifiDirectClient? = null

    private var pendingWifiDirectMirrorIp: String? = null
    private var latestPhoneBatteryPercent = -1
    private var initialized = false
    private var released = false

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    /** Installs listeners and registers the Wi-Fi Direct receiver. */
    fun initialize() {
        if (initialized || released) return
        initialized = true

        configureRfcommListeners()
        initializeWifiDirect()
    }

    /**
     * Starts the RFCOMM reconnect loop.
     * MainActivity remains responsible for calling this only after permission.
     */
    fun start(): Boolean {
        if (released) return false
        if (!initialized) initialize()

        return rfcommClient.start()
    }

    /** Releases both owned transports. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true

        rfcommClient.release()
        wifiDirectClient?.let { client ->
            // Detach before release because WifiDirectClient can already have queued
            // receiver callbacks on the main handler.
            client.connectionListener = null
            client.release()
        }
        wifiDirectClient = null
        pendingWifiDirectMirrorIp = null
    }

    // ---------------------------------------------------------------------
    // Commands sent from the glasses to the phone
    // ---------------------------------------------------------------------

    fun sendTextFieldFocus(focused: Boolean, fieldId: String? = null): Boolean =
        rfcommClient.sendTextFieldFocus(focused, fieldId)

    fun sendMirrorRequest(start: Boolean): Boolean {
        if (!start) pendingWifiDirectMirrorIp = null
        return if (!released) rfcommClient.sendMirrorRequest(start) else false
    }

    /** Continues a deferred Wi-Fi Direct mirror after PermissionCoordinator grants access. */
    fun onWifiDirectPermissionAvailable() {
        if (released) return
        val mirrorIp = pendingWifiDirectMirrorIp ?: return
        val client = wifiDirectClient ?: return

        if (client.isConnected) {
            pendingWifiDirectMirrorIp = null
            callbacks.onMirroringChanged(true, mirrorIp)
            return
        }

        if (!client.discoverPeers()) {
            pendingWifiDirectMirrorIp = null
            callbacks.onWifiDirectPermissionRequired()
        }
    }

    /** Cancels any deferred mirror when the user declines the permission request. */
    fun onWifiDirectPermissionDenied() {
        if (released) return
        pendingWifiDirectMirrorIp = null
    }

    fun requestClipboard(): Boolean =
        rfcommClient.requestClipboard()

    fun sendSubtitleControl(control: SubtitleControl): Boolean =
        rfcommClient.sendSubtitleControl(control)

    fun requestGooglePhoneAuthorization(): Boolean =
        rfcommClient.requestGooglePhoneAuthorization()

    fun disconnectGooglePhoneAuth(): Boolean =
        rfcommClient.disconnectGooglePhoneAuth()

    fun sendAssistantStatus(status: AssistantStatus): Boolean =
        rfcommClient.sendAssistantStatus(status)

    // ---------------------------------------------------------------------
    // SyncMessenger implementation
    // ---------------------------------------------------------------------

    override fun isConnected(): Boolean =
        rfcommClient.isConnected()

    override fun sendSyncRequest(request: SyncRequest): Boolean =
        rfcommClient.sendSyncRequest(request)

    // The glasses client cannot send these two message types, so RfcommClient
    // currently returns false. Delegate anyway to preserve its exact contract.
    override fun sendSyncSnapshot(snapshot: SyncSnapshot): Boolean =
        rfcommClient.sendSyncSnapshot(snapshot)

    override fun sendSyncError(error: SyncError): Boolean =
        rfcommClient.sendSyncError(error)

    // ---------------------------------------------------------------------
    // GlassesRouteTransport implementation
    // ---------------------------------------------------------------------

    override fun sendRouteRequest(request: RouteTransportRequest): Boolean =
        rfcommClient.sendRouteRequest(request)

    override fun sendRouteCancellation(cancellation: RouteTransportCancellation): Boolean =
        rfcommClient.sendRouteCancellation(cancellation)

    // ---------------------------------------------------------------------
    // GlassesDestinationSearchTransport implementation
    // ---------------------------------------------------------------------

    override fun sendDestinationSearchRequest(request: DestinationSearchRequest): Boolean =
        rfcommClient.sendDestinationSearchRequest(request)

    override fun sendDestinationSearchCancellation(
        cancellation: DestinationSearchCancellation
    ): Boolean = rfcommClient.sendDestinationSearchCancellation(cancellation)

    // ---------------------------------------------------------------------
    // Incoming RFCOMM listener installation
    // ---------------------------------------------------------------------

    private fun configureRfcommListeners() {
        rfcommClient.transportConnectionListener = object : TransportConnectionListener {
            override fun onStatusChanged(status: String) {
                if (released) return
                callbacks.onConnectionStatusChanged(status)
            }

            override fun onConnectionChanged(connected: Boolean) {
                if (released) return
                if (!connected) {
                    latestPhoneBatteryPercent = -1
                }

                callbacks.onConnectionChanged(
                    connected = connected,
                    phoneBatteryPercent = latestPhoneBatteryPercent
                )
            }
        }

        rfcommClient.remoteInputListener = object : RemoteInputListener {
            override fun onPointerReceived(data: TransportMessage.Pointer) {
                if (released) return
                callbacks.onPointerReceived(data)
            }

            override fun onKeyReceived(key: String) {
                if (released) return
                callbacks.onKeyReceived(key)
            }

            override fun onLongPressWithClipboard(clipboardContent: String?) {
                if (released) return
                callbacks.onLongPressWithClipboard(clipboardContent)
            }

            override fun onClipboardReceived(clipboardContent: String) {
                if (released) return
                callbacks.onClipboardReceived(clipboardContent)
            }
        }

        rfcommClient.remoteContentListener = object : RemoteContentListener {
            override fun onDeviceStatusReceived(status: TransportMessage.DeviceStatus) {
                if (released) return
                status.phoneBatteryPercent?.let { battery ->
                    latestPhoneBatteryPercent = battery
                    callbacks.onPhoneBatteryChanged(battery)
                }
            }

            override fun onImageReceived(imageData: ByteArray, fileName: String) {
                if (released) return
                callbacks.onImageReceived(imageData, fileName)
            }

            override fun onOpenBrowserRequested(address: String) {
                if (released) return
                callbacks.onOpenBrowserRequested(address)
            }
        }

        rfcommClient.mirrorSessionListener = object : MirrorSessionListener {
            override fun onMirrorStateChanged(
                enabled: Boolean,
                ip: String?,
                isWifiDirect: Boolean
            ) {
                if (released) return
                handleMirrorStateChanged(enabled, ip, isWifiDirect)
            }
        }

        rfcommClient.remoteAuthListener = object : RemoteAuthListener {
            override fun onGoogleAuthStateReceived(
                status: GooglePhoneAuthStatus,
                account: GoogleAccountSummary?,
                detail: String?
            ) {
                if (released) return
                callbacks.onGoogleAuthStateReceived(status, account, detail)
            }
        }

        rfcommClient.syncMessageListener = object : SyncMessageListener {
            override fun onSyncSnapshotReceived(snapshot: SyncSnapshot) {
                if (released) return
                callbacks.onSyncSnapshotReceived(snapshot)
            }

            override fun onSyncErrorReceived(error: SyncError) {
                if (released) return
                callbacks.onSyncErrorReceived(error)
            }
        }

        rfcommClient.routeMessageListener = object : RouteMessageListener {
            override fun onRouteResultReceived(result: RouteTransportResult) {
                if (released) return
                callbacks.onRouteResultReceived(result)
            }

            override fun onRouteErrorReceived(error: RouteTransportError) {
                if (released) return
                callbacks.onRouteErrorReceived(error)
            }
        }

        rfcommClient.destinationSearchMessageListener =
            object : DestinationSearchMessageListener {
                override fun onDestinationSearchResultReceived(
                    result: DestinationSearchResult
                ) {
                    if (released) return
                    callbacks.onDestinationSearchResultReceived(result)
                }

                override fun onDestinationSearchErrorReceived(
                    error: DestinationSearchError
                ) {
                    if (released) return
                    callbacks.onDestinationSearchErrorReceived(error)
                }
            }

        rfcommClient.subtitleMessageListener = object : SubtitleMessageListener {
            override fun onSubtitleStatusReceived(status: SubtitleStatus) {
                if (released) return
                callbacks.onSubtitleStatusReceived(status)
            }

            override fun onSubtitleTranscriptReceived(transcript: SubtitleTranscript) {
                if (released) return
                callbacks.onSubtitleTranscriptReceived(transcript)
            }
        }

        rfcommClient.assistantControlListener = object : AssistantControlListener {
            override fun onAssistantControlReceived(control: AssistantControl) {
                if (released) return
                callbacks.onAssistantControlReceived(control)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Mirroring and Wi-Fi Direct handoff
    // ---------------------------------------------------------------------

    private fun handleMirrorStateChanged(
        enabled: Boolean,
        ip: String?,
        isWifiDirect: Boolean
    ) {
        if (released) return
        Log.d(
            TAG,
            "Mirror state changed: enabled=$enabled, ip=$ip, wifiDirect=$isWifiDirect"
        )

        // A disabled/non-P2P mirror supersedes any earlier P2P handoff request.
        if (!enabled || !isWifiDirect) {
            pendingWifiDirectMirrorIp = null
        }

        if (enabled && isWifiDirect) {
            if (wifiDirectClient?.isConnected != true) {
                Log.d(TAG, "Wi-Fi Direct required; starting peer discovery")
                pendingWifiDirectMirrorIp = null
                if (ip.isNullOrBlank()) {
                    Log.e(TAG, "Cannot start Wi-Fi Direct mirroring without a mirror IP")
                    return
                }
                pendingWifiDirectMirrorIp = ip
                if (wifiDirectClient == null) {
                    pendingWifiDirectMirrorIp = null
                    Log.e(TAG, "Wi-Fi Direct client is unavailable")
                    return
                }
                val client = wifiDirectClient ?: return
                if (!client.hasRequiredRuntimePermission()) {
                    Log.d(TAG, "Wi-Fi Direct permission required before peer discovery")
                    callbacks.onWifiDirectPermissionRequired()
                    return
                }
                if (!client.discoverPeers()) {
                    pendingWifiDirectMirrorIp = null
                    callbacks.onWifiDirectPermissionRequired()
                }
                return
            }

            Log.d(TAG, "Wi-Fi Direct already connected; enabling mirroring")
            pendingWifiDirectMirrorIp = null
        }

        callbacks.onMirroringChanged(enabled, ip)
    }

    private fun initializeWifiDirect() {
        wifiDirectClient = WifiDirectClient(appContext).apply {
            connectionListener = object : WifiDirectConnectionListener {
                override fun onConnected(groupOwnerIp: String) {
                    if (released) return
                    Log.d(TAG, "Wi-Fi Direct connected, group owner: $groupOwnerIp")

                    pendingWifiDirectMirrorIp?.let { mirrorIp ->
                        Log.d(TAG, "Enabling pending mirror connection to $mirrorIp")
                        callbacks.onMirroringChanged(true, mirrorIp)
                        pendingWifiDirectMirrorIp = null
                    }
                }

                override fun onDisconnected() {
                    if (released) return
                    Log.d(TAG, "Wi-Fi Direct disconnected")
                    pendingWifiDirectMirrorIp = null
                }

                override fun onPeersAvailable(peers: List<WifiP2pDevice>) {
                    if (released) return
                    Log.d(TAG, "Wi-Fi Direct peers available: ${peers.size}")
                    if (pendingWifiDirectMirrorIp == null) {
                        Log.d(TAG, "Ignoring peers because no mirror handoff is pending")
                        return
                    }
                    peers.forEach { device ->
                        Log.d(
                            TAG,
                            "  - ${device.deviceName} (${device.deviceAddress}) " +
                                    "status=${device.status}"
                        )
                    }

                    val connectedDevice = peers.firstOrNull { it.status == WifiP2pDevice.CONNECTED }
                    if (connectedDevice != null) {
                        Log.d(TAG, "Already connected to: ${connectedDevice.deviceName}")
                        return
                    }

                    val availableDevices = peers.filter { it.status == WifiP2pDevice.AVAILABLE }
                    val availableDevice = selectWifiDirectPeer(
                        availableDevices,
                        preferredWifiDirectDeviceAddress
                    )

                    if (availableDevice != null) {
                        Log.d(TAG, "Auto-connecting to: ${availableDevice.deviceName}")
                        wifiDirectClient?.connectToDevice(availableDevice)
                    } else {
                        Log.w(
                            TAG,
                            "No unique Wi-Fi Direct phone peer found " +
                                "(available=${availableDevices.size}, configured=${preferredWifiDirectDeviceAddress != null})"
                        )
                    }
                }

                override fun onError(message: String) {
                    if (released) return
                    Log.e(TAG, "Wi-Fi Direct error: $message")
                    pendingWifiDirectMirrorIp = null
                }
            }

            initialize()
        }
    }

}
