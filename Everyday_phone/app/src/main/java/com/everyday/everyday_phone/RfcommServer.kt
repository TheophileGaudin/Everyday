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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import android.content.ClipboardManager
import android.util.Base64
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * RFCOMM Server that sends trackpad data to the glasses.
 * 
 * Sends delta movements for cursor control, detects tap/double-tap gestures,
 * and handles keyboard input when text fields are focused.
 */
class RfcommServer(private val context: Context) {
    
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
    
    var onStatusChanged: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onTextFieldFocusChanged: ((Boolean) -> Unit)? = null
    var onBatteryChanged: ((Int) -> Unit)? = null
    var onMirrorActionRequested: ((Boolean) -> Unit)? = null
    var onClipboardRequested: (() -> Unit)? = null
    var onGoogleAuthRequested: (() -> Unit)? = null
    var onGoogleCalendarSnapshotRequested: (() -> Unit)? = null
    var onGoogleDisconnectRequested: (() -> Unit)? = null
    var onPayloadSyncRequested: (() -> Unit)? = null
    
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
    private var lastSentLocationPayload: String? = null
    private var lastSentGoogleAuthState: String? = null
    private var lastSentGoogleCalendarSnapshot: String? = null
    
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

    // Note: Location data is managed by LocationWeatherService
    // These methods are kept for backward compatibility with MainActivity
    fun updateLatLon(lat: Double, lon: Double) { /* no-op, LocationWeatherService handles this */ }
    fun updateTownCountry(town: String, country: String) { /* no-op, LocationWeatherService handles this */ }
    fun updateWeather(weather: WeatherData?) { /* no-op, LocationWeatherService handles this */ }



    init {
        instance = this
    }

    fun start(): Boolean {
        if (isRunning.get()) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            onStatusChanged?.invoke("Bluetooth not supported")
            return false
        }

        if (!hasPermissions()) {
            onStatusChanged?.invoke("Missing permissions")
            return false
        }

        if (bluetoothAdapter?.isEnabled != true) {
            onStatusChanged?.invoke("Bluetooth disabled")
            return false
        }

