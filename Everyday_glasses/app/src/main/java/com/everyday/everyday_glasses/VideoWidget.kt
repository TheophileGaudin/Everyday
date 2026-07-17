package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.security.MessageDigest
import kotlin.math.max

/**
 * Lightweight dashboard placeholder backed by a single, short-lived fullscreen player.
 * No decoder or TextureView remains attached while this widget is windowed/minimized.
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
) : BaseWidget(x, y, width, height) {

    companion object {
        private const val POSITION_UPDATE_MS = 500L
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val PREFS_NAME = "video_playback_progress"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val progressKey = MessageDigest.getInstance("SHA-256")
        .digest(contentUri.toString().toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val thumbnailSource = Rect()
    private var thumbnail: Bitmap? = null
    private var playerView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var durationMs = C.TIME_UNSET
    private var positionMs = progressPreferences.getLong(progressKey, 0L).coerceAtLeast(0L)
    private var playbackError: String? = null

    var onStateChanged: (() -> Unit)? = null
    var onPlaybackVisibilityChanged: ((Boolean) -> Unit)? = null

    override val minimizeLabel: String = "V"

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 200, 215)
        textSize = 14f
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 12, 18, 30)
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 78, 92)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.ColorValue.hoverBorder
    }
    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 64, 96)
    }
    private val controlTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }

    private val seekBackBounds = RectF()
    private val playPauseBounds = RectF()
    private val seekForwardBounds = RectF()
    private val progressBounds = RectF()

    private val positionUpdate = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            positionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            durationMs = activePlayer.duration
            onStateChanged?.invoke()
            mainHandler.postDelayed(this, POSITION_UPDATE_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                durationMs = player?.duration ?: C.TIME_UNSET
                playbackError = null
            } else if (playbackState == Player.STATE_ENDED) {
                positionMs = 0L
                persistPosition()
            }
            onStateChanged?.invoke()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onStateChanged?.invoke()
        }

        override fun onPlayerError(error: PlaybackException) {
            playbackError = error.errorCodeName
            onStateChanged?.invoke()
        }
    }

    init {
        backgroundPaint.color = Color.rgb(12, 18, 30)
        loadThumbnail()
    }

    private fun loadThumbnail() {
        Thread {
            val loaded = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, contentUri)
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
            mainHandler.post {
                thumbnail = loaded
                loaded?.let { thumbnailSource.set(0, 0, it.width, it.height) }
                onStateChanged?.invoke()
            }
        }.start()
    }

    fun startFullscreenPlayback() {
        if (!isFullscreen || player != null) return

        val view = LayoutInflater.from(context)
            .inflate(R.layout.view_video_player, rootLayout, false) as PlayerView
        val insertIndex = rootLayout.indexOfChild(containerView).let { if (it >= 0) it else 0 }
        rootLayout.addView(view, insertIndex)
        playerView = view

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val newPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .also {
                it.setAudioAttributes(AudioAttributes.DEFAULT, true)
                it.addListener(playerListener)
                it.setMediaItem(MediaItem.fromUri(contentUri), positionMs)
                it.prepare()
                it.playWhenReady = true
            }
        player = newPlayer
        view.player = newPlayer
        playbackError = null
        mainHandler.removeCallbacks(positionUpdate)
        mainHandler.post(positionUpdate)
        onPlaybackVisibilityChanged?.invoke(true)
        onStateChanged?.invoke()
    }

    fun stopFullscreenPlayback() {
        mainHandler.removeCallbacks(positionUpdate)
        player?.let {
            positionMs = it.currentPosition.coerceAtLeast(0L)
            durationMs = it.duration
            it.removeListener(playerListener)
        }
        persistPosition()
        playerView?.player = null
        player?.release()
        player = null
        playerView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        playerView = null
        onPlaybackVisibilityChanged?.invoke(false)
        onStateChanged?.invoke()
    }

    fun handleContentTap(px: Float, py: Float): Boolean {
        if (!isFullscreen) {
            toggleFullscreen()
            return true
        }
        when {
            seekBackBounds.contains(px, py) -> player?.seekBack()
            playPauseBounds.contains(px, py) -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            seekForwardBounds.contains(px, py) -> player?.seekForward()
            else -> player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        onStateChanged?.invoke()
        return true
    }

    /** Fullscreen minimize returns to the useful placeholder, not directly to the icon tray. */
    override fun minimize() {
        if (isFullscreen) {
            toggleFullscreen()
        } else {
            super.minimize()
        }
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }
        updateControlBounds()
        if (isFullscreen) {
            drawFullscreenControls(canvas)
        } else {
            drawPlaceholder(canvas)
        }

        if (shouldShowBorder()) {
            canvas.drawRect(widgetBounds, hoverBorderPaint)
            if (!isFullscreen) drawResizeHandle(canvas)
            if (shouldShowBorderButtons()) drawBorderButtons(canvas)
        }
    }

    private fun drawPlaceholder(canvas: Canvas) {
        canvas.drawRect(widgetBounds, backgroundPaint)
        thumbnail?.let { bitmap -> canvas.drawBitmap(bitmap, thumbnailSource, contentBounds, null) }
        canvas.drawRect(
            contentBounds.left,
            max(contentBounds.top, contentBounds.bottom - 58f),
            contentBounds.right,
            contentBounds.bottom,
            overlayPaint,
        )
        canvas.drawText(ellipsize(displayName, titlePaint, contentBounds.width() - 24f), contentBounds.left + 12f, contentBounds.bottom - 32f, titlePaint)
        canvas.drawText("Resume ${formatTime(positionMs)}", contentBounds.left + 12f, contentBounds.bottom - 12f, secondaryPaint)
        drawProgress(canvas, contentBounds.left, contentBounds.bottom - 4f, contentBounds.right)
    }

    private fun drawFullscreenControls(canvas: Canvas) {
        val panelTop = widgetBounds.bottom - 82f
        canvas.drawRect(widgetBounds.left, panelTop, widgetBounds.right, widgetBounds.bottom, overlayPaint)
        canvas.drawText(ellipsize(displayName, titlePaint, widgetBounds.width() - 32f), widgetBounds.left + 16f, panelTop + 20f, titlePaint)
        canvas.drawText("${formatTime(positionMs)} / ${formatTime(durationMs)}", widgetBounds.left + 16f, panelTop + 42f, secondaryPaint)
        drawProgress(canvas, progressBounds.left, progressBounds.centerY(), progressBounds.right)
        drawControl(canvas, seekBackBounds, "-10")
        drawControl(canvas, playPauseBounds, if (player?.isPlaying == true) "Pause" else "Play")
        drawControl(canvas, seekForwardBounds, "+10")
        playbackError?.let { canvas.drawText("Playback error: $it", widgetBounds.left + 16f, panelTop - 12f, secondaryPaint) }
    }

    private fun drawControl(canvas: Canvas, bounds: RectF, label: String) {
        canvas.drawRoundRect(bounds, 8f, 8f, controlPaint)
        val baseline = bounds.centerY() - (controlTextPaint.ascent() + controlTextPaint.descent()) / 2f
        canvas.drawText(label, bounds.centerX(), baseline, controlTextPaint)
    }

    private fun drawProgress(canvas: Canvas, left: Float, centerY: Float, right: Float) {
        canvas.drawRect(left, centerY - 2f, right, centerY + 2f, progressTrackPaint)
        val fraction = if (durationMs > 0 && durationMs != C.TIME_UNSET) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
        canvas.drawRect(left, centerY - 3f, left + (right - left) * fraction, centerY + 3f, progressPaint)
    }

    private fun updateControlBounds() {
        val bottom = widgetBounds.bottom - 10f
        val top = bottom - 34f
        val buttonWidth = 74f
        val gap = 10f
        val totalWidth = buttonWidth * 3f + gap * 2f
        val start = widgetBounds.centerX() - totalWidth / 2f
        seekBackBounds.set(start, top, start + buttonWidth, bottom)
        playPauseBounds.set(seekBackBounds.right + gap, top, seekBackBounds.right + gap + buttonWidth, bottom)
        seekForwardBounds.set(playPauseBounds.right + gap, top, playPauseBounds.right + gap + buttonWidth, bottom)
        progressBounds.set(widgetBounds.left + 190f, widgetBounds.bottom - 47f, widgetBounds.right - 16f, widgetBounds.bottom - 39f)
    }

    private fun persistPosition() {
        progressPreferences.edit().putLong(progressKey, positionMs.coerceAtLeast(0L)).apply()
    }

    private fun ellipsize(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    private fun formatTime(timeMs: Long): String {
        if (timeMs < 0 || timeMs == C.TIME_UNSET) return "--:--"
        val seconds = timeMs / 1000L
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

    override fun onPause() {
        stopFullscreenPlayback()
    }

    override fun onResume() {
        if (isFullscreen) startFullscreenPlayback()
    }

    override fun onDisplayVisibilityChanged(visible: Boolean) {
        if (visible && isFullscreen) startFullscreenPlayback() else stopFullscreenPlayback()
    }

    override fun onDestroy() {
        stopFullscreenPlayback()
        thumbnail?.recycle()
        thumbnail = null
    }
}
