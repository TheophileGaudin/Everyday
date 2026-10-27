@file:Suppress("DEPRECATION")
// RenderScript is intentionally retained until the mirror conversion
// is migrated and performance-tested on the glasses.
package com.everyday.everyday_glasses.widgets.phone_screen_mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.everyday.everyday_glasses.interaction.commands.AppCommandDefinition
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.WidgetCommand
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetMenu
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets._common.WidgetInteractionState
import com.everyday.everyday_glasses.widgets._common.WidgetTheme
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

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
) : BaseWidget(MirrorPlugin.TYPE, x, y, widgetWidth, widgetHeight) {
    companion object {
        private const val TAG = "ScreenMirrorWidget"

        private const val MIN_WIDTH = MIN_WIDGET_SIZE
        private const val MIN_HEIGHT = MIN_WIDGET_SIZE
        private const val STREAM_PORT = 5050
        private const val RECONNECT_DELAY_MS = 1000L
        private const val BLACK_THRESHOLD = 20

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
    private val frameLock = Any()

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

    // Where the last frame was drawn: the part of the frame shown ([shownFrameRect], in frame pixels)
    // and where on the widget ([shownWidgetRect]). A cursor point maps back through them to the phone.
    private val shownFrameRect = Rect()
    private val shownWidgetRect = RectF()
    private var shownFrameWidth = 0
    private var shownFrameHeight = 0

    /** True from a press on the shown phone screen to its release: the press is a touch on the phone. */
    private var phoneTouchActive = false
    /** Touches go back to the phone on the stream socket, written off the UI thread, in order. */
    private val inputWriter = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "MirrorInputWriter") }
