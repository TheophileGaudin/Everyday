package com.everyday.shared.sync

import org.json.JSONObject

object SubtitleProtocol {
    private const val FIELD_EVENT = "event"
    private const val EVENT_CONTROL = "subtitle_control"
    private const val EVENT_STATUS = "subtitle_status"
    private const val EVENT_TRANSCRIPT = "subtitle_transcript"

    fun encodeControl(control: SubtitleControl): String {
        return JSONObject()
            .put(FIELD_EVENT, EVENT_CONTROL)
            .put("action", control.action.wireName)
            .put("source", control.options.source.wireName)
            .put("language", control.options.languageTag)
            .put("phonePlaybackEnabled", control.options.phonePlaybackEnabled)
            .put("microphoneEnabled", control.options.microphoneEnabled)
            .put("translationEnabled", control.options.translationEnabled)
            .put("requestedAtMs", control.requestedAtMs)
            .toString()
    }

    fun encodeStatus(status: SubtitleStatus): String {
        val json = JSONObject()
            .put(FIELD_EVENT, EVENT_STATUS)
            .put("state", status.state.wireName)
            .put("timestampMs", status.timestampMs)

        status.message?.let { json.put("message", it) }
        status.sessionId?.let { json.put("sessionId", it) }
        return json.toString()
    }

    fun encodeTranscript(transcript: SubtitleTranscript): String {
        return JSONObject()
            .put(FIELD_EVENT, EVENT_TRANSCRIPT)
            .put("sessionId", transcript.sessionId)
            .put("segmentId", transcript.segmentId)
            .put("text", transcript.text)
            .put("isFinal", transcript.isFinal)
            .put("timestampMs", transcript.timestampMs)
            .toString()
    }

    fun decodeControl(raw: String): SubtitleControl? {
        val json = parseEvent(raw, EVENT_CONTROL) ?: return null
        val action = SubtitleControlAction.fromWireName(json.optString("action")) ?: return null
        return SubtitleControl(
            action = action,
            options = SubtitleOptions(
                source = SubtitleSource.fromWireName(json.optString("source")),
                languageTag = json.optString("language", "en-US"),
                phonePlaybackEnabled = json.optBoolean("phonePlaybackEnabled", true),
                microphoneEnabled = json.optBoolean("microphoneEnabled", false),
                translationEnabled = json.optBoolean("translationEnabled", false)
            ),
            requestedAtMs = json.optLong("requestedAtMs", System.currentTimeMillis())
        )
    }

    fun decodeStatus(raw: String): SubtitleStatus? {
        val json = parseEvent(raw, EVENT_STATUS) ?: return null
        val state = SubtitleStatusState.fromWireName(json.optString("state")) ?: return null
        return SubtitleStatus(
            state = state,
            message = json.optString("message").takeIf { it.isNotBlank() },
            sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
            timestampMs = json.optLong("timestampMs", System.currentTimeMillis())
        )
    }

    fun decodeTranscript(raw: String): SubtitleTranscript? {
        val json = parseEvent(raw, EVENT_TRANSCRIPT) ?: return null
        val sessionId = json.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
        val text = json.optString("text")
        return SubtitleTranscript(
            sessionId = sessionId,
            segmentId = json.optLong("segmentId"),
            text = text,
            isFinal = json.optBoolean("isFinal", false),
            timestampMs = json.optLong("timestampMs", System.currentTimeMillis())
        )
    }

    private fun parseEvent(raw: String, expectedEvent: String): JSONObject? {
        return try {
            val json = JSONObject(raw)
            if (json.optString(FIELD_EVENT) == expectedEvent) json else null
        } catch (_: Exception) {
            null
        }
    }
}
