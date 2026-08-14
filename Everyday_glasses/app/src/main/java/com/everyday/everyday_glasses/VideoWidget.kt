package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.everyday.everyday_glasses.video.VideoItem
import com.everyday.everyday_glasses.video.VideoPlayerController
import com.everyday.everyday_glasses.video.VideoPlayerUi
import kotlin.math.roundToInt

/**
 * X3player's complete player UI hosted inside an Everyday widget.
 *
 * The native view hierarchy is kept below WidgetContainer's canvas so the existing widget
 * chrome remains the interaction layer for moving, resizing, pinning, minimizing and closing.
 * Cursor taps are translated into synthetic touch events for the native controls.
 */
class VideoWidget(
    private val context: Context,
    private val rootLayout: ViewGroup,
    private val containerView: View,
    val contentUri: Uri,
    val displayName: String,
    x: Float,
    y: Float,
    width: Float = 360f,
    height: Float = 220f,
    queueItems: List<VideoItem> = emptyList(),
) : BaseWidget(WidgetTypeId.VIDEO, x, y, width, height) {

    private val host = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false
    }

    private val controller = VideoPlayerController(
        context = context,
        initialUri = contentUri,
        initialDisplayName = displayName,
        queueItems = queueItems,
    )

    private val playerUi = VideoPlayerUi(
        context = context,
        host = host,
        controller = controller,
        onExitRequested = {
            if (isFullscreen) {
                toggleFullscreen()
            } else {
                lifecycleListener?.onCloseRequested(this)
            }
        },
    )

    private var playbackStarted = false
    private var released = false
    private var displayVisible = true
    private var mediaSourceActive = false
    var listener: VideoWidgetListener? = null
    private val onStateChanged: (() -> Unit)? get() = listener?.let { value -> { value.onStateChanged() } }
    private val onPlaybackVisibilityChanged: ((Boolean) -> Unit)?
        get() = listener?.let { value -> { active -> value.onPlaybackVisibilityChanged(active) } }

    override val minimizeLabel: String = "V"
    override val minWidth: Float = 320f
    override val minHeight: Float = 180f

    init {
        backgroundPaint.color = Color.BLACK
        playerUi.onStateChanged = {
            onStateChanged?.invoke()
        }

        val insertIndex = rootLayout.indexOfChild(containerView).let { if (it >= 0) it else 0 }
        rootLayout.addView(host, insertIndex, FrameLayout.LayoutParams(1, 1))
        syncHostLayout()
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        syncHostLayout()
    }

    /** Start playback and keep the player attached while the widget remains alive. */
    fun startPlayback(autoPlay: Boolean = true) {
        if (released) return
        playbackStarted = true
        displayVisible = true
        syncHostLayout()
        controller.start(autoPlay)
        setMediaSourceActive(true)
        onStateChanged?.invoke()
    }

    fun setWindowedMode() {
        playerUi.setWindowedMode()
        syncHostLayout()
    }

    fun onCursorActivity() {
        if (!isMinimized && !released) playerUi.onUserActivity()
    }

    fun onCursorVisibilityChanged(visible: Boolean) {
        if (!isMinimized && !released) playerUi.onCursorVisibilityChanged(visible)
    }

    override fun toggleMinimize() {
        super.toggleMinimize()
        // BaseWidget changes isMinimized directly, so refresh the native host after the
        // callback-driven chrome transition has completed.
        syncHostLayout()
    }

    /** Whether playback should resume automatically after a lifecycle/display pause. */
    private val shouldAutoplayOnResume: Boolean
        get() = controller.shouldAutoplayOnResume

    /** Forward a cursor tap in WidgetContainer content coordinates to X3player's controls. */
    fun handleContentTap(px: Float, py: Float): Boolean {
        if (isMinimized || released) return true
        val localX = (px - x).coerceIn(0f, widgetWidth.coerceAtLeast(1f) - 1f)
        val localY = (py - y).coerceIn(0f, widgetHeight.coerceAtLeast(1f) - 1f)
        dispatchSyntheticTap(localX, localY)
        return true
    }

    /** Forward an Everyday double tap to X3player's progress/overlay behavior. */
    fun handleContentDoubleTap(px: Float, py: Float): Boolean {
        if (isMinimized || released) return true
        val localX = (px - x).coerceIn(0f, widgetWidth.coerceAtLeast(1f) - 1f)
        val localY = (py - y).coerceIn(0f, widgetHeight.coerceAtLeast(1f) - 1f)
        return playerUi.handleDoubleTap(localX, localY)
    }

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean = playerUi.handleKeyEvent(keyCode, event)

    private fun dispatchSyntheticTap(localX: Float, localY: Float) {
        val target = playerUi.root
        val now = SystemClock.uptimeMillis()
        val downTime = now
        val down = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_DOWN, localX, localY, 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(down)
        down.recycle()

        val up = MotionEvent.obtain(downTime, now + 40L, MotionEvent.ACTION_UP, localX, localY, 0)
        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(up)
        up.recycle()
    }

    private fun syncHostLayout() {
        val params = (host.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(1, 1)
        params.width = widgetWidth.roundToInt().coerceAtLeast(1)
        params.height = widgetHeight.roundToInt().coerceAtLeast(1)
        params.leftMargin = x.roundToInt()
        params.topMargin = y.roundToInt()
        host.layoutParams = params
        host.scaleX = 1f
        host.scaleY = 1f
        host.translationX = 0f
        host.translationY = 0f
        host.visibility = if (!isMinimized && displayVisible) View.VISIBLE else View.GONE
    }

    private fun setMediaSourceActive(active: Boolean) {
        if (mediaSourceActive == active) return
        mediaSourceActive = active
        onPlaybackVisibilityChanged?.invoke(active)
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // The native player is opaque. A fallback fill prevents a frame of transparency while
        // its surface is being attached or when the display lifecycle has hidden the host.
        if (host.visibility != View.VISIBLE) {
            canvas.drawRect(widgetBounds, backgroundPaint)
        }

        if (shouldShowBorder()) {
            canvas.drawRect(widgetBounds, hoverBorderPaint)
            if (!isFullscreen) drawResizeHandle(canvas)
            if (shouldShowBorderButtons()) drawBorderButtons(canvas)
        }
    }

    override fun onPause() {
        if (!playbackStarted || released) return
        controller.releaseForPause()
        setMediaSourceActive(false)
        onStateChanged?.invoke()
    }

    override fun onResume() {
        if (!playbackStarted || released || !displayVisible) return
        controller.start(shouldAutoplayOnResume)
        setMediaSourceActive(true)
        syncHostLayout()
    }

    override fun onDisplayVisibilityChanged(visible: Boolean) {
        displayVisible = visible
        syncHostLayout()
        if (!playbackStarted || released) return

        if (visible) {
            controller.start(shouldAutoplayOnResume)
            setMediaSourceActive(true)
        } else {
            controller.releaseForPause()
            setMediaSourceActive(false)
        }
        onStateChanged?.invoke()
    }

    override fun onDestroy() {
        if (released) return
        released = true
        playbackStarted = false
        setMediaSourceActive(false)
        playerUi.release()
        controller.release()
        (host.parent as? ViewGroup)?.removeView(host)
        listener = null
    }
}
