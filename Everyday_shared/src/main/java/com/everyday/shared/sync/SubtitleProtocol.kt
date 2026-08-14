package com.everyday.shared.sync

import org.json.JSONObject

internal object SubtitleProtocol {
    internal fun controlToJson(control: SubtitleControl): JSONObject {
        return JSONObject()
            .put("action", control.action.wireName)
            .put("source", control.options.source.wireName)
            .put("language", control.options.languageTag)
            .put("phonePlaybackEnabled", control.options.phonePlaybackEnabled)
            .put("microphoneEnabled", control.options.microphoneEnabled)
            .put("translationEnabled", control.options.translationEnabled)
            .put("requestedAtMs", control.requestedAtMs)
    }

    internal fun statusToJson(status: SubtitleStatus): JSONObject {
        val json = JSONObject()
            .put("state", status.state.wireName)
            .put("timestampMs", status.timestampMs)

        status.message?.let { json.put("message", it) }
        status.sessionId?.let { json.put("sessionId", it) }
        return json
    }

    internal fun transcriptToJson(transcript: SubtitleTranscript): JSONObject {
        return JSONObject()
            .put("sessionId", transcript.sessionId)
            .put("segmentId", transcript.segmentId)
            .put("text", transcript.text)
            .put("isFinal", transcript.isFinal)
            .put("timestampMs", transcript.timestampMs)
    }

    internal fun controlFromJson(json: JSONObject): SubtitleControl? {
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

    internal fun statusFromJson(json: JSONObject): SubtitleStatus? {
        val state = SubtitleStatusState.fromWireName(json.optString("state")) ?: return null
        return SubtitleStatus(
            state = state,
            message = json.optString("message").takeIf { it.isNotBlank() },
            sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
            timestampMs = json.optLong("timestampMs", System.currentTimeMillis())
        )
    }

    internal fun transcriptFromJson(json: JSONObject): SubtitleTranscript? {
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

}
