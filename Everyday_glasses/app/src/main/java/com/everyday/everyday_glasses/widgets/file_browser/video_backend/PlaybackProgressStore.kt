package com.everyday.everyday_glasses.widgets.file_browser.video_backend

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import androidx.core.content.edit

interface PlaybackProgressStore {
    fun load(uri: String): PlaybackProgress?

    fun save(
        uri: String,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    )

    fun clear(uri: String)
}

class PrefsPlaybackProgressStore(
    context: Context,
) : PlaybackProgressStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(uri: String): PlaybackProgress? {
        val key = keyFor(uri)
        val stored = preferences.getString(key, null)
        if (!stored.isNullOrBlank()) {
            return runCatching {
                JSONObject(stored).let { json ->
                    PlaybackProgress(
                        videoId = json.optLong("videoId", uri.hashCode().toLong()),
                        uri = json.optString("uri", uri),
                        resumePositionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                        durationMs = json.optLong("durationMs", 0L).coerceAtLeast(0L),
                        completed = json.optBoolean("completed", false),
                        lastPlayedAt = json.optLong("lastPlayedAt", 0L),
                    )
                }
            }.getOrNull()
        }

        // The original widget stored only a raw position under this same URI hash.
        // Preserve that progress when upgrading to the richer X3player record.
        val legacyPosition = preferences.getLong(legacyKeyFor(uri), Long.MIN_VALUE)
        return legacyPosition.takeIf { it != Long.MIN_VALUE }?.let {
            PlaybackProgress(
                videoId = uri.hashCode().toLong(),
                uri = uri,
                resumePositionMs = it.coerceAtLeast(0L),
                durationMs = 0L,
                completed = false,
                lastPlayedAt = 0L,
            )
        }
    }

    override fun save(uri: String, positionMs: Long, durationMs: Long, completed: Boolean) {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val json = JSONObject()
            .put("videoId", uri.hashCode().toLong())
            .put("uri", uri)
            .put("positionMs", safePosition)
            .put("durationMs", safeDuration)
            .put("completed", completed)
            .put("lastPlayedAt", System.currentTimeMillis())
        preferences.edit {
            remove(legacyKeyFor(uri))
                .putString(keyFor(uri), json.toString())
        }
    }

    override fun clear(uri: String) {
        preferences.edit {
            remove(keyFor(uri))
                .remove(legacyKeyFor(uri))
        }
    }

    private fun keyFor(uri: String): String {
        return "progress_${digestFor(uri)}"
    }

    private fun legacyKeyFor(uri: String): String = digestFor(uri)

    private fun digestFor(uri: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        private const val PREFS_NAME = "video_playback_progress"
    }
}
