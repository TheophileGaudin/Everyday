package com.everyday.everyday_glasses.video

import android.net.Uri

data class VideoItem(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
)

data class PlaybackProgress(
    val videoId: Long,
    val uri: String,
    val resumePositionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val lastPlayedAt: Long,
)

data class UploadedSubtitle(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val createdAtEpochMs: Long,
)

enum class SubtitleSelectionMode {
    NONE,
    EMBEDDED,
    EXTERNAL,
}

data class SubtitleSelection(
    val mode: SubtitleSelectionMode,
    val externalSubtitleId: Long? = null,
    val embeddedTrackKey: String? = null,
)

data class VideoSubtitleState(
    val subtitles: List<UploadedSubtitle> = emptyList(),
    val selection: SubtitleSelection? = null,
) {
    val selectedSubtitle: UploadedSubtitle?
        get() = selection
            ?.takeIf { it.mode == SubtitleSelectionMode.EXTERNAL }
            ?.externalSubtitleId
            ?.let { selectedId -> subtitles.firstOrNull { it.id == selectedId } }
}

