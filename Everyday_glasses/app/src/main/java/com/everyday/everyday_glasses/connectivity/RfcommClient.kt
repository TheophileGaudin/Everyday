package com.everyday.everyday_glasses.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.everyday.everyday_glasses.auth_permissions.GooglePhoneAuthStatus
import com.everyday.everyday_glasses.auth_permissions.GoogleAccountSummary
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.transport.AssistantControlAction
import com.everyday.shared.transport.SyncMessenger
import com.everyday.shared.transport.AssistantStatus
import com.everyday.shared.transport.DestinationSearchCancellation
import com.everyday.shared.transport.DestinationSearchRequest
import com.everyday.shared.transport.RouteTransportCancellation
import com.everyday.shared.transport.RouteTransportRequest
import com.everyday.shared.transport.TransportDecodeResult
import com.everyday.shared.transport.TransportGoogleAuthStatus
import com.everyday.shared.transport.TransportMessage
import com.everyday.shared.transport.TransportProtocol
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RFCOMM Client that receives cursor events and key presses from the phone.
 * Can also send messages to the phone (e.g., text field focus state).
 * Connection is kept alive during pause to allow instant resuming.
 */
class RfcommClient(
    private val context: Context,
    private val preferredPhoneAddress: String? = null
) : SyncMessenger, GlassesRouteTransport, GlassesDestinationSearchTransport {

    companion object {
        private const val TAG = "RfcommClient"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val BATTERY_UPDATE_INTERVAL_MS = 30000L

        // Throttle RX logs so they are readable in Logcat
        private const val RX_LOG_MIN_INTERVAL_MS = 5000L
    }

    var transportConnectionListener: TransportConnectionListener? = null
    var remoteInputListener: RemoteInputListener? = null
    var remoteContentListener: RemoteContentListener? = null
    var mirrorSessionListener: MirrorSessionListener? = null
    var remoteAuthListener: RemoteAuthListener? = null
    var syncMessageListener: SyncMessageListener? = null
    var routeMessageListener: RouteMessageListener? = null
    var destinationSearchMessageListener: DestinationSearchMessageListener? = null
    var subtitleMessageListener: SubtitleMessageListener? = null
    var assistantControlListener: AssistantControlListener? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    @Volatile
    private var released = false
    private val lifecycleLock = Any()
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RfcommWriter").apply { isDaemon = true }
    }

    // Battery monitoring
    private var batteryUpdateRunnable: Runnable? = null
    private val batteryWritePending = AtomicBoolean(false)


    private val handler = Handler(Looper.getMainLooper())

    fun start(): Boolean {
        if (released) return false
        if (isRunning.get()) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            if (!released) transportConnectionListener?.onStatusChanged("Bluetooth not supported")
            return false
        }

        if (!hasPermissions()) {
            if (!released) transportConnectionListener?.onStatusChanged("Missing permissions")
            return false
        }

        if (bluetoothAdapter?.isEnabled != true) {
            if (!released) transportConnectionListener?.onStatusChanged("Bluetooth disabled")
            return false
        }

        isRunning.set(true)
        connectToPhone()
        return true
    }

    override fun isConnected(): Boolean = isConnected.get()

    /** Stops the connection and publishes the normal disconnection callbacks. */
    fun stop() {
        stopInternal(notify = true)
    }

    /**
     * Permanently releases this client.
     *
     * Release is intentionally silent: an Activity being destroyed must not receive
     * late connection or message callbacks while its views are going away.
     */
    fun release() {
        synchronized(lifecycleLock) {
            if (released) return
            released = true
            // Make the state transition and queue removal atomic with postIfActive(),
            // so a background RX thread cannot enqueue work after this cleanup.
            handler.removeCallbacksAndMessages(null)
        }
        stopInternal(notify = false)
        clearListeners()
        writerExecutor.shutdownNow()
    }

    private fun stopInternal(notify: Boolean) {
        isRunning.set(false)
        isConnected.set(false)
        
        // Stop battery monitoring
        batteryUpdateRunnable?.let { handler.removeCallbacks(it) }
        batteryUpdateRunnable = null

        try { socket?.close() } catch (_: IOException) {}
        flushRxLogOnDisconnect("stop()")
        socket = null
        outputStream = null

        if (notify && !released) {
            transportConnectionListener?.onStatusChanged("Disconnected")
            transportConnectionListener?.onConnectionChanged(false)
        }
    }

    private fun clearListeners() {
        transportConnectionListener = null
        remoteInputListener = null
        remoteContentListener = null
        mirrorSessionListener = null
        remoteAuthListener = null
        syncMessageListener = null
        routeMessageListener = null
        destinationSearchMessageListener = null
        subtitleMessageListener = null
        assistantControlListener = null
    }

    /**
     * Send text field focus state to the phone.
     * When focused, phone shows keyboard; when unfocused, phone hides keyboard.
     */
    fun sendTextFieldFocus(focused: Boolean, fieldId: String? = null): Boolean =
        enqueueMessage(TransportMessage.TextFieldFocus(focused, fieldId), "send text field focus")

    /**
     * Send request to start/stop mirroring.
     */
    fun sendMirrorRequest(start: Boolean): Boolean =
        enqueueMessage(TransportMessage.MirrorRequest(start), "send mirror request")
    
    /**
     * Send request to phone to send its current clipboard content.
     * This is useful when opening paste menu to ensure we have the latest clipboard.
     */
    fun requestClipboard(): Boolean =
        enqueueMessage(TransportMessage.ClipboardRequest, "request clipboard")

    /** Asks the phone to make an HTTP request for the glasses' assistant. */
    fun sendHttpRelayRequest(request: TransportMessage.HttpRelayRequest): Boolean =
        enqueueMessage(request, "relay assistant request")

    override fun sendSyncRequest(request: SyncRequest): Boolean =
        enqueueMessage(TransportMessage.SyncRequestMessage(request), "request sync")

    override fun sendRouteRequest(request: RouteTransportRequest): Boolean =
        enqueueMessage(TransportMessage.RouteRequestMessage(request), "send route request")

    override fun sendRouteCancellation(
        cancellation: RouteTransportCancellation
    ): Boolean = enqueueMessage(TransportMessage.RouteCancellationMessage(cancellation), "cancel route request")

    override fun sendDestinationSearchRequest(
        request: DestinationSearchRequest
    ): Boolean = enqueueMessage(TransportMessage.DestinationSearchRequestMessage(request), "send destination search")

    override fun sendDestinationSearchCancellation(
        cancellation: DestinationSearchCancellation
    ): Boolean = enqueueMessage(TransportMessage.DestinationSearchCancellationMessage(cancellation), "cancel destination search")

    fun sendSubtitleControl(control: SubtitleControl): Boolean =
        enqueueMessage(TransportMessage.SubtitleControlMessage(control), "send subtitle control")

    fun requestGooglePhoneAuthorization(): Boolean =
        enqueueMessage(TransportMessage.GoogleAuthRequest, "request Google auth")

    fun disconnectGooglePhoneAuth(): Boolean =
        enqueueMessage(TransportMessage.GoogleAuthDisconnect, "request Google disconnect")

    fun sendAssistantStatus(status: AssistantStatus): Boolean {
        Log.d(
            TAG,
            "TX assistant.status state=${status.state.wireName} " +
                "session=${status.sessionId ?: "-"} origin=${status.origin?.wireName ?: "-"}"
        )
        return enqueueMessage(TransportMessage.AssistantStatusMessage(status), "send assistant status")
    }

    override fun sendSyncSnapshot(snapshot: SyncSnapshot): Boolean = false
    override fun sendSyncError(error: SyncError): Boolean = false
    
    /**
     * Send battery update to phone.
     */
    private fun sendBatteryUpdate() {
        if (!isConnected.get()) return
        if (!batteryWritePending.compareAndSet(false, true)) return

        val batteryLevel = getGlassesBatteryLevel()
        if (batteryLevel !in 0..100) {
            batteryWritePending.set(false)
            return
        }
        val queued = enqueueMessage(
            TransportMessage.DeviceStatus(glassesBatteryPercent = batteryLevel),
            "send device status"
        ) {
            batteryWritePending.set(false)
        }
        if (!queued) {
            batteryWritePending.set(false)
        }
    }

    /**
     * Serialize socket writes away from the main thread. BluetoothOutputStream.write()
     * can block indefinitely when the peer stops reading, so it must never run on the UI
     * handler that processes touch and layout changes.
     */
    private fun enqueueWrite(
        message: String,
        operation: String,
        onComplete: (() -> Unit)? = null
    ): Boolean {
        if (released || !isConnected.get()) return false
        val output = outputStream ?: return false

        try {
            writerExecutor.execute {
                try {
                    if (released || !isConnected.get() || outputStream !== output) return@execute
                    synchronized(output) {
                        output.write(message.toByteArray())
                        output.flush()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to $operation", e)
                } finally {
                    onComplete?.invoke()
                }
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return true
    }

    private fun enqueueMessage(
        message: TransportMessage,
        operation: String,
        onComplete: (() -> Unit)? = null
    ): Boolean {
        val encoded = TransportProtocol.encode(message) + "\n"
        Log.d(TAG, "TX kind=${message.kind} bytes=${encoded.length}")
        return enqueueWrite(encoded, operation, onComplete)
    }
    
    /**
     * Get the current glasses battery level.
     */
    private fun getGlassesBatteryLevel(): Int {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryIntent?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    (level * 100 / scale)
                } else {
                    -1
                }
            } ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery level", e)
            -1
        }
    }
    
    /**
     * Start periodic battery monitoring and updates to phone.
     */
    private fun startBatteryMonitoring() {
        // Stop any existing monitoring
        batteryUpdateRunnable?.let { handler.removeCallbacks(it) }
        
        // Create new runnable
        batteryUpdateRunnable = object : Runnable {
            override fun run() {
                if (!released && isConnected.get()) {
                    sendBatteryUpdate()
                    postDelayedIfActive(this, BATTERY_UPDATE_INTERVAL_MS)
                }
            }
        }
        
        // Send initial update immediately
        postIfActive { sendBatteryUpdate() }
        
        // Schedule periodic updates
        postDelayedIfActive(batteryUpdateRunnable!!, BATTERY_UPDATE_INTERVAL_MS)
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Suppress warning because we checked hasPermissions() in start() before calling this
    @SuppressLint("MissingPermission")
    private fun connectToPhone() {
        Thread {
            while (!released && isRunning.get() && !isConnected.get()) {
                val phone = findPairedPhone()

                if (phone == null) {
                    postIfActive { transportConnectionListener?.onStatusChanged("No paired phone found") }
                    Thread.sleep(3000)
                    continue
                }

                // Grab name safely here to use in the handler
                val deviceName = phone.name ?: phone.address
                postIfActive { transportConnectionListener?.onStatusChanged("Connecting to $deviceName...") }

                try {
                    // Use insecure socket for better compatibility
                    Log.d(TAG, "Creating RFCOMM socket...")
                    socket = phone.createRfcommSocketToServiceRecord(SPP_UUID)
                    try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}

                    Log.d(TAG, "Attempting to connect...")
                    socket?.connect()
                    Log.d(TAG, "Connect returned, isConnected=${socket?.isConnected}")

                    if (released || !isRunning.get()) {
                        try { socket?.close() } catch (_: IOException) {}
                        socket = null
                        outputStream = null
                        return@Thread
                    }

                    if (socket?.isConnected == true) {
                        isConnected.set(true)
                        outputStream = socket?.outputStream
                        Log.d(TAG, "Successfully connected to phone!")

                        postIfActive {
                            transportConnectionListener?.onStatusChanged("Connected")
                            transportConnectionListener?.onConnectionChanged(true)
                        }

                        // Start battery monitoring
                        startBatteryMonitoring()

                        receiveData()
                    } else {
                        Log.w(TAG, "Socket connect() returned but isConnected is false")
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "Connection failed: ${e.message}")
                    postIfActive { transportConnectionListener?.onStatusChanged("Connection failed, retrying...") }
                    try { socket?.close() } catch (_: IOException) {}
                    socket = null
                    outputStream = null
                    Thread.sleep(2000)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission error: ${e.message}")
                    postIfActive { transportConnectionListener?.onStatusChanged("Permission error") }
                    break
                }
            }
        }.start()
    }

    // Suppress warning because we checked hasPermissions() in start()
    @SuppressLint("MissingPermission")
    private fun findPairedPhone(): BluetoothDevice? {
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: return null

        // Log devices to help debug
        for (device in pairedDevices) {
            Log.d(TAG, "Found paired device: ${device.name} [${device.address}]")
        }

        preferredPhoneAddress?.let { preferredAddress ->
            pairedDevices.firstOrNull { it.address.equals(preferredAddress, ignoreCase = true) }
                ?.let { preferred ->
                    Log.d(TAG, "Selected configured phone: ${preferred.name ?: preferred.address}")
                    return preferred
                }
        }

        // Exclude known non-phone devices. Do not silently choose the first remaining
        // device when several candidates exist: that can connect to a watch, headset,
        // or a second phone by accident.
        val phoneLikeDevices = pairedDevices.filter { device ->
            val name = device.name?.lowercase() ?: ""
            !name.contains("rayneo") && 
            !name.contains("glasses") && 
            !name.contains("watch") && 
            !name.contains("buds") && 
            !name.contains("headphone")
        }

        val phoneClassDevices = phoneLikeDevices.filter { device ->
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
        }
        val candidates = phoneClassDevices.ifEmpty { phoneLikeDevices }
        val selected = candidates.singleOrNull()
        Log.d(TAG, "Selected device: ${selected?.name ?: "none (candidates=${candidates.size})"}")
        return selected
    }

    private fun receiveData() {
        Thread {
            try {
                val stream = socket?.inputStream ?: return@Thread
                val reader = BufferedReader(InputStreamReader(stream))

                while (isConnected.get() && isRunning.get()) {
                    // Keep reading continuously so the socket buffer cannot fill.
                    val line = reader.readLine() ?: break

                    // Log ALL received lines for debugging
                    //Log.d(TAG, "RX: ${line.take(150)}")

                    if (line.length > TransportProtocol.MAX_MESSAGE_CHARS) {
                        Log.w(TAG, "RX rejected oversized message bytes=${line.length}")
                        continue
                    }

                    when (val decoded = TransportProtocol.decode(line)) {
                        is TransportDecodeResult.Success -> {
                            logRxThrottled(decoded.message.kind, line.length)
                            routeMessage(decoded.message)
                        }
                        is TransportDecodeResult.Failure -> Log.w(
                            TAG,
                            "RX rejected kind=${decoded.kind ?: "unknown"} bytes=${line.length}: ${decoded.reason}"
                        )
                    }
                }
            } catch (_: IOException) {
                Log.d(TAG, "Connection lost")
            }

            if (released) return@Thread
            isConnected.set(false)
            outputStream = null
            
            // Stop battery monitoring
            batteryUpdateRunnable?.let { handler.removeCallbacks(it) }
            batteryUpdateRunnable = null


            flushRxLogOnDisconnect("connection_lost")


            postIfActive {
                transportConnectionListener?.onStatusChanged("Disconnected")
                transportConnectionListener?.onConnectionChanged(false)
            }

            if (!released && isRunning.get()) {
                postDelayedIfActive({
                    if (!released && isRunning.get() && !isConnected.get()) {
                        transportConnectionListener?.onStatusChanged("Reconnecting...")
                        connectToPhone()
                    }
                }, 2000)
            }
        }.start()
    }

    private fun routeMessage(message: TransportMessage) {
        when (message) {
            is TransportMessage.Pointer -> postIfActive { remoteInputListener?.onPointerReceived(message) }
            is TransportMessage.KeyInput -> postIfActive { remoteInputListener?.onKeyReceived(message.key) }
            is TransportMessage.LongPress -> postIfActive { remoteInputListener?.onLongPressWithClipboard(message.clipboard) }
            is TransportMessage.ClipboardUpdate -> postIfActive { remoteInputListener?.onClipboardReceived(message.content) }
            is TransportMessage.MirrorState -> postIfActive {
                mirrorSessionListener?.onMirrorStateChanged(message)
            }
            is TransportMessage.OpenBrowser -> postIfActive { remoteContentListener?.onOpenBrowserRequested(message.address) }
            is TransportMessage.GoogleAuthState -> {
                val status = when (message.status) {
                    TransportGoogleAuthStatus.SIGNED_OUT -> GooglePhoneAuthStatus.SIGNED_OUT
                    TransportGoogleAuthStatus.AUTHORIZING -> GooglePhoneAuthStatus.AUTHORIZING
                    TransportGoogleAuthStatus.AUTHORIZED -> GooglePhoneAuthStatus.AUTHORIZED
                    TransportGoogleAuthStatus.ERROR -> GooglePhoneAuthStatus.ERROR
                }
                val account = message.account?.let {
                    GoogleAccountSummary(
                        it.email,
                        it.displayName
                    )
                }
                postIfActive { remoteAuthListener?.onGoogleAuthStateReceived(status, account, message.detail) }
            }
            is TransportMessage.DeviceStatus -> postIfActive { remoteContentListener?.onDeviceStatusReceived(message) }
            is TransportMessage.SyncSnapshotMessage -> postIfActive { syncMessageListener?.onSyncSnapshotReceived(message.snapshot) }
            is TransportMessage.SyncErrorMessage -> postIfActive { syncMessageListener?.onSyncErrorReceived(message.error) }
            // A worker thread waits for this answer: hand it over here, not through the UI thread.
            is TransportMessage.HttpRelayResponse -> if (!released) syncMessageListener?.onHttpRelayResponse(message)
            is TransportMessage.SubtitleStatusMessage -> postIfActive { subtitleMessageListener?.onSubtitleStatusReceived(message.status) }
            is TransportMessage.SubtitleTranscriptMessage -> postIfActive {
                subtitleMessageListener?.onSubtitleTranscriptReceived(message.transcript)
            }
            is TransportMessage.AssistantControlMessage -> {
                if (message.control.action != AssistantControlAction.KEEPALIVE) {
                    Log.d(
                        TAG,
                        "RX assistant.control action=${message.control.action.wireName} " +
                            "session=${message.control.sessionId}"
                    )
                }
                postIfActive { assistantControlListener?.onAssistantControlReceived(message.control) }
            }
            is TransportMessage.AssistantStatusMessage -> Unit
            is TransportMessage.RouteResultMessage -> postIfActive {
                routeMessageListener?.onRouteResultReceived(message.result)
            }
            is TransportMessage.RouteErrorMessage -> postIfActive {
                routeMessageListener?.onRouteErrorReceived(message.error)
            }
            is TransportMessage.DestinationSearchResultMessage -> postIfActive {
                destinationSearchMessageListener?.onDestinationSearchResultReceived(message.result)
            }
            is TransportMessage.DestinationSearchErrorMessage -> postIfActive {
                destinationSearchMessageListener?.onDestinationSearchErrorReceived(message.error)
            }
            is TransportMessage.Image -> {
                val bytes = runCatching { Base64.decode(message.base64Data, Base64.NO_WRAP) }
                    .getOrElse {
                        Log.w(TAG, "RX rejected kind=${message.kind}: invalid base64 image")
                        return
                    }
                postIfActive { remoteContentListener?.onImageReceived(bytes, message.fileName) }
            }
            else -> Log.w(TAG, "RX unexpected kind=${message.kind} for glasses endpoint")
        }
    }

    private fun postIfActive(action: () -> Unit): Boolean {
        synchronized(lifecycleLock) {
            if (released) return false
            return handler.post {
                if (!released) action()
            }
        }
    }

    private fun postDelayedIfActive(action: Runnable, delayMs: Long): Boolean {
        synchronized(lifecycleLock) {
            if (released) return false
            return handler.postDelayed(action, delayMs)
        }
    }


    // --- Throttled RX logging state ---
    private val rxLogLock = Any()
    @Volatile private var lastRxLogAtMs: Long = 0L
    @Volatile private var suppressedRxCount: Int = 0
    @Volatile private var lastRxKind: String = ""
    @Volatile private var lastRxSize: Int = 0
    @Volatile private var lastRxSummary: String = ""

    /**
     * Logs at most once per RX_LOG_MIN_INTERVAL_MS.
     * If more messages arrive, it aggregates them and logs a summary when allowed.
     */
    private fun logRxThrottled(kind: String, size: Int, summary: String = "") {
        val now = SystemClock.elapsedRealtime()

        synchronized(rxLogLock) {
            // Always keep the latest message info
            lastRxKind = kind
            lastRxSize = size
            lastRxSummary = summary

            val elapsed = now - lastRxLogAtMs
            if (elapsed >= RX_LOG_MIN_INTERVAL_MS) {
                Log.d(TAG, "RX kind=$kind bytes=$size suppressed=$suppressedRxCount")
                suppressedRxCount = 0
                lastRxLogAtMs = now
            } else {
                suppressedRxCount += 1
            }
        }
    }

    /** Call this when disconnecting so you donâ€™t lose the last aggregated RX info. */
    private fun flushRxLogOnDisconnect(reason: String) {
        synchronized(rxLogLock) {
            if (lastRxKind.isNotBlank()) {
                Log.d(
                    TAG,
                    "RX closed reason=$reason lastKind=$lastRxKind bytes=$lastRxSize suppressed=$suppressedRxCount"
                )
            }
            suppressedRxCount = 0
        }
    }

}
