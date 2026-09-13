package com.everyday.everyday_glasses

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
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
}

/**
 * Owns communication with the paired phone.
 *
 * This class owns transport mechanics and connection state. It forwards
 * application behavior to PhoneConnectionCallbacks.
 */
class PhoneConnectionCoordinator(
    context: Context,
    private val callbacks: PhoneConnectionCallbacks
) : SyncMessenger, GlassesRouteTransport, GlassesDestinationSearchTransport {

    companion object {
        private const val TAG = "PhoneConnectionCoordinator"
    }

    // ---------------------------------------------------------------------
    // Owned transport objects and transport-related state
    // ---------------------------------------------------------------------

    // Neither transport needs to retain MainActivity itself.
    private val appContext = context.applicationContext
    private val rfcommClient = RfcommClient(appContext)
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

        rfcommClient.isPaused = false
        return rfcommClient.start()
    }

    /** Releases both owned transports. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true

        rfcommClient.stop()
        wifiDirectClient?.release()
        wifiDirectClient = null
        pendingWifiDirectMirrorIp = null
    }

    // ---------------------------------------------------------------------
    // Commands sent from the glasses to the phone
    // ---------------------------------------------------------------------

    fun sendTextFieldFocus(focused: Boolean, fieldId: String? = null): Boolean =
        rfcommClient.sendTextFieldFocus(focused, fieldId)

    fun sendMirrorRequest(start: Boolean): Boolean =
        rfcommClient.sendMirrorRequest(start)

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
                callbacks.onConnectionStatusChanged(status)
            }

            override fun onConnectionChanged(connected: Boolean) {
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
                callbacks.onPointerReceived(data)
            }

            override fun onKeyReceived(key: String) {
                callbacks.onKeyReceived(key)
            }

            override fun onLongPressWithClipboard(clipboardContent: String?) {
                callbacks.onLongPressWithClipboard(clipboardContent)
            }

            override fun onClipboardReceived(clipboardContent: String) {
                callbacks.onClipboardReceived(clipboardContent)
            }
        }

        rfcommClient.remoteContentListener = object : RemoteContentListener {
            override fun onDeviceStatusReceived(status: TransportMessage.DeviceStatus) {
                status.phoneBatteryPercent?.let { battery ->
                    latestPhoneBatteryPercent = battery
                    callbacks.onPhoneBatteryChanged(battery)
                }
            }

            override fun onImageReceived(imageData: ByteArray, fileName: String) {
                callbacks.onImageReceived(imageData, fileName)
            }

            override fun onOpenBrowserRequested(address: String) {
                callbacks.onOpenBrowserRequested(address)
            }
        }

        rfcommClient.mirrorSessionListener = object : MirrorSessionListener {
            override fun onMirrorStateChanged(
                enabled: Boolean,
                ip: String?,
                isWifiDirect: Boolean
            ) {
                handleMirrorStateChanged(enabled, ip, isWifiDirect)
            }
        }

        rfcommClient.remoteAuthListener = object : RemoteAuthListener {
            override fun onGoogleAuthStateReceived(
                status: GooglePhoneAuthStatus,
                account: GoogleAccountSummary?,
                detail: String?
            ) {
                callbacks.onGoogleAuthStateReceived(status, account, detail)
            }
        }

        rfcommClient.syncMessageListener = object : SyncMessageListener {
            override fun onSyncSnapshotReceived(snapshot: SyncSnapshot) {
                callbacks.onSyncSnapshotReceived(snapshot)
            }

            override fun onSyncErrorReceived(error: SyncError) {
                callbacks.onSyncErrorReceived(error)
            }
        }

        rfcommClient.routeMessageListener = object : RouteMessageListener {
            override fun onRouteResultReceived(result: RouteTransportResult) {
                callbacks.onRouteResultReceived(result)
            }

            override fun onRouteErrorReceived(error: RouteTransportError) {
                callbacks.onRouteErrorReceived(error)
            }
        }

        rfcommClient.destinationSearchMessageListener =
            object : DestinationSearchMessageListener {
                override fun onDestinationSearchResultReceived(
                    result: DestinationSearchResult
                ) {
                    callbacks.onDestinationSearchResultReceived(result)
                }

                override fun onDestinationSearchErrorReceived(
                    error: DestinationSearchError
                ) {
                    callbacks.onDestinationSearchErrorReceived(error)
                }
            }

        rfcommClient.subtitleMessageListener = object : SubtitleMessageListener {
            override fun onSubtitleStatusReceived(status: SubtitleStatus) {
                callbacks.onSubtitleStatusReceived(status)
            }

            override fun onSubtitleTranscriptReceived(transcript: SubtitleTranscript) {
                callbacks.onSubtitleTranscriptReceived(transcript)
            }
        }

        rfcommClient.assistantControlListener = object : AssistantControlListener {
            override fun onAssistantControlReceived(control: AssistantControl) {
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
        Log.d(
            TAG,
            "Mirror state changed: enabled=$enabled, ip=$ip, wifiDirect=$isWifiDirect"
        )

        if (enabled && isWifiDirect) {
            if (wifiDirectClient?.isConnected != true) {
                Log.d(TAG, "Wi-Fi Direct required; starting peer discovery")
                pendingWifiDirectMirrorIp = ip
                wifiDirectClient?.discoverPeers()
                return
            }

            Log.d(TAG, "Wi-Fi Direct already connected; enabling mirroring")
        }

        callbacks.onMirroringChanged(enabled, ip)
    }

    private fun initializeWifiDirect() {
        wifiDirectClient = WifiDirectClient(appContext).apply {
            connectionListener = object : WifiDirectConnectionListener {
                override fun onConnected(groupOwnerIp: String) {
                    Log.d(TAG, "Wi-Fi Direct connected, group owner: $groupOwnerIp")

                    pendingWifiDirectMirrorIp?.let { mirrorIp ->
                        Log.d(TAG, "Enabling pending mirror connection to $mirrorIp")
                        callbacks.onMirroringChanged(true, mirrorIp)
                        pendingWifiDirectMirrorIp = null
                    }
                }

                override fun onDisconnected() {
                    Log.d(TAG, "Wi-Fi Direct disconnected")
                }

                override fun onPeersAvailable(peers: List<WifiP2pDevice>) {
                    Log.d(TAG, "Wi-Fi Direct peers available: ${peers.size}")
                    peers.forEach { device ->
                        Log.d(
                            TAG,
                            "  - ${device.deviceName} (${device.deviceAddress}) " +
                                    "status=${device.status}"
                        )
                    }

                    val availableDevice = peers.firstOrNull {
                        it.status == WifiP2pDevice.AVAILABLE
                    }

                    if (availableDevice != null) {
                        Log.d(TAG, "Auto-connecting to: ${availableDevice.deviceName}")
                        wifiDirectClient?.connectToDevice(availableDevice)
                    } else {
                        peers.firstOrNull {
                            it.status == WifiP2pDevice.CONNECTED
                        }?.let { device ->
                            Log.d(TAG, "Already connected to: ${device.deviceName}")
                        }
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Wi-Fi Direct error: $message")
                }
            }

            initialize()
        }
    }
}