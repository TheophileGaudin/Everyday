package com.everyday.everyday_glasses.widgets.file_browser.video_backend

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives changes produced by a [VideoPlayerController]. */
interface VideoPlaybackObserver {
    fun onPlaybackStateChanged() = Unit
    fun onVideoItemChanged(item: VideoItem) = Unit
    fun onSubtitleCandidatesChanged(candidates: List<LocalSubtitleCandidate>) = Unit
}

@OptIn(markerClass = [UnstableApi::class])
class VideoPlayerController(
    context: Context,
    initialUri: Uri,
    initialDisplayName: String,
    queueItems: List<VideoItem>,
) {
    enum class SubtitleTrackKind {
        EMBEDDED,
        EXTERNAL,
    }

    data class SubtitleTrackOption(
        val key: String,
        val kind: SubtitleTrackKind,
        val externalSubtitleId: Long?,
        val label: String,
        val supported: Boolean,
        val selected: Boolean,
    )

    var observer: VideoPlaybackObserver? = null

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val progressStore = PrefsPlaybackProgressStore(appContext)
    private val subtitleStore = PrefsSubtitleStore(appContext)
    private val subtitleScanner = LocalSubtitleImportScanner(appContext)
    private val queue = normalizeQueue(initialUri, initialDisplayName, queueItems)
    private var queueIndex = queue.indexOfFirst { it.contentUri == initialUri }.coerceAtLeast(0)
    private var currentItemValue = queue[queueIndex]
    private var playerValue: ExoPlayer? = null
    private var started = false
    private var released = false
    private var shouldAutoplay = true
    private var hasFatalPlaybackError = false
    private var appliedSubtitleUri: String? = null
    private var appliedExternalSubtitleIds: Set<Long> = emptySet()
    private var lastProgressTapAt = 0L
    private var subtitleStateValue = VideoSubtitleState()
    private var subtitleTrackOptionsValue: List<SubtitleTrackOptionInternal> = emptyList()
    private var subtitleCandidatesValue: List<LocalSubtitleCandidate> = emptyList()
    private val preparing = AtomicBoolean(false)

    var playbackMessage: String? = null
        private set

    val currentItem: VideoItem
        get() = currentItemValue

    val subtitleState: VideoSubtitleState
        get() = subtitleStateValue

    val subtitleTrackOptions: List<SubtitleTrackOption>
        get() = subtitleTrackOptionsValue.map { it.publicOption }

    val subtitleCandidates: List<LocalSubtitleCandidate>
        get() = subtitleCandidatesValue

    val player: ExoPlayer?
        get() = playerValue

    val isPlaying: Boolean
        get() = playerValue?.isPlaying == true

    val shouldAutoplayOnResume: Boolean
        get() = shouldAutoplay

    val playbackState: Int
        get() = playerValue?.playbackState ?: Player.STATE_IDLE

    val positionMs: Long
        get() = playerValue?.currentPosition?.coerceAtLeast(0L) ?: 0L

    val durationMs: Long
        get() = playerValue?.duration?.takeIf { it > 0L } ?: 0L

    val errorText: String?
        get() = if (hasFatalPlaybackError) playbackErrorText else null

    private var playbackErrorText: String? = null

    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            notifyChanged()
            if (playerValue != null && !released) handler.postDelayed(this, POSITION_UPDATE_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            hasFatalPlaybackError = true
            playbackErrorText = "This file could not be played.\n${error.errorCodeName}"
            notifyChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    hasFatalPlaybackError = false
                    playbackErrorText = null
                    updateAudioTrackStatus(
                        playerValue?.currentTracks ?: Tracks.EMPTY
                    )
                }

                Player.STATE_ENDED -> {
                    savePlaybackProgress(forceCompleted = true)
                }

                Player.STATE_BUFFERING, // loading spinner is handled in PlayerView.kt
                Player.STATE_IDLE -> Unit
            }

            notifyChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                playbackMessage = null
                startPositionUpdates()
            } else {
                savePlaybackProgress()
            }
            notifyChanged()
        }

        override fun onTracksChanged(tracks: Tracks) {
            rebuildSubtitleTrackOptions(tracks)
            applyStoredSubtitleSelection()
            updateAudioTrackStatus(tracks)
            notifyChanged()
        }
    }

    fun start(autoPlay: Boolean) {
        released = false
        shouldAutoplay = autoPlay
        ensurePlayer()
        if (!started || playerValue?.currentMediaItem == null) {
            prepareCurrent(autoPlay = autoPlay)
        } else if (autoPlay) {
            playerValue?.play()
            startPositionUpdates()
        }
        notifyChanged()
    }

    fun releaseForPause() {
        shouldAutoplay = isPlaying
        savePlaybackProgress()
        handler.removeCallbacks(positionUpdateRunnable)
        playerValue?.removeListener(playerListener)
        playerValue?.release()
        playerValue = null
        released = true
        notifyChanged()
    }

    fun release() {
        releaseForPause()
        scope.cancel()
        observer = null
    }

    fun playPause() {
        val activePlayer = playerValue ?: return
        if (activePlayer.isPlaying) {
            activePlayer.pause()
        } else {
            val duration = activePlayer.duration.takeIf { it > 0L } ?: 0L
            val nearEnd = duration > 0L && activePlayer.currentPosition >= (duration - PLAYBACK_RESTART_THRESHOLD_MS).coerceAtLeast(0L)
            if (activePlayer.playbackState == Player.STATE_ENDED || nearEnd) activePlayer.seekTo(0L)
            activePlayer.play()
            shouldAutoplay = true
        }
        notifyChanged()
    }

    fun restart() {
        resetProgressTapGesture()
        playerValue?.seekTo(0L)
        playerValue?.playWhenReady = true
        shouldAutoplay = true
        notifyChanged()
    }

    fun stop() {
        resetProgressTapGesture()
        playerValue?.pause()
        playerValue?.seekTo(0L)
        shouldAutoplay = false
        savePlaybackProgress()
        notifyChanged()
    }

    fun seekBy(deltaMs: Long) {
        val activePlayer = playerValue ?: return
        val target = resolveSeekTarget(
            activePlayer.currentPosition,
            activePlayer.duration.takeIf { it > 0L },
            deltaMs,
        )
        activePlayer.seekTo(target)
        notifyChanged()
    }

    fun handleProgressTap() {
        val now = SystemClock.elapsedRealtime()
        if (lastProgressTapAt > 0L && now - lastProgressTapAt <= PROGRESS_DOUBLE_TAP_WINDOW_MS) {
            handler.removeCallbacks(progressForwardRunnable)
            seekBy(-PROGRESS_SEEK_MS)
            resetProgressTapGesture()
            return
        }
        lastProgressTapAt = now
        handler.removeCallbacks(progressForwardRunnable)
        handler.postDelayed(progressForwardRunnable, PROGRESS_DOUBLE_TAP_WINDOW_MS)
        notifyChanged()
    }

    fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        notifyChanged()
    }

    fun canMovePrevious(): Boolean = queueIndex > 0

    fun canMoveNext(): Boolean = queueIndex < queue.lastIndex

    fun movePrevious(): Boolean = moveTo(queueIndex - 1)

    fun moveNext(): Boolean = moveTo(queueIndex + 1)

    fun loadSubtitleCandidates() {
        val item = currentItemValue
        scope.launch {
            val candidates = runCatching { subtitleScanner.findCandidatesFor(item) }
                .onFailure { error ->
                    Log.w(TAG, "Subtitle scan failed for ${item.displayName}", error)
                }
                .getOrDefault(emptyList())
            subtitleCandidatesValue = candidates
            observer?.onSubtitleCandidatesChanged(candidates)
            notifyChanged()
        }
    }

    fun importSubtitle(candidate: LocalSubtitleCandidate, onResult: (Result<UploadedSubtitle>) -> Unit) {
        val uri = currentItemValue.contentUri.toString()
        scope.launch {
            val result = runCatching { subtitleStore.addSubtitle(uri, candidate.contentUri) }
            result.onSuccess {
                subtitleStateValue = subtitleStore.load(uri)
                reloadCurrentMediaItem()
            }
            onResult(result)
            notifyChanged()
        }
    }

    fun selectNoSubtitles() {
        val uri = currentItemValue.contentUri.toString()
        scope.launch {
            subtitleStore.selectNone(uri)
            subtitleStateValue = subtitleStore.load(uri)
            applyStoredSubtitleSelection()
            notifyChanged()
        }
    }

    fun selectSubtitleTrack(option: SubtitleTrackOption) {
        if (!option.supported) return
        val uri = currentItemValue.contentUri.toString()
        scope.launch {
            when (option.kind) {
                SubtitleTrackKind.EMBEDDED -> subtitleStore.selectEmbedded(uri, option.key)
                SubtitleTrackKind.EXTERNAL -> option.externalSubtitleId?.let { subtitleStore.selectExternal(uri, it) }
            }
            subtitleStateValue = subtitleStore.load(uri)
            applyStoredSubtitleSelection()
            notifyChanged()
        }
    }

    fun savePlaybackProgress(forceCompleted: Boolean = false) {
        val item = currentItemValue
        val duration = durationMs
        val position = positionMs
        val completed = forceCompleted || shouldMarkCompleted(position, duration) || playbackState == Player.STATE_ENDED
        progressStore.save(
            uri = item.contentUri.toString(),
            positionMs = if (completed) duration else position,
            durationMs = duration,
            completed = completed,
        )
    }

    private fun moveTo(index: Int): Boolean {
        if (index !in queue.indices) return false
        savePlaybackProgress()
        queueIndex = index
        currentItemValue = queue[queueIndex]
        shouldAutoplay = true
        observer?.onVideoItemChanged(currentItemValue)
        prepareCurrent(autoPlay = true)
        return true
    }

    private fun ensurePlayer() {
        if (playerValue != null) return
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        playerValue = ExoPlayer.Builder(appContext, renderersFactory)
            .setSeekBackIncrementMs(PROGRESS_SEEK_MS)
            .setSeekForwardIncrementMs(PROGRESS_SEEK_MS)
            .build()
            .also { player ->
                player.setAudioAttributes(AudioAttributes.DEFAULT, true)
                player.addListener(playerListener)
            }
        released = false
    }

    private fun prepareCurrent(autoPlay: Boolean) {
        val activePlayer = playerValue ?: return
        if (!preparing.compareAndSet(false, true)) return

        val item = currentItemValue
        scope.launch {
            try {
                val uri = item.contentUri.toString()
                subtitleStateValue = subtitleStore.load(uri)

                val startPosition = resolveResumePosition(
                    progressStore.load(uri),
                    autoResumeEnabled = true
                )

                activePlayer.setMediaItem(
                    buildMediaItem(item, subtitleStateValue),
                    startPosition
                )
                activePlayer.prepare()
                activePlayer.playWhenReady = autoPlay
                shouldAutoplay = autoPlay
                started = true
                appliedSubtitleUri = uri
                appliedExternalSubtitleIds =
                    subtitleStateValue.subtitles.mapTo(linkedSetOf()) { it.id }
                hasFatalPlaybackError = false
                playbackErrorText = null
                playbackMessage = null
                startPositionUpdates()
                observer?.onVideoItemChanged(item)
            } finally {
                preparing.set(false)
                notifyChanged()
            }
        }
    }

    private fun reloadCurrentMediaItem() {
        val activePlayer = playerValue ?: return
        val wasPlaying = activePlayer.isPlaying || activePlayer.playWhenReady
        val currentPosition = activePlayer.currentPosition.coerceAtLeast(0L)
        scope.launch {
            val uri = currentItemValue.contentUri.toString()
            subtitleStateValue = subtitleStore.load(uri)
            activePlayer.setMediaItem(buildMediaItem(currentItemValue, subtitleStateValue), currentPosition)
            activePlayer.prepare()
            activePlayer.playWhenReady = wasPlaying
            appliedSubtitleUri = uri
            appliedExternalSubtitleIds = subtitleStateValue.subtitles.mapTo(linkedSetOf()) { it.id }
            notifyChanged()
        }
    }

    private fun buildMediaItem(item: VideoItem, state: VideoSubtitleState): MediaItem {
        val builder = MediaItem.Builder().setUri(item.contentUri)
        if (state.subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(state.subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(subtitle.contentUri)
                    .setMimeType(subtitle.mimeType)
                    .setLabel(subtitle.displayName)
                    .setId(externalSubtitleTrackId(subtitle.id))
                    .build()
            })
        }
        return builder.build()
    }

    private fun rebuildSubtitleTrackOptions(tracks: Tracks) {
        var fallbackIndex = 0
        subtitleTrackOptionsValue = tracks.groups
            .asSequence()
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group ->
                (0 until group.length).asSequence().map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    val externalId = format.id
                        ?.takeIf { it.startsWith(EXTERNAL_SUBTITLE_TRACK_PREFIX) }
                        ?.removePrefix(EXTERNAL_SUBTITLE_TRACK_PREFIX)
                        ?.toLongOrNull()
                    val kind = if (externalId == null) SubtitleTrackKind.EMBEDDED else SubtitleTrackKind.EXTERNAL
                    val key = externalId?.let(::externalSubtitleTrackId)
                        ?: embeddedSubtitleTrackKey(group, trackIndex, format)
                    fallbackIndex += 1
                    val baseLabel = format.label
                        ?.takeIf { it.isNotBlank() }
                        ?: format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }?.uppercase(Locale.US)
                        ?: "Subtitle $fallbackIndex"
                    val sourceLabel = if (kind == SubtitleTrackKind.EMBEDDED) "Embedded" else "Uploaded"
                    val details = listOfNotNull(sourceLabel, subtitleFormatLabel(format)).joinToString(" - ")
                    val supported = group.isTrackSupported(trackIndex)
                    val label = buildString {
                        append(baseLabel)
                        if (details.isNotBlank()) append(" - ").append(details)
                        if (!supported) append(" - Unsupported")
                    }
                    SubtitleTrackOptionInternal(
                        key = key,
                        group = group,
                        trackIndex = trackIndex,
                        kind = kind,
                        externalSubtitleId = externalId,
                        label = label,
                        supported = supported,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
            }
            .sortedWith(compareBy<SubtitleTrackOptionInternal> { it.kind }.thenBy { it.label.lowercase(Locale.US) })
            .toList()
        notifyChanged()
    }

    private fun applyStoredSubtitleSelection() {
        val activePlayer = playerValue ?: return
        val builder = activePlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        when (val selection = subtitleStateValue.selection) {
            null -> {
                val option = subtitleTrackOptionsValue.firstOrNull {
                    val format = it.group.getTrackFormat(it.trackIndex)
                    shouldAutoSelectEmbeddedSubtitle(
                        isEmbedded = it.kind == SubtitleTrackKind.EMBEDDED,
                        isSupported = it.supported,
                        isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    )
                }
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
                option?.let { builder.setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.trackIndex)) }
            }
            is SubtitleSelection -> when (selection.mode) {
                SubtitleSelectionMode.NONE -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                SubtitleSelectionMode.EMBEDDED,
                SubtitleSelectionMode.EXTERNAL -> {
                    val option = subtitleTrackOptionsValue.firstOrNull { candidate ->
                        when (selection.mode) {
                            SubtitleSelectionMode.EMBEDDED -> candidate.kind == SubtitleTrackKind.EMBEDDED && candidate.key == selection.embeddedTrackKey
                            SubtitleSelectionMode.EXTERNAL -> candidate.externalSubtitleId == selection.externalSubtitleId
                            else -> false
                        } && candidate.supported
                    }
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
                    option?.let { builder.setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.trackIndex)) }
                }
            }
        }
        val parameters = builder.build()
        if (parameters != activePlayer.trackSelectionParameters) activePlayer.trackSelectionParameters = parameters
    }

    private fun updateAudioTrackStatus(tracks: Tracks) {
        val audioTracks = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).map { index -> Triple(group, index, group.getTrackFormat(index)) } }
        if (audioTracks.isEmpty() || audioTracks.any { (group, index, _) -> group.isTrackSelected(index) && group.isTrackSupported(index) }) {
            playbackMessage = null
            return
        }
        val supportedTrack = audioTracks.firstOrNull { (group, index, _) -> group.isTrackSupported(index) }
        if (supportedTrack != null) {
            val (group, index) = supportedTrack
            playerValue?.let { activePlayer ->
                activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
            }
            playbackMessage = null
            return
        }
        val codecNames = audioTracks.map { (_, _, format) -> audioFormatLabel(format) }.distinct().joinToString(", ").ifBlank { "unknown codec" }
        playbackMessage = "Audio unavailable: $codecNames is not supported on this device."
    }

    private fun startPositionUpdates() {
        handler.removeCallbacks(positionUpdateRunnable)
        handler.post(positionUpdateRunnable)
    }

    private fun resetProgressTapGesture() {
        handler.removeCallbacks(progressForwardRunnable)
        lastProgressTapAt = 0L
    }

    private val progressForwardRunnable = Runnable {
        seekBy(PROGRESS_SEEK_MS)
        lastProgressTapAt = 0L
    }

    private fun notifyChanged() {
        observer?.onPlaybackStateChanged()
    }

    private data class SubtitleTrackOptionInternal(
        val key: String,
        val group: Tracks.Group,
        val trackIndex: Int,
        val kind: SubtitleTrackKind,
        val externalSubtitleId: Long?,
        val label: String,
        val supported: Boolean,
        val selected: Boolean,
    ) {
        val publicOption: SubtitleTrackOption
            get() = SubtitleTrackOption(key, kind, externalSubtitleId, label, supported, selected)
    }

    private fun subtitleFormatLabel(format: Format): String? = when (format.sampleMimeType) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_VTT -> "WebVTT"
        MimeTypes.TEXT_SSA -> "SSA/ASS"
        MimeTypes.APPLICATION_TTML -> "TTML"
        else -> format.sampleMimeType?.substringAfterLast('/')?.uppercase(Locale.US)
    }

    private fun audioFormatLabel(format: Format): String = when (format.sampleMimeType) {
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/eac3-joc" -> "E-AC-3 JOC"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/true-hd" -> "TrueHD"
        "audio/opus" -> "Opus"
        "audio/flac" -> "FLAC"
        "audio/mp4a-latm" -> "AAC"
        else -> format.sampleMimeType ?: format.codecs ?: "unknown codec"
    }

    private fun embeddedSubtitleTrackKey(group: Tracks.Group, trackIndex: Int, format: Format): String {
        return listOf(
            group.mediaTrackGroup.id,
            format.language ?: C.LANGUAGE_UNDETERMINED,
            format.sampleMimeType ?: "unknown",
            format.codecs ?: "",
            trackIndex.toString(),
        ).joinToString("|")
    }

    private fun externalSubtitleTrackId(id: Long): String = "$EXTERNAL_SUBTITLE_TRACK_PREFIX$id"

    private fun normalizeQueue(initialUri: Uri, initialDisplayName: String, items: List<VideoItem>): List<VideoItem> {
        val result = items.filter { it.contentUri != initialUri }.sortedBy { it.displayName.lowercase(Locale.US) }.toMutableList()
        val initial = items.firstOrNull { it.contentUri == initialUri } ?: VideoItem(initialUri.hashCode().toLong(), initialUri, initialDisplayName)
        result += initial
        return result.sortedBy { it.displayName.lowercase(Locale.US) }
    }

    companion object {
        private const val TAG = "VideoPlayerController"
        private const val POSITION_UPDATE_MS = 500L
        private const val PROGRESS_SEEK_MS = 10_000L
        private const val PROGRESS_DOUBLE_TAP_WINDOW_MS = 350L
        private const val PLAYBACK_RESTART_THRESHOLD_MS = 1_000L
        private const val EXTERNAL_SUBTITLE_TRACK_PREFIX = "external:"
    }
}
