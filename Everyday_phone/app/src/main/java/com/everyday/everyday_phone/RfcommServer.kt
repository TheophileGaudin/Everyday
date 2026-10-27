package com.everyday.everyday_phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import android.content.ClipboardManager
import android.util.Base64
import com.everyday.shared.transport.PointerAction
import com.everyday.shared.transport.AssistantControl
import com.everyday.shared.transport.AssistantStatus
import com.everyday.shared.transport.SyncMessenger
import com.everyday.shared.transport.TransportDecodeResult
import com.everyday.shared.transport.TransportGoogleAccount
import com.everyday.shared.transport.TransportGoogleAuthStatus
import com.everyday.shared.transport.TransportMessage
import com.everyday.shared.transport.TransportProtocol
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * RFCOMM Server that sends trackpad data to the glasses.
 * 
 * Sends delta movements for cursor control, detects tap/double-tap gestures,
 * and handles keyboard input when text fields are focused.
 */
class RfcommServer(private val context: Context) : SyncMessenger, PhoneRouteTransport, PhoneDestinationSearchTransport {

    /** Receives RFCOMM connection lifecycle and glasses device-state events. */
    interface DeviceListener {
        fun onServerStatusChanged(status: String) = Unit
        fun onConnectionChanged(connected: Boolean) = Unit
        fun onTextFieldFocusChanged(focused: Boolean) = Unit
        fun onGlassesBatteryChanged(batteryPercent: Int) = Unit
    }

    /** Receives user actions requested by the glasses. */
    interface PhoneActionListener {
        fun onMirrorActionRequested(start: Boolean) = Unit
        fun onClipboardRequested() = Unit
        fun onGoogleAuthRequested() = Unit
        fun onGoogleDisconnectRequested() = Unit
    }

    /** Receives sync and navigation requests decoded from the glasses transport. */
    interface DataRequestListener {
        fun onSyncRequested(request: SyncRequest) = Unit
        fun onRouteRequestReceived(request: com.everyday.shared.transport.RouteTransportRequest) = Unit
        fun onRouteCancellationReceived(cancellation: com.everyday.shared.transport.RouteTransportCancellation) = Unit
        fun onDestinationSearchRequestReceived(request: com.everyday.shared.transport.DestinationSearchRequest) = Unit
        fun onDestinationSearchCancellationReceived(cancellation: com.everyday.shared.transport.DestinationSearchCancellation) = Unit
        /** The glasses have no internet: they ask the phone to make this request (their voice assistant's). */
        fun onHttpRelayRequested(request: TransportMessage.HttpRelayRequest) = Unit
    }

    /** Receives controls for interactive glasses experiences. */
    interface ExperienceControlListener {
        fun onSubtitleControlRequested(control: SubtitleControl) = Unit
        fun onAssistantStatusReceived(status: AssistantStatus) = Unit
    }
    
    companion object {
        private const val TAG = "RfcommServer"

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "EverydayPhone"

        // Tap detection: if total movement < threshold, it's a tap
        private const val TAP_THRESHOLD = 30f

        // Double-tap detection: max time between taps
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L

        // Long-press detection
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val LONG_PRESS_MAX_MOVEMENT = 30f

        var instance: RfcommServer? = null
            private set
    }
    
    var deviceListener: DeviceListener? = null
    var phoneActionListener: PhoneActionListener? = null
    var dataRequestListener: DataRequestListener? = null
    var experienceControlListener: ExperienceControlListener? = null
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    
    private var acceptThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Touch state
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var totalDistance: Float = 0f
    private var currentPointerCount: Int = 1  // Track number of fingers touching
    private var pendingMoveDx: Float = 0f
    private var pendingMoveDy: Float = 0f

    // Avoid resending identical cached payloads over the same RFCOMM stream.
    private var lastSentGoogleAuthState: String? = null
    private var lastSentSyncSnapshot: String? = null
    
    // Double-tap detection
    private var lastTapTime: Long = 0L
    private var pendingTapRunnable: Runnable? = null
    
    // Long-press detection
    private var touchDownTime: Long = 0L
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var longPressRunnable: Runnable? = null
    private var longPressFired: Boolean = false
    
    // Text field focus state
    private var isTextFieldFocused = AtomicBoolean(false)
    
    fun isTextFieldFocused(): Boolean = isTextFieldFocused.get()

    init {
        instance = this
    }

    fun start(): Boolean {
        if (isRunning.get()) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            deviceListener?.onServerStatusChanged("Bluetooth not supported")
            return false
        }

