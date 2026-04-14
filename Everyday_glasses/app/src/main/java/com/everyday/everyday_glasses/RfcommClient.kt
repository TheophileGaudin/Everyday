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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RFCOMM Client that receives cursor events and key presses from the phone.
 * Can also send messages to the phone (e.g., text field focus state).
 * Connection is kept alive during pause to allow instant resuming.
 */
class RfcommClient(private val context: Context) {

    companion object {
        private const val TAG = "RfcommClient"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val BATTERY_UPDATE_INTERVAL_MS = 30000L

        // Throttle RX logs so they are readable in Logcat
        private const val RX_LOG_MIN_INTERVAL_MS = 5000L
    }

    var onStatusChanged: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onDataReceived: ((PhoneData) -> Unit)? = null
    var onKeyReceived: ((String) -> Unit)? = null
    var onMirrorStateChanged: ((enabled: Boolean, ip: String?, isWifiDirect: Boolean) -> Unit)? = null
    var onLongPressWithClipboard: ((String?) -> Unit)? = null  // Called when phone sends long-press with clipboard
    var onClipboardReceived: ((String) -> Unit)? = null  // Called when phone clipboard changes
    var onWeatherReceived: ((WeatherInfo) -> Unit)? = null // Called when weather data is received
    var onImageReceived: ((ByteArray, String) -> Unit)? = null // Called when image data is received (data, fileName)
    var onGoogleAuthStateReceived: ((GooglePhoneAuthStatus, GoogleAccountSummary?, String?) -> Unit)? = null
    var onGoogleCalendarSnapshotReceived: ((GoogleCalendarSnapshot) -> Unit)? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    // @Volatile ensures the background thread sees changes from the UI thread immediately
    @Volatile
    var isPaused: Boolean = false
    
    // Battery monitoring
    private var batteryUpdateRunnable: Runnable? = null


    private val handler = Handler(Looper.getMainLooper())

    data class PhoneData(
        val time: String,
        val battery: Int,
        val event: String,  // "move", "tap", "update", "key", "longpress", "scroll"
        val dx: Int,
        val dy: Int,
        val key: String? = null,  // For "key" events
        val clipboard: String? = null,  // For "longpress" events
        val pointerCount: Int = 1  // Number of fingers touching (1 = normal, 2+ = scroll mode)
    )

    data class WeatherInfo(
        val town: String,
        val country: String,
        val temp: String,
        val desc: String,
        val min: String,
        val max: String,
        val timestamp: Long,  // When this data was last updated on the phone
        val speedMps: Float? = null,  // Optional phone-reported speed (m/s)
        val sunriseEpochMs: Long? = null,
        val sunsetEpochMs: Long? = null
    )

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

