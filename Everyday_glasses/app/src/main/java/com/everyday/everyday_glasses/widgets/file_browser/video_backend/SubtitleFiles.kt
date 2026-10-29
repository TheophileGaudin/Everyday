package com.everyday.everyday_glasses.widgets.file_browser.video_backend

import androidx.media3.common.MimeTypes
import java.util.Locale

enum class SubtitleImportType(
    val playbackMimeType: String,
) {
    SUBRIP(MimeTypes.APPLICATION_SUBRIP),
    WEBVTT(MimeTypes.TEXT_VTT),
    SSA(MimeTypes.TEXT_SSA),
    TTML(MimeTypes.APPLICATION_TTML),
    SUB(MimeTypes.APPLICATION_SUBRIP),
    TEXT_AUTO(MimeTypes.APPLICATION_SUBRIP),
}

fun resolveSupportedSubtitleImportType(rawMimeType: String?, displayName: String?): SubtitleImportType? {
    val normalizedMimeType = rawMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)

    when (normalizedMimeType) {
        "application/x-subrip",
        "application/srt",
        "text/srt" -> return SubtitleImportType.SUBRIP

        MimeTypes.TEXT_VTT,
        "application/x-webvtt",
        "application/vtt" -> return SubtitleImportType.WEBVTT

        "text/x-ssa",
        "text/x-ass",
        "application/x-ass" -> return SubtitleImportType.SSA

        "application/ttml+xml" -> return SubtitleImportType.TTML

        "application/x-subviewer",
        "text/x-subviewer",
        "text/x-microdvd",
        "text/x-mpl2" -> return SubtitleImportType.SUB
    }

    val extension = subtitleExtension(displayName)

    return when (extension) {
        "srt" -> SubtitleImportType.SUBRIP
        "vtt", "vvt" -> SubtitleImportType.WEBVTT
        "ssa", "ass" -> SubtitleImportType.SSA
        "ttml", "dfxp", "xml" -> SubtitleImportType.TTML
        "sub" -> SubtitleImportType.SUB
        "txt" -> SubtitleImportType.TEXT_AUTO
        else -> null
    }
}

fun resolveSupportedSubtitleMimeType(rawMimeType: String?, displayName: String?): String? {
    return resolveSupportedSubtitleImportType(rawMimeType, displayName)?.playbackMimeType
}

fun isSupportedSubtitleFileName(displayName: String?): Boolean {
    return resolveSupportedSubtitleImportType(null, displayName) != null
}

fun subtitleExtension(displayName: String?): String {
    return displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
        .orEmpty()
}
