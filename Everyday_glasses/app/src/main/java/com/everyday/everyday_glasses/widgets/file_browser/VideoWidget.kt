package com.everyday.everyday_glasses.widgets.file_browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.everyday.everyday_glasses.runtime.VideoWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.runtime.WakeSleepManager
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.VideoItem
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.VideoPlayerController
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.VideoPlayerUi
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
    rootLayout: ViewGroup,
    containerView: View,
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
    private val onPlaybackVisibilityChanged: ((Boolean) -> Unit)?
        get() = listener?.let { value -> { active -> value.onPlaybackVisibilityChanged(active) } }

    override val minimizeLabel: String = "V"
    override val minWidth: Float = 320f
    override val minHeight: Float = 180f

    /** A video only plays while the display is awake, even when it is pinned. */
    override fun isVisibleIn(state: WakeSleepManager.DisplayState): Boolean =
        state == WakeSleepManager.DisplayState.WAKE && !isMinimized

    override val requestsMediaBrightnessCap: Boolean get() = isFullscreen

    init {
        backgroundPaint.color = Color.BLACK
        playerUi.onStateChanged = {
            notifyTransientChange()
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
        notifyTransientChange()
    }

    fun setWindowedMode() {
        playerUi.setWindowedMode()
        syncHostLayout()
    }

    override fun onCursorActivity() {
        if (!isMinimized && !released) playerUi.onUserActivity()
    }

    override fun onCursorVisibilityChanged(visible: Boolean) {
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

    override fun onContentTap(px: Float, py: Float): Boolean {
        if (baseHitTest(px, py) != BaseHitArea.CONTENT) return false
        handleContentTap(px, py)
        return true
    }

    /** Windowed, only a double-tap on the video reaches the player; the rest does nothing. */
    override fun onDoubleTap(px: Float, py: Float): Boolean {
        if (isFullscreen || baseHitTest(px, py) == BaseHitArea.CONTENT) handleContentDoubleTap(px, py)
        return true
    }

    /** Forward a cursor tap in WidgetContainer content coordinates to X3player's controls. */
    fun handleContentTap(px: Float, py: Float) {
        if (isMinimized || released) return
        val localX = (px - x).coerceIn(0f, widgetWidth.coerceAtLeast(1f) - 1f)
        val localY = (py - y).coerceIn(0f, widgetHeight.coerceAtLeast(1f) - 1f)
        dispatchSyntheticTap(localX, localY)
    }

    /** Forward an Everyday double tap to X3player's progress/overlay behavior. */
    fun handleContentDoubleTap(px: Float, py: Float): Boolean {
        if (isMinimized || released) return true
        val localX = (px - x).coerceIn(0f, widgetWidth.coerceAtLeast(1f) - 1f)
        val localY = (py - y).coerceIn(0f, widgetHeight.coerceAtLeast(1f) - 1f)
        return playerUi.handleDoubleTap(localX, localY)
    }

    override fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean = playerUi.handleKeyEvent(keyCode, event)

    private fun dispatchSyntheticTap(localX: Float, localY: Float) {
        val target = playerUi.root
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, localX, localY, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(down)
        down.recycle()

        val up = MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_UP, localX, localY, 0)
        up.source = InputDevice.SOURCE_TOUCHSCREEN
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
        notifyTransientChange()
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
        notifyTransientChange()
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