        isRunning.set(true)
        connectToPhone()
        return true
    }

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

        onStatusChanged?.invoke("Disconnected")
        onConnectionChanged?.invoke(false)
    }

    /**
     * Send text field focus state to the phone.
     * When focused, phone shows keyboard; when unfocused, phone hides keyboard.
     */
    fun sendTextFieldFocus(focused: Boolean, fieldId: String? = null) {
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            val batteryLevel = getGlassesBatteryLevel()
            val fieldIdPart = if (fieldId != null) ""","fieldId":"$fieldId"""" else ""
            val message = """{"textFieldFocused":$focused$fieldIdPart,"battery":$batteryLevel}""" + "\n"
            
            Log.d(TAG, "Sending to phone: $message")
            
            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send text field focus", e)
        }
    }

    /**
     * Send request to start/stop mirroring.
     */
    fun sendMirrorRequest(start: Boolean) {
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            val message = """{"action":"mirrorRequest","start":$start}""" + "\n"
            Log.d(TAG, "Sending mirror request: $message")
            
            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send mirror request", e)
        }
    }
    
    /**
     * Send request to phone to send its current clipboard content.
     * This is useful when opening paste menu to ensure we have latest clipboard.
     */
    fun requestClipboard() {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val message = """{"action":"requestClipboard"}""" + "\n"
            Log.d(TAG, "Requesting clipboard from phone")

            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to request clipboard", e)
        }
    }

    /**
     * Send request to phone to send current weather/location data.
     * Called when glasses wake from sleep or want fresh data.
     */
    fun requestWeather() {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val message = """{"action":"requestWeather"}""" + "\n"
            Log.d(TAG, "Requesting weather from phone")

            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to request weather", e)
        }
    }

    fun requestGooglePhoneAuthorization() {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val message = """{"action":"requestGoogleAuth"}""" + "\n"
            Log.d(TAG, "Requesting Google auth from phone")

            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to request Google auth", e)
        }
    }

    fun requestGoogleCalendarSnapshot() {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val message = """{"action":"requestGoogleCalendarSnapshot"}""" + "\n"
            //Log.d(TAG, "Requesting Google calendar snapshot from phone")

            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to request Google calendar snapshot", e)
        }
    }

    fun requestPayloadSync() {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val message = """{"action":"requestPayloadSync"}""" + "\n"

            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to request cached payload sync", e)
        }
    }

    fun disconnectGooglePhoneAuth() {
        if (!isConnected.get()) return
        val output = outputStream ?: return

        try {
            val message = """{"action":"disconnectGoogleAuth"}""" + "\n"
            Log.d(TAG, "Requesting Google disconnect on phone")

            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to request Google disconnect", e)
        }
    }
    
    /**
     * Send battery update to phone.
     */
    private fun sendBatteryUpdate() {
        if (!isConnected.get()) return
        val output = outputStream ?: return
        
        try {
            val batteryLevel = getGlassesBatteryLevel()
            val message = """{"battery":$batteryLevel}""" + "\n"
            
            synchronized(output) {
                output.write(message.toByteArray())
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send battery update", e)
        }
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
                    handler.post { onStatusChanged?.invoke("No paired phone found") }
                    Thread.sleep(3000)
                    continue
                }

                // Grab name safely here to use in the handler
                val deviceName = phone.name ?: phone.address
                handler.post { onStatusChanged?.invoke("Connecting to $deviceName...") }

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
                            onStatusChanged?.invoke("Connected")
                            onConnectionChanged?.invoke(true)
                        }

                        // Start battery monitoring
                        startBatteryMonitoring()

                        receiveData()
                    } else {
                        Log.w(TAG, "Socket connect() returned but isConnected is false")
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "Connection failed: ${e.message}")
                    handler.post { onStatusChanged?.invoke("Connection failed, retrying...") }
                    try { socket?.close() } catch (e2: IOException) {}
                    socket = null
                    outputStream = null
                    Thread.sleep(2000)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission error: ${e.message}")
                    handler.post { onStatusChanged?.invoke("Permission error") }
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

                    // IF PAUSED: Skip processing, but keep the loop alive
                    if (isPaused) {
                        logRxThrottled(kind = "paused_drop", raw = line, summary = "paused=true (dropped)")
                        continue
                    }

                    // Mirror control?
                    if (parseMirrorMessage(line)) {
                        logRxThrottled(kind = "mirror", raw = line, summary = "mirror message parsed")
                        continue
                    }

                    // Clipboard update?
                    if (parseClipboardMessage(line)) {
                        logRxThrottled(kind = "clipboard", raw = line, summary = "clipboard message parsed")
                        continue
                    }

                    // Weather/location update? Parse BEFORE generic parseData to ensure it's handled
                    if (parseWeatherMessage(line)) {
                        continue
                    }

                    if (parseGoogleAuthStateMessage(line)) {
                        continue
                    }

                    if (parseGoogleCalendarSnapshotMessage(line)) {
                        continue
                    }

                    // Image data from phone?
                    if (parseImageMessage(line)) {
                        logRxThrottled(kind = "image", raw = line.take(100), summary = "image message parsed")
                        continue
                    }

                    val data = parseData(line)
                    if (data != null) {
                        val sum = "event=${data.event} dx=${data.dx} dy=${data.dy} batt=${data.battery} time=${data.time}" +
                                (if (data.key != null) " key=\"${data.key.take(30)}\"" else "") +
                                (if (data.pointerCount != 1) " pointers=${data.pointerCount}" else "")

                        logRxThrottled(kind = "data", raw = line, summary = sum)

                        if (data.event == "key" && data.key != null) {
                            handler.post { onKeyReceived?.invoke(data.key) }
                        } else if (data.event == "longpress") {
                            handler.post { onLongPressWithClipboard?.invoke(data.clipboard) }
                        } else {
                            handler.post { onDataReceived?.invoke(data) }
                        }
                    } else {
                        logRxThrottled(kind = "parse_fail", raw = line, summary = "parseData returned null")
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
                onStatusChanged?.invoke("Disconnected")
                onConnectionChanged?.invoke(false)
            }

            if (isRunning.get()) {
                handler.postDelayed({
                    if (isRunning.get() && !isConnected.get()) {
                        onStatusChanged?.invoke("Reconnecting...")
                        connectToPhone()
                    }
                }, 2000)
            }
        }.start()
    }

    private fun parseData(json: String): PhoneData? {
        return try {
            val timeRegex = """"time":"([^"]+)"""".toRegex()
            val batteryRegex = """"battery":(\d+)""".toRegex()
            val eventRegex = """"event":"([^"]+)"""".toRegex()
            val dxRegex = """"dx":(-?\d+)""".toRegex()
            val dyRegex = """"dy":(-?\d+)""".toRegex()
            val keyRegex = """"key":"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
            
            val time = timeRegex.find(json)?.groupValues?.get(1) ?: "?"
            val battery = batteryRegex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val event = eventRegex.find(json)?.groupValues?.get(1) ?: "update"
            val dx = dxRegex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val dy = dyRegex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            // Parse key for key events (handle escaped characters including newlines)
            val key = if (event == "key") {
                keyRegex.find(json)?.groupValues?.get(1)
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?.replace("\\n", "\n")  // Handle escaped newlines
            } else null
            
            // Parse clipboard for longpress events
            val clipboardRegex = """"clipboard":"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
            val clipboard = if (event == "longpress") {
                clipboardRegex.find(json)?.groupValues?.get(1)
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?.replace("\\n", "\n")
                    ?.replace("\\r", "\r")
            } else null
            
            // Parse pointer count for multi-touch scroll
            val pointerCountRegex = """"pointerCount":(\d+)""".toRegex()
            val pointerCount = pointerCountRegex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            PhoneData(time, battery, event, dx, dy, key, clipboard, pointerCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse: $json", e)
            null
        }
    }
    
    /**
     * Parse mirror control messages from phone.
     * Format: {"mirror":"on","ip":"192.168.x.x","port":5050}
     *         {"mirror":"off"}
     * Returns true if message was a mirror control message.
     */
     private fun parseMirrorMessage(json: String): Boolean {
        return try {
            val mirrorRegex = """"mirror":"(on|off)"""".toRegex()
            val ipRegex = """"ip":"([^"]+)"""".toRegex()
            val wifiDirectRegex = """"wifiDirect":(true|false)""".toRegex()

            val mirrorMatch = mirrorRegex.find(json)
            if (mirrorMatch != null) {
                val mirrorState = mirrorMatch.groupValues[1]
                val enabled = mirrorState == "on"

                val ip = if (enabled) {
                    ipRegex.find(json)?.groupValues?.get(1)
                } else null

                // Parse explicit WiFi Direct flag (default false for backwards compatibility)
                val isWifiDirect = wifiDirectRegex.find(json)?.groupValues?.get(1) == "true"

                Log.d(TAG, "Mirror message: enabled=$enabled, ip=$ip, wifiDirect=$isWifiDirect")
                handler.post { onMirrorStateChanged?.invoke(enabled, ip, isWifiDirect) }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing mirror message: $json", e)
            false
        }
     }
    
    /**
     * Parse clipboard update messages from phone.
     * Format: {"event":"clipboard","content":"..."}
     * Returns true if message was a clipboard message.
     */
    private fun parseClipboardMessage(json: String): Boolean {
        return try {
            // Check if this is a clipboard event
            if (!json.contains(""""event":"clipboard"""")) {
                return false
            }
            
            val contentRegex = """"content":"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
            val contentMatch = contentRegex.find(json)
            
            if (contentMatch != null) {
                val content = contentMatch.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                
                Log.d(TAG, "Clipboard received: ${content.take(50)}...")
                handler.post { onClipboardReceived?.invoke(content) }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing clipboard message: $json", e)
            false
        }
    }

    /**
     * Parse weather/location messages from phone.
     * Supports two formats:
     * 1. Full format with weather object: {"event":"weather","location":{...},"weather":{"temp":15,"desc":"sunny.png",...}}
     * 2. Simple format: {"event":"weather","location":{...},"weatherSimple":"15°C"}
     * Returns true if message was a weather message.
     */
    private fun parseWeatherMessage(json: String): Boolean {
        return try {
            // Only handle weather events
            if (!json.contains(""""event":"weather"""") && !json.contains(""""event" : "weather"""")) {
                return false
            }

            //Log.d(TAG, "Parsing weather message: ${json.take(200)}...")

            // Extract location fields
            val town = Regex(""""town"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "?"
            val country = Regex(""""country"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "?"

            // Try to extract full weather object first
            var temp = Regex(""""temp"\s*:\s*([-0-9.]+)""").find(json)?.groupValues?.get(1) ?: ""
            var desc = Regex(""""desc"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
            val min = Regex(""""min"\s*:\s*([-0-9.]+)""").find(json)?.groupValues?.get(1) ?: "?"
            val max = Regex(""""max"\s*:\s*([-0-9.]+)""").find(json)?.groupValues?.get(1) ?: "?"

            // If no full weather object, try simple format (weatherSimple: "15°C")
            if (temp.isEmpty()) {
                val weatherSimple = Regex(""""weatherSimple"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                if (weatherSimple != null) {
                    // Parse temp from simple format like "15°C"
                    val tempMatch = Regex("""(-?\d+)""").find(weatherSimple)
                    temp = tempMatch?.groupValues?.get(1) ?: "?"
                    // No description in simple format
                    desc = ""
                    Log.d(TAG, "Parsed weatherSimple: temp=$temp from '$weatherSimple'")
                }
            }

            // Detect placeholder / junk location coming from phone
            val townIsPlaceholder =
                town.isBlank() || town == "?" || town.equals("Loading...", ignoreCase = true)
            val countryIsPlaceholder =
                country.isBlank() || country == "?"

            // If the phone is sending "Loading..." / empty location, IGNORE this update
            // but return true so we consume the weather event and it won't fall through.
            if (townIsPlaceholder && countryIsPlaceholder) {
                Log.d(TAG, "Ignoring weather update (placeholder location): town='$town' country='$country'")
                return true
            }

            // Extract payload timestamp (when data was last updated on phone)
            val timestamp = Regex(""""timestamp"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val speedMps = Regex(""""speedMps"\s*:\s*([-0-9.]+)""")
                .find(json)
                ?.groupValues
                ?.getOrNull(1)
                ?.toFloatOrNull()
                ?.coerceAtLeast(0f)
            val sunriseEpochMs = Regex(""""sunrise"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
            val sunsetEpochMs = Regex(""""sunset"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

            val weatherInfo = WeatherInfo(
                town = town,
                country = country,
                temp = temp,
                desc = desc,
                min = min,
                max = max,
                timestamp = timestamp,
                speedMps = speedMps,
                sunriseEpochMs = sunriseEpochMs,
                sunsetEpochMs = sunsetEpochMs
            )

            //Log.d(TAG, "Weather parsed: ${weatherInfo.town}, ${weatherInfo.country}, temp=${weatherInfo.temp}, timestamp=$timestamp")
            handler.post { onWeatherReceived?.invoke(weatherInfo) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing weather message: $json", e)
            false
        }
    }

    private fun parseGoogleAuthStateMessage(json: String): Boolean {
        return try {
            if (!json.contains(""""event":"google_auth_state"""")) {
                return false
            }

            val root = JSONObject(json)
            val statusValue = root.optString("status", "error")
            val detail = root.optString("detail").takeIf { it.isNotBlank() }
            val account = root.optJSONObject("account")?.let { accountJson ->
                val email = accountJson.optString("email")
                if (email.isBlank()) null else GoogleAccountSummary(
                    email = email,
                    displayName = accountJson.optString("displayName").takeIf { it.isNotBlank() }
                )
            }
            val status = when (statusValue) {
                "signed_out" -> GooglePhoneAuthStatus.SIGNED_OUT
                "authorizing" -> GooglePhoneAuthStatus.AUTHORIZING
                "authorized" -> GooglePhoneAuthStatus.AUTHORIZED
                else -> GooglePhoneAuthStatus.ERROR
            }
            handler.post { onGoogleAuthStateReceived?.invoke(status, account, detail) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing google auth state message: $json", e)
            false
        }
    }

    private fun parseGoogleCalendarSnapshotMessage(json: String): Boolean {
        return try {
            if (!json.contains(""""event":"google_calendar_snapshot"""")) {
                return false
            }

            val root = JSONObject(json)
            val account = root.optJSONObject("account")?.let { accountJson ->
                val email = accountJson.optString("email")
                if (email.isBlank()) null else GoogleAccountSummary(
                    email = email,
                    displayName = accountJson.optString("displayName").takeIf { it.isNotBlank() }
                )
            }
            val events = mutableListOf<GoogleCalendarEvent>()
            val items = root.optJSONArray("events") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                events += GoogleCalendarEvent(
                    id = item.optString("id"),
                    summary = item.optString("summary"),
                    startIso = item.optString("startIso"),
                    htmlLink = item.optString("htmlLink").takeIf { it.isNotBlank() }
                )
            }
            val snapshot = GoogleCalendarSnapshot(
                account = account,
                events = events,
                fetchedAtMs = root.optLong("fetchedAtMs", 0L),
                staleAfterMs = root.optLong("staleAfterMs", 0L),
                sourceMode = GoogleAuthState.AuthMode.PHONE_FALLBACK
            )
            handler.post { onGoogleCalendarSnapshotReceived?.invoke(snapshot) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing google calendar snapshot message: $json", e)
            false
        }
    }

    /**
     * Parse image messages from phone.
     * Format: {"event":"image","fileName":"...","data":"<base64>"}
     * Returns true if message was an image message.
     */
    private fun parseImageMessage(json: String): Boolean {
        return try {
            // Check if this is an image event
            if (!json.contains(""""event":"image"""")) {
                return false
            }

            Log.d(TAG, "Parsing image message (${json.length} chars)")

            // Extract file name
            val fileNameRegex = """"fileName":"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
            val fileName = fileNameRegex.find(json)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: "image.jpg"

            // Extract base64 data
            val dataRegex = """"data":"([^"]+)"""".toRegex()
            val base64Data = dataRegex.find(json)?.groupValues?.get(1)

            if (base64Data != null) {
                // Decode base64 to bytes
                val imageData = Base64.decode(base64Data, Base64.NO_WRAP)
                Log.d(TAG, "Image received: $fileName (${imageData.size} bytes)")

                handler.post { onImageReceived?.invoke(imageData, fileName) }
                true
            } else {
                Log.w(TAG, "Image message missing data")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing image message", e)
            false
        }
    }


    // --- Throttled RX logging state ---
    private val rxLogLock = Any()
    @Volatile private var lastRxLogAtMs: Long = 0L
    @Volatile private var suppressedRxCount: Int = 0
    @Volatile private var lastRxKind: String = ""
    @Volatile private var lastRxRaw: String = ""
    @Volatile private var lastRxSummary: String = ""

    /**
     * Logs at most once per RX_LOG_MIN_INTERVAL_MS.
     * If more messages arrive, it aggregates them and logs a summary when allowed.
     */
    private fun logRxThrottled(kind: String, raw: String, summary: String = "") {
        val now = android.os.SystemClock.elapsedRealtime()

        synchronized(rxLogLock) {
            // Always keep the latest message info
            lastRxKind = kind
            lastRxRaw = raw
            lastRxSummary = summary

            val elapsed = now - lastRxLogAtMs
            if (elapsed >= RX_LOG_MIN_INTERVAL_MS) {
                suppressedRxCount = 0
                lastRxLogAtMs = now
            } else {
                suppressedRxCount += 1
            }
        }
    }

    // ---- Weather RX tracking (independent from generic RX throttling) ----
    private val weatherLogLock = Any()
    @Volatile private var weatherSeenCount: Int = 0
    @Volatile private var lastWeatherSeenAtMs: Long = 0L
    @Volatile private var lastWeatherLogAtMs: Long = 0L
    private val WEATHER_LOG_MIN_INTERVAL_MS = 5000L

    private fun logWeatherRxThrottled(line: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Extract a few fields for readability (best-effort)
        synchronized(weatherLogLock) {
            weatherSeenCount += 1
            lastWeatherSeenAtMs = now

            if (now - lastWeatherLogAtMs >= WEATHER_LOG_MIN_INTERVAL_MS) {
                lastWeatherLogAtMs = now
            }
        }
    }


    /** Call this when disconnecting so you don’t lose the last aggregated RX info. */
    private fun flushRxLogOnDisconnect(reason: String) {
        synchronized(rxLogLock) {
            suppressedRxCount = 0
        }
    }

}
