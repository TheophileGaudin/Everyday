package com.everyday.everyday_glasses.video

import androidx.media3.common.MimeTypes
import kotlin.math.roundToLong

internal data class NormalizedSubtitleText(
    val mimeType: String,
    val fileExtension: String,
    val content: String,
)

internal fun normalizeTextSubtitleForPlayback(
    importType: SubtitleImportType,
    sourceText: String,
): NormalizedSubtitleText? {
    val normalizedText = normalizeLineEndings(sourceText).trimStart('\uFEFF')
    return when (importType) {
        SubtitleImportType.SUBRIP -> normalizedText
            .takeIf(::looksLikeSubRip)
            ?.let {
                NormalizedSubtitleText(
                    mimeType = MimeTypes.APPLICATION_SUBRIP,
                    fileExtension = "srt",
                    content = it.trim(),
                )
            }
        SubtitleImportType.WEBVTT -> normalizedText
            .takeIf { looksLikeWebVtt(it) && WEBVTT_TIMESTAMP_REGEX.containsMatchIn(it) }
            ?.let {
                NormalizedSubtitleText(
                    mimeType = MimeTypes.TEXT_VTT,
                    fileExtension = "vtt",
                    content = it.trim(),
                )
            }
        SubtitleImportType.SSA -> normalizedText
            .takeIf { looksLikeSsa(it) && SSA_DIALOGUE_REGEX.containsMatchIn(it) }
            ?.let {
                NormalizedSubtitleText(
                    mimeType = MimeTypes.TEXT_SSA,
                    fileExtension = "ssa",
                    content = it.trim(),
                )
            }
        SubtitleImportType.TTML -> normalizedText
            .takeIf(::looksLikeTtml)
            ?.let {
                NormalizedSubtitleText(
                    mimeType = MimeTypes.APPLICATION_TTML,
                    fileExtension = "ttml",
                    content = it.trim(),
                )
            }
        SubtitleImportType.SUB,
        SubtitleImportType.TEXT_AUTO -> normalizeUnknownTextSubtitleForPlayback(normalizedText)
    }
}

private fun normalizeUnknownTextSubtitleForPlayback(sourceText: String): NormalizedSubtitleText? {
    val trimmedText = sourceText.trim()
    if (trimmedText.isBlank()) return null

    if (looksLikeWebVtt(trimmedText) && WEBVTT_TIMESTAMP_REGEX.containsMatchIn(trimmedText)) {
        return NormalizedSubtitleText(
            mimeType = MimeTypes.TEXT_VTT,
            fileExtension = "vtt",
            content = trimmedText,
        )
    }

    if (looksLikeSubRip(trimmedText)) {
        return NormalizedSubtitleText(
            mimeType = MimeTypes.APPLICATION_SUBRIP,
            fileExtension = "srt",
            content = trimmedText,
        )
    }

    if (looksLikeSsa(trimmedText) && SSA_DIALOGUE_REGEX.containsMatchIn(trimmedText)) {
        return NormalizedSubtitleText(
            mimeType = MimeTypes.TEXT_SSA,
            fileExtension = "ssa",
            content = trimmedText,
        )
    }

    if (looksLikeTtml(trimmedText) && TTML_TIMING_REGEX.containsMatchIn(trimmedText)) {
        return NormalizedSubtitleText(
            mimeType = MimeTypes.APPLICATION_TTML,
            fileExtension = "ttml",
            content = trimmedText,
        )
    }

    val transcoded = convertTextBasedSubToSubRip(trimmedText) ?: return null
    return NormalizedSubtitleText(
        mimeType = MimeTypes.APPLICATION_SUBRIP,
        fileExtension = "srt",
        content = transcoded,
    )
}

private fun looksLikeWebVtt(sourceText: String): Boolean {
    return sourceText.startsWith("WEBVTT", ignoreCase = true)
}

private fun looksLikeSubRip(sourceText: String): Boolean {
    return SUBRIP_TIMESTAMP_REGEX.containsMatchIn(sourceText)
}

private fun looksLikeSsa(sourceText: String): Boolean {
    return sourceText.contains("[Script Info]", ignoreCase = true) ||
        sourceText.contains("[Events]", ignoreCase = true)
}

private fun looksLikeTtml(sourceText: String): Boolean {
    return sourceText.contains("<tt", ignoreCase = true) &&
        sourceText.contains("</tt>", ignoreCase = true)
}

private fun convertTextBasedSubToSubRip(sourceText: String): String? {
    return convertMicroDvdToSubRip(sourceText)
        ?: convertSubViewerToSubRip(sourceText)
        ?: convertMpl2ToSubRip(sourceText)
}

