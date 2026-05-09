package com.everyday.shared.sync

enum class SubtitleSource(val wireName: String) {
    PHONE_PLAYBACK("phone_playback"),
    MICROPHONE("microphone"),
    GLASSES_PLAYBACK("glasses_playback");

    companion object {
        fun fromWireName(value: String?): SubtitleSource {
            return values().firstOrNull { it.wireName == value } ?: PHONE_PLAYBACK
        }
    }
}

enum class SubtitleControlAction(val wireName: String) {
    START("start"),
    STOP("stop"),
    SET_OPTIONS("set_options");

    companion object {
        fun fromWireName(value: String?): SubtitleControlAction? {
            return values().firstOrNull { it.wireName == value }
        }
    }
}

enum class SubtitleStatusState(val wireName: String) {
    MODEL_MISSING("model_missing"),
    DOWNLOADING_MODEL("downloading_model"),
    PERMISSION_NEEDED("permission_needed"),
    LISTENING("listening"),
    STOPPED("stopped"),
    ERROR("error");

    companion object {
        fun fromWireName(value: String?): SubtitleStatusState? {
            return values().firstOrNull { it.wireName == value }
        }
    }
}

data class SubtitleOptions(
    val source: SubtitleSource = SubtitleSource.PHONE_PLAYBACK,
    val languageTag: String = "en-US",
    val phonePlaybackEnabled: Boolean = true,
    val microphoneEnabled: Boolean = false,
    val translationEnabled: Boolean = false
)

data class SubtitleControl(
    val action: SubtitleControlAction,
    val options: SubtitleOptions = SubtitleOptions(),
    val requestedAtMs: Long = System.currentTimeMillis()
)

data class SubtitleStatus(
    val state: SubtitleStatusState,
    val message: String? = null,
    val sessionId: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class SubtitleTranscript(
    val sessionId: String,
    val segmentId: Long,
    val text: String,
    val isFinal: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)