        return startServer()
    }

    fun isConnected(): Boolean = isConnected.get()
    
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
        
        onStatusChanged?.invoke("Stopped")
        onConnectionChanged?.invoke(false)
    }

    private fun resetSentPayloadCache() {
        lastSentLocationPayload = null
        lastSentGoogleAuthState = null
        lastSentGoogleCalendarSnapshot = null
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
        sendEventWithPointerCount("down", 0f, 0f, pointerCount)
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
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            val data = """{"event":"pointercount","pointerCount":$pointerCount}""" + "\n"
            
            fileLog("Sending pointer count event: $pointerCount")
            
            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            fileLog("Failed to send pointer count event: ${e.message}")
            isConnected.set(false)
        }
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
        sendEvent("move", sendDx.toFloat(), sendDy.toFloat())
    }
    
    fun onTouchEnd() {
        // Cancel any pending long-press
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        pendingMoveDx = 0f
        pendingMoveDy = 0f
        
        // Send "up" event so glasses can end any drag operation
        // Include current pointer count so glasses know the gesture state
        sendEventWithPointerCount("up", 0f, 0f, currentPointerCount)
        
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
        fileLog("Clipboard content: ${clipboardText?.take(50) ?: "(empty)"}")
        
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
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val battery = getBatteryLevel()
            
            // Escape special characters in clipboard text
            val escapedClipboard = clipboardText?.let {
                it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
            }
            
            val clipboardPart = if (escapedClipboard != null) ""","clipboard":"$escapedClipboard"""" else ""
            val data = """{"time":"$time","battery":$battery,"event":"longpress"$clipboardPart}""" + "\n"
            
            fileLog("Sending long-press event")
            
            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            fileLog("Failed to send long-press event: ${e.message}")
            isConnected.set(false)
        }
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
            sendEvent("doubletap", 0f, 0f)
        } else {
            // Potential single tap - wait to see if another tap comes
            lastTapTime = currentTime
            pendingTapRunnable = Runnable {
                fileLog("Single tap confirmed")
                sendEvent("tap", 0f, 0f)
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
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val battery = getBatteryLevel()
            
            // Escape special characters in JSON
            val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            val data = """{"time":"$time","battery":$battery,"event":"key","key":"$escapedKey"}""" + "\n"
            
            fileLog("Sending key: $key")
            
            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            fileLog("Failed to send key: ${e.message}")
            isConnected.set(false)
        }
    }
    
    /**
     * Send mirror control message to glasses.
     * @param enabled Whether mirroring should be enabled
     * @param ip The phone's IP address for WiFi streaming (required when enabled)
     */
    fun sendMirrorControl(enabled: Boolean, ip: String? = null, isWifiDirect: Boolean = false) {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val data = if (enabled && ip != null) {
                // Explicitly tell glasses if this is WiFi Direct
                """{"mirror":"on","ip":"$ip","port":${ScreenStreamServer.STREAM_PORT},"wifiDirect":$isWifiDirect}""" + "\n"
            } else {
                """{"mirror":"off"}""" + "\n"
            }

            fileLog("Sending mirror control: $data")

            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            fileLog("Failed to send mirror control: ${e.message}")
        }
    }
    
    /**
     * Send clipboard update to glasses.
     * Called when phone clipboard changes.
     */
    fun sendClipboardUpdate(clipboardText: String) {
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            // Escape special characters in clipboard text
            val escapedClipboard = clipboardText
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            
            val data = """{"event":"clipboard","content":"$escapedClipboard"}""" + "\n"
            
            fileLog("Sending clipboard update (${clipboardText.length} chars)")
            
            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            fileLog("Failed to send clipboard update: ${e.message}")
            isConnected.set(false)
        }
    }

    /**
     * Send location and weather update to glasses.
     * @param timestamp Epoch millis when this data was last updated on the phone
     */
    fun sendLocationUpdate(
        town: String,
        country: String,
        weather: WeatherData?,
        timestamp: Long = System.currentTimeMillis(),
        speedMps: Float? = null
    ) {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            // Construct JSON
            // If weather is null, send only location
            val weatherPart = if (weather != null) {
                val sunrisePart = weather.sunriseEpochMs?.let { ""","sunrise":$it""" } ?: ""
                val sunsetPart = weather.sunsetEpochMs?.let { ""","sunset":$it""" } ?: ""
                ""","weather":{"temp":${weather.tempCurrent},"desc":"${weather.weatherDescCurrent}","min":${weather.tempMinToday},"max":${weather.tempMaxToday},"forecast":"${weather.weatherDescToday}"$sunrisePart$sunsetPart}"""
            } else ""

            val safeTown = town.replace("\"", "\\\"")
            val safeCountry = country.replace("\"", "\\\"")
            val speedPart = speedMps
                ?.takeIf { it.isFinite() }
                ?.coerceAtLeast(0f)
                ?.let { ""","speedMps":${String.format(Locale.ENGLISH, "%.3f", it)}""" }
                ?: ""

            // Include payload timestamp so glasses can determine data freshness
            val data = """{"event":"weather","timestamp":$timestamp$speedPart,"location":{"town":"$safeTown","country":"$safeCountry"}$weatherPart}""" + "\n"

            if (data == lastSentLocationPayload) {
                return
            }

            fileLog("Sending location update (timestamp=$timestamp, speedMps=${speedMps ?: "na"})")

            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
            lastSentLocationPayload = data
        } catch (e: IOException) {
            fileLog("Failed to send location update: ${e.message}")
            isConnected.set(false)
        }
    }

    fun sendGoogleAuthState(
        status: PhoneGoogleAuthState.Status,
        account: PhoneGoogleAccountSummary? = null,
        detail: String? = null
    ) {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val statusValue = when (status) {
                PhoneGoogleAuthState.Status.SIGNED_OUT -> "signed_out"
                PhoneGoogleAuthState.Status.AUTHORIZING -> "authorizing"
                PhoneGoogleAuthState.Status.AUTHORIZED -> "authorized"
                PhoneGoogleAuthState.Status.ERROR -> "error"
            }
            val accountPart = account?.let {
                ""","account":{"email":"${escapeJson(it.email)}","displayName":"${escapeJson(it.displayName ?: "")}"}"""
            } ?: ""
            val detailPart = detail?.takeIf { it.isNotBlank() }?.let {
                ""","detail":"${escapeJson(it)}""""
            } ?: ""
            val data = """{"event":"google_auth_state","status":"$statusValue"$accountPart$detailPart}""" + "\n"

            if (data == lastSentGoogleAuthState) {
                return
            }

            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
            lastSentGoogleAuthState = data
        } catch (e: IOException) {
            fileLog("Failed to send Google auth state: ${e.message}")
            isConnected.set(false)
        }
    }

    fun sendGoogleCalendarSnapshot(snapshot: PhoneGoogleCalendarSnapshot) {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val eventsJson = snapshot.events.joinToString(",") { event ->
                """{"id":"${escapeJson(event.id)}","summary":"${escapeJson(event.summary)}","startIso":"${escapeJson(event.startIso)}","htmlLink":"${escapeJson(event.htmlLink ?: "")}"}"""
            }
            val data = """{"event":"google_calendar_snapshot","fetchedAtMs":${snapshot.fetchedAtMs},"staleAfterMs":${snapshot.staleAfterMs},"account":{"email":"${escapeJson(snapshot.account.email)}","displayName":"${escapeJson(snapshot.account.displayName ?: "")}"},"events":[$eventsJson]}""" + "\n"

            if (data == lastSentGoogleCalendarSnapshot) {
                return
            }

            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
            lastSentGoogleCalendarSnapshot = data
        } catch (e: IOException) {
            fileLog("Failed to send Google calendar snapshot: ${e.message}")
            isConnected.set(false)
        }
    }

    /**
     * Send an image to the glasses.
     * The image is sent as base64-encoded data in chunks to avoid Bluetooth buffer issues.
     * @param imageData The raw image bytes (e.g., from reading a JPEG file)
     * @param fileName The original file name for display purposes
     */
    fun sendImage(imageData: ByteArray, fileName: String) {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            // Encode image as base64
            val base64Data = Base64.encodeToString(imageData, Base64.NO_WRAP)

            // Send image as a single message with base64 data
            // For very large images, we could chunk this, but most phone images after
            // compression should fit in a reasonable message
            val safeFileName = fileName.replace("\"", "\\\"")
            val data = """{"event":"image","fileName":"$safeFileName","data":"$base64Data"}""" + "\n"

            fileLog("Sending image: $fileName (${imageData.size} bytes, ${base64Data.length} base64 chars)")

            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }

            fileLog("Image sent successfully")
        } catch (e: IOException) {
            fileLog("Failed to send image: ${e.message}")
            isConnected.set(false)
        }
    }

    private fun sendEvent(type: String, dx: Float, dy: Float) {
        sendEventWithPointerCount(type, dx, dy, if (type == "move") currentPointerCount else 1)
    }
    
    private fun sendEventWithPointerCount(type: String, dx: Float, dy: Float, pointerCount: Int) {
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            // Always include pointerCount so glasses know the full gesture state
            val pointerCountPart = if (pointerCount > 1) {
                ""","pointerCount":$pointerCount"""
            } else ""

            val statusPart = if (type == "update") {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val battery = getBatteryLevel()
                """"time":"$time","battery":$battery,"""
            } else ""

            val data = """{$statusPart"event":"$type","dx":${dx.toInt()},"dy":${dy.toInt()}$pointerCountPart}""" + "\n"
            
            synchronized(output) {
                output.write(data.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            isConnected.set(false)
        }
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
                onStatusChanged?.invoke("Failed to create server")
                return false
            }
            
            isRunning.set(true)
            
            acceptThread = Thread {
                acceptConnections()
            }.apply {
                name = "RfcommAcceptThread"
                start()
            }
            
            onStatusChanged?.invoke("Waiting for glasses...")
            return true
            
        } catch (e: Exception) {
            onStatusChanged?.invoke("Failed to start server")
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
                    onStatusChanged?.invoke("Glasses connected")
                    onConnectionChanged?.invoke(true)
                }

                // Trigger immediate location/weather update
                triggerImmediateLocationUpdate()

                startPeriodicUpdates()
                receiveMessages(socket)
                
            } catch (e: IOException) {
                if (isRunning.get()) {
                    handler.post { onStatusChanged?.invoke("Connection error") }
                }
                break
            }
        }
    }

    /**
     * Send location data to glasses immediately on connection.
     * Uses LocationWeatherService's cached data if available.
     */
    private fun triggerImmediateLocationUpdate() {
        val service = LocationWeatherService.instance
        if (service != null) {
            val data = service.getCurrentLocationData()
            if (data != null) {
                sendLocationUpdate(data.town, data.countryCode, data.weather, data.timestamp, data.speedMps)
                fileLog("Sent immediate location: ${data.town}, ${data.countryCode}, timestamp=${data.timestamp}")
            } else {
                fileLog("No cached location yet, LocationWeatherService will send when ready")
            }
        } else {
            fileLog("LocationWeatherService not running")
        }
    }

    private fun startPeriodicUpdates() {
        // Heartbeat thread - sends time/battery update every second
        Thread {
            while (isConnected.get() && isRunning.get()) {
                sendEvent("update", 0f, 0f)
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
                    
                    fileLog("Received from glasses: $line")
                    
                    // Parse messages from glasses
                    parseGlassesMessage(line)
                }
            } catch (e: IOException) {
                fileLog("Connection read error: ${e.message}")
            }
            
            isConnected.set(false)
            outputStream = null
            resetSentPayloadCache()
            
            // Reset text field state on disconnect
            if (isTextFieldFocused.getAndSet(false)) {
                handler.post { onTextFieldFocusChanged?.invoke(false) }
            }
            
            handler.post {
                onStatusChanged?.invoke("Disconnected")
                onConnectionChanged?.invoke(false)
                if (isRunning.get()) onStatusChanged?.invoke("Waiting for glasses...")
            }
        }.start()
    }
    
    /**
     * Parse messages received from the glasses.
     * Format: {"textFieldFocused": true/false, "fieldId": "optional_id", "battery": 75}
     */
    private fun parseGlassesMessage(json: String) {
        try {
            // Check for text field focus message
            val focusMatch = Regex(""""textFieldFocused"\s*:\s*(true|false)""").find(json)
            if (focusMatch != null) {
                val focused = focusMatch.groupValues[1] == "true"
                val wasChanged = isTextFieldFocused.getAndSet(focused) != focused
                
                if (wasChanged) {
                    fileLog("Text field focus changed to: $focused")
                    handler.post { onTextFieldFocusChanged?.invoke(focused) }
                }
            }
            
            // Check for battery level
            val batteryMatch = Regex(""""battery"\s*:\s*(\d+)""").find(json)
            if (batteryMatch != null) {
                val batteryPercent = batteryMatch.groupValues[1].toIntOrNull()
                if (batteryPercent != null && batteryPercent in 0..100) {
                    handler.post { onBatteryChanged?.invoke(batteryPercent) }
                }
            }

            // Check for mirror request
            // Format: {"action":"mirrorRequest","start":true/false}
            if (json.contains(""""action":"mirrorRequest"""") || json.contains(""""action" : "mirrorRequest"""")) {
                val startMatch = Regex(""""start"\s*:\s*(true|false)""").find(json)
                if (startMatch != null) {
                    val start = startMatch.groupValues[1] == "true"
                    fileLog("Mirror request received: start=$start")
                    handler.post { onMirrorActionRequested?.invoke(start) }
                }
            }
            
            // Check for clipboard request
            // Format: {"action":"requestClipboard"}
            if (json.contains(""""action":"requestClipboard"""") || json.contains(""""action" : "requestClipboard"""")) {
                fileLog("Clipboard request received from glasses")
                handler.post { onClipboardRequested?.invoke() }
            }

            // Check for weather request (glasses woke up or want fresh data)
            // Format: {"action":"requestWeather"}
            if (json.contains(""""action":"requestWeather"""") || json.contains(""""action" : "requestWeather"""")) {
                fileLog("Weather request received from glasses")
                // Force LocationWeatherService to attempt a fresh API call, then send data
                handler.post {
                    val service = LocationWeatherService.instance
                    if (service != null) {
                        // Always trigger a force update to attempt fresh API data
                        // forceUpdate() will send to glasses once data is ready
                        service.forceUpdate()
                        fileLog("Triggered force update in response to weather request")
                    }
                }
            }

            if (json.contains(""""action":"requestGoogleAuth"""") || json.contains(""""action" : "requestGoogleAuth"""")) {
                fileLog("Google auth request received from glasses")
                handler.post { onGoogleAuthRequested?.invoke() }
            }

            if (json.contains(""""action":"requestGoogleCalendarSnapshot"""") || json.contains(""""action" : "requestGoogleCalendarSnapshot"""")) {
                fileLog("Google calendar snapshot request received from glasses")
                handler.post { onGoogleCalendarSnapshotRequested?.invoke() }
            }

            if (json.contains(""""action":"disconnectGoogleAuth"""") || json.contains(""""action" : "disconnectGoogleAuth"""")) {
                fileLog("Google disconnect request received from glasses")
                handler.post { onGoogleDisconnectRequested?.invoke() }
            }

            if (json.contains(""""action":"requestPayloadSync"""") || json.contains(""""action" : "requestPayloadSync"""")) {
                fileLog("Cached payload sync request received from glasses")
                handler.post { onPayloadSyncRequested?.invoke() }
            }
        } catch (e: Exception) {
            fileLog("Failed to parse glasses message: $json - ${e.message}")
        }
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
    
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
