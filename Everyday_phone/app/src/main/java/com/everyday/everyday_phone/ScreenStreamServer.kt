package com.everyday.everyday_phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams the phone screen over TCP using H.264 encoding.
 * 
 * Protocol:
 * - Server listens on port 5050
 * - Each frame sent as: [4-byte length][4-byte flags][H.264 data]
 * - Glasses connect and receive continuous stream
 * 
 * Orientation handling:
 * - Orientation is detected at connection time and encoder configured accordingly
 * - If display rotation changes mid-stream, we gracefully disconnect
 * - Glasses will auto-reconnect and get the new orientation
 */
class ScreenStreamServer(private val context: Context) {

    companion object {
        private const val TAG = "ScreenStreamServer"
        const val STREAM_PORT = 5050
        
        // Capture dimensions (must be multiples of 16 for encoder compatibility)
        // Using square capture for orientation-agnostic streaming
        private const val CAPTURE_SIZE = 640  // 640 = 40 * 16, square capture
        private const val CAPTURE_SHORT_SIDE = 480   // Legacy, used when manual orientation is set
        private const val CAPTURE_LONG_SIDE = 848    // Legacy, used when manual orientation is set
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 1_000_000  // 1 Mbps
        
        // Request code for MediaProjection permission
        const val REQUEST_MEDIA_PROJECTION = 1001
    }

    enum class ConnectionMode {
        REGULAR_WIFI,
        WIFI_DIRECT,
        NONE
    }
    
    // State
    private val isRunning = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    
    // MediaProjection
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // Encoding
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    
    // Network
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    
    // Screen metrics
    private var screenDensity = 1
    
    // Display rotation tracking
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var rotationChecker: Runnable? = null
    private var currentIsLandscape = false  // Current orientation used for encoding
    private var useSquareCapture = true     // Use square capture for seamless orientation
    private var lastKnownRotation = -1      // Track last rotation to detect changes
    private val ROTATION_CHECK_INTERVAL = 500L  // Check every 500ms
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Error tracking
    private val lastError = AtomicReference<String?>(null)
    private val framesSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    
    // Callbacks
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onOrientationChanged: ((Boolean) -> Unit)? = null  // Called when orientation changes, param is isLandscape
    
