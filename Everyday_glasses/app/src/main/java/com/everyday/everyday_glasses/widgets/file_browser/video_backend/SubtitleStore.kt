package com.everyday.everyday_glasses.widgets.file_browser.video_backend

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import androidx.core.content.edit
import androidx.core.net.toUri

class PrefsSubtitleStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val importPreparer = SubtitleImportPreparer(appContext)

    fun load(videoUri: String): VideoSubtitleState {
        val json = readJson(videoUri) ?: return VideoSubtitleState()
        val subtitles = json.optJSONArray("subtitles")?.let(::parseSubtitles).orEmpty()
        val selection = json.optJSONObject("selection")?.let(::parseSelection)
        val validSelection = when (selection?.mode) {
            SubtitleSelectionMode.EXTERNAL -> selection.takeIf { selected ->
                subtitles.any { it.id == selected.externalSubtitleId }
            }
            else -> selection
        }
        return VideoSubtitleState(subtitles, validSelection)
    }

    suspend fun addSubtitle(videoUri: String, contentUri: Uri): UploadedSubtitle {
        val displayName = queryDisplayName(contentUri) ?: contentUri.lastPathSegment ?: "Subtitle"
        val prepared = importPreparer.prepare(
            videoId = videoUri.hashCode().toLong(),
            contentUri = contentUri,
            displayName = displayName,
            rawMimeType = appContext.contentResolver.getType(contentUri),
        )
        val current = load(videoUri)
        val existing = current.subtitles.firstOrNull { it.contentUri.toString() == prepared.playbackUri.toString() }
        val subtitle = existing?.copy(
            displayName = displayName,
            mimeType = prepared.mimeType,
            createdAtEpochMs = System.currentTimeMillis(),
        ) ?: UploadedSubtitle(
            id = stableSubtitleId(videoUri, prepared.playbackUri.toString()),
            contentUri = prepared.playbackUri,
            displayName = displayName,
            mimeType = prepared.mimeType,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val subtitles = current.subtitles.filterNot { it.id == subtitle.id } + subtitle
        write(videoUri, VideoSubtitleState(subtitles, SubtitleSelection(SubtitleSelectionMode.EXTERNAL, subtitle.id)))
        return subtitle
    }

    fun selectNone(videoUri: String) {
        val state = load(videoUri)
        write(videoUri, state.copy(selection = SubtitleSelection(SubtitleSelectionMode.NONE)))
    }

    fun selectExternal(videoUri: String, subtitleId: Long) {
        val state = load(videoUri)
        if (state.subtitles.none { it.id == subtitleId }) return
        write(videoUri, state.copy(selection = SubtitleSelection(SubtitleSelectionMode.EXTERNAL, subtitleId)))
    }

     fun selectEmbedded(videoUri: String, trackKey: String) {
        val state = load(videoUri)
        write(videoUri, state.copy(selection = SubtitleSelection(SubtitleSelectionMode.EMBEDDED, embeddedTrackKey = trackKey)))
    }

    private fun readJson(videoUri: String): JSONObject? {
        val stored = preferences.getString(keyFor(videoUri), null) ?: return null
        return runCatching { JSONObject(stored) }.getOrNull()
    }

    private fun write(videoUri: String, state: VideoSubtitleState) {
        val json = JSONObject()
            .put("subtitles", JSONArray().apply {
                state.subtitles.forEach { subtitle ->
                    put(JSONObject()
                        .put("id", subtitle.id)
                        .put("uri", subtitle.contentUri.toString())
                        .put("displayName", subtitle.displayName)
                        .put("mimeType", subtitle.mimeType)
                        .put("createdAt", subtitle.createdAtEpochMs))
                }
            })
        state.selection?.let { selection ->
            json.put("selection", JSONObject()
                .put("mode", selection.mode.name)
                .put("externalId", selection.externalSubtitleId)
                .put("embeddedKey", selection.embeddedTrackKey))
        }
        preferences.edit { putString(keyFor(videoUri), json.toString()) }
    }

    private fun parseSubtitles(array: JSONArray): List<UploadedSubtitle> = buildList {
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val uri = json.optString("uri").takeIf { it.isNotBlank() } ?: continue
            add(UploadedSubtitle(
                id = json.optLong("id", uri.hashCode().toLong()),
                contentUri = uri.toUri(),
                displayName = json.optString("displayName", "Subtitle"),
                mimeType = json.optString("mimeType", "application/x-subrip"),
                createdAtEpochMs = json.optLong("createdAt", 0L),
            ))
        }
    }

    private fun parseSelection(json: JSONObject): SubtitleSelection? {
        val mode = runCatching { SubtitleSelectionMode.valueOf(json.optString("mode")) }.getOrNull() ?: return null
        return SubtitleSelection(
            mode = mode,
            externalSubtitleId = json.optLong("externalId", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
            embeddedTrackKey = json.optString("embeddedKey").takeIf { it.isNotBlank() },
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun stableSubtitleId(videoUri: String, subtitleUri: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$videoUri\n$subtitleUri".toByteArray(StandardCharsets.UTF_8))
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (digest[index].toLong() and 0xffL) }
        return value and Long.MAX_VALUE
    }

    private fun keyFor(videoUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(videoUri.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "video_$digest"
    }

    companion object {
        private const val PREFS_NAME = "video_subtitles"
    }
}

