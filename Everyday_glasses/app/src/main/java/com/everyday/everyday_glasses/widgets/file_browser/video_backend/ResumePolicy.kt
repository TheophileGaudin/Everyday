package com.everyday.everyday_glasses.widgets.file_browser.video_backend

private const val MIN_RESUME_POSITION_MS = 30_000L
private const val MAX_COMPLETION_THRESHOLD = 0.95f

fun resolveResumePosition(progress: PlaybackProgress?, autoResumeEnabled: Boolean): Long {
    if (!autoResumeEnabled || progress == null) return 0L
    if (progress.completed) return 0L
    if (progress.durationMs <= 0L) return 0L
    if (progress.resumePositionMs < MIN_RESUME_POSITION_MS) return 0L
    if (progress.resumePositionMs >= progress.durationMs * MAX_COMPLETION_THRESHOLD) return 0L
    return progress.resumePositionMs
}

fun shouldMarkCompleted(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    return positionMs >= durationMs * MAX_COMPLETION_THRESHOLD
}