private var lastContentBoundsDetectionMs = 0L

    private var nv21Buffer: ByteArray? = null
    private var argbPixels: IntArray? = null

    private var lastDisplayTime = 0L
    private var frameCount = 0
    private var convertedFrames = 0
    private var skippedFrames = 0

    var listener: MirrorWidgetListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isHostPaused = false
    private var isDisplayVisible = true
    private var lastReportedMediaActive = false

    private val errorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
        color = WidgetTheme.ColorValue.streamErrorBorder
    }
    private val errorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.ColorValue.streamErrorText
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val connectingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.ColorValue.warningText
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    // Run/Pause button paints
    private val runButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.ColorValue.pinnedButton
    }
    private val stopButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.ColorValue.stopButton
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
                val pixel = bitmap[x, y]
                if (!isBlackPixel(pixel)) {
                    top = maxOf(0, y - sampleStep)
                    break@outer
                }
            }
        }

        // Detect bottom black bar
        outer@ for (y in height - 1 downTo height / 2 step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap[x, y]
                if (!isBlackPixel(pixel)) {
                    bottom = minOf(height, y + sampleStep)
                    break@outer
                }
            }
        }

        // Detect left black bar
        outer@ for (x in 0 until width / 2 step sampleStep) {
            for (y in top until bottom step sampleStep) {
                val pixel = bitmap[x, y]
                if (!isBlackPixel(pixel)) {
                    left = maxOf(0, x - sampleStep)
                    break@outer
                }
            }
        }

        // Detect right black bar
        outer@ for (x in width - 1 downTo width / 2 step sampleStep) {
            for (y in top until bottom step sampleStep) {
                val pixel = bitmap[x, y]
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
                if (isBlackPixel(bitmap[sampleX, sampleY])) {
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
                if (!isBlackPixel(bitmap[sampleX, sampleY])) {
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
                "Detected content: $detectedContentRect" +
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

    override val requestsMediaBrightnessCap: Boolean get() = isBinocularMediaActive()

    /** Re-applies display visibility (minimized may have changed) and reports the change. */
    private fun notifyStateChanged() {
        refreshDisplayVisibility()
        notifyTransientChange()
    }

    private fun dispatchMediaStateIfChanged() {
        val isActive = isBinocularMediaActive()
        if (lastReportedMediaActive == isActive) return
        lastReportedMediaActive = isActive
        handler.post { listener?.onMediaStateChanged(isActive) }
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
            baseState == BaseState.MOVING ||
            baseState == BaseState.RESIZING ||
            baseState == BaseState.HOVER_RESIZE

    // Use unified border hover from BaseWidget - include run/pause button as a border button
    override fun isPointOverBorderButton(px: Float, py: Float): Boolean {
        if (super.isPointOverBorderButton(px, py)) return true
        // Run/pause button is part of border
        if (runPauseButtonBounds.contains(px, py)) return true
        return false
    }

    override fun isBorderHit(px: Float, py: Float): Boolean = hitTest(px, py) == HitArea.BORDER

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
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) return HitArea.BORDER
        return HitArea.NONE
    }

    override fun dragHitTest(px: Float, py: Float): DragHit = when (hitTest(px, py)) {
        HitArea.BORDER, HitArea.CONTENT -> DragHit.DRAG
        HitArea.RESIZE_HANDLE -> DragHit.RESIZE
        else -> DragHit.NONE
    }

    override fun updateHover(px: Float, py: Float) {
        if (baseState == BaseState.MOVING || baseState == BaseState.RESIZING) return

        // Use unified hover state from BaseWidget
        val baseResult = updateHoverState(px, py)

        // ScreenMirror doesn't use HOVER_CONTENT, so content becomes IDLE.
        val newState = WidgetInteractionState(baseResult).toChromeLocal(
            idle = State.IDLE,
            content = State.IDLE,
            border = State.HOVER_BORDER,
            moving = State.MOVING
        )

        if (newState != state) {
            Log.d(TAG, "State changed: $state -> $newState")
            state = newState
            notifyStateChanged()
        }
    }

    override fun onContentTap(px: Float, py: Float): Boolean {
        // A tap on the shown phone screen already reached the phone as a press and release.
        if (hitTest(px, py) == HitArea.CONTENT && toFrame(px, py) != null) return true
        if (hitTest(px, py) != HitArea.RUN_PAUSE_BUTTON) return false
        val shouldStart = streamState != StreamState.STREAMING && streamState != StreamState.CONNECTING
        Log.d(TAG, "Run/Pause button tapped. Requesting start=$shouldStart")
        listener?.requestMirror(shouldStart)
        return true
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
            handler.post { notifyTransientChange() }
            dispatchMediaStateIfChanged()
        }
    }

    // ==================== Controlling the phone ====================

    private fun rememberShownFrame(bitmap: Bitmap, frameRect: Rect, widgetRect: RectF) {
        shownFrameRect.set(frameRect)
        shownWidgetRect.set(widgetRect)
        shownFrameWidth = bitmap.width
        shownFrameHeight = bitmap.height
    }

    /** The widget point ([px], [py]) as a point of the video frame, in fractions of it; null off the shown screen. */
    private fun toFrame(px: Float, py: Float, clamp: Boolean = false): Pair<Float, Float>? {
        if (streamState != StreamState.STREAMING || shownWidgetRect.isEmpty || shownFrameWidth <= 0) return null
        if (!clamp && !shownWidgetRect.contains(px, py)) return null
        val x = px.coerceIn(shownWidgetRect.left, shownWidgetRect.right)
        val y = py.coerceIn(shownWidgetRect.top, shownWidgetRect.bottom)
        val frameX = shownFrameRect.left + (x - shownWidgetRect.left) / shownWidgetRect.width() * shownFrameRect.width()
        val frameY = shownFrameRect.top + (y - shownWidgetRect.top) / shownWidgetRect.height() * shownFrameRect.height()
        return frameX / shownFrameWidth to frameY / shownFrameHeight
    }

    /** Sends one touch event to the phone: {"a":action,"x":..,"y":..} in fractions of the frame. */
    private fun sendInput(action: String, frame: Pair<Float, Float>, dy: Float = 0f) {
        val socket = streamSocket ?: return
        if (action != "move") Log.d(TAG, "Phone touch $action at $frame")
        val line = JSONObject().put("a", action).put("x", frame.first.toDouble()).put("y", frame.second.toDouble())
            .apply { if (dy != 0f) put("dy", dy.toDouble()) }
            .toString() + "\n"
        inputWriter.execute {
            try {
                val out = socket.getOutputStream()
                out.write(line.toByteArray())
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Input not sent: ${e.message}")
            }
        }
    }

    /** A press on the shown phone screen becomes a finger on the phone: it follows the cursor until released. */
    override fun onPress(px: Float, py: Float): Boolean {
        if (hitTest(px, py) != HitArea.CONTENT) return false
        val frame = toFrame(px, py) ?: return false
        phoneTouchActive = true
        sendInput("down", frame)
        return true
    }

    override fun onPressMove(px: Float, py: Float): Boolean {
        if (!phoneTouchActive) return false
        toFrame(px, py, clamp = true)?.let { sendInput("move", it) }
        return true
    }

    override fun onPressRelease(px: Float, py: Float) {
        if (!phoneTouchActive) return
        phoneTouchActive = false
        toFrame(px, py, clamp = true)?.let { sendInput("up", it) }
    }

    override fun onPressCancel(px: Float, py: Float) = onPressRelease(px, py)

    /** A double-tap on the shown phone screen reached the phone as two taps; it doesn't open the menu. */
    override fun onDoubleTap(px: Float, py: Float): Boolean =
        hitTest(px, py) == HitArea.CONTENT && toFrame(px, py) != null

    /** A two-finger scroll over the shown phone screen scrolls the phone. */
    override fun onScrollAt(px: Float, py: Float, dy: Float): Boolean {
        val frame = toFrame(px, py) ?: return false
        if (shownWidgetRect.height() <= 0f || shownFrameHeight <= 0) return false
        val frameDy = dy / shownWidgetRect.height() * shownFrameRect.height() / shownFrameHeight
        sendInput("scroll", frame, frameDy)
        return true
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

                } catch (_: InterruptedException) {
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
                try { streamSocket?.close() } catch (_: Exception) {}
                streamSocket = null
                inputStream = null

                if (isStreaming.get()) {
                    clearError()
                    updateStreamState(StreamState.CONNECTING)
                    try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: InterruptedException) { break }
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
        phoneTouchActive = false
        shownWidgetRect.setEmpty()
        try { streamSocket?.close() } catch (_: Exception) {}
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
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

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

            } catch (_: SocketTimeoutException) {
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
            rsOutputBitmap = createBitmap(width, height)
            outputAllocation = Allocation.createFromBitmap(rs, rsOutputBitmap)
        }

        inputAllocation!!.copyFrom(nv21)
        script.setInput(inputAllocation)
        script.forEach(outputAllocation)
        outputAllocation!!.copyTo(rsOutputBitmap)

        synchronized(frameLock) {
            if (frameBitmap == null || frameBitmap!!.width != width || frameBitmap!!.height != height) {
                frameBitmap?.recycle()
                frameBitmap = createBitmap(width, height)
            }
            val canvas = Canvas(frameBitmap!!)
            canvas.drawBitmap(rsOutputBitmap!!, 0f, 0f, null)
        }

        convertedFrames++
        handler.post { listener?.onFrameReady() }
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

        val bitmap = createBitmap(width, height)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        synchronized(frameLock) {
            frameBitmap?.recycle()
            frameBitmap = bitmap
        }

        convertedFrames++
        handler.post { listener?.onFrameReady() }
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
                            rememberShownFrame(bitmap, contentRect, dstRect)
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
                            rememberShownFrame(bitmap, srcRect, dstRect)
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
            canvas.drawText("Reconnecting...", widgetBounds.centerX(), widgetBounds.centerY(), connectingTextPaint)
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

    override fun onDestroy() {
        stopStreaming()
        lastReportedMediaActive = false
        handler.post { listener?.onMediaStateChanged(false) }
        try {
            yuvToRgbScript?.destroy()
            renderScript?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing RenderScript", e)
        }
        yuvToRgbScript = null
        renderScript = null
        inputWriter.shutdown()
    }

    fun release() = onDestroy()

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

        super.onDrag(dx, dy, screenWidth, screenHeight)

        if (previousWidth != widgetWidth || previousHeight != widgetHeight) {
            resetDetectedContentBounds()
        }
    }

    override fun onPause() {
        if (!isHostPaused) {
            isHostPaused = true
            dispatchMediaStateIfChanged()
        }
    }

    override fun onResume() {
        if (isHostPaused) {
            isHostPaused = false
            dispatchMediaStateIfChanged()
        }
    }

    override fun onDisplayVisibilityChanged(visible: Boolean) {
        if (isDisplayVisible == visible) return
        isDisplayVisible = visible
        dispatchMediaStateIfChanged()
    }
}