    // MediaProjection callback (required on Android 14+)
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            fileLog("MediaProjection stopped by system")
            setError("MediaProjection stopped by system")
            handler.post {
                isRunning.set(false)
                isStreaming.set(false)
                onStreamingStateChanged?.invoke(false)
            }
        }
    }

    var connectionMode = ConnectionMode.NONE
        private set
    var onConnectionModeChanged: ((ConnectionMode) -> Unit)? = null
    
    init {
        fileLog("ScreenStreamServer initialized")
        try {
            mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        } catch (e: Exception) {
            fileLog("Failed to get MediaProjectionManager: ${e.message}")
            setError("Init: ${e.message}")
        }
    }
    
    private fun setError(message: String) {
        lastError.set(message)
        fileLog("Error: $message")
        handler.post { 
            onError?.invoke(message)
            onStatusUpdate?.invoke("Error: $message")
        }
    }
    
    fun getLastError(): String? = lastError.get()
    
    fun getStats(): String {
        return "Frames: ${framesSent.get()}, Bytes: ${bytesSent.get() / 1024}KB"
    }

    /**
     * Get the best available IP address for streaming.
     * Checks regular WiFi first, then WiFi Direct interface.
     * Returns Pair(ip, mode) or null if no suitable interface found.
     */
    fun getBestStreamingIp(): Pair<String, ConnectionMode>? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var regularWifiIp: String? = null
            var wifiDirectIp: String? = null

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val name = networkInterface.name
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val hostAddress = address.hostAddress ?: continue

                        // Check for WiFi Direct interface
                        if (name.startsWith("p2p") || name.contains("p2p") ||
                            hostAddress.startsWith("192.168.49.")) {
                            fileLog("Found WiFi Direct IP: $hostAddress on $name")
                            wifiDirectIp = hostAddress
                        }
                        // Regular WiFi (wlan0)
                        else if (name.startsWith("wlan")) {
                            fileLog("Found WiFi IP: $hostAddress on $name")
                            regularWifiIp = hostAddress
                        }
                    }
                }
            }

            // Prefer regular WiFi, fall back to WiFi Direct
            return when {
                regularWifiIp != null -> Pair(regularWifiIp, ConnectionMode.REGULAR_WIFI)
                wifiDirectIp != null -> Pair(wifiDirectIp, ConnectionMode.WIFI_DIRECT)
                else -> null
            }
        } catch (e: Exception) {
            fileLog("Error getting IP address: ${e.message}")
            return null
        }
    }

    // Keep the old method for backwards compatibility, but have it use the new one
    fun getWifiIpAddress(): String? {
        return getBestStreamingIp()?.first
    }
    
    /**
     * Request screen capture permission from the user.
     */
    fun requestPermission(activity: Activity) {
        try {
            val intent = mediaProjectionManager?.createScreenCaptureIntent()
            if (intent != null) {
                activity.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
            } else {
                setError("MediaProjection not available")
            }
        } catch (e: Exception) {
            fileLog("Error requesting permission: ${e.message}")
            setError("Permission request: ${e.message}")
        }
    }
    
    /**
     * Handle the result from requestPermission().
     */
    fun onPermissionResult(resultCode: Int, data: Intent?): Boolean {
        try {
            if (resultCode != Activity.RESULT_OK || data == null) {
                fileLog("MediaProjection permission denied")
                setError("Screen capture permission denied")
                return false
            }
            
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                setError("Failed to create MediaProjection")
                return false
            }
            
            // Register callback (required on Android 14+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    mediaProjection?.registerCallback(projectionCallback, handler)
                } catch (e: Exception) {
                    fileLog("Failed to register projection callback: ${e.message}")
                }
            }
            
            fileLog("MediaProjection permission granted")
            return true
        } catch (e: Exception) {
            fileLog("Error in onPermissionResult: ${e.message}")
            setError("Permission result: ${e.message}")
            return false
        }
    }
    
    /**
     * Get current display rotation.
     */
    private fun getCurrentRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.defaultDisplay.rotation
    }
    
    /**
     * Check if display is currently in landscape by comparing actual screen dimensions.
     * This works even when our app is in background.
     */
    private fun isDisplayLandscape(): Boolean {
        val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return false
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics.widthPixels > metrics.heightPixels
    }
    
    /**
     * Check if current rotation is landscape.
     */
    private fun isRotationLandscape(rotation: Int): Boolean {
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }
    
    /**
     * Get capture dimensions based on mode.
     * If useSquareCapture is true, returns square dimensions.
     * Otherwise uses orientation-specific dimensions.
     */
    private fun getCaptureWidth(landscape: Boolean): Int = 
        if (useSquareCapture) CAPTURE_SIZE
        else if (landscape) CAPTURE_LONG_SIDE else CAPTURE_SHORT_SIDE
    
    private fun getCaptureHeight(landscape: Boolean): Int = 
        if (useSquareCapture) CAPTURE_SIZE
        else if (landscape) CAPTURE_SHORT_SIDE else CAPTURE_LONG_SIDE
    
    /**
     * Start the stream server.
     */
    fun start(displayMetrics: DisplayMetrics) {
        if (isRunning.get()) {
            fileLog("Server already running")
            return
        }
        
        if (mediaProjection == null) {
            setError("No screen capture permission")
            return
        }
        
        screenDensity = displayMetrics.densityDpi
        
        isRunning.set(true)
        framesSent.set(0)
        bytesSent.set(0)
        lastError.set(null)
        
        // Start server thread
        Thread {
            try {
                runServer()
            } catch (e: Exception) {
                fileLog("Server thread crashed: ${e.message}\n${e.stackTraceToString()}")
                setError("Server crashed: ${e.message}")
            }
        }.apply {
            name = "ScreenStreamServer"
            start()
        }
        
        fileLog("Stream server started")
        handler.post { onStatusUpdate?.invoke("Server started on port $STREAM_PORT") }
    }
    
    /**
     * Start listening for display rotation changes.
     */
    private fun startDisplayListener() {
        // Record initial rotation
        lastKnownRotation = getCurrentRotation()
        
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            
            override fun onDisplayChanged(displayId: Int) {
                // Only care about the default display
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                
                // Get rotation directly from the display, not through our app's context
                val display = displayManager?.getDisplay(displayId) ?: return
                val newRotation = display.rotation
                val newIsLandscape = isRotationLandscape(newRotation)
                
                fileLog("onDisplayChanged: rotation=$newRotation, newLandscape=$newIsLandscape, currentLandscape=$currentIsLandscape, streaming=${isStreaming.get()}")
                
                // Check if orientation actually changed (portrait <-> landscape)
                if (newIsLandscape != currentIsLandscape && isStreaming.get()) {
                    fileLog("*** DISPLAY ROTATION CHANGED: rotation=$newRotation, landscape=$newIsLandscape (was $currentIsLandscape) ***")
                    handler.post {
                        Toast.makeText(context, "Display rotated! Reconnecting...", Toast.LENGTH_SHORT).show()
                        onStatusUpdate?.invoke("Display rotated, reconnecting...")
                    }
                    
                    // Gracefully close the client socket to trigger reconnection
                    disconnectClientForOrientationChange()
                } else if (newRotation != lastKnownRotation) {
                    // Rotation changed but not portrait<->landscape (e.g., 90->270)
                    handler.post {
                        Toast.makeText(context, "Rotation: $lastKnownRotation -> $newRotation (no action)", Toast.LENGTH_SHORT).show()
                    }
                }
                
                lastKnownRotation = newRotation
            }
        }
        
        displayManager?.registerDisplayListener(displayListener, handler)
        fileLog("Display listener started")
        handler.post {
            val rotation = getCurrentRotation()
            Toast.makeText(context, "Display listener enabled (rotation=$rotation)", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Stop the display listener.
     */
    private fun stopDisplayListener() {
        displayListener?.let {
            displayManager?.unregisterDisplayListener(it)
        }
        displayListener = null
        
        rotationChecker?.let {
            handler.removeCallbacks(it)
        }
        rotationChecker = null
    }
    
    /**
     * Start periodic rotation checking as fallback.
     * DisplayListener may not fire when other apps rotate.
     */
    private fun startRotationChecker() {
        var checkCount = 0
        
        rotationChecker = object : Runnable {
            override fun run() {
                if (!isStreaming.get() || !isRunning.get()) {
                    return
                }
                
                checkCount++
                
                // Get rotation directly from display
                val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val newRotation = display?.rotation ?: -1
                val newIsLandscape = isRotationLandscape(newRotation)
                
                // Show diagnostic toast every 5 seconds (every 10 checks)
                if (checkCount % 10 == 0) {
                    handler.post {
                        Toast.makeText(context, "Check #$checkCount: rot=$newRotation, land=$newIsLandscape, cur=$currentIsLandscape", Toast.LENGTH_SHORT).show()
                    }
                }
                
                if (newIsLandscape != currentIsLandscape) {
                    fileLog("*** ROTATION CHECKER: detected change! rotation=$newRotation, landscape=$newIsLandscape (was $currentIsLandscape) ***")
                    handler.post {
                        Toast.makeText(context, "Rotation detected! Reconnecting...", Toast.LENGTH_SHORT).show()
                        onStatusUpdate?.invoke("Rotation detected, reconnecting...")
                    }
                    disconnectClientForOrientationChange()
                    return  // Stop checking, will restart on next connection
                }
                
                // Schedule next check
                handler.postDelayed(this, ROTATION_CHECK_INTERVAL)
            }
        }
        
        handler.postDelayed(rotationChecker!!, ROTATION_CHECK_INTERVAL)
        fileLog("Rotation checker started")
        handler.post {
            Toast.makeText(context, "Rotation checker started (cur=$currentIsLandscape)", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Gracefully disconnect client to trigger reconnection with new orientation.
     */
    private fun disconnectClientForOrientationChange() {
        fileLog("*** DISCONNECTING client for orientation change ***")
        
        // Close the client socket - this will cause the streaming loop to exit cleanly
        try {
            clientSocket?.close()
            fileLog("Client socket closed successfully")
        } catch (e: Exception) {
            fileLog("Error closing client socket: ${e.message}")
        }
        
        // The streaming loop will detect the closed socket, clean up,
        // and wait for the next connection with fresh orientation detection
    }
    
    /**
     * Stop the stream server.
     */
    fun stop() {
        fileLog("Stopping stream server")
        isRunning.set(false)
        isStreaming.set(false)
        
        stopCapture()
        
        try { clientSocket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        
        clientSocket = null
        serverSocket = null
        outputStream = null
        
        handler.post { 
            onStreamingStateChanged?.invoke(false)
            onStatusUpdate?.invoke("Server stopped")
        }
    }
    
    /**
     * Check if server is running.
     */
    fun isRunning(): Boolean = isRunning.get()
    
    /**
     * Check if actively streaming to a client.
     */
    fun isStreaming(): Boolean = isStreaming.get()
    
    /**
     * Get current orientation mode.
     */
    fun isLandscapeMode(): Boolean = currentIsLandscape
    
    /**
     * Check if using square capture mode.
     */
    fun isSquareCaptureMode(): Boolean = useSquareCapture
    
    /**
     * Toggle between square capture and orientation-specific capture.
     * When switching to orientation-specific, requires restart.
     */
    fun toggleCaptureMode(): Boolean {
        useSquareCapture = !useSquareCapture
        fileLog("Toggled capture mode: square=$useSquareCapture")
        
        handler.post {
            val modeStr = if (useSquareCapture) "SQUARE (seamless)" else "ORIENTATION-SPECIFIC"
            Toast.makeText(context, "Capture mode: $modeStr", Toast.LENGTH_SHORT).show()
            onOrientationChanged?.invoke(currentIsLandscape)
        }
        
        // Need restart to apply
        return isStreaming.get() || isRunning.get()
    }
    
    /**
     * Toggle through capture modes: Auto (square) → Portrait → Landscape → Auto
     * On Android 14+, this requires stopping and restarting mirroring.
     */
    fun toggleOrientation(): Boolean {
        if (useSquareCapture) {
            // Auto → Portrait
            useSquareCapture = false
            currentIsLandscape = false
            fileLog("Switching from Auto to Portrait")
        } else if (!currentIsLandscape) {
            // Portrait → Landscape
            currentIsLandscape = true
            fileLog("Switching from Portrait to Landscape")
        } else {
            // Landscape → Auto
            useSquareCapture = true
            currentIsLandscape = false
            fileLog("Switching from Landscape to Auto")
        }
        
        // Notify listener
        handler.post {
            val modeStr = when {
                useSquareCapture -> "AUTO (seamless)"
                currentIsLandscape -> "LANDSCAPE"
                else -> "PORTRAIT"
            }
            Toast.makeText(context, "Mode: $modeStr", Toast.LENGTH_SHORT).show()
            onOrientationChanged?.invoke(currentIsLandscape)
        }
        
        // On Android 14+, MediaProjection can't be reused after VirtualDisplay is released
        return isStreaming.get() || isRunning.get()
    }
    
    /**
     * Set orientation mode explicitly.
     */
    fun setOrientation(landscape: Boolean) {
        if (landscape != currentIsLandscape) {
            toggleOrientation()
        }
    }
    
    private fun runServer() {
        try {
            serverSocket = ServerSocket(STREAM_PORT)
            fileLog("Server listening on port $STREAM_PORT")
            handler.post { onStatusUpdate?.invoke("Waiting for glasses on port $STREAM_PORT") }
            
            while (isRunning.get()) {
                try {
                    // Wait for client connection
                    val socket = serverSocket?.accept() ?: break
                    fileLog("Client connected from ${socket.inetAddress}")
                    handler.post { onStatusUpdate?.invoke("Glasses connected: ${socket.inetAddress}") }
                    
                    // Use the manually set orientation (currentIsLandscape)
                    val captureWidth = getCaptureWidth(currentIsLandscape)
                    val captureHeight = getCaptureHeight(currentIsLandscape)
                    fileLog("*** CONNECTION: landscape=$currentIsLandscape, capture=${captureWidth}x${captureHeight} ***")
                    
                    // Show toast with connection orientation
                    handler.post {
                        val orientationStr = if (currentIsLandscape) "LANDSCAPE" else "PORTRAIT"
                        Toast.makeText(context, "Client connected: $orientationStr ${captureWidth}x${captureHeight}", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Configure socket for low latency
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 65536
                    
                    // Close previous client if any
                    try { clientSocket?.close() } catch (e: Exception) {}
                    
                    clientSocket = socket
                    outputStream = socket.getOutputStream()
                    
                    isStreaming.set(true)
                    handler.post { onStreamingStateChanged?.invoke(true) }
                    
                    // Start capture and encoding with current orientation
                    if (!startCapture(captureWidth, captureHeight)) {
                        fileLog("startCapture failed, closing connection")
                        handler.post {
                            Toast.makeText(context, "Failed to start capture with ${captureWidth}x${captureHeight}", Toast.LENGTH_LONG).show()
                        }
                        isStreaming.set(false)
                        continue  // Go back to waiting for next connection
                    }
                    
                    // Stream until disconnected
                    streamToClient()
                    
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        fileLog("Client error: ${e.message}")
                        // Don't report as error if it was just a socket close for orientation change
                        if (e.message?.contains("closed") != true) {
                            setError("Client: ${e.message}")
                        }
                    }
                }
                
                // Clean up after client disconnect
                isStreaming.set(false)
                
                handler.post { 
                    onStreamingStateChanged?.invoke(false)
                    onStatusUpdate?.invoke("Glasses disconnected. Frames sent: ${framesSent.get()}")
                }
                stopCapture()
                
                try { clientSocket?.close() } catch (e: Exception) {}
                clientSocket = null
                outputStream = null
            }
            
        } catch (e: Exception) {
            if (isRunning.get()) {
                fileLog("Server error: ${e.message}")
                setError("Server: ${e.message}")
            }
        }
        
        fileLog("Server stopped")
    }
    
    private fun startCapture(captureWidth: Int, captureHeight: Int): Boolean {
        fileLog("Starting capture: ${captureWidth}x${captureHeight}")
        handler.post { onStatusUpdate?.invoke("Starting capture ${captureWidth}x${captureHeight}") }
        
        try {
            // Create encoder with low-latency settings
            fileLog("Creating encoder format...")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, captureWidth, captureHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                
                // Low latency settings
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    } catch (e: Exception) {
                        fileLog("KEY_LOW_LATENCY not supported")
                    }
                }
                
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                } catch (e: Exception) {
                    fileLog("KEY_LATENCY not supported")
                }
                
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (e: Exception) {
                    fileLog("KEY_PRIORITY not supported")
                }
                
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            
            fileLog("Creating encoder...")
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderSurface = createInputSurface()
                start()
            }
            
            fileLog("Encoder started successfully")
            handler.post { onStatusUpdate?.invoke("Encoder started") }
            
            // Create virtual display that renders to encoder surface
            fileLog("Creating virtual display...")
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenMirror",
                captureWidth,
                captureHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface,
                null,
                null
            )
            
            if (virtualDisplay == null) {
                fileLog("Failed to create virtual display - returned null")
                setError("Failed to create virtual display")
                return false
            }
            
            fileLog("Virtual display created successfully")
            handler.post { onStatusUpdate?.invoke("Streaming...") }
            return true
            
        } catch (e: Exception) {
            fileLog("Failed to start capture: ${e.message}\n${e.stackTraceToString()}")
            setError("Capture: ${e.message}")
            handler.post {
                Toast.makeText(context, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }
    
    private fun stopCapture() {
        fileLog("Stopping capture")
        
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            fileLog("Error releasing virtual display: ${e.message}")
        }
        virtualDisplay = null
        
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            fileLog("Error releasing encoder: ${e.message}")
        }
        encoder = null
        encoderSurface = null
    }
    
    private fun streamToClient() {
        val enc = encoder ?: return
        val out = outputStream ?: return
        
        val bufferInfo = MediaCodec.BufferInfo()
        val headerBuffer = ByteArray(8)
        
        fileLog("Starting stream to client")
        
        try {
            while (isStreaming.get() && isRunning.get()) {
                val outputIndex = enc.dequeueOutputBuffer(bufferInfo, 10000)
                
                when {
                    outputIndex >= 0 -> {
                        try {
                            val outputBuffer = enc.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                // Read encoded data
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.get(data)
                                
                                // Write header: [4-byte length][4-byte flags]
                                headerBuffer[0] = ((bufferInfo.size shr 24) and 0xFF).toByte()
                                headerBuffer[1] = ((bufferInfo.size shr 16) and 0xFF).toByte()
                                headerBuffer[2] = ((bufferInfo.size shr 8) and 0xFF).toByte()
                                headerBuffer[3] = (bufferInfo.size and 0xFF).toByte()
                                
                                headerBuffer[4] = ((bufferInfo.flags shr 24) and 0xFF).toByte()
                                headerBuffer[5] = ((bufferInfo.flags shr 16) and 0xFF).toByte()
                                headerBuffer[6] = ((bufferInfo.flags shr 8) and 0xFF).toByte()
                                headerBuffer[7] = (bufferInfo.flags and 0xFF).toByte()
                                
                                // Send to client
                                synchronized(out) {
                                    out.write(headerBuffer)
                                    out.write(data)
                                    out.flush()
                                }
                                
                                framesSent.incrementAndGet()
                                bytesSent.addAndGet(8 + data.size.toLong())
                            }
                        } finally {
                            enc.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = enc.outputFormat
                        fileLog("Encoder format changed: $newFormat")
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Timeout, just continue
                    }
                }
            }
        } catch (e: Exception) {
            if (isStreaming.get()) {
                // Only log as error if it wasn't a deliberate disconnect
                if (e.message?.contains("closed") == true || e.message?.contains("reset") == true) {
                    fileLog("Stream ended (socket closed)")
                } else {
                    fileLog("Stream error: ${e.message}")
                    setError("Stream: ${e.message}")
                }
            }
        }
        
        fileLog("Stream ended. Total frames: ${framesSent.get()}, bytes: ${bytesSent.get()}")
    }
    
    /**
     * Release all resources.
     */
    fun release() {
        stop()
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            fileLog("Error stopping MediaProjection: ${e.message}")
        }
        mediaProjection = null
    }
}
