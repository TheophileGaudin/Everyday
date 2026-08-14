package com.everyday.everyday_glasses.video

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.everyday.everyday_glasses.InteractionTimeouts
import com.everyday.everyday_glasses.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

class VideoPlayerUi(
    private val context: Context,
    private val host: FrameLayout,
    private val controller: VideoPlayerController,
    private val onExitRequested: () -> Unit,
) {
    var onStateChanged: (() -> Unit)? = null

    private val playerContext = ContextThemeWrapper(context, R.style.Theme_EverydayVideoPlayer)
    private val handler = Handler(Looper.getMainLooper())
    val root: View = LayoutInflater.from(playerContext).inflate(R.layout.view_x3_player, host, false)

    private val playerView: PlayerView = root.findViewById(R.id.playerView)
    private val controlsOverlay: View = root.findViewById(R.id.controlsOverlay)
    private val titleText: TextView = root.findViewById(R.id.titleText)
    private val errorText: TextView = root.findViewById(R.id.errorText)
    private val playbackMessageText: TextView = root.findViewById(R.id.playbackMessageText)
    private val positionText: TextView = root.findViewById(R.id.positionText)
    private val playbackProgress: com.google.android.material.progressindicator.LinearProgressIndicator =
        root.findViewById(R.id.playbackProgress)
    private val progressContainer: View = root.findViewById(R.id.progressContainer)
    private val backButton: MaterialButton = root.findViewById(R.id.backButton)
    private val playPauseButton: MaterialButton = root.findViewById(R.id.playPauseButton)
    private val restartButton: MaterialButton = root.findViewById(R.id.restartButton)
    private val stopButton: MaterialButton = root.findViewById(R.id.stopButton)
    private val captionsButton: MaterialButton = root.findViewById(R.id.captionsButton)
    private val optionsButton: MaterialButton = root.findViewById(R.id.optionsButton)
    private val advancedOverlay: View = root.findViewById(R.id.advancedOptionsOverlay)
    private val advancedBackButton: MaterialButton = root.findViewById(R.id.advancedBackButton)
    private val seekBackButton: MaterialButton = root.findViewById(R.id.seekBackButton)
    private val seekForwardButton: MaterialButton = root.findViewById(R.id.seekForwardButton)
    private val previousButton: MaterialButton = root.findViewById(R.id.previousButton)
    private val nextButton: MaterialButton = root.findViewById(R.id.nextButton)
    private val subtitleOverlay: View = root.findViewById(R.id.subtitleOverlay)
    private val subtitleUploadButton: MaterialButton = root.findViewById(R.id.subtitleUploadButton)
    private val subtitleNoneButton: MaterialButton = root.findViewById(R.id.subtitleNoneButton)
    private val subtitleMessageText: TextView = root.findViewById(R.id.subtitleMessageText)
    private val uploadedSubtitleListContainer: LinearLayout = root.findViewById(R.id.uploadedSubtitleListContainer)
    private val subtitleListScrollView: ScrollView = root.findViewById(R.id.subtitleListScrollView)
    private val subtitleImportOverlay: View = root.findViewById(R.id.subtitleImportOverlay)
    private val subtitleImportMessageText: TextView = root.findViewById(R.id.subtitleImportMessageText)
    private val subtitleImportListContainer: LinearLayout = root.findViewById(R.id.subtitleImportListContainer)
    private val subtitleImportScrollView: ScrollView = root.findViewById(R.id.subtitleImportScrollView)

    private var controlsVisible = true
    private var controlsHideScheduled = false
    private var hasFatalError = false
    private var released = false
    private val controlsHideRunnable = Runnable {
        controlsHideScheduled = false
        if (released) return@Runnable

        if (shouldAutoHidePlayerControls(
                controlsVisible = controlsVisible,
                isPlaying = controller.isPlaying,
                hasOpenOverlay = hasOpenOverlay(),
                hasFatalError = hasFatalError,
            )
        ) {
            setControlsVisible(false)
        } else {
            scheduleControlsAutoHide()
        }
    }
    private val subtitleTrackButtons = linkedMapOf<String, MaterialButton>()
    private val subtitleImportButtons = mutableListOf<MaterialButton>()
    private var subtitleRowsSignature: String? = null

    init {
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        controller.onStateChanged = {
            render()
            onStateChanged?.invoke()
        }
        controller.onItemChanged = {
            subtitleRowsSignature = null
            renderSubtitleRows()
            render()
        }
        controller.onSubtitleCandidatesChanged = {
            renderSubtitleImportRows()
            render()
            // Everyday mirrors this native hierarchy into the binocular output on demand.
            // Notify again after Android has laid out the newly inserted rows; the immediate
            // controller notification can otherwise capture the still-empty pre-layout frame.
            subtitleImportListContainer.requestLayout()
            root.post {
                if (!released) onStateChanged?.invoke()
            }
        }
        setupClickHandlers()
        renderSubtitleRows()
        renderSubtitleImportRows()
        render()
    }

    fun handleDoubleTap(localX: Float, localY: Float): Boolean {
        if (subtitleImportOverlay.visibility == View.VISIBLE) {
            closeSubtitleImportOverlay()
            return true
        }
        if (subtitleOverlay.visibility == View.VISIBLE) {
            closeSubtitleMenu()
            return true
        }
        if (advancedOverlay.visibility == View.VISIBLE) {
            closeAdvancedOptions()
            return true
        }
        if (isInside(progressContainer, localX, localY)) {
            controller.seekBy(-PROGRESS_SEEK_MS)
            return true
        }
        return false
    }

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                controller.playPause()
                setControlsVisible(true)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                controller.seekBy(-PROGRESS_SEEK_MS)
                setControlsVisible(true)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                controller.seekBy(PROGRESS_SEEK_MS)
                setControlsVisible(true)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                controller.movePrevious()
                setControlsVisible(true)
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                controller.moveNext()
                setControlsVisible(true)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                controller.adjustVolume(AudioManager.ADJUST_RAISE)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                controller.adjustVolume(AudioManager.ADJUST_LOWER)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    subtitleImportOverlay.visibility == View.VISIBLE -> closeSubtitleImportOverlay()
                    subtitleOverlay.visibility == View.VISIBLE -> closeSubtitleMenu()
                    advancedOverlay.visibility == View.VISIBLE -> closeAdvancedOptions()
                    else -> onExitRequested()
                }
                true
            }
            else -> false
        }
    }

    fun release() {
        released = true
        handler.removeCallbacks(controlsHideRunnable)
        controlsHideScheduled = false
        controller.onStateChanged = null
        controller.onItemChanged = null
        controller.onSubtitleCandidatesChanged = null
        onStateChanged = null
        playerView.player = null
        (root.parent as? ViewGroup)?.removeView(root)
    }

    fun setWindowedMode() {
        // Windowed widgets always use direct cursor clicks. The same complete view hierarchy
        // remains attached; only the host transform changes in VideoWidget.
        if (!controlsVisible) setControlsVisible(true)
    }

    /** Re-sync the controls with Everyday's global cursor activity. */
    fun onUserActivity() {
        if (released) return
        if (!controlsVisible) {
            setControlsVisible(true)
        } else {
            scheduleControlsAutoHide(resetTimer = true)
        }
    }

    /** Keep player controls in step with the cursor's visible/hidden state. */
    fun onCursorVisibilityChanged(visible: Boolean) {
        if (released) return
        if (visible) {
            onUserActivity()
        } else if (shouldAutoHidePlayerControls(
                controlsVisible = controlsVisible,
                isPlaying = controller.isPlaying,
                hasOpenOverlay = hasOpenOverlay(),
                hasFatalError = hasFatalError,
            )
        ) {
            setControlsVisible(false)
        }
    }

    private fun setupClickHandlers() {
        root.setOnClickListener {
            when {
                subtitleImportOverlay.visibility == View.VISIBLE -> closeSubtitleImportOverlay()
                subtitleOverlay.visibility == View.VISIBLE -> closeSubtitleMenu()
                advancedOverlay.visibility == View.VISIBLE -> closeAdvancedOptions()
                else -> setControlsVisible(!controlsVisible)
            }
        }
        playerView.setOnClickListener { toggleOrCloseOverlays() }
        controlsOverlay.setOnClickListener { /* child controls handle their own clicks */ }
        backButton.setOnClickListener { onExitRequested() }
        playPauseButton.setOnClickListener { controller.playPause(); setControlsVisible(true) }
        restartButton.setOnClickListener { controller.restart(); setControlsVisible(true) }
        stopButton.setOnClickListener { controller.stop(); setControlsVisible(true) }
        progressContainer.setOnClickListener { controller.handleProgressTap(); setControlsVisible(true) }
        captionsButton.setOnClickListener { openSubtitleMenu() }
        optionsButton.setOnClickListener { openAdvancedOptions() }
        advancedBackButton.setOnClickListener { closeAdvancedOptions() }
        seekBackButton.setOnClickListener { controller.seekBy(-PROGRESS_SEEK_MS); setControlsVisible(true) }
        seekForwardButton.setOnClickListener { controller.seekBy(PROGRESS_SEEK_MS); setControlsVisible(true) }
        previousButton.setOnClickListener { controller.movePrevious(); setControlsVisible(true) }
        nextButton.setOnClickListener { controller.moveNext(); setControlsVisible(true) }
        subtitleUploadButton.setOnClickListener { openSubtitleImportOverlay() }
        subtitleNoneButton.setOnClickListener { controller.selectNoSubtitles(); setControlsVisible(true) }

        advancedOverlay.setOnClickListener { view -> if (view === advancedOverlay) closeAdvancedOptions() }
        subtitleOverlay.setOnClickListener { view -> if (view === subtitleOverlay) closeSubtitleMenu() }
        subtitleImportOverlay.setOnClickListener { view -> if (view === subtitleImportOverlay) closeSubtitleImportOverlay() }
    }

    private fun render() {
        playerView.player = controller.player
        titleText.text = controller.currentItem.displayName
        val error = controller.errorText
        hasFatalError = error != null
        errorText.text = error.orEmpty()
        errorText.visibility = if (error == null) View.GONE else View.VISIBLE
        playbackMessageText.text = controller.playbackMessage.orEmpty()
        playbackMessageText.visibility = if (controller.playbackMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        positionText.text = "${formatDuration(controller.positionMs)} / ${formatDuration(controller.durationMs)}"
        playbackProgress.progress = if (controller.durationMs > 0L) {
            ((controller.positionMs * PROGRESS_MAX) / controller.durationMs).toInt().coerceIn(0, PROGRESS_MAX.toInt())
        } else 0
        updatePlayPauseLabel()
        previousButton.isEnabled = controller.canMovePrevious()
        nextButton.isEnabled = controller.canMoveNext()
        renderSubtitleRows()
        scheduleControlsAutoHide()
        root.invalidate()
    }

    private fun toggleOrCloseOverlays() {
        when {
            subtitleImportOverlay.visibility == View.VISIBLE -> closeSubtitleImportOverlay()
            subtitleOverlay.visibility == View.VISIBLE -> closeSubtitleMenu()
            advancedOverlay.visibility == View.VISIBLE -> closeAdvancedOptions()
            else -> setControlsVisible(!controlsVisible)
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            closeAdvancedOptions(notify = false)
            closeSubtitleImportOverlay(notify = false)
            closeSubtitleMenu(notify = false)
        }
        scheduleControlsAutoHide()
        render()
    }

    private fun scheduleControlsAutoHide(resetTimer: Boolean = false) {
        if (resetTimer) {
            handler.removeCallbacks(controlsHideRunnable)
            controlsHideScheduled = false
        }

        if (!shouldAutoHidePlayerControls(
                controlsVisible = controlsVisible,
                isPlaying = controller.isPlaying,
                hasOpenOverlay = hasOpenOverlay(),
                hasFatalError = hasFatalError,
            )
        ) {
            if (controlsHideScheduled) {
                handler.removeCallbacks(controlsHideRunnable)
                controlsHideScheduled = false
            }
            return
        }

        if (!controlsHideScheduled) {
            controlsHideScheduled = true
            handler.postDelayed(controlsHideRunnable, CONTROL_HIDE_DELAY_MS)
        }
    }

    private fun hasOpenOverlay(): Boolean =
        advancedOverlay.visibility == View.VISIBLE ||
            subtitleOverlay.visibility == View.VISIBLE ||
            subtitleImportOverlay.visibility == View.VISIBLE

    private fun openAdvancedOptions() {
        closeSubtitleImportOverlay(notify = false)
        closeSubtitleMenu(notify = false)
        advancedOverlay.visibility = View.VISIBLE
        setControlsVisible(true)
    }

    private fun closeAdvancedOptions(notify: Boolean = true) {
        advancedOverlay.visibility = View.GONE
        if (notify) {
            scheduleControlsAutoHide(resetTimer = true)
            root.invalidate()
        }
    }

    private fun openSubtitleMenu() {
        closeSubtitleImportOverlay(notify = false)
        closeAdvancedOptions(notify = false)
        subtitleMessageText.visibility = View.GONE
        subtitleOverlay.visibility = View.VISIBLE
        setControlsVisible(true)
    }

    private fun closeSubtitleMenu(notify: Boolean = true) {
        subtitleOverlay.visibility = View.GONE
        subtitleMessageText.visibility = View.GONE
        if (notify) {
            scheduleControlsAutoHide(resetTimer = true)
            root.invalidate()
        }
    }

    private fun openSubtitleImportOverlay() {
        closeAdvancedOptions(notify = false)
        subtitleImportOverlay.visibility = View.VISIBLE
        subtitleImportMessageText.text = playerContext.getString(R.string.subtitle_import_subtitle)
        subtitleImportButtons.clear()
        subtitleImportListContainer.removeAllViews()
        setControlsVisible(true)
        controller.loadSubtitleCandidates()
    }

    private fun closeSubtitleImportOverlay(notify: Boolean = true) {
        subtitleImportOverlay.visibility = View.GONE
        subtitleImportMessageText.text = playerContext.getString(R.string.subtitle_import_subtitle)
        if (notify) {
            scheduleControlsAutoHide(resetTimer = true)
            root.invalidate()
        }
    }

    private fun renderSubtitleRows() {
        val options = controller.subtitleTrackOptions
        val selection = controller.subtitleState.selection
        val signature = buildString {
            append(selection?.mode)
            append(':')
            append(selection?.externalSubtitleId)
            append(':')
            append(selection?.embeddedTrackKey)
            options.forEach { option ->
                append('|').append(option.key)
                    .append(':').append(option.selected)
                    .append(':').append(option.supported)
                    .append(':').append(option.label)
            }
        }
        if (signature == subtitleRowsSignature) return
        subtitleRowsSignature = signature

        subtitleNoneButton.text = if (controller.subtitleState.selection?.mode == SubtitleSelectionMode.NONE) {
            playerContext.getString(R.string.subtitles_none) + playerContext.getString(R.string.subtitles_current_suffix)
        } else {
            playerContext.getString(R.string.subtitles_none)
        }
        uploadedSubtitleListContainer.removeAllViews()
        subtitleTrackButtons.clear()
        options.forEach { option ->
            val button = LayoutInflater.from(playerContext)
                .inflate(R.layout.row_subtitle_option, uploadedSubtitleListContainer, false) as MaterialButton
            button.text = if (option.selected) option.label + playerContext.getString(R.string.subtitles_current_suffix) else option.label
            button.isEnabled = option.supported
            button.setOnClickListener { controller.selectSubtitleTrack(option); setControlsVisible(true) }
            uploadedSubtitleListContainer.addView(button)
            subtitleTrackButtons[option.key] = button
        }
        subtitleNoneButton.isEnabled = true
        subtitleListScrollView.post { subtitleListScrollView.scrollTo(0, 0) }
    }

    private fun renderSubtitleImportRows() {
        subtitleImportListContainer.removeAllViews()
        subtitleImportButtons.clear()
        val candidates = controller.subtitleCandidates
        subtitleImportMessageText.text = if (candidates.isEmpty()) {
            playerContext.getString(R.string.subtitle_import_empty)
        } else {
            playerContext.getString(R.string.subtitle_import_subtitle)
        }
        candidates.forEach { candidate ->
            val button = LayoutInflater.from(playerContext)
                .inflate(R.layout.row_subtitle_import_option, subtitleImportListContainer, false) as MaterialButton
            button.text = buildSubtitleImportRowLabel(candidate)
            button.setOnClickListener {
                controller.importSubtitle(candidate) { result ->
                    if (!released) {
                        result.onSuccess {
                            closeSubtitleImportOverlay()
                            subtitleMessageText.visibility = View.GONE
                        }.onFailure { failure ->
                            subtitleMessageText.text = failure.message ?: playerContext.getString(R.string.subtitle_upload_failed)
                            subtitleMessageText.visibility = View.VISIBLE
                        }
                        setControlsVisible(true)
                    }
                }
            }
            subtitleImportListContainer.addView(button)
            subtitleImportButtons += button
        }
        subtitleImportScrollView.post { subtitleImportScrollView.scrollTo(0, 0) }
    }

    private fun buildSubtitleImportRowLabel(candidate: LocalSubtitleCandidate): String {
        val path = candidate.relativePath?.trimEnd('/')?.takeIf { it.isNotBlank() }
        return if (path == null) candidate.displayName else "${candidate.displayName}\n$path"
    }

    private fun updatePlayPauseLabel() {
        if (controller.isPlaying) {
            playPauseButton.text = ""
            playPauseButton.icon = ContextCompat.getDrawable(playerContext, R.drawable.ic_pause)
            playPauseButton.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            playPauseButton.iconPadding = 0
        } else {
            playPauseButton.icon = null
            playPauseButton.text = "\u25B6"
        }
    }

    private fun isInside(view: View, localX: Float, localY: Float): Boolean {
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val left = location[0] - rootLocation[0]
        val top = location[1] - rootLocation[1]
        return localX >= left && localX <= left + view.width && localY >= top && localY <= top + view.height
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val CONTROL_HIDE_DELAY_MS = InteractionTimeouts.USER_ACTIVITY_HIDE_DELAY_MS
        private const val PROGRESS_SEEK_MS = 10_000L
        private const val PROGRESS_MAX = 1000L
    }
}