interface MirrorWidgetListener {
    fun onFrameReady()
    fun requestMirror(start: Boolean)
    fun onMediaStateChanged(active: Boolean)
}

/** Puts the phone screen mirror on the dashboard and connects its frames to the glasses output. */
class MirrorPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val context: Context get() = services.androidContext
    private val current: () -> ScreenMirrorWidget? = { services.singleton(TYPE) as? ScreenMirrorWidget }
    private val onMediaFrame: (sourceId: String) -> Unit = { sourceId -> services.notifyMediaFrame(sourceId) }
    private val setMediaSourceActive: (sourceId: String, active: Boolean) -> Unit = { sourceId, active -> services.setMediaSourceActive(sourceId, active) }
    private val refreshBrightnessRules: () -> Unit = { services.refreshBrightnessRules() }

    override val lifecycle = WidgetLifecycleRegistration.singleton(
        ScreenMirrorWidget::class, MirrorWidgetState.Codec,
        current = current,
        create = { ScreenMirrorWidget(context, 20f, 20f, 180f, 320f) },
        restore = ::restore,
        capture = ::capture,
        configure = ::configure,
        teardown = { widget ->
            setMediaSourceActive(widget.binocularSourceId, false)
            widget.onDestroy()
        },
    )

    /** Where the phone's running mirror streams from; null when there is none (or the phone is away). */
    private var liveMirrorIp: String? = null

    init {
        services.appData.subscribe(AppChannels.mirroring) { mirror -> setMirroringEnabled(mirror.enabled, mirror.phoneIp) }
        services.appData.subscribe(AppChannels.phoneConnected) { connected -> if (!connected) setMirroringEnabled(false, null) }
    }

    private fun setMirroringEnabled(enabled: Boolean, phoneIp: String?) {
        liveMirrorIp = phoneIp.takeIf { enabled }
        services.withTransientChanges {
            current()?.setMirroringEnabled(enabled, phoneIp)
            current()?.refreshDisplayVisibility()
            refreshBrightnessRules()
            services.notifyTransientChange()
        }
    }

    private fun restore(state: MirrorWidgetState): ScreenMirrorWidget {
        val widget = ScreenMirrorWidget(context, state.x, state.y, state.width, state.height)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }

    private fun capture(widget: ScreenMirrorWidget): MirrorWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return MirrorWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    private fun configure(widget: ScreenMirrorWidget) {
        widget.listener = object : MirrorWidgetListener {
            override fun onFrameReady() {
                if (widget.isBinocularMediaActive()) onMediaFrame(widget.binocularSourceId)
            }
            override fun onMediaStateChanged(active: Boolean) {
                setMediaSourceActive(widget.binocularSourceId, active)
                // The mirror's media brightness cap follows its live media state.
                refreshBrightnessRules()
            }
            override fun requestMirror(start: Boolean) {
                services.host?.requestMirror(start)
            }
        }
        widget.refreshDisplayVisibility()
        // A mirror already running when the widget opens: start showing it.
        liveMirrorIp?.let { widget.setMirroringEnabled(true, it) }
    }

    /** The phone screen mirror as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("mirror")
        override val descriptor = WidgetDescriptor(
            TYPE,
            "Screen mirror",
            WidgetInstancePolicy.SINGLETON,
            order = 9,
            storesClosedTemplate = true
        )
        override val codec get() = MirrorWidgetState.Codec
        override fun create(context: WidgetContext) = MirrorPlugin(context)
        override val commands = listOf(
            WidgetCommand(
                AppCommandDefinition(
                    "toggle:mirror",
                    "Toggle: Screen Mirror",
                    "Mirror",
                    "Show or hide phone screen mirroring",
                    shortcutEligible = true
                ), WidgetMenu.TOGGLE
            ),
        )
    }
}

/** What a saved dashboard keeps of the phone screen mirror: only the common fields. */
data class MirrorWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<MirrorWidgetState>(MirrorPlugin.TYPE, MirrorWidgetState::class) {
        override fun toJson(state: MirrorWidgetState): JSONObject = JSONObject().apply { putCommonFields(state) }

        override fun fromJson(payload: JSONObject): MirrorWidgetState? =
            parseSizedWidgetObject(payload) { _, c, w, h ->
                MirrorWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }
    }
}