package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.renderscript.*
import android.util.Log
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A widget that displays mirrored screen content from the phone.
 * Can be toggled to fullscreen mode.
 * 
 * Refactored to extend BaseWidget for common functionality.
 */
class ScreenMirrorWidget(
    private val context: Context,
    x: Float,
    y: Float,
    widgetWidth: Float,
    widgetHeight: Float
) : BaseWidget(x, y, widgetWidth, widgetHeight) {
    companion object {
        private const val TAG = "ScreenMirrorWidget"
        
        private const val MIN_WIDTH = 120f
        private const val MIN_HEIGHT = 80f
        private const val STREAM_PORT = 5050
        private const val RECONNECT_DELAY_MS = 1000L
        
        private const val TARGET_DISPLAY_FPS = 15
        private const val MIN_FRAME_INTERVAL_MS = 1000L / TARGET_DISPLAY_FPS
        private const val CONTENT_BOUNDS_RECHECK_MS = 5000L
    }
    
    enum class State { IDLE, HOVER_BORDER, MOVING }
    enum class HitArea { NONE, CONTENT, BORDER, FULLSCREEN_BUTTON, PIN_BUTTON, MINIMIZE_BUTTON, RESIZE_HANDLE, RUN_PAUSE_BUTTON }
    enum class StreamState { DISABLED, DISCONNECTED, CONNECTING, STREAMING, ERROR }
    
    var state = State.IDLE
    
    // Label for minimized state
    override val minimizeLabel: String = "SM"
    
    var streamState = StreamState.DISABLED
        private set
    
    private val lastError = AtomicReference<String?>(null)
    private val errorTimestamp = AtomicLong(0)
    
    private var mirroringEnabled = false
    private var phoneIpAddress: String? = null
    
    private var streamSocket: Socket? = null
    private var inputStream: InputStream? = null
    private val isStreaming = AtomicBoolean(false)
    private var streamThread: Thread? = null
    
    private var decoder: MediaCodec? = null
    private var frameBitmap: Bitmap? = null
    private val frameLock = Object()
    
    private var sourceWidth = 480
    private var sourceHeight = 854
    val binocularSourceId: String = "screen-mirror-widget"
    
    private var renderScript: RenderScript? = null
    private var yuvToRgbScript: ScriptIntrinsicYuvToRGB? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null
    private var rsOutputBitmap: Bitmap? = null
    private var useRenderScript = true
    
    private var detectedContentRect: Rect? = null
    private val BLACK_THRESHOLD = 20
    private var lastContentBoundsDetectionMs = 0L
    
    private var nv21Buffer: ByteArray? = null
    private var argbPixels: IntArray? = null
    
    private var lastDisplayTime = 0L
    private var frameCount = 0
    private var convertedFrames = 0
    private var skippedFrames = 0
    
    var onStateChanged: ((State) -> Unit)? = null
    var onStreamStateChanged: ((StreamState) -> Unit)? = null
    var onFrameReady: (() -> Unit)? = null
    var onMirrorRequest: ((Boolean) -> Unit)? = null
    var onMediaStateChanged: ((Boolean) -> Unit)? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var isHostPaused = false
    private var isDisplayVisible = true
    private var lastReportedMediaActive = false
    
    private val streamingBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
        color = Color.parseColor("#44AA44")
    }
    private val errorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
        color = Color.parseColor("#AA4444")
    }
    private val errorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6666")
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    // Run/Pause button paints
    private val runButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44AA66") // Green
    }
    private val stopButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F") // Red
    }
    private val runIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stopIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val runPauseButtonBounds = RectF()
    
    init {
        // minWidth/minHeight are open properties in BaseWidget, override default values here or use constants
        // Note: BaseWidget uses 64x64, ScreenMirrorWidget prefers larger min size.
        // We can override the properties if needed, or just rely on drag logic clamping.
        // BaseWidget's onDrag uses minWidth/minHeight property.
        
        initRenderScript()
        updateBaseBounds()
        isPinned = true
    }
    
    // Override min dimensions from BaseWidget
    override val minWidth = MIN_WIDTH
    override val minHeight = MIN_HEIGHT
    
    private fun initRenderScript() {
        try {
            renderScript = RenderScript.create(context)
            yuvToRgbScript = ScriptIntrinsicYuvToRGB.create(renderScript, Element.U8_4(renderScript))
            useRenderScript = true
            Log.d(TAG, "RenderScript initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript init failed: ${e.message}", e)
            useRenderScript = false
        }
    }
    
    private fun detectContentBounds(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = 4
        
        var left = 0
        var top = 0
        var right = width
        var bottom = height
        
        // Detect top black bar
        outer@ for (y in 0 until height / 2 step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                if (!isBlackPixel(pixel)) {
                    top = maxOf(0, y - sampleStep)
                    break@outer
                }
            }
        }
        
        // Detect bottom black bar
        outer@ for (y in height - 1 downTo height / 2 step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                if (!isBlackPixel(pixel)) {
                    bottom = minOf(height, y + sampleStep)
                    break@outer
                }
            }
        }
        
        // Detect left black bar
        outer@ for (x in 0 until width / 2 step sampleStep) {
            for (y in top until bottom step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                if (!isBlackPixel(pixel)) {
                    left = maxOf(0, x - sampleStep)
                    break@outer
                }
            }
        }
        
        // Detect right black bar
        outer@ for (x in width - 1 downTo width / 2 step sampleStep) {
            for (y in top until bottom step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                if (!isBlackPixel(pixel)) {
                    right = minOf(width, x + sampleStep)
                    break@outer
                }
            }
        }
        
        if (right - left < width / 4 || bottom - top < height / 4) {
            return Rect(0, 0, width, height)
        }
        
        return Rect(left, top, right, bottom)
    }
    
    private fun isBlackPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return r < BLACK_THRESHOLD && g < BLACK_THRESHOLD && b < BLACK_THRESHOLD
    }
    
    // BaseWidget handles resizeHandleBounds updates in updateCommonBounds

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        
        // Update run/pause button bounds (to the right of pin button)
        runPauseButtonBounds.set(
            pinButtonBounds.right + BUTTON_SPACING,
            pinButtonBounds.top,
            pinButtonBounds.right + BUTTON_SPACING + PIN_BUTTON_SIZE,
            pinButtonBounds.bottom
        )
    }
    
    private fun setError(message: String?) {
        lastError.set(message)
        errorTimestamp.set(System.currentTimeMillis())
        if (message != null) Log.e(TAG, "Error: $message")
    }
    
    private fun clearError() { lastError.set(null) }

    private fun resetDetectedContentBounds() {
        detectedContentRect = null
        lastContentBoundsDetectionMs = 0L
    }

    private fun isRegionMostlyBlack(bitmap: Bitmap, rect: Rect): Boolean {
        if (rect.isEmpty) return true

        val sampleXs = intArrayOf(
            rect.left + rect.width() / 4,
            rect.centerX(),
            rect.right - rect.width() / 4 - 1
        ).map { it.coerceIn(0, bitmap.width - 1) }

        val sampleYs = intArrayOf(
            rect.top + rect.height() / 4,
            rect.centerY(),
            rect.bottom - rect.height() / 4 - 1
        ).map { it.coerceIn(0, bitmap.height - 1) }

        var blackSamples = 0
        var totalSamples = 0

        for (sampleY in sampleYs) {
            for (sampleX in sampleXs) {
                totalSamples++
                if (isBlackPixel(bitmap.getPixel(sampleX, sampleY))) {
                    blackSamples++
                }
            }
        }

        return blackSamples >= totalSamples - 1
    }

    private fun hasNonBlackContentBelow(
        bitmap: Bitmap,
        contentRect: Rect,
        displayedRectBottom: Int
    ): Boolean {
        if (displayedRectBottom >= contentRect.bottom - 4) return false

        val probeTop = (displayedRectBottom + (contentRect.bottom - displayedRectBottom) / 4)
            .coerceIn(0, bitmap.height - 1)
        val probeBottom = (contentRect.bottom - (contentRect.bottom - displayedRectBottom) / 4)
            .coerceIn(0, bitmap.height - 1)
        val probeCenter = ((probeTop + probeBottom) / 2).coerceIn(0, bitmap.height - 1)

        val sampleYs = intArrayOf(probeTop, probeCenter, probeBottom)
        val sampleXs = intArrayOf(
            contentRect.left + contentRect.width() / 4,
            contentRect.centerX(),
            contentRect.right - contentRect.width() / 4 - 1
        ).map { it.coerceIn(0, bitmap.width - 1) }

        for (sampleY in sampleYs) {
            for (sampleX in sampleXs) {
                if (!isBlackPixel(bitmap.getPixel(sampleX, sampleY))) {
                    return true
                }
            }
        }

        return false
    }

    private fun shouldRecoverDisplayedCrop(bitmap: Bitmap, contentRect: Rect): Boolean {
        if (isFullscreen) return false

        val contentWidth = contentRect.width().toFloat()
        val contentHeight = contentRect.height().toFloat()
        if (contentWidth <= 0f || contentHeight <= 0f) return false

        val scale = contentBounds.width() / contentWidth
        val scaledHeight = contentHeight * scale
        if (scaledHeight <= contentBounds.height() + 1f) return false

        val visibleHeight = minOf(scaledHeight, contentBounds.height())
        val srcHeight = (visibleHeight / scale).toInt().coerceIn(1, contentRect.height())
        val displayedSourceRect = Rect(
            contentRect.left,
            contentRect.top,
            contentRect.right,
            contentRect.top + srcHeight
        )

        return isRegionMostlyBlack(bitmap, displayedSourceRect) &&
            hasNonBlackContentBelow(bitmap, contentRect, displayedSourceRect.bottom)
    }

    private fun updateContentBoundsIfNeeded(bitmap: Bitmap, now: Long): Rect {
        val needsTimedRefresh = detectedContentRect == null ||
            now - lastContentBoundsDetectionMs >= CONTENT_BOUNDS_RECHECK_MS

        val currentRect = detectedContentRect ?: Rect(0, 0, bitmap.width, bitmap.height)
        val needsRecoveryRefresh = detectedContentRect != null &&
            shouldRecoverDisplayedCrop(bitmap, currentRect)

        if (needsTimedRefresh || needsRecoveryRefresh) {
            detectedContentRect = detectContentBounds(bitmap)
            lastContentBoundsDetectionMs = now
            Log.d(
                TAG,
                "Detected content: ${detectedContentRect}" +
                    if (needsRecoveryRefresh) " (recovered stale crop)" else ""
            )
        }

        return detectedContentRect ?: Rect(0, 0, bitmap.width, bitmap.height)
    }

    fun isBinocularMediaActive(): Boolean {
        return !isMinimized &&
            isDisplayVisible &&
            !isHostPaused &&
            streamState == StreamState.STREAMING
    }

    fun shouldApplyMediaBrightnessCap(): Boolean = mirroringEnabled

    private fun dispatchMediaStateIfChanged() {
        val isActive = isBinocularMediaActive()
        if (lastReportedMediaActive == isActive) return
        lastReportedMediaActive = isActive
        handler.post { onMediaStateChanged?.invoke(isActive) }
    }
    
    fun setMirroringEnabled(enabled: Boolean, phoneIp: String? = null) {
        Log.d(TAG, "setMirroringEnabled: $enabled, ip=$phoneIp")
        mirroringEnabled = enabled
        phoneIpAddress = phoneIp
        clearError()
        
        if (enabled && phoneIp != null) {
            startStreaming(phoneIp)
        } else {
            stopStreaming()
            updateStreamState(StreamState.DISABLED)
        }
        dispatchMediaStateIfChanged()
    }
    
    override fun isHovering(): Boolean =
        isBorderHovered ||
            state == State.MOVING ||
            baseState == BaseState.HOVER_RESIZE ||
            baseState == BaseState.RESIZING

    // Use unified border hover from BaseWidget - include run/pause button as a border button
    override fun isPointOverBorderButton(px: Float, py: Float): Boolean {
        if (super.isPointOverBorderButton(px, py)) return true
        // Run/pause button is part of border
        if (runPauseButtonBounds.contains(px, py)) return true
        return false
    }
    
    fun hitTest(px: Float, py: Float): HitArea {
        // Check base widget buttons first (close button is handled by baseHitTest)
        val baseResult = baseHitTest(px, py)
        when (baseResult) {
            BaseHitArea.CLOSE_BUTTON -> return HitArea.NONE  // Handled via onCloseRequested callback
            BaseHitArea.FULLSCREEN_BUTTON -> return HitArea.FULLSCREEN_BUTTON
            BaseHitArea.MINIMIZE_BUTTON -> return HitArea.MINIMIZE_BUTTON
            BaseHitArea.PIN_BUTTON -> return HitArea.PIN_BUTTON
            BaseHitArea.RESIZE_HANDLE -> return HitArea.RESIZE_HANDLE
            else -> {}
        }

        // ScreenMirrorWidget-specific: run/pause button
        if (runPauseButtonBounds.contains(px, py)) return HitArea.RUN_PAUSE_BUTTON

        if (contentBounds.contains(px, py)) return HitArea.CONTENT

        val expandedBounds = RectF(
            widgetBounds.left - BaseWidget.BORDER_HIT_AREA,
            widgetBounds.top - BaseWidget.BORDER_HIT_AREA,
            widgetBounds.right + BaseWidget.BORDER_HIT_AREA,
            widgetBounds.bottom + BaseWidget.BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) return HitArea.BORDER
        return HitArea.NONE
    }

    override fun updateHover(px: Float, py: Float) {
        if (state == State.MOVING) return

        // Use unified hover state from BaseWidget
        val baseResult = updateHoverState(px, py)

        // Map base state to local state
        // Note: ScreenMirror doesn't use HOVER_CONTENT, so content becomes IDLE
        val newState = when (baseResult) {
            BaseState.HOVER_RESIZE, BaseState.RESIZING -> State.HOVER_BORDER
            BaseState.HOVER_BORDER -> State.HOVER_BORDER
            BaseState.MOVING -> State.MOVING
            else -> State.IDLE
        }

        if (newState != state) {
            Log.d(TAG, "State changed: $state -> $newState")
            state = newState
            onStateChanged?.invoke(state)
        }
    }
    
    fun onTap(px: Float, py: Float): Boolean {
        // Check close button first (handled by base class)
        if (closeButtonBounds.contains(px, py)) {
            onCloseRequested?.invoke()
            return true
        }

        val hitArea = hitTest(px, py)
        Log.d(TAG, "onTap at ($px, $py) -> hitArea=$hitArea, currentState=$state")

        return when (hitArea) {
            HitArea.FULLSCREEN_BUTTON -> {
                Log.d(TAG, "Fullscreen button tapped")
                toggleFullscreen()
                true
            }
            HitArea.MINIMIZE_BUTTON -> {
                toggleMinimize()
                true
            }
            HitArea.PIN_BUTTON -> {
                isPinned = !isPinned
                Log.d(TAG, "Pin toggled: $isPinned")
                true
            }
            HitArea.RUN_PAUSE_BUTTON -> {
                val shouldStart = streamState != StreamState.STREAMING && streamState != StreamState.CONNECTING
                Log.d(TAG, "Run/Pause button tapped. Requesting start=$shouldStart")
                onMirrorRequest?.invoke(shouldStart)
                true
            }
            HitArea.BORDER -> {
                if (!isFullscreen) {
                    Log.d(TAG, "Border tapped - starting move")
                    state = State.MOVING
                    baseState = BaseState.MOVING
                    onStateChanged?.invoke(state)
                }
                true
            }
            HitArea.RESIZE_HANDLE -> {
                // Resize started via drag
                true
            }
            HitArea.CONTENT -> true
            HitArea.NONE -> false
        }
    }
    
    override fun startDrag(isResize: Boolean) {
        super.startDrag(isResize)
        state = if (isResize) {
            // ScreenMirrorWidget doesn't have RESIZING state in its enum, so keep it IDLE or map it if we add RESIZING
            // But baseState will be RESIZING, so onDrag will handle it.
            // Let's assume we don't need to change local state for resizing if it's not in the enum
            // Or better: update enum. But I cannot change enum easily without breaking other things.
            // Wait, BaseWidget handles resizing.
            // ScreenMirrorWidget.State has IDLE, HOVER_BORDER, MOVING.
            // So if resizing, maybe we should just keep it as is, or maybe IDLE?
            // If I set MOVING, it might look like dragging.
            State.IDLE
        } else {
            State.MOVING
        }
        onStateChanged?.invoke(state)
    }

    override fun onDragEnd() {
        super.onDragEnd()
        if (state == State.MOVING) {
            state = State.IDLE
            baseState = BaseState.IDLE
            onStateChanged?.invoke(state)
        }
    }
    
    override fun containsPoint(px: Float, py: Float): Boolean {
        if (fullscreenButtonBounds.contains(px, py)) return true
        if (minimizeButtonBounds.contains(px, py)) return true
        if (pinButtonBounds.contains(px, py)) return true
        if (runPauseButtonBounds.contains(px, py)) return true
        
        // Check resize handle (not in fullscreen or minimized)
        if (!isFullscreen && !isMinimized && resizeHandleBounds.contains(px, py)) {
            return true
        }
        
        return super.containsPoint(px, py)
    }
    
    private fun updateStreamState(newState: StreamState) {
        if (streamState != newState) {
            streamState = newState
            handler.post { onStreamStateChanged?.invoke(newState) }
            dispatchMediaStateIfChanged()
        }
    }
    
    private fun startStreaming(ip: String) {
        if (isStreaming.get()) return
        
        isStreaming.set(true)
        updateStreamState(StreamState.CONNECTING)
        clearError()
        frameCount = 0
        skippedFrames = 0
        convertedFrames = 0
        lastDisplayTime = 0L
        resetDetectedContentBounds()
        
        streamThread = Thread {
            while (isStreaming.get()) {
                try {
                    Log.d(TAG, "Connecting to $ip:$STREAM_PORT")
                    streamSocket = Socket(ip, STREAM_PORT).apply {
                        soTimeout = 5000
                        tcpNoDelay = true
                        receiveBufferSize = 65536
                    }
                    inputStream = streamSocket?.getInputStream()
                    
                    Log.d(TAG, "Connected to stream server")
                    updateStreamState(StreamState.STREAMING)
                    clearError()
                    
                    initDecoder()
                    receiveFrames()
                    
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Stream thread interrupted")
                    break
                } catch (e: Exception) {
                    if (isStreaming.get()) {
                        Log.e(TAG, "Connection error: ${e.message}", e)
                        setError("Connection: ${e.message}")
                        updateStreamState(StreamState.ERROR)
                    }
                }
                
                releaseDecoder()
                try { streamSocket?.close() } catch (e: Exception) {}
                streamSocket = null
                inputStream = null
                
                if (isStreaming.get()) {
                    clearError()
                    updateStreamState(StreamState.CONNECTING)
                    try { Thread.sleep(RECONNECT_DELAY_MS) } catch (e: InterruptedException) { break }
                }
            }
            
            updateStreamState(if (mirroringEnabled) StreamState.DISCONNECTED else StreamState.DISABLED)
            Log.d(TAG, "Stream ended. Received:$frameCount Converted:$convertedFrames Skipped:$skippedFrames")
        }.apply {
            name = "ScreenMirrorStream"
            start()
        }
    }
    
    private fun stopStreaming() {
        isStreaming.set(false)
        try { streamSocket?.close() } catch (e: Exception) {}
        streamSocket = null
        inputStream = null
        streamThread?.interrupt()
        streamThread = null
        releaseDecoder()
        releaseRenderScriptBuffers()
        synchronized(frameLock) {
            frameBitmap?.recycle()
            frameBitmap = null
        }
        dispatchMediaStateIfChanged()
    }
    
    private fun releaseRenderScriptBuffers() {
        try {
            inputAllocation?.destroy()
            outputAllocation?.destroy()
            rsOutputBitmap?.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing RS buffers", e)
        }
        inputAllocation = null
        outputAllocation = null
        rsOutputBitmap = null
    }
    
    private fun initDecoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sourceWidth, sourceHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, 0)
                start()
            }
            Log.d(TAG, "Decoder initialized, useRenderScript=$useRenderScript")
        } catch (e: Exception) {
            Log.e(TAG, "Decoder init failed: ${e.message}", e)
            setError("Decoder: ${e.message}")
        }
    }
    
    private fun releaseDecoder() {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        }
        decoder = null
    }
    
    private fun receiveFrames() {
        val input = inputStream ?: return
        
        val headerBuffer = ByteArray(8)
        Log.d(TAG, "Starting stream reception")
        
        while (isStreaming.get()) {
            try {
                var bytesRead = 0
                while (bytesRead < 8) {
                    val read = input.read(headerBuffer, bytesRead, 8 - bytesRead)
                    if (read == -1) throw Exception("Stream ended (EOF)")
                    bytesRead += read
                }
                
                val frameLength = ((headerBuffer[0].toInt() and 0xFF) shl 24) or
                                 ((headerBuffer[1].toInt() and 0xFF) shl 16) or
                                 ((headerBuffer[2].toInt() and 0xFF) shl 8) or
                                 (headerBuffer[3].toInt() and 0xFF)
                
                val flags = ((headerBuffer[4].toInt() and 0xFF) shl 24) or
                           ((headerBuffer[5].toInt() and 0xFF) shl 16) or
                           ((headerBuffer[6].toInt() and 0xFF) shl 8) or
                           (headerBuffer[7].toInt() and 0xFF)
                
                if (frameLength <= 0 || frameLength > 1024 * 1024) {
                    Log.w(TAG, "Invalid frame length: $frameLength")
                    continue
                }
                
                val frameData = ByteArray(frameLength)
                bytesRead = 0
                while (bytesRead < frameLength) {
                    val read = input.read(frameData, bytesRead, frameLength - bytesRead)
                    if (read == -1) throw Exception("Stream ended during frame read")
                    bytesRead += read
                }
                
                frameCount++
                decodeFrame(frameData, flags)
                
                // if (frameCount % 60 == 0) {
                //     Log.d(TAG, "Stats: recv=$frameCount conv=$convertedFrames skip=$skippedFrames")
                // }
                
            } catch (e: SocketTimeoutException) {
                // Log.d(TAG, "Socket timeout, continuing...")
            } catch (e: Exception) {
                if (isStreaming.get()) {
                    Log.e(TAG, "Receive error: ${e.message}", e)
                    setError("Receive: ${e.message}")
                }
                break
            }
        }
    }
    
    private fun decodeFrame(data: ByteArray, flags: Int) {
        val dec = decoder ?: return
        
        try {
            val inputIndex = dec.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer = dec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                dec.queueInputBuffer(inputIndex, 0, data.size, 0, flags)
            } else {
                return
            }
            
            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
            
            while (outputIndex >= 0) {
                val now = System.currentTimeMillis()
                val timeSinceLastDisplay = now - lastDisplayTime
                
                val shouldProcess = timeSinceLastDisplay >= MIN_FRAME_INTERVAL_MS
                
                if (shouldProcess && bufferInfo.size > 0) {
                    try {
                        val image = dec.getOutputImage(outputIndex)
                        if (image != null) {
                            convertImageToBitmap(image)
                            image.close()
                            lastDisplayTime = now
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Image error: ${e.message}")
                    }
                } else if (!shouldProcess) {
                    skippedFrames++
                }
                
                dec.releaseOutputBuffer(outputIndex, false)
                outputIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
            }
            
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = dec.outputFormat
                sourceWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                sourceHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                Log.d(TAG, "Format changed: ${sourceWidth}x${sourceHeight}")
                releaseRenderScriptBuffers()
                resetDetectedContentBounds()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
        }
    }
    
    private fun convertImageToBitmap(image: Image) {
        val width = image.width
        val height = image.height
        
        if (width <= 0 || height <= 0) return
        
        try {
            if (useRenderScript && renderScript != null) {
                convertWithRenderScript(image, width, height)
            } else {
                convertWithSoftware(image, width, height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Convert error: ${e.message}", e)
            if (useRenderScript) {
                Log.w(TAG, "RenderScript failed, falling back to software")
                useRenderScript = false
                try {
                    convertWithSoftware(image, width, height)
                } catch (e2: Exception) {
                    Log.e(TAG, "Software fallback failed: ${e2.message}")
                }
            }
        }
    }
    
    private fun convertWithRenderScript(image: Image, width: Int, height: Int) {
        val rs = renderScript ?: throw IllegalStateException("RenderScript null")
        val script = yuvToRgbScript ?: throw IllegalStateException("Script null")
        
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        
        val nv21Size = width * height * 3 / 2
        if (nv21Buffer == null || nv21Buffer!!.size != nv21Size) {
            nv21Buffer = ByteArray(nv21Size)
        }
        val nv21 = nv21Buffer!!
        
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }
        yBuffer.rewind()
        
        var uvIndex = width * height
        if (uvPixelStride == 2 && uvRowStride == width) {
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val srcOffset = row * uvRowStride + col * 2
                    if (srcOffset + 1 < uBuffer.limit()) {
                        nv21[uvIndex++] = vBuffer.get(srcOffset)
                        nv21[uvIndex++] = uBuffer.get(srcOffset)
                    }
                }
            }
        } else {
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val uvOffset = row * uvRowStride + col * uvPixelStride
                    if (uvOffset < uBuffer.limit() && uvOffset < vBuffer.limit()) {
                        nv21[uvIndex++] = vBuffer.get(uvOffset)
                        nv21[uvIndex++] = uBuffer.get(uvOffset)
                    }
                }
            }
        }
        uBuffer.rewind()
        vBuffer.rewind()
        
        if (inputAllocation == null) {
            val inputType = Type.Builder(rs, Element.U8(rs)).setX(nv21Size).create()
            inputAllocation = Allocation.createTyped(rs, inputType, Allocation.USAGE_SCRIPT)
        }
        
        if (outputAllocation == null || rsOutputBitmap == null ||
            rsOutputBitmap!!.width != width || rsOutputBitmap!!.height != height) {
            outputAllocation?.destroy()
            rsOutputBitmap?.recycle()
            rsOutputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outputAllocation = Allocation.createFromBitmap(rs, rsOutputBitmap)
        }
        
        inputAllocation!!.copyFrom(nv21)
        script.setInput(inputAllocation)
        script.forEach(outputAllocation)
        outputAllocation!!.copyTo(rsOutputBitmap)
        
        synchronized(frameLock) {
            if (frameBitmap == null || frameBitmap!!.width != width || frameBitmap!!.height != height) {
                frameBitmap?.recycle()
                frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val canvas = Canvas(frameBitmap!!)
            canvas.drawBitmap(rsOutputBitmap!!, 0f, 0f, null)
        }
        
        convertedFrames++
        handler.post { onFrameReady?.invoke() }
    }
    
    private fun convertWithSoftware(image: Image, width: Int, height: Int) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        
        val pixelCount = width * height
        if (argbPixels == null || argbPixels!!.size != pixelCount) {
            argbPixels = IntArray(pixelCount)
        }
        val pixels = argbPixels!!
        
        val yLimit = yBuffer.limit()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()
        
        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIndex = row * yRowStride + col
                val uvRow = row / 2
                val uvCol = col / 2
                val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride
                
                if (yIndex >= yLimit || uvIndex >= uLimit || uvIndex >= vLimit) {
                    pixels[row * width + col] = 0xFF000000.toInt()
                    continue
                }
                
                val y = yBuffer.get(yIndex).toInt() and 0xFF
                val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                
                var r = y + ((359 * v) shr 8)
                var g = y - ((88 * u + 183 * v) shr 8)
                var b = y + ((454 * u) shr 8)
                
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                
                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        synchronized(frameLock) {
            frameBitmap?.recycle()
            frameBitmap = bitmap
        }
        
        convertedFrames++
        handler.post { onFrameReady?.invoke() }
    }
    
    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Show the shell while streaming or when hover chrome is active.
        if (streamState == StreamState.STREAMING || shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)
        }
        
        synchronized(frameLock) {
            frameBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    try {
                        val now = System.currentTimeMillis()
                        val contentRect = updateContentBoundsIfNeeded(bitmap, now)
                        val contentWidth = contentRect.width().toFloat()
                        val contentHeight = contentRect.height().toFloat()
                        
                        if (isFullscreen) {
                            val srcAspect = contentWidth / contentHeight
                            val dstAspect = contentBounds.width() / contentBounds.height()
                            
                            val dstRect = if (srcAspect > dstAspect) {
                                val scaledHeight = contentBounds.width() / srcAspect
                                val yOffset = (contentBounds.height() - scaledHeight) / 2f
                                RectF(
                                    contentBounds.left,
                                    contentBounds.top + yOffset,
                                    contentBounds.right,
                                    contentBounds.top + yOffset + scaledHeight
                                )
                            } else {
                                val scaledWidth = contentBounds.height() * srcAspect
                                val xOffset = (contentBounds.width() - scaledWidth) / 2f
                                RectF(
                                    contentBounds.left + xOffset,
                                    contentBounds.top,
                                    contentBounds.left + xOffset + scaledWidth,
                                    contentBounds.bottom
                                )
                            }
                            
                            canvas.drawBitmap(bitmap, contentRect, dstRect, bitmapPaint)
                        } else {
                            val scale = contentBounds.width() / contentWidth
                            val scaledHeight = contentHeight * scale
                            
                            val dstRect = RectF(
                                contentBounds.left,
                                contentBounds.top,
                                contentBounds.right,
                                contentBounds.top + minOf(scaledHeight, contentBounds.height())
                            )
                            
                            val visibleHeight = minOf(scaledHeight, contentBounds.height())
                            val srcHeight = (visibleHeight / scale).toInt()
                            val srcRect = Rect(
                                contentRect.left,
                                contentRect.top,
                                contentRect.right,
                                contentRect.top + minOf(srcHeight, contentRect.height())
                            )
                            
                            canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Draw error: ${e.message}")
                    }
                }
            }
        }
        
        lastError.get()?.let { error ->
            if (isHovering()) {
                val displayError = if (error.length > 40) error.take(37) + "..." else error
                canvas.drawText(displayError, widgetBounds.centerX(), widgetBounds.bottom - 30f, errorTextPaint)
            }
        }
        
        if (streamState == StreamState.CONNECTING) {
            val connectingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFAA00")
                textSize = 20f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Reconnecting...", widgetBounds.centerX(), widgetBounds.centerY(), connectingPaint)
        }
        
        if (shouldShowBorder()) {
            val borderP = when {
                streamState == StreamState.ERROR -> errorBorderPaint
                else -> hoverBorderPaint
            }
            canvas.drawRoundRect(widgetBounds, 8f, 8f, borderP)
        }
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawRunPauseButton(canvas)
            drawResizeHandle(canvas)
        }
    }
    
    private fun drawRunPauseButton(canvas: Canvas) {
        val cx = runPauseButtonBounds.centerX()
        val cy = runPauseButtonBounds.centerY()
        val iconSize = PIN_BUTTON_SIZE * 0.35f
        
        val isRunning = streamState == StreamState.STREAMING || streamState == StreamState.CONNECTING

        if (isRunning) {
            // Draw "Stop" button (square) with red background
            canvas.drawOval(runPauseButtonBounds, stopButtonPaint)
            
            // Draw square icon
            canvas.drawRect(
                cx - iconSize,
                cy - iconSize,
                cx + iconSize,
                cy + iconSize,
                stopIconPaint
            )
        } else {
            // Draw "Run" button (triangle) with green background
            canvas.drawOval(runPauseButtonBounds, runButtonPaint)
            
            // Draw White Triangle
            val path = Path()
            path.moveTo(cx - iconSize * 0.6f, cy - iconSize)
            path.lineTo(cx + iconSize, cy)
            path.lineTo(cx - iconSize * 0.6f, cy + iconSize)
            path.close()
            
            canvas.drawPath(path, runIconPaint)
        }
    }

    fun release() {
        stopStreaming()
        lastReportedMediaActive = false
        handler.post { onMediaStateChanged?.invoke(false) }
        try {
            yuvToRgbScript?.destroy()
            renderScript?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing RenderScript", e)
        }
        yuvToRgbScript = null
        renderScript = null
    }

    override fun minimize() {
        super.minimize()
        dispatchMediaStateIfChanged()
    }

    override fun restore() {
        super.restore()
        dispatchMediaStateIfChanged()
    }

    override fun onDrag(dx: Float, dy: Float, screenWidth: Float, screenHeight: Float) {
        val previousWidth = widgetWidth
        val previousHeight = widgetHeight
        if (isFullscreen) return
        
        if (state == State.MOVING) {
            super.onDrag(dx, dy, screenWidth, screenHeight)
        } else if (baseState == BaseState.HOVER_RESIZE || baseState == BaseState.RESIZING) {
            super.onDrag(dx, dy, screenWidth, screenHeight)
        }

        if (previousWidth != widgetWidth || previousHeight != widgetHeight) {
            resetDetectedContentBounds()
        }
    }

    fun onHostPause() {
        if (!isHostPaused) {
            isHostPaused = true
            dispatchMediaStateIfChanged()
        }
    }

    fun onHostResume() {
        if (isHostPaused) {
            isHostPaused = false
            dispatchMediaStateIfChanged()
        }
    }

    fun onDisplayVisibilityChanged(visible: Boolean) {
        if (isDisplayVisible == visible) return
        isDisplayVisible = visible
        dispatchMediaStateIfChanged()
    }
}