private fun convertMicroDvdToSubRip(sourceText: String): String? {
    val cues = mutableListOf<SubtitleCue>()
    var fps = DEFAULT_MICRO_DVD_FPS

    for (line in sourceText.lineSequence().map { it.trim() }) {
        if (line.isBlank()) continue
        val match = MICRO_DVD_LINE_REGEX.matchEntire(line) ?: return null
        val startFrame = match.groupValues[1].toLongOrNull() ?: return null
        val endFrame = match.groupValues[2].toLongOrNull() ?: return null
        val payload = match.groupValues[3].trim()

        if (cues.isEmpty() && startFrame == 1L && endFrame == 1L) {
            payload.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { fps = it }
                ?: return null
            continue
        }

        if (endFrame <= startFrame) continue

        val text = normalizeSubtitlePayload(payload)
        if (text.isBlank()) continue

        cues += SubtitleCue(
            startMs = framesToMilliseconds(startFrame, fps),
            endMs = framesToMilliseconds(endFrame, fps),
            text = text,
        )
    }

    return buildSubRip(cues)
}

private fun convertSubViewerToSubRip(sourceText: String): String? {
    val lines = sourceText.lines()
    val cues = mutableListOf<SubtitleCue>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isBlank() -> index += 1
            line.startsWith("[") -> index += 1
            else -> {
                val match = SUB_VIEWER_TIMING_REGEX.matchEntire(line) ?: return null
                index += 1
                val textLines = mutableListOf<String>()
                while (index < lines.size && lines[index].isNotBlank()) {
                    textLines += lines[index].trimEnd()
                    index += 1
                }

                val text = textLines.joinToString(separator = "\n").trim()
                if (text.isNotBlank()) {
                    cues += SubtitleCue(
                        startMs = parseClockTimeToMilliseconds(match.groupValues[1]),
                        endMs = parseClockTimeToMilliseconds(match.groupValues[2]),
                        text = normalizeSubtitlePayload(text),
                    )
                }
            }
        }
    }

    return buildSubRip(cues)
}

private fun convertMpl2ToSubRip(sourceText: String): String? {
    val cues = mutableListOf<SubtitleCue>()

    for (line in sourceText.lineSequence().map { it.trim() }) {
        if (line.isBlank()) continue
        val match = MPL2_LINE_REGEX.matchEntire(line) ?: return null
        val startUnits = match.groupValues[1].toLongOrNull() ?: return null
        val endUnits = match.groupValues[2].toLongOrNull() ?: return null
        if (endUnits <= startUnits) continue

        val text = normalizeSubtitlePayload(match.groupValues[3])
        if (text.isBlank()) continue

        cues += SubtitleCue(
            startMs = startUnits * 100L,
            endMs = endUnits * 100L,
            text = text,
        )
    }

    return buildSubRip(cues)
}

private fun normalizeSubtitlePayload(payload: String): String {
    return payload
        .replace("|", "\n")
        .replace("\\N", "\n")
        .trim()
}

private fun buildSubRip(cues: List<SubtitleCue>): String? {
    if (cues.isEmpty()) return null

    return buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1)
            append('\n')
            append(formatSubRipTimestamp(cue.startMs))
            append(" --> ")
            append(formatSubRipTimestamp(cue.endMs))
            append('\n')
            append(cue.text)
            append("\n\n")
        }
    }.trim()
}

private fun framesToMilliseconds(frameNumber: Long, fps: Double): Long {
    return ((frameNumber * 1000.0) / fps).roundToLong()
}

private fun parseClockTimeToMilliseconds(rawValue: String): Long {
    val parts = rawValue.trim().replace(',', '.').split(':', '.')
    require(parts.size == 4) { "Unsupported subtitle timestamp: $rawValue" }
    val hours = parts[0].toLong()
    val minutes = parts[1].toLong()
    val seconds = parts[2].toLong()
    val fraction = parts[3]
    val millis = when (fraction.length) {
        1 -> fraction.toLong() * 100L
        2 -> fraction.toLong() * 10L
        else -> fraction.take(3).toLong()
    }
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

private fun formatSubRipTimestamp(timeMs: Long): String {
    val safeTimeMs = timeMs.coerceAtLeast(0L)
    val totalSeconds = safeTimeMs / 1_000L
    val milliseconds = safeTimeMs % 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, milliseconds)
}

private fun normalizeLineEndings(sourceText: String): String {
    return sourceText
        .replace("\r\n", "\n")
        .replace('\r', '\n')
}

private data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

private val MICRO_DVD_LINE_REGEX = Regex("""^\{(\d+)\}\{(\d+)\}(.*)$""")
private val MPL2_LINE_REGEX = Regex("""^\[(\d+)]\[(\d+)](.*)$""")
private val SUB_VIEWER_TIMING_REGEX =
    Regex("""^(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})\s*,\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})$""")
private val SUBRIP_TIMESTAMP_REGEX =
    Regex("""\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}""")
private val WEBVTT_TIMESTAMP_REGEX =
    Regex("""(?:\d{1,2}:)?\d{2}:\d{2}\.\d{3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}\.\d{3}""")
private val SSA_DIALOGUE_REGEX = Regex("""(?im)^\s*Dialogue\s*:""")
private val TTML_TIMING_REGEX = Regex("""(?i)\b(?:begin|end|dur)\s*=""")

private const val DEFAULT_MICRO_DVD_FPS = 25.0
