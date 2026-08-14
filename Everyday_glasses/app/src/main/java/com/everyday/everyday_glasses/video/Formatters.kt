package com.everyday.everyday_glasses.video

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

fun formatDuration(durationMs: Long): String {
    val totalSeconds = max(durationMs, 0L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatDate(timestampMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))
}