        if (!hasPermissions()) {
            deviceListener?.onServerStatusChanged("Missing permissions")
            return false
        }

        if (bluetoothAdapter?.isEnabled != true) {
            deviceListener?.onServerStatusChanged("Bluetooth disabled")
            return false
        }

        return startServer()
    }

    override fun isConnected(): Boolean = isConnected.get()

    override fun sendSyncRequest(request: SyncRequest): Boolean = false

    override fun sendRouteResult(
        result: com.everyday.shared.transport.RouteTransportResult
    ): Boolean = sendMessage(TransportMessage.RouteResultMessage(result))

    override fun sendRouteError(
        error: com.everyday.shared.transport.RouteTransportError
    ): Boolean = sendMessage(TransportMessage.RouteErrorMessage(error))

    override fun sendDestinationSearchResult(
        result: com.everyday.shared.transport.DestinationSearchResult
    ): Boolean = sendMessage(TransportMessage.DestinationSearchResultMessage(result))

    override fun sendDestinationSearchError(
        error: com.everyday.shared.transport.DestinationSearchError
    ): Boolean = sendMessage(TransportMessage.DestinationSearchErrorMessage(error))
    
    fun stop() {
        isRunning.set(false)
        
        try { clientSocket?.close() } catch (e: IOException) {}
        try { serverSocket?.close() } catch (e: IOException) {}
        
        acceptThread?.interrupt()
        acceptThread = null
        clientSocket = null
        outputStream = null
        isConnected.set(false)
        
        // Cancel any pending tap
        pendingTapRunnable?.let { handler.removeCallbacks(it) }
        pendingTapRunnable = null
        resetSentPayloadCache()
        
        deviceListener?.onServerStatusChanged("Stopped")
        deviceListener?.onConnectionChanged(false)
    }

    private fun resetSentPayloadCache() {
        lastSentGoogleAuthState = null
        lastSentSyncSnapshot = null
    }
    
    fun onTouchStart(x: Float, y: Float, pointerCount: Int = 1) {
        lastX = x
        lastY = y
        totalDistance = 0f
        pendingMoveDx = 0f
        pendingMoveDy = 0f
        touchDownTime = System.currentTimeMillis()
        touchStartX = x
        touchStartY = y
        longPressFired = false
        currentPointerCount = pointerCount
        
        // Cancel any previous long-press
        longPressRunnable?.let { handler.removeCallbacks(it) }
        
        // Schedule long-press detection (only when text field is focused)
        if (isTextFieldFocused.get()) {
            longPressRunnable = Runnable {
                if (!longPressFired) {
                    longPressFired = true
                    handleLongPress()
                }
            }
            handler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT_MS)
        }
        
        // Send "down" event so glasses can initiate drag if cursor is over draggable element
        // Include pointerCount so glasses know if this is a two-finger gesture from the start
        sendEventWithPointerCount(PointerAction.DOWN, 0f, 0f, pointerCount)
    }
    
    fun onPointerCountChanged(pointerCount: Int, x: Float = lastX, y: Float = lastY) {
        val previousCount = currentPointerCount
        currentPointerCount = pointerCount
        lastX = x
        lastY = y
        
        // Send pointer count change to glasses immediately
        if (pointerCount != previousCount) {
            sendPointerCountEvent(pointerCount)
        }
    }
    
    /**
     * Send a pointer count change event to glasses.
     * This allows the glasses to enter/exit scroll mode without waiting for a move event.
     */
    private fun sendPointerCountEvent(pointerCount: Int) {
        sendMessage(TransportMessage.Pointer(PointerAction.POINTER_COUNT, pointerCount = pointerCount))
    }
    
    fun onTouchMove(x: Float, y: Float, pointerCount: Int = 1) {
        val dx = x - lastX
        val dy = y - lastY
        totalDistance += sqrt(dx * dx + dy * dy)
        lastX = x
        lastY = y
        currentPointerCount = pointerCount
        
        // Cancel long-press if finger moved too much
        val distFromStart = sqrt((x - touchStartX) * (x - touchStartX) + (y - touchStartY) * (y - touchStartY))
        if (distFromStart > LONG_PRESS_MAX_MOVEMENT) {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }
        
        // Preserve fractional deltas locally so Bluetooth packets only carry
        // movement that survives the integer protocol encoding.
        pendingMoveDx += dx
        pendingMoveDy += dy

        val sendDx = pendingMoveDx.toInt()
        val sendDy = pendingMoveDy.toInt()
        if (sendDx == 0 && sendDy == 0) {
            return
        }

        pendingMoveDx -= sendDx.toFloat()
        pendingMoveDy -= sendDy.toFloat()

        // Send coalesced delta movement with pointer count
        sendEvent(PointerAction.MOVE, sendDx.toFloat(), sendDy.toFloat())
    }
    
    fun onTouchEnd() {
        // Cancel any pending long-press
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        pendingMoveDx = 0f
        pendingMoveDy = 0f
        
        // Send "up" event so glasses can end any drag operation
        // Include current pointer count so glasses know the gesture state
        sendEventWithPointerCount(PointerAction.UP, 0f, 0f, currentPointerCount)
        
        // Only detect tap if long-press wasn't fired
        if (!longPressFired && totalDistance < TAP_THRESHOLD) {
            handleTapDetected()
        }
        
        // Reset
        totalDistance = 0f
        longPressFired = false
        currentPointerCount = 1
    }
    
    /**
     * Handle long-press: send clipboard content to glasses.
     */
    private fun handleLongPress() {
        fileLog("Long-press detected while text field focused")
        
        // Get clipboard content
        val clipboardText = getClipboardText()
        fileLog("Clipboard available=${clipboardText != null}")
        
        // Send long-press event with clipboard content
        sendLongPressEvent(clipboardText)
    }
    
    /**
     * Get current clipboard text content.
     */
    private fun getClipboardText(): String? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            fileLog("Failed to get clipboard: ${e.message}")
            null
        }
    }

    /**
     * Send a long-press event to the glasses with clipboard content.
     */
    private fun sendLongPressEvent(clipboardText: String?) {
        sendMessage(TransportMessage.LongPress(clipboardText))
    }
    
    /**
     * Handle tap detection with double-tap support.
     */
    private fun handleTapDetected() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTap = currentTime - lastTapTime
        
        // Cancel any pending single tap
        pendingTapRunnable?.let { handler.removeCallbacks(it) }
        pendingTapRunnable = null
        
        if (timeSinceLastTap < DOUBLE_TAP_TIMEOUT_MS) {
            // Double tap detected
            fileLog("Double tap detected")
            lastTapTime = 0L  // Reset to prevent triple-tap being detected as double
            sendEvent(PointerAction.DOUBLE_TAP, 0f, 0f)
        } else {
            // Potential single tap - wait to see if another tap comes
            lastTapTime = currentTime
            pendingTapRunnable = Runnable {
                fileLog("Single tap confirmed")
                sendEvent(PointerAction.TAP, 0f, 0f)
                pendingTapRunnable = null
            }
            handler.postDelayed(pendingTapRunnable!!, DOUBLE_TAP_TIMEOUT_MS)
        }
    }
    
    /**
     * Send a key press event to the glasses.
     * Called when user types on the keyboard.
     */
    fun sendKeyPress(key: String) {
        sendMessage(TransportMessage.KeyInput(key))
    }
    
    /** Tells the glasses where to stream the mirrored screen from, or that mirroring stopped. */
    fun sendMirrorState(state: TransportMessage.MirrorState) {
        if (state.enabled && state.ip.isNullOrBlank()) {
            fileLog("Mirror state not sent: enabled state requires an IP address")
            return
        }
        sendMessage(state)
    }
    
    /**
     * Send clipboard update to glasses.
     * Called when phone clipboard changes.
     */
    fun sendClipboardUpdate(clipboardText: String) {
        sendMessage(TransportMessage.ClipboardUpdate(clipboardText))
    }

    fun sendOpenBrowser(address: String): Boolean {
        return sendMessage(TransportMessage.OpenBrowser(address))
    }

    override fun sendSyncSnapshot(snapshot: SyncSnapshot): Boolean {
        if (snapshot.isEmpty) return false
        val data = TransportProtocol.encode(TransportMessage.SyncSnapshotMessage(snapshot)) + "\n"
        if (data == lastSentSyncSnapshot) return true
        val sent = writeEncoded(TransportMessage.SyncSnapshotMessage(snapshot).kind, data)
        if (sent) lastSentSyncSnapshot = data
        return sent
    }

    override fun sendSyncError(error: SyncError): Boolean =
        sendMessage(TransportMessage.SyncErrorMessage(error))

    override fun sendHttpRelayResponse(response: TransportMessage.HttpRelayResponse): Boolean = sendMessage(response)


    fun sendSubtitleStatus(status: SubtitleStatus) {
        sendMessage(TransportMessage.SubtitleStatusMessage(status))
    }

    fun sendSubtitleTranscript(transcript: SubtitleTranscript) {
        sendMessage(TransportMessage.SubtitleTranscriptMessage(transcript))
    }

    fun sendAssistantControl(control: AssistantControl): Boolean =
        sendMessage(TransportMessage.AssistantControlMessage(control))

    fun sendGoogleAuthState(
        status: PhoneGoogleAuthState.Status,
        account: PhoneGoogleAccountSummary? = null,
        detail: String? = null
    ) {
        val transportStatus = when (status) {
            PhoneGoogleAuthState.Status.SIGNED_OUT -> TransportGoogleAuthStatus.SIGNED_OUT
            PhoneGoogleAuthState.Status.AUTHORIZING -> TransportGoogleAuthStatus.AUTHORIZING
            PhoneGoogleAuthState.Status.AUTHORIZED -> TransportGoogleAuthStatus.AUTHORIZED
            PhoneGoogleAuthState.Status.ERROR -> TransportGoogleAuthStatus.ERROR
        }
        val message = TransportMessage.GoogleAuthState(
            status = transportStatus,
            account = account?.let { TransportGoogleAccount(it.email, it.displayName) },
            detail = detail
        )
        val data = TransportProtocol.encode(message) + "\n"
        if (data == lastSentGoogleAuthState) return
        if (writeEncoded(message.kind, data)) lastSentGoogleAuthState = data
    }

    /**
     * Send an image to the glasses.
     * The image is sent as base64-encoded data in chunks to avoid Bluetooth buffer issues.
     * @param imageData The raw image bytes (e.g., from reading a JPEG file)
     * @param fileName The original file name for display purposes
     */
    fun sendImage(imageData: ByteArray, fileName: String) {
        if (!isConnected.get()) return
        if (outputStream == null) return

        try {
            // Encode image as base64
            val base64Data = Base64.encodeToString(imageData, Base64.NO_WRAP)

            // Send image as a single message with base64 data
            // For very large images, we could chunk this, but most phone images after
            // compression should fit in a reasonable message
            sendMessage(TransportMessage.Image(fileName, base64Data))
        } catch (e: Exception) {
            fileLog("Failed to encode image: ${e.message}")
        }
    }

    private fun sendEvent(action: PointerAction, dx: Float, dy: Float) {
        sendEventWithPointerCount(
            action,
            dx,
            dy,
            if (action == PointerAction.MOVE) currentPointerCount else 1
        )
    }
    
    private fun sendEventWithPointerCount(action: PointerAction, dx: Float, dy: Float, pointerCount: Int) {
        sendMessage(TransportMessage.Pointer(action, dx.toInt(), dy.toInt(), pointerCount))
    }
    
    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startServer(): Boolean {
        try {
            serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            if (serverSocket == null) {
                deviceListener?.onServerStatusChanged("Failed to create server")
                return false
            }
            
            isRunning.set(true)
            
            acceptThread = Thread {
                acceptConnections()
            }.apply {
                name = "RfcommAcceptThread"
                start()
            }
            
            deviceListener?.onServerStatusChanged("Waiting for glasses...")
            return true
            
        } catch (e: Exception) {
            deviceListener?.onServerStatusChanged("Failed to start server")
            return false
        }
    }
    
    private fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val socket = serverSocket?.accept() ?: continue

                clientSocket?.close()
                clientSocket = socket
                outputStream = socket.outputStream
                isConnected.set(true)
                resetSentPayloadCache()

                handler.post {
                    deviceListener?.onServerStatusChanged("Glasses connected")
                    deviceListener?.onConnectionChanged(true)
                }

                startPeriodicUpdates(socket)
                receiveMessages(socket)
                
            } catch (e: IOException) {
                if (isRunning.get()) {
                    handler.post { deviceListener?.onServerStatusChanged("Connection error") }
                }
                break
            }
        }
    }

    private fun startPeriodicUpdates(socket: BluetoothSocket) {
        // Device telemetry has its own cadence and is not interpreted as pointer input.
        Thread {
            while (isConnected.get() && isRunning.get() && clientSocket === socket) {
                sendMessage(
                    TransportMessage.DeviceStatus(
                        time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                        phoneBatteryPercent = getBatteryLevel().takeIf { it in 0..100 }
                    )
                )
                Thread.sleep(1000)
            }
        }.start()
        // Note: Location updates are handled by LocationWeatherService
    }
    
    /**
     * Receive messages from glasses (e.g., text field focus state).
     */
    private fun receiveMessages(socket: BluetoothSocket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                
                while (isConnected.get() && isRunning.get()) {
                    val line = reader.readLine() ?: break

                    if (line.length > TransportProtocol.MAX_MESSAGE_CHARS) {
                        fileLog("RX rejected oversized message bytes=${line.length}")
                        continue
                    }

                    when (val decoded = TransportProtocol.decode(line)) {
                        is TransportDecodeResult.Success -> {
                            fileLog("RX kind=${decoded.message.kind} bytes=${line.length}")
                            routeMessage(decoded.message)
                        }
                        is TransportDecodeResult.Failure -> fileLog(
                            "RX rejected kind=${decoded.kind ?: "unknown"} bytes=${line.length}: ${decoded.reason}"
                        )
                    }
                }
            } catch (e: IOException) {
                fileLog("Connection read error: ${e.message}")
            }
            
            // A replacement connection may already be installed while this reader is unwinding.
            // The old reader must not tear down the new socket or trigger a false route fallback.
            if (clientSocket !== socket) return@Thread
            isConnected.set(false)
            outputStream = null
            resetSentPayloadCache()
            
            // Reset text field state on disconnect
            if (isTextFieldFocused.getAndSet(false)) {
                handler.post { deviceListener?.onTextFieldFocusChanged(false) }
            }
            
            handler.post {
                deviceListener?.onServerStatusChanged("Disconnected")
                deviceListener?.onConnectionChanged(false)
                if (isRunning.get()) deviceListener?.onServerStatusChanged("Waiting for glasses...")
            }
        }.start()
    }
    
    private fun routeMessage(message: TransportMessage) {
        when (message) {
            is TransportMessage.TextFieldFocus -> {
                val changed = isTextFieldFocused.getAndSet(message.focused) != message.focused
                if (changed) handler.post { deviceListener?.onTextFieldFocusChanged(message.focused) }
            }
            is TransportMessage.DeviceStatus -> message.glassesBatteryPercent?.let { battery ->
                handler.post { deviceListener?.onGlassesBatteryChanged(battery) }
            }
            is TransportMessage.MirrorRequest -> handler.post { phoneActionListener?.onMirrorActionRequested(message.start) }
            TransportMessage.ClipboardRequest -> handler.post { phoneActionListener?.onClipboardRequested() }
            TransportMessage.GoogleAuthRequest -> handler.post { phoneActionListener?.onGoogleAuthRequested() }
            TransportMessage.GoogleAuthDisconnect -> handler.post { phoneActionListener?.onGoogleDisconnectRequested() }
            // Sync dispatch stays on the RFCOMM reader thread; refresh work is then
            // handed to the coordinator's bounded executors.
            is TransportMessage.SyncRequestMessage -> dataRequestListener?.onSyncRequested(message.request)
            is TransportMessage.RouteRequestMessage -> dataRequestListener?.onRouteRequestReceived(message.request)
            is TransportMessage.RouteCancellationMessage -> dataRequestListener?.onRouteCancellationReceived(message.cancellation)
            is TransportMessage.DestinationSearchRequestMessage -> dataRequestListener?.onDestinationSearchRequestReceived(message.request)
            is TransportMessage.DestinationSearchCancellationMessage -> dataRequestListener?.onDestinationSearchCancellationReceived(message.cancellation)
            is TransportMessage.SubtitleControlMessage -> handler.post {
                experienceControlListener?.onSubtitleControlRequested(message.control)
            }
            is TransportMessage.AssistantStatusMessage -> handler.post {
                experienceControlListener?.onAssistantStatusReceived(message.status)
            }
            is TransportMessage.HttpRelayRequest -> dataRequestListener?.onHttpRelayRequested(message)
else -> fileLog("RX unexpected kind=${message.kind} for phone endpoint")
        }
    }

private fun sendMessage(message: TransportMessage): Boolean =
        writeEncoded(message.kind, TransportProtocol.encode(message) + "\n")

    private fun writeEncoded(kind: String, encoded: String): Boolean {
        if (!isConnected.get()) return false
        val output = outputStream ?: return false
        return try {
            fileLog("TX kind=$kind bytes=${encoded.length}")
            synchronized(output) {
                output.write(encoded.toByteArray())
                output.flush()
            }
            true
        } catch (error: IOException) {
            fileLog("TX failed kind=$kind: ${error.message}")
            isConnected.set(false)
            false
        }
    }
    
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
