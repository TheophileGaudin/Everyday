package com.everyday.everyday_glasses.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everyday.everyday_glasses.auth_permissions.GoogleAccountSummary
import com.everyday.everyday_glasses.auth_permissions.GooglePhoneAuthStatus
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
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
import com.everyday.shared.transport.HttpRelayPolicy
import com.everyday.shared.transport.TransportMessage
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    preferredPhoneAddress: String? = null,
    private val preferredWifiDirectDeviceAddress: String? = null
) : SyncMessenger, GlassesRouteTransport, GlassesDestinationSearchTransport {

    companion object {
        private const val TAG = "PhoneConnectionCoordinator"

        /** How long the glasses try to join the phone's group before falling back to the shared Wi-Fi network. */
        private const val GROUP_JOIN_TIMEOUT_MS = 15_000L

        /** How long a request made through the phone may take (the phone's own timeouts are shorter). */
        private const val PHONE_INTERNET_TIMEOUT_MS = 120_000L

        /** After the glasses failed to reach a server, their requests go through the phone this long. */
        private const val PREFER_PHONE_INTERNET_MS = 2 * 60_000L

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

    private val handler = Handler(Looper.getMainLooper())

    /** A mirror waiting for the Wi-Fi Direct link (joining, or asking for permission first). */
    private var pendingMirror: TransportMessage.MirrorState? = null

    /** True while the glasses are in a group they joined for mirroring, so they leave it when it stops. */
    private var joinedMirrorGroup = false

    private val groupJoinTimeout = Runnable {
        val mirror = pendingMirror ?: return@Runnable
        pendingMirror = null
        val lanIp = mirror.lanIp
        if (lanIp != null) {
            Log.w(TAG, "Wi-Fi Direct join timed out; streaming over Wi-Fi from $lanIp")
            callbacks.onMirroringChanged(true, lanIp)
        } else {
            Log.w(TAG, "Wi-Fi Direct join timed out and there is no Wi-Fi fallback")
        }
    }
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
        pendingMirror = null
        handler.removeCallbacks(groupJoinTimeout)
        failPendingPhoneRequests("App closing")
    }

    // ---------------------------------------------------------------------
    // Commands sent from the glasses to the phone
    // ---------------------------------------------------------------------

    fun sendTextFieldFocus(focused: Boolean, fieldId: String? = null): Boolean =
        rfcommClient.sendTextFieldFocus(focused, fieldId)

    fun sendMirrorRequest(start: Boolean): Boolean {
        if (!start) cancelPendingMirror()
        return if (!released) rfcommClient.sendMirrorRequest(start) else false
    }

    /** Continues a deferred Wi-Fi Direct mirror after PermissionCoordinator grants access. */
    fun onWifiDirectPermissionAvailable() {
        if (released) return
        val mirror = pendingMirror ?: return
        pendingMirror = null
        handleMirrorStateChanged(mirror)
    }

    /** Falls back to the shared Wi-Fi network, if any, when the user declines the permission request. */
    fun onWifiDirectPermissionDenied() {
        if (released) return
        val lanIp = pendingMirror?.lanIp
        cancelPendingMirror()
        if (lanIp != null) callbacks.onMirroringChanged(true, lanIp)
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
                    failPendingPhoneRequests("Phone disconnected")
                }

                callbacks.onConnectionChanged(
                    connected = connected,
                    phoneBatteryPercent = latestPhoneBatteryPercent
                )
            }
        }

        rfcommClient.remoteInputListener = object : RemoteInputListener {
            override fun onPointerReceived(pointer: TransportMessage.Pointer) {
                if (released) return
                callbacks.onPointerReceived(pointer)
            }

            override fun onKeyReceived(key: String) {
                if (released) return
                callbacks.onKeyReceived(key)
            }

            override fun onLongPressWithClipboard(clipboard: String?) {
                if (released) return
                callbacks.onLongPressWithClipboard(clipboard)
            }

            override fun onClipboardReceived(clipboard: String) {
                if (released) return
                callbacks.onClipboardReceived(clipboard)
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
            override fun onMirrorStateChanged(state: TransportMessage.MirrorState) {
                if (released) return
                handleMirrorStateChanged(state)
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
            override fun onHttpRelayResponse(response: TransportMessage.HttpRelayResponse) {
                pendingPhoneRequests.remove(response.requestId)?.complete(response)
            }

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
    // The assistant's internet: the glasses' own, or the phone's
    // ---------------------------------------------------------------------

    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val pendingPhoneRequests = ConcurrentHashMap<String, CompletableFuture<TransportMessage.HttpRelayResponse>>()
    @Volatile private var preferPhoneInternetUntilMs = 0L

    /**
     * Added to the assistant's HTTP clients so it works wherever the glasses are: requests go out
     * directly when the glasses have internet (Wi-Fi, or RayNeo's link), and through the phone's
     * connection otherwise (4G, Wi-Fi, whatever it has), over Bluetooth. A direct request that
     * couldn't reach the server is sent again through the phone, which is then preferred for a
     * couple of minutes. Only [HttpRelayPolicy]'s hosts are relayed.
     */
    val assistantInternet: Interceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!HttpRelayPolicy.isAllowed(request.url.toString())) return chain.proceed(request)
            val phoneReachable = rfcommClient.isConnected()
            if (phoneReachable && (System.currentTimeMillis() < preferPhoneInternetUntilMs || !glassesHaveInternet())) {
                return throughPhone(request)
            }
            return try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (!phoneReachable || !neverReachedServer(e)) throw e
                Log.w(TAG, "Assistant request couldn't reach ${request.url.host} (${e.javaClass.simpleName}); using the phone's internet")
                preferPhoneInternetUntilMs = System.currentTimeMillis() + PREFER_PHONE_INTERNET_MS
                throughPhone(request)
            }
        }
    }

    private fun glassesHaveInternet(): Boolean {
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Failures that happen before anything is sent, so sending again through the phone can't repeat a request. */
    private fun neverReachedServer(e: IOException): Boolean = e is UnknownHostException || e is ConnectException ||
        e is NoRouteToHostException || (e is SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true))

    /** Makes [request] through the phone and waits for its answer, as if it had been made here. */
    private fun throughPhone(request: Request): Response {
        val body = request.body?.let { requestBody -> Buffer().also(requestBody::writeTo).readByteArray() }
        if (body != null && body.size > HttpRelayPolicy.MAX_BODY_BYTES) throw IOException("Request too large to send through the phone")
        val id = UUID.randomUUID().toString()
        val answer = CompletableFuture<TransportMessage.HttpRelayResponse>()
        pendingPhoneRequests[id] = answer
        val sent = rfcommClient.sendHttpRelayRequest(
            TransportMessage.HttpRelayRequest(
                requestId = id,
                method = request.method,
                url = request.url.toString(),
                headers = request.headers.names().associateWith { request.headers[it].orEmpty() },
                contentType = request.body?.contentType()?.toString(),
                bodyBase64 = body?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) },
            )
        )
        if (!sent) {
            pendingPhoneRequests.remove(id)
            throw IOException("No internet: the glasses are offline and the phone isn't connected")
        }
        val response = try {
            answer.get(PHONE_INTERNET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw SocketTimeoutException("The phone didn't answer in time")
        } catch (e: ExecutionException) {
            throw IOException(e.cause?.message ?: "Request through the phone failed")
        } finally {
            pendingPhoneRequests.remove(id)
        }
        response.error?.let { throw IOException("Through the phone: $it") }
        val bytes = response.bodyBase64?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } ?: ByteArray(0)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(response.code)
            .message("")
            .body(bytes.toResponseBody(response.contentType?.toMediaTypeOrNull()))
            .build()
    }

    private fun failPendingPhoneRequests(reason: String) {
        pendingPhoneRequests.values.forEach { it.completeExceptionally(IOException(reason)) }
        pendingPhoneRequests.clear()
    }

    // ---------------------------------------------------------------------
    // Mirroring and Wi-Fi Direct handoff
    // ---------------------------------------------------------------------

    /**
     * The phone started, moved or stopped its mirror. With its Wi-Fi Direct group's credentials, the
     * glasses join the group and stream from the phone there; if that fails or takes longer than
     * [GROUP_JOIN_TIMEOUT_MS], they stream over the shared Wi-Fi network when the phone gave one.
     * Without credentials (an older phone app), they find the phone by peer discovery as before.
     */
    private fun handleMirrorStateChanged(state: TransportMessage.MirrorState) {
        if (released) return
        Log.d(
            TAG,
            "Mirror state changed: enabled=${state.enabled}, ip=${state.ip}, wifiDirect=${state.wifiDirect}, " +
                "group=${state.networkName}, lan=${state.lanIp}"
        )
        cancelPendingMirror()
        val ip = state.ip
        if (!state.enabled || ip.isNullOrBlank()) {
            leaveMirrorGroup()
            callbacks.onMirroringChanged(false, null)
            return
        }
        if (!state.wifiDirect) {
            callbacks.onMirroringChanged(true, ip)
            return
        }

        val client = wifiDirectClient
        if (client == null) {
            Log.e(TAG, "Wi-Fi Direct client is unavailable")
            state.lanIp?.let { callbacks.onMirroringChanged(true, it) }
            return
        }
        if (client.isConnected && client.groupOwnerAddress == ip) {
            Log.d(TAG, "Already in the phone's group; enabling mirroring")
            callbacks.onMirroringChanged(true, ip)
            return
        }
        pendingMirror = state
        if (!client.hasRequiredRuntimePermission()) {
            Log.d(TAG, "Wi-Fi Direct permission required before joining")
            callbacks.onWifiDirectPermissionRequired()
            return
        }
        val name = state.networkName
        val passphrase = state.passphrase
        val started = if (name != null && passphrase != null) {
            client.joinGroup(name, passphrase).also { if (it) joinedMirrorGroup = true }
        } else {
            Log.d(TAG, "No group credentials; starting peer discovery")
            client.discoverPeers()
        }
        if (!started) {
            pendingMirror = null
            state.lanIp?.let { callbacks.onMirroringChanged(true, it) }
            return
        }
        handler.postDelayed(groupJoinTimeout, GROUP_JOIN_TIMEOUT_MS)
    }

    private fun cancelPendingMirror() {
        pendingMirror = null
        handler.removeCallbacks(groupJoinTimeout)
    }

    /** Leaves the phone's group when the glasses joined it for mirroring. */
    private fun leaveMirrorGroup() {
        if (!joinedMirrorGroup) return
        joinedMirrorGroup = false
        wifiDirectClient?.disconnect()
    }

    /** The Wi-Fi Direct link is up: start the mirror that was waiting for it. */
    private fun onMirrorLinkReady(groupOwnerIp: String) {
        val mirror = pendingMirror ?: return
        cancelPendingMirror()
        val ip = mirror.ip ?: groupOwnerIp
        Log.d(TAG, "Wi-Fi Direct ready; streaming from $ip")
        callbacks.onMirroringChanged(true, ip)
    }

    /** The Wi-Fi Direct link failed: stream over the shared Wi-Fi network if the phone gave one. */
    private fun onMirrorLinkFailed(reason: String) {
        val mirror = pendingMirror ?: return
        cancelPendingMirror()
        val lanIp = mirror.lanIp
        Log.w(TAG, "Wi-Fi Direct failed ($reason); Wi-Fi fallback: $lanIp")
        if (lanIp != null) callbacks.onMirroringChanged(true, lanIp)
    }

    private fun initializeWifiDirect() {
        wifiDirectClient = WifiDirectClient(appContext).apply {
            connectionListener = object : WifiDirectConnectionListener {
                override fun onConnected(groupOwnerIp: String) {
                    if (released) return
                    Log.d(TAG, "Wi-Fi Direct connected, group owner: $groupOwnerIp")
                    onMirrorLinkReady(groupOwnerIp)
                }

                override fun onDisconnected() {
                    if (released) return
                    Log.d(TAG, "Wi-Fi Direct disconnected")
                }

                override fun onPeersAvailable(peers: List<WifiP2pDevice>) {
                    if (released) return
                    Log.d(TAG, "Wi-Fi Direct peers available: ${peers.size}")
                    // Peer discovery is only the fallback for a phone app that sends no group credentials.
                    val mirror = pendingMirror ?: return
                    if (mirror.networkName != null) return
                    if (peers.any { it.status == WifiP2pDevice.CONNECTED }) return
                    val availableDevices = peers.filter { it.status == WifiP2pDevice.AVAILABLE }
                    val availableDevice = selectWifiDirectPeer(availableDevices, preferredWifiDirectDeviceAddress)
                    if (availableDevice != null) {
                        Log.d(TAG, "Auto-connecting to: ${availableDevice.deviceName}")
                        wifiDirectClient?.connectToDevice(availableDevice)
                    } else {
                        Log.w(TAG, "No unique Wi-Fi Direct phone peer found (available=${availableDevices.size})")
                    }
                }

                override fun onError(message: String) {
                    if (released) return
                    Log.e(TAG, "Wi-Fi Direct error: $message")
                    onMirrorLinkFailed(message)
                }
            }

            initialize()
        }
    }

}

interface GlassesRouteTransport {
    fun isConnected(): Boolean
    fun sendRouteRequest(request: RouteTransportRequest): Boolean
    fun sendRouteCancellation(cancellation: RouteTransportCancellation): Boolean
}

interface GlassesDestinationSearchTransport {
    fun isConnected(): Boolean
    fun sendDestinationSearchRequest(request: DestinationSearchRequest): Boolean
    fun sendDestinationSearchCancellation(cancellation: DestinationSearchCancellation): Boolean
}