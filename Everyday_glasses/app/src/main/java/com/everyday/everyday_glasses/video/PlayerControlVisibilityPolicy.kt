package com.everyday.everyday_glasses.video

internal fun shouldAutoHidePlayerControls(
    controlsVisible: Boolean,
    isPlaying: Boolean,
    hasOpenOverlay: Boolean,
    hasFatalError: Boolean,
): Boolean = controlsVisible && isPlaying && !hasOpenOverlay && !hasFatalError

internal fun resolveSeekTarget(
    currentPositionMs: Long,
    durationMs: Long?,
    deltaMs: Long,
): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    val target = current + deltaMs
    return durationMs
        ?.takeIf { it > 0L }
        ?.let { target.coerceIn(0L, it) }
        ?: target.coerceAtLeast(0L)
}

internal fun shouldAutoSelectEmbeddedSubtitle(
    isEmbedded: Boolean,
    isSupported: Boolean,
    isForced: Boolean,
    isDefault: Boolean,
): Boolean = isEmbedded && isSupported && (isForced || isDefault)

