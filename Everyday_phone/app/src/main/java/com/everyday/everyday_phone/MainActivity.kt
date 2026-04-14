package com.everyday.everyday_phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder

/**
 * Phone app with trackpad for cursor control on glasses.
 * Shows a soft keyboard when a text field is focused on the glasses.
 * Can mirror phone screen to glasses over WiFi.
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        //note: this API key can run out if overused.
        private const val GEOCODE_MAPS_CO_API_KEY = "6974a768992ec642181119eby0e50e7"

        private const val TAG = "Everyday_phone"
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_BACKGROUND_LOCATION = 2
        private const val ACTION_START_MIRROR = "com.everyday.everyday_phone.START_MIRROR"
        private const val REQUEST_CHANNEL_ID = "mirror_request_channel"
        private const val REQUEST_NOTIFICATION_ID = 2002

        private const val REQUEST_WIFI_DIRECT_PERMISSIONS = 2001
        private const val REQUEST_IMAGE_PICKER = 2002
        private const val REQUEST_IMAGE_PERMISSION = 2003
        private const val GOOGLE_CALENDAR_REFRESH_INTERVAL_MS = 3 * 60 * 1000L
    }
    
    private lateinit var statusText: TextView
    private lateinit var glassesIcon: android.widget.ImageView
    // coordsText is now commented out in layout for minimalist design
    // private lateinit var coordsText: TextView
    private lateinit var trackpad: View
    private lateinit var trackpadLabel: TextView
    private lateinit var trackpadContainer: FrameLayout
    private lateinit var keyboardView: KeyboardView
    // instructionText removed for minimalist design
    // private lateinit var instructionText: TextView
    private lateinit var closeButton: View
    private var mirrorButton: Button? = null
    private var imagePickerButton: Button? = null
    private lateinit var phoneGoogleAuthManager: PhoneGoogleAuthManager
    
    private var rfcommServer: RfcommServer? = null
    private var screenStreamServer: ScreenStreamServer? = null
    private var isKeyboardShown = false
    private var isMirroringEnabled = false
    private var isGlassesConnected = false
    private var glassesBatteryPercent: Int? = null

    // Location & Weather
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null


    // Clipboard monitoring
    private var clipboardManager: ClipboardManager? = null
    private var lastSentClipboard: String? = null  // Track what we last sent to avoid duplicates
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    private var wifiDirectManager: WifiDirectManager? = null
    private var useWifiDirectFallback = false

    private lateinit var googleAuthorizationLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    private val mainHandler = Handler(Looper.getMainLooper())
    private var googleCalendarRefreshRunnable: Runnable? = null


    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup file logging
        FileLogger.initialize(this)

        // Startup log for debugging
        fileLog("MainActivity onCreate - App starting up")

        phoneGoogleAuthManager = PhoneGoogleAuthManager(this)
        googleAuthorizationLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            phoneGoogleAuthManager.handleAuthorizationIntentResult(
                resultCode = result.resultCode,
                data = result.data,
                launcher = googleAuthorizationLauncher
            )
        }
        
        // Global crash handler to see what's happening
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            fileLog("UNCAUGHT EXCEPTION in $thread: ${throwable.message}\n${throwable.stackTraceToString()}")
            // Try to show in UI (may not work if UI thread crashed)
            try {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        "Crash: ${throwable.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {}
        }
        
        setContentView(R.layout.activity_main)
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        statusText = findViewById(R.id.statusText)
        glassesIcon = findViewById(R.id.glassesIcon)
        // coordsText commented out in layout for minimalist design
        // coordsText = findViewById(R.id.coordsText)
        trackpad = findViewById(R.id.trackpad)
        trackpadLabel = findViewById(R.id.trackpadLabel)
        trackpadContainer = findViewById(R.id.trackpadContainer)
        keyboardView = findViewById(R.id.keyboardView)
        // instructionText removed for minimalist design
        // instructionText = findViewById(R.id.instructionText)
        closeButton = findViewById(R.id.closeButton)
        
        // Close button - closes the app
        closeButton.setOnClickListener {
            finish()
        }
        
        rfcommServer = RfcommServer(this).apply {
            onStatusChanged = { status ->
                runOnUiThread { 
                    // Minimalist design: don't show verbose status
                    // statusText.text = status 
                }
            }
            onConnectionChanged = { connected ->
                runOnUiThread { 
                    isGlassesConnected = connected
                    if (!connected) {
                        glassesBatteryPercent = null
                        lastSentClipboard = null  // Reset on disconnect
                        stopLocationWeatherService()
                    } else {
                        startLocationWeatherService(forceRefresh = true)
                        // When connected, try to send location update
                        updateLocationAndWeather()
                        // Also send current clipboard so glasses have it
                        sendCurrentClipboardToGlasses()
                        sendCachedPayloadsToGlasses(pushGoogleState = true)
                    }
                    updateGoogleCalendarRefreshSchedule()
                    updateStatusDisplay()
                    trackpad.alpha = if (connected) 1.0f else 0.5f
                    updateMirrorButton()
                    updateImagePickerButton()

                    if (!connected) {
                        hideKeyboard()
                        // Stop mirroring when glasses disconnect
                        if (isMirroringEnabled) {
                            stopMirroring()
                        }
                    }
                }
            }
            onTextFieldFocusChanged = { focused ->
                runOnUiThread {
                    if (focused) {
                        showKeyboard()
                        // Send clipboard when text field is focused (so paste is available)
                        sendCurrentClipboardToGlasses()
                    } else {
                        hideKeyboard()
                    }
                }
            }
            onBatteryChanged = { batteryPercent ->
                runOnUiThread {
                    glassesBatteryPercent = batteryPercent
                    updateStatusDisplay()
                }
            }
            onMirrorActionRequested = { start ->
                runOnUiThread {
                    if (start) {
                        startMirroringFromBackground()
                    } else {
                        stopMirroring()
                    }
                }
            }
            
            onClipboardRequested = {
                runOnUiThread {
                    sendCurrentClipboardToGlasses()
                }
            }

            onGoogleAuthRequested = {
                runOnUiThread {
                    beginPhoneGoogleAuthorization()
                }
            }

            onGoogleCalendarSnapshotRequested = {
                runOnUiThread {
                    syncGoogleSnapshotToGlasses(pushCachedFirst = true)
                }
            }

            onGoogleDisconnectRequested = {
                runOnUiThread {
                    disconnectPhoneGoogleAuthorization()
                }
            }

            onPayloadSyncRequested = {
                runOnUiThread {
                    sendCachedPayloadsToGlasses(pushGoogleState = true)
                }
            }
        }
        
        // Initialize screen stream server
        screenStreamServer = ScreenStreamServer(this).apply {
            onStreamingStateChanged = { streaming ->
                runOnUiThread {
                    fileLog("Streaming state changed: $streaming")
                    // Don't overwrite detailed status updates
                }
            }
            onError = { error ->
                runOnUiThread {
                    fileLog("Stream error: $error")
                    // DEBUG: coordsText commented out for minimalist design
                    // coordsText.text = "❌ $error"
                    // coordsText.setTextColor(0xFFFF6666.toInt())
                }
            }
            onStatusUpdate = { status ->
                runOnUiThread {
                    fileLog("Stream status: $status")
                    // DEBUG: coordsText commented out for minimalist design
                    // coordsText.text = status
                    // Reset color to green unless it's an error
                    // if (!status.startsWith("❌") && !status.startsWith("Error")) {
                    //     coordsText.setTextColor(0xFF00FF00.toInt())
                    // }
                }
            }
            onOrientationChanged = { isLandscape ->
                // Orientation button removed - no action needed
            }
        }
        
        setupTrackpad()
        setupKeyboard()
        setupImagePickerButton()
        setupMirrorButton()
        setupClipboardMonitoring()
        
        if (hasPermissions()) {
            fileLog("Has permissions, starting services")
            rfcommServer?.start()
            checkLocationPermission()
            // Request background location for Android 10+ (must be after foreground location is granted)
            requestBackgroundLocationIfNeeded()
        } else {
            fileLog("Missing permissions, requesting")
            requestPermissions()
        }

        // Initialize WiFi Direct (check permissions first on Android 13+)
        checkWifiDirectPermissions()
        
        // Check for overlay permission to allow background starts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(
                this,
                "Please grant 'Display over other apps' to allow background mirroring",
                android.widget.Toast.LENGTH_LONG
            ).show()
            
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        handleIntent(intent)


    }

    private fun initializeWifiDirect() {
        wifiDirectManager = WifiDirectManager(this).apply {
            onGroupFormed = { ownerIp ->
                fileLog("WiFi Direct group formed, IP: $ownerIp")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WiFi Direct ready: $ownerIp", Toast.LENGTH_SHORT).show()
                }

                // If we were waiting for WiFi Direct to start mirroring, now request permission
                if (useWifiDirectFallback && !isMirroringEnabled) {
                    runOnUiThread {
                        screenStreamServer?.requestPermission(this@MainActivity)
                    }
                }
                // If already mirroring (e.g., reconnect), send the new IP
                else if (useWifiDirectFallback && screenStreamServer?.isRunning() == true) {
                    rfcommServer?.sendMirrorControl(true, ownerIp, isWifiDirect = true)
                }
            }

            onGroupRemoved = {
                fileLog("WiFi Direct group removed")
            }

            onError = { error ->
                fileLog("WiFi Direct error: $error")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WiFi Direct: $error", Toast.LENGTH_SHORT).show()
                    useWifiDirectFallback = false
                }
            }

            onStateChanged = { enabled ->
                fileLog("WiFi Direct enabled: $enabled")
            }

            initialize()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_MIRROR) {
            fileLog("Received start mirror intent")
            // Cancel notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(REQUEST_NOTIFICATION_ID)
            
            // Start mirroring
            startMirroring()
        }
    }

    private fun beginPhoneGoogleAuthorization() {
        phoneGoogleAuthManager.beginAuthorization(
            activity = this,
            launcher = googleAuthorizationLauncher,
            onStateChanged = { state ->
                pushGoogleStateToGlasses(state)
                updateGoogleCalendarRefreshSchedule()
                val message = when (state.status) {
                    PhoneGoogleAuthState.Status.AUTHORIZING -> "Authorizing Google..."
                    PhoneGoogleAuthState.Status.AUTHORIZED -> "Google Calendar connected"
                    PhoneGoogleAuthState.Status.ERROR -> state.detail ?: "Google authorization failed"
                    PhoneGoogleAuthState.Status.SIGNED_OUT -> "Google disconnected"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            },
            onSnapshotChanged = { snapshot ->
                if (snapshot != null) {
                    rfcommServer?.sendGoogleCalendarSnapshot(snapshot)
                }
            }
        )
    }

    private fun sendCachedPayloadsToGlasses(pushGoogleState: Boolean) {
        if (!isGlassesConnected) return

        val googleState = phoneGoogleAuthManager.loadState()
        if (pushGoogleState) {
            pushGoogleStateToGlasses(googleState)
        }

        LocationWeatherService.instance?.pushCachedPayloadToGlasses()

        val cachedSnapshot = phoneGoogleAuthManager.loadCachedSnapshot()
        cachedSnapshot?.let { snapshot ->
            rfcommServer?.sendGoogleCalendarSnapshot(snapshot)
        }

        if (googleState.status == PhoneGoogleAuthState.Status.AUTHORIZED && shouldRefreshGoogleSnapshot(cachedSnapshot)) {
            syncGoogleSnapshotToGlasses(
                pushCachedFirst = false,
                pushFreshToGlasses = true
            )
        }
    }

    private fun syncGoogleSnapshotToGlasses(
        pushCachedFirst: Boolean,
        pushFreshToGlasses: Boolean = true
    ) {
        val state = phoneGoogleAuthManager.loadState()
        if (pushCachedFirst) {
            pushGoogleStateToGlasses(state)
        }

        if (pushCachedFirst) {
            phoneGoogleAuthManager.loadCachedSnapshot()?.let { snapshot ->
                rfcommServer?.sendGoogleCalendarSnapshot(snapshot)
            }
        }

        if (!isGlassesConnected || state.status != PhoneGoogleAuthState.Status.AUTHORIZED) {
            return
        }

        phoneGoogleAuthManager.refreshSnapshotSilently { result ->
            result.onSuccess { snapshot ->
                if (pushFreshToGlasses && isGlassesConnected) {
                    rfcommServer?.sendGoogleCalendarSnapshot(snapshot)
                }
            }.onFailure { error ->
                if (error.message != "Google Calendar refresh already in progress") {
                    fileLog("Google auth: background snapshot refresh failed: ${error::class.java.simpleName}: ${error.message}")
                }
            }
        }
    }

    private fun updateGoogleCalendarRefreshSchedule() {
        googleCalendarRefreshRunnable?.let { mainHandler.removeCallbacks(it) }

        val shouldRefresh = isGlassesConnected &&
            phoneGoogleAuthManager.loadState().status == PhoneGoogleAuthState.Status.AUTHORIZED

        if (!shouldRefresh) return

        val runnable = object : Runnable {
            override fun run() {
                syncGoogleSnapshotToGlasses(
                    pushCachedFirst = false,
                    pushFreshToGlasses = true
                )
                mainHandler.postDelayed(this, GOOGLE_CALENDAR_REFRESH_INTERVAL_MS)
            }
        }
        googleCalendarRefreshRunnable = runnable
        mainHandler.postDelayed(runnable, GOOGLE_CALENDAR_REFRESH_INTERVAL_MS)
    }

    private fun shouldRefreshGoogleSnapshot(snapshot: PhoneGoogleCalendarSnapshot?): Boolean {
        if (snapshot == null) return true
        val staleAfterMs = snapshot.staleAfterMs.takeIf { it > 0L } ?: return false
        return System.currentTimeMillis() > snapshot.fetchedAtMs + staleAfterMs
    }

    private fun disconnectPhoneGoogleAuthorization() {
        phoneGoogleAuthManager.revokeAccess(
            activity = this,
            onStateChanged = { state ->
                pushGoogleStateToGlasses(state)
                updateGoogleCalendarRefreshSchedule()
                Toast.makeText(this, "Google disconnected", Toast.LENGTH_SHORT).show()
            },
            onComplete = {}
        )
    }

    private fun pushGoogleStateToGlasses(state: PhoneGoogleAuthState) {
        rfcommServer?.sendGoogleAuthState(
            status = state.status,
            account = state.account,
            detail = state.detail
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        googleCalendarRefreshRunnable?.let { mainHandler.removeCallbacks(it) }

        // Remove clipboard listener
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)

        rfcommServer?.stop()
        screenStreamServer?.release()
        wifiDirectManager?.release()

        // Stop foreground services if running
        ScreenCaptureService.stop(this)
        LocationWeatherService.stop(this)
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == ScreenStreamServer.REQUEST_MEDIA_PROJECTION) {
            try {
                fileLog("MediaProjection result: resultCode=$resultCode, data=$data")

                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Start foreground service FIRST (required on Android 14+)
                    fileLog("Starting foreground service...")
                    ScreenCaptureService.start(this)

                    // Small delay to ensure service is running before getting MediaProjection
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            fileLog("Getting MediaProjection...")
                            if (screenStreamServer?.onPermissionResult(resultCode, data) == true) {
                                // Permission granted, start streaming
                                fileLog("MediaProjection obtained, starting mirroring...")
                                startMirroringAfterPermission()
                            } else {
                                // Failed to create MediaProjection
                                fileLog("Failed to get MediaProjection")
                                isMirroringEnabled = false
                                updateMirrorButton()
                                ScreenCaptureService.stop(this)
                                // DEBUG: coordsText.text = "Error: Failed to get projection"
                            }
                        } catch (e: Exception) {
                            fileLog("Error in delayed handler: ${e.message}\n${e.stackTraceToString()}")
                            // DEBUG: coordsText.text = "Error: ${e.message}"
                            ScreenCaptureService.stop(this)
                        }
                    }, 200)
                } else {
                    // Permission denied
                    fileLog("Permission denied by user")
                    isMirroringEnabled = false
                    updateMirrorButton()
                    // DEBUG: coordsText.text = "Permission denied"
                }
            } catch (e: Exception) {
                fileLog("Error in onActivityResult: ${e.message}\n${e.stackTraceToString()}")
                // DEBUG: coordsText.text = "Error: ${e.message}"
            }
        } else if (requestCode == REQUEST_IMAGE_PICKER) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                handleSelectedImage(data.data!!)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTrackpad() {
        trackpad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val (x, y) = trackpadCentroid(event)
                    rfcommServer?.onTouchStart(x, y, event.pointerCount)
                    // DEBUG: coordsText.text = "Touch started"
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Additional finger touched
                    val (x, y) = trackpadCentroid(event)
                    rfcommServer?.onPointerCountChanged(event.pointerCount, x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val (x, y) = trackpadCentroid(event)
                    rfcommServer?.onTouchMove(x, y, event.pointerCount)
                    // DEBUG: coordsText.text = "Moving: ${event.x.toInt()}, ${event.y.toInt()}"
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // One finger lifted
                    val remainingCount = event.pointerCount - 1
                    val (x, y) = trackpadCentroid(event, excludeActionPointer = true)
                    rfcommServer?.onPointerCountChanged(remainingCount, x, y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    rfcommServer?.onTouchEnd()
                    // DEBUG: coordsText.text = "Released"
                }
            }
            true
        }
    }

    private fun trackpadCentroid(
        event: MotionEvent,
        excludeActionPointer: Boolean = false
    ): Pair<Float, Float> {
        val ignoredIndex = if (excludeActionPointer) event.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var activeCount = 0

        for (i in 0 until event.pointerCount) {
            if (i == ignoredIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            activeCount++
        }

        if (activeCount == 0) return Pair(event.x, event.y)
        return Pair(sumX / activeCount, sumY / activeCount)
    }
    
    private fun setupKeyboard() {
        keyboardView.onKeyPressed = { key ->
            rfcommServer?.sendKeyPress(key)
            
            // DEBUG: Visual feedback commented out for minimalist design
            // coordsText.text = when (key) {
            //     "BACKSPACE" -> "⌫"
            //     "\n" -> "↵"
            //     " " -> "␣"
            //     else -> key
            // }
        }
    }
    
    // ==================== Screen Mirroring ====================
    
    private fun setupImagePickerButton() {
        imagePickerButton = findViewById(R.id.imagePickerButton)
        imagePickerButton?.setOnClickListener {
            openImagePicker()
        }
        updateImagePickerButton()
    }

    /**
     * Open the system image picker to select an image to send to glasses.
     * Requests permission first if not already granted.
     */
    private fun openImagePicker() {
        if (!hasImagePermission()) {
            requestImagePermission()
            return
        }

        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE_PICKER)
    }

    /**
     * Check if the app has permission to read images.
     */
    private fun hasImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request permission to read images.
     */
    private fun requestImagePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_IMAGE_PERMISSION)
    }

    /**
     * Handle the selected image from the picker.
     */
    private fun handleSelectedImage(imageUri: Uri) {
        Thread {
            try {
                // Read the image from URI
                val inputStream = contentResolver.openInputStream(imageUri)
                if (inputStream == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to read image", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // Decode bitmap to get original dimensions
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (originalBitmap == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // Scale down large images to reduce transfer time
                // Target max dimension of 1024px (good balance for AR glasses display)
                val maxDimension = 1024
                val scaledBitmap = if (originalBitmap.width > maxDimension || originalBitmap.height > maxDimension) {
                    val scale = maxDimension.toFloat() / maxOf(originalBitmap.width, originalBitmap.height)
                    val newWidth = (originalBitmap.width * scale).toInt()
                    val newHeight = (originalBitmap.height * scale).toInt()
                    Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true).also {
                        if (it != originalBitmap) originalBitmap.recycle()
                    }
                } else {
                    originalBitmap
                }

                // Compress to JPEG
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val imageData = outputStream.toByteArray()
                scaledBitmap.recycle()

                // Get file name from URI
                val fileName = getFileNameFromUri(imageUri) ?: "image.jpg"

                fileLog("Sending image to glasses: $fileName (${imageData.size} bytes)")

                runOnUiThread {
                    Toast.makeText(this, "Sending image to glasses...", Toast.LENGTH_SHORT).show()
                }

                // Send to glasses
                rfcommServer?.sendImage(imageData, fileName)

                runOnUiThread {
                    Toast.makeText(this, "Image sent!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                fileLog("Error sending image: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Failed to send image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Get the file name from a content URI.
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = it.getString(nameIndex)
                    }
                }
            }
        }
        if (fileName == null) {
            fileName = uri.path?.substringAfterLast('/')
        }
        return fileName
    }

    private fun checkWifiDirectPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        // Location is needed for WiFi Direct on older Android versions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_WIFI_DIRECT_PERMISSIONS)
        } else {
            initializeWifiDirect()
        }
    }
    
    private fun updateImagePickerButton() {
        imagePickerButton?.apply {
            isEnabled = isGlassesConnected
            alpha = if (isGlassesConnected) 1.0f else 0.5f
        }
    }
    
    private fun setupMirrorButton() {
        mirrorButton = findViewById(R.id.mirrorButton)
        mirrorButton?.setOnClickListener {
            toggleMirroring()
        }
        updateMirrorButton()
    }
    
    private fun updateMirrorButton() {
        mirrorButton?.apply {
            isEnabled = isGlassesConnected
            text = if (isMirroringEnabled) "📺 Stop Mirror" else "📺 Start Mirror"
            alpha = if (isGlassesConnected) 1.0f else 0.5f
        }
    }
    
    private fun toggleMirroring() {
        if (isMirroringEnabled) {
            stopMirroring()
        } else {
            startMirroring()
        }
    }

    private fun startMirroring() {
        fileLog("Starting mirroring...")

        // Check for best available connection (regular WiFi or WiFi Direct)
        val bestIp = screenStreamServer?.getBestStreamingIp()

        when {
            bestIp != null -> {
                // We have an IP (either regular WiFi or WiFi Direct already active)
                fileLog("Starting mirror with ${bestIp.second}: ${bestIp.first}")
                useWifiDirectFallback = (bestIp.second == ScreenStreamServer.ConnectionMode.WIFI_DIRECT)
                // Request screen capture permission (will call startMirroringAfterPermission on success)
                screenStreamServer?.requestPermission(this)
            }

            wifiDirectManager?.isWifiDirectEnabled == true -> {
                // No IP yet, but WiFi Direct is available - create a group first
                fileLog("No network IP, creating WiFi Direct group...")
                useWifiDirectFallback = true
                Toast.makeText(this, "Creating WiFi Direct connection...", Toast.LENGTH_SHORT).show()
                wifiDirectManager?.createGroup()
                // The onGroupFormed callback will trigger permission request
            }

            else -> {
                fileLog("No WiFi or WiFi Direct available")
                Toast.makeText(this, "Please enable WiFi for screen mirroring", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMirroringFromBackground() {
        fileLog("Starting mirroring from background...")
        
        // Create intent to launch activity
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_START_MIRROR
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create notification channel for high priority alerts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                REQUEST_CHANNEL_ID,
                "Mirror Requests",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for requested screen mirroring"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        // Build notification with full screen intent
        val builder = androidx.core.app.NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.glasses_connected) // Assuming this resource exists, or use generic
            .setContentTitle("Screen Mirroring Requested")
            .setContentText("Tap to start mirroring to glasses")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            
        // Show notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(REQUEST_NOTIFICATION_ID, builder.build())
        
        // Also try direct start (best effort)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            fileLog("Failed to start activity directly: ${e.message}")
        }
    }

    private fun startMirroringAfterPermission() {
        val bestIp = screenStreamServer?.getBestStreamingIp()
        if (bestIp == null) {
            ScreenCaptureService.stop(this)
            return
        }

        isMirroringEnabled = true
        updateMirrorButton()

        val metrics = resources.displayMetrics
        screenStreamServer?.start(metrics)

        // Explicitly pass whether this is WiFi Direct
        val isWifiDirect = (bestIp.second == ScreenStreamServer.ConnectionMode.WIFI_DIRECT)
        rfcommServer?.sendMirrorControl(true, bestIp.first, isWifiDirect)

        fileLog("Mirror started, IP: ${bestIp.first}, WiFi Direct: $isWifiDirect")
    }
    
    private fun stopMirroring() {
        fileLog("Stopping mirroring...")
        
        isMirroringEnabled = false
        updateMirrorButton()

        // Tell glasses to stop receiving
        rfcommServer?.sendMirrorControl(false)
        
        // Stop stream server
        screenStreamServer?.stop()
        
        // Stop foreground service
        ScreenCaptureService.stop(this)
        
        // DEBUG: coordsText.text = "Mirror stopped"
    }
    
    private fun showKeyboard() {
        if (isKeyboardShown) return
        isKeyboardShown = true
        
        // Shrink trackpad to top half
        val params = trackpad.layoutParams as FrameLayout.LayoutParams
        params.height = trackpadContainer.height / 2
        params.gravity = android.view.Gravity.TOP
        trackpad.layoutParams = params
        
        // Trackpad label already hidden in layout (visibility="gone")
        // trackpadLabel.text = "TRACKPAD\n(cursor control)"
        // trackpadLabel.textSize = 14f
        
        // Show keyboard in bottom half
        val keyboardParams = keyboardView.layoutParams as FrameLayout.LayoutParams
        keyboardParams.height = trackpadContainer.height / 2
        keyboardParams.gravity = android.view.Gravity.BOTTOM
        keyboardView.layoutParams = keyboardParams
        keyboardView.visibility = View.VISIBLE
        
        // DEBUG: Minimalist design, no instructions
        // instructionText.text = "Use keyboard to type • Trackpad for cursor"
        // coordsText.text = "⌨️ Keyboard active"
    }
    
    private fun hideKeyboard() {
        if (!isKeyboardShown) return
        isKeyboardShown = false
        
        // Restore trackpad to full size
        val params = trackpad.layoutParams as FrameLayout.LayoutParams
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = android.view.Gravity.NO_GRAVITY
        trackpad.layoutParams = params
        
        // Trackpad label already hidden in layout (visibility="gone")
        // trackpadLabel.text = "TRACKPAD\n\nTouch anywhere"
        // trackpadLabel.textSize = 20f
        
        // Hide keyboard
        keyboardView.visibility = View.GONE
        
        // DEBUG: Minimalist design, no instructions
        // instructionText.text = "Touch coordinates will be sent to your AR glasses"
        // coordsText.text = "Touch the trackpad"
    }
    
    // ==================== Clipboard Monitoring ====================
    
    private fun setupClipboardMonitoring() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        fileLog("Clipboard monitoring started")
    }
    
    /**
     * Called when phone clipboard content changes.
     * Sends the new clipboard content to glasses.
     */
    private fun onClipboardChanged() {
        if (!isGlassesConnected) return
        
        val clipboardText = getClipboardText()
        if (clipboardText != null && clipboardText != lastSentClipboard) {
            fileLog("Clipboard changed, sending to glasses: ${clipboardText.take(50)}...")
            lastSentClipboard = clipboardText
            rfcommServer?.sendClipboardUpdate(clipboardText)
            
            // DEBUG: Show feedback (commented out for minimalist design)
            // runOnUiThread {
            //     coordsText.text = "📋 Clipboard sent"
            // }
        }
    }
    
    /**
     * Send current clipboard to glasses (called on demand, e.g., when text field is focused).
     * This works around Android's clipboard access restrictions when app is in background.
     */
    private fun sendCurrentClipboardToGlasses() {
        if (!isGlassesConnected) return
        
        val clipboardText = getClipboardText()
        if (clipboardText != null) {
            // Always send when requested, even if same as last sent
            fileLog("Sending current clipboard on request: ${clipboardText.take(50)}...")
            lastSentClipboard = clipboardText
            rfcommServer?.sendClipboardUpdate(clipboardText)
        }
    }
    
    /**
     * Get current clipboard text content.
     */
    private fun getClipboardText(): String? {
        return try {
            val clip = clipboardManager?.primaryClip
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
    
    private fun hasPermissions(): Boolean {
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
        return bluetooth
    }
    
    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS)
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    rfcommServer?.start()
                    checkLocationPermission()
                }
            }

            REQUEST_WIFI_DIRECT_PERMISSIONS -> {
                // WiFi Direct permissions - initialize even if denied (will just not work as fallback)
                initializeWifiDirect()
            }

            REQUEST_BACKGROUND_LOCATION -> {
                checkLocationPermission()
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    fileLog("Background location permission granted")
                } else {
                    fileLog("Background location permission denied - location updates may not work in background")
                }
            }

            REQUEST_IMAGE_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    fileLog("Image permission granted, opening picker")
                    openImagePicker()
                } else {
                    fileLog("Image permission denied")
                    Toast.makeText(this, "Permission required to select images", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Request background location permission for Android 10+.
     * This must be requested AFTER foreground location is already granted.
     */
    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return // Not needed before Android 10
        }

        val foregroundGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val backgroundGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (foregroundGranted && !backgroundGranted) {
            fileLog("Requesting background location permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_BACKGROUND_LOCATION
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationWeatherService(forceRefresh: Boolean = false) {
        if (!hasLocationPermission()) return
        LocationWeatherService.start(this, forceRefresh = forceRefresh)
    }

    private fun stopLocationWeatherService() {
        LocationWeatherService.stop(this)
    }

    private fun resolveTownAndCountryCode(location: Location): Pair<String, String> {
        val lat = location.latitude
        val lon = location.longitude

        // 1) Prefer geocode.maps.co (often returns more precise "town"/"village" like Ely)
        try {
            val apiKey = URLEncoder.encode(GEOCODE_MAPS_CO_API_KEY, "UTF-8")
            val url = "https://geocode.maps.co/reverse?lat=$lat&lon=$lon&api_key=$apiKey"
            val response = URL(url).readText()
            val json = JSONObject(response)
            val address = json.optJSONObject("address")

            if (address != null) {
                val town = listOf("city", "town", "village", "municipality")
                    .asSequence()
                    .mapNotNull { key -> address.optString(key, null)?.takeIf { it.isNotBlank() } }
                    .firstOrNull()
                    ?: "Unknown"

                val countryCode = address.optString("country_code", null)
                    ?.takeIf { it.isNotBlank() }
                    ?.uppercase(Locale.ROOT)
                    ?: "Unknown"

                if (town != "Unknown" && countryCode != "Unknown") {
                    return Pair(town, countryCode)
                }
            }
        } catch (e: Exception) {
            fileLog("maps.co geocoding failed: ${e.message}")
        }

        // 2) Fallback: Android Geocoder (still send ISO country code, not localized name)
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)

            if (!addresses.isNullOrEmpty()) {
                val a = addresses[0]

                val town = a.locality
                    ?: a.subLocality
                    ?: a.subAdminArea
                    ?: a.adminArea
                    ?: "Unknown"

                val countryCode = (a.countryCode ?: "Unknown").uppercase(Locale.ROOT)

                Pair(town, countryCode)
            } else {
                Pair("Unknown", "Unknown")
            }
        } catch (e: Exception) {
            fileLog("Android Geocoder failed: ${e.message}")
            Pair("Unknown", "Unknown")
        }
    }


    private fun checkLocationPermission() {
        if (!hasLocationPermission()) {
            return
        }

        lastLocation = locationManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ?: locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }



    private fun updateLocationAndWeather() {
        // Prefer LocationWeatherService when it's running - it has the authoritative data
        val service = LocationWeatherService.instance
        if (service != null) {
            val data = service.getCurrentLocationData()
            if (data != null) {
                rfcommServer?.sendLocationUpdate(data.town, data.countryCode, data.weather, data.timestamp, data.speedMps)
                fileLog("Sent location from LocationWeatherService: ${data.town}, timestamp=${data.timestamp}")
                return
            }
            // Service running but no data yet - trigger a fetch
            service.forceUpdate()
            return
        }

        // Fallback: use MainActivity's own location data (when service not running)
        checkLocationPermission()
        if (lastLocation != null) {
            fetchWeatherAndSend(lastLocation!!)
        }
    }

    private fun fetchWeatherAndSend(location: Location) {
        if (!isGlassesConnected) return

        Thread {
            // Prefer maps.co for a more precise town (e.g., Ely), fallback to Android Geocoder
            val (town, countryCode) = resolveTownAndCountryCode(location)

            rfcommServer?.updateTownCountry(town, countryCode)

            // Fetch rich weather and send rich payload with current timestamp
            WeatherHelper.fetchWeather(location.latitude, location.longitude) { weather ->
                rfcommServer?.updateWeather(weather)

                // IMPORTANT: Send ISO country code instead of localized country name
                // (JSON field name remains "country", value is now "GB", "FR", etc.)
                // Include current timestamp to ensure glasses can validate data freshness
                val speedMps = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else null
                rfcommServer?.sendLocationUpdate(town, countryCode, weather, System.currentTimeMillis(), speedMps)
            }
        }.start()
    }

    /**
     * Update the status display with glasses connection and battery.
     * Shows glasses icon (normal or dashed) + battery % (or --- when not connected)
     */
    private fun updateStatusDisplay() {
        // Update glasses icon based on connection status
        if (isGlassesConnected) {
            glassesIcon.setImageResource(R.drawable.glasses_connected)
        } else {
            glassesIcon.setImageResource(R.drawable.glasses_disconnected)
        }
        
        // Update battery display
        val batteryDisplay = if (isGlassesConnected && glassesBatteryPercent != null) {
            "${glassesBatteryPercent}%"
        } else {
            "---"
        }
        statusText.text = batteryDisplay
    }
}
