package com.everyday.everyday_glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.transport.SyncMessenger
import com.everyday.shared.transport.AssistantControl
import com.everyday.shared.transport.AssistantStatus
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RFCOMM Client that receives cursor events and key presses from the phone.
 * Can also send messages to the phone (e.g., text field focus state).
 * Connection is kept alive during pause to allow instant resuming.
 */
class RfcommClient(private val context: Context) : SyncMessenger, GlassesRouteTransport, GlassesDestinationSearchTransport {

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
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RfcommWriter").apply { isDaemon = true }
    }

    // @Volatile ensures the background thread sees changes from the UI thread immediately
    @Volatile
    var isPaused: Boolean = false
    
    // Battery monitoring
    private var batteryUpdateRunnable: Runnable? = null
    private val batteryWritePending = AtomicBoolean(false)


    private val handler = Handler(Looper.getMainLooper())

    fun start(): Boolean {
        if (isRunning.get()) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            transportConnectionListener?.onStatusChanged("Bluetooth not supported")
            return false
        }

        if (!hasPermissions()) {
            transportConnectionListener?.onStatusChanged("Missing permissions")
            return false
        }

        if (bluetoothAdapter?.isEnabled != true) {
            transportConnectionListener?.onStatusChanged("Bluetooth disabled")
            return false
        }

        isRunning.set(true)
        connectToPhone()
        return true
    }

    override fun isConnected(): Boolean = isConnected.get()

    fun stop() {
        isRunning.set(false)
        isConnected.set(false)
        
        // Stop battery monitoring
        batteryUpdateRunnable?.let { handler.removeCallbacks(it) }
        batteryUpdateRunnable = null

        try { socket?.close() } catch (e: IOException) {}
        flushRxLogOnDisconnect("stop()")
        socket = null
        outputStream = null

        transportConnectionListener?.onStatusChanged("Disconnected")
        transportConnectionListener?.onConnectionChanged(false)
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
     * This is useful when opening paste menu to ensure we have latest clipboard.
     */
    fun requestClipboard(): Boolean =
        enqueueMessage(TransportMessage.ClipboardRequest, "request clipboard")

    override fun sendSyncRequest(request: SyncRequest): Boolean =
        enqueueMessage(TransportMessage.SyncRequestMessage(request), "request sync")

    override fun sendRouteRequest(request: com.everyday.shared.transport.RouteTransportRequest): Boolean =
        enqueueMessage(TransportMessage.RouteRequestMessage(request), "send route request")

    override fun sendRouteCancellation(
        cancellation: com.everyday.shared.transport.RouteTransportCancellation
    ): Boolean = enqueueMessage(TransportMessage.RouteCancellationMessage(cancellation), "cancel route request")

    override fun sendDestinationSearchRequest(
        request: com.everyday.shared.transport.DestinationSearchRequest
    ): Boolean = enqueueMessage(TransportMessage.DestinationSearchRequestMessage(request), "send destination search")

    override fun sendDestinationSearchCancellation(
        cancellation: com.everyday.shared.transport.DestinationSearchCancellation
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
        if (!isConnected.get()) return false
        val output = outputStream ?: return false

        writerExecutor.execute {
            try {
                if (!isConnected.get() || outputStream !== output) return@execute
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
                if (isConnected.get() && !isPaused) {
                    sendBatteryUpdate()
                    handler.postDelayed(this, BATTERY_UPDATE_INTERVAL_MS)
                }
            }
        }
        
        // Send initial update immediately
        handler.post { sendBatteryUpdate() }
        
        // Schedule periodic updates
        handler.postDelayed(batteryUpdateRunnable!!, BATTERY_UPDATE_INTERVAL_MS)
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
            while (isRunning.get() && !isConnected.get()) {
                val phone = findPairedPhone()

                if (phone == null) {
                    handler.post { transportConnectionListener?.onStatusChanged("No paired phone found") }
                    Thread.sleep(3000)
                    continue
                }

                // Grab name safely here to use in the handler
                val deviceName = phone.name ?: phone.address
                handler.post { transportConnectionListener?.onStatusChanged("Connecting to $deviceName...") }

                try {
                    // Use insecure socket for better compatibility
                    Log.d(TAG, "Creating RFCOMM socket...")
                    socket = phone.createRfcommSocketToServiceRecord(SPP_UUID)
                    try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) {}

                    Log.d(TAG, "Attempting to connect...")
                    socket?.connect()
                    Log.d(TAG, "Connect returned, isConnected=${socket?.isConnected}")

                    if (socket?.isConnected == true) {
                        isConnected.set(true)
                        outputStream = socket?.outputStream
                        Log.d(TAG, "Successfully connected to phone!")

                        handler.post {
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
                    handler.post { transportConnectionListener?.onStatusChanged("Connection failed, retrying...") }
                    try { socket?.close() } catch (e2: IOException) {}
                    socket = null
                    outputStream = null
                    Thread.sleep(2000)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission error: ${e.message}")
                    handler.post { transportConnectionListener?.onStatusChanged("Permission error") }
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

        // Look for a phone-like device (not glasses or watches)
        // Prioritize devices that look like phones
        val likelyPhone = pairedDevices.find { device ->
            val name = device.name?.lowercase() ?: ""
            // Exclude known non-phone devices
            !name.contains("rayneo") && 
            !name.contains("glasses") && 
            !name.contains("watch") && 
            !name.contains("buds") && 
            !name.contains("headphone")
        }
        
        Log.d(TAG, "Selected device: ${likelyPhone?.name ?: "none found"}")
        return likelyPhone ?: pairedDevices.firstOrNull()
    }

    private fun receiveData() {
        Thread {
            try {
                val stream = socket?.inputStream ?: return@Thread
                val reader = BufferedReader(InputStreamReader(stream))

                while (isConnected.get() && isRunning.get()) {
                    // We read the line regardless of pause state to clear the buffer
                    val line = reader.readLine() ?: break

                    // Log ALL received lines for debugging
                    //Log.d(TAG, "RX: ${line.take(150)}")

                    if (line.length > TransportProtocol.MAX_MESSAGE_CHARS) {
                        Log.w(TAG, "RX rejected oversized message bytes=${line.length}")
                        continue
                    }

                    // IF PAUSED: Skip processing, but keep the loop alive
                    if (isPaused) {
                        logRxThrottled(kind = "paused_drop", size = line.length, summary = "paused=true (dropped)")
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
            } catch (e: IOException) {
                Log.d(TAG, "Connection lost")
            }

            isConnected.set(false)
            outputStream = null
            
            // Stop battery monitoring
            batteryUpdateRunnable?.let { handler.removeCallbacks(it) }
            batteryUpdateRunnable = null


            flushRxLogOnDisconnect("connection_lost")


            handler.post {
                transportConnectionListener?.onStatusChanged("Disconnected")
                transportConnectionListener?.onConnectionChanged(false)
            }

            if (isRunning.get()) {
                handler.postDelayed({
                    if (isRunning.get() && !isConnected.get()) {
                        transportConnectionListener?.onStatusChanged("Reconnecting...")
                        connectToPhone()
                    }
                }, 2000)
            }
        }.start()
    }

    private fun routeMessage(message: TransportMessage) {
        when (message) {
            is TransportMessage.Pointer -> handler.post { remoteInputListener?.onPointerReceived(message) }
            is TransportMessage.KeyInput -> handler.post { remoteInputListener?.onKeyReceived(message.key) }
            is TransportMessage.LongPress -> handler.post { remoteInputListener?.onLongPressWithClipboard(message.clipboard) }
            is TransportMessage.ClipboardUpdate -> handler.post { remoteInputListener?.onClipboardReceived(message.content) }
            is TransportMessage.MirrorState -> handler.post {
                mirrorSessionListener?.onMirrorStateChanged(message.enabled, message.ip, message.wifiDirect)
            }
            is TransportMessage.OpenBrowser -> handler.post { remoteContentListener?.onOpenBrowserRequested(message.address) }
            is TransportMessage.GoogleAuthState -> {
                val status = when (message.status) {
                    TransportGoogleAuthStatus.SIGNED_OUT -> GooglePhoneAuthStatus.SIGNED_OUT
                    TransportGoogleAuthStatus.AUTHORIZING -> GooglePhoneAuthStatus.AUTHORIZING
                    TransportGoogleAuthStatus.AUTHORIZED -> GooglePhoneAuthStatus.AUTHORIZED
                    TransportGoogleAuthStatus.ERROR -> GooglePhoneAuthStatus.ERROR
                }
                val account = message.account?.let { GoogleAccountSummary(it.email, it.displayName) }
                handler.post { remoteAuthListener?.onGoogleAuthStateReceived(status, account, message.detail) }
            }
            is TransportMessage.DeviceStatus -> handler.post { remoteContentListener?.onDeviceStatusReceived(message) }
            is TransportMessage.SyncSnapshotMessage -> handler.post { syncMessageListener?.onSyncSnapshotReceived(message.snapshot) }
            is TransportMessage.SyncErrorMessage -> handler.post { syncMessageListener?.onSyncErrorReceived(message.error) }
            is TransportMessage.SubtitleStatusMessage -> handler.post { subtitleMessageListener?.onSubtitleStatusReceived(message.status) }
            is TransportMessage.SubtitleTranscriptMessage -> handler.post {
                subtitleMessageListener?.onSubtitleTranscriptReceived(message.transcript)
            }
            is TransportMessage.AssistantControlMessage -> {
                if (message.control.action != com.everyday.shared.transport.AssistantControlAction.KEEPALIVE) {
                    Log.d(
                        TAG,
                        "RX assistant.control action=${message.control.action.wireName} " +
                            "session=${message.control.sessionId}"
                    )
                }
                handler.post { assistantControlListener?.onAssistantControlReceived(message.control) }
            }
            is TransportMessage.AssistantStatusMessage -> Unit
            is TransportMessage.RouteResultMessage -> handler.post {
                routeMessageListener?.onRouteResultReceived(message.result)
            }
            is TransportMessage.RouteErrorMessage -> handler.post {
                routeMessageListener?.onRouteErrorReceived(message.error)
            }
            is TransportMessage.DestinationSearchResultMessage -> handler.post {
                destinationSearchMessageListener?.onDestinationSearchResultReceived(message.result)
            }
            is TransportMessage.DestinationSearchErrorMessage -> handler.post {
                destinationSearchMessageListener?.onDestinationSearchErrorReceived(message.error)
            }
            is TransportMessage.Image -> {
                val bytes = runCatching { Base64.decode(message.base64Data, Base64.NO_WRAP) }
                    .getOrElse {
                        Log.w(TAG, "RX rejected kind=${message.kind}: invalid base64 image")
                        return
                    }
                handler.post { remoteContentListener?.onImageReceived(bytes, message.fileName) }
            }
            else -> Log.w(TAG, "RX unexpected kind=${message.kind} for glasses endpoint")
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
        val now = android.os.SystemClock.elapsedRealtime()

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
