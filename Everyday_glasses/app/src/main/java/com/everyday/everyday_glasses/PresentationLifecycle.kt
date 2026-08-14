package com.everyday.everyday_glasses

import android.os.Handler
import kotlin.math.min

enum class PresentationMode {
    FULLSCREEN,
    WINDOWED
}

enum class PresentationExpiryPolicy {
    RESTORE_PREVIOUS,
    LEAVE_WINDOWED,
    CLOSE
}

/** The temporary presentation contract shared by assistant features and the widget host. */
data class PresentationSpec(
    val mode: PresentationMode,
    val durationSeconds: Int,
    val onExpiry: PresentationExpiryPolicy
) {
    init {
        require(durationSeconds in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
            "Presentation duration must be between $MIN_DURATION_SECONDS and $MAX_DURATION_SECONDS seconds"
        }
    }

    companion object {
        const val MIN_DURATION_SECONDS = 5
        const val MAX_DURATION_SECONDS = 120
    }
}

/** Bounds are kept platform-independent so the lifecycle state machine is unit-testable. */
data class PresentationBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * The exact widget state needed to undo a temporary presentation.
 *
 * [bounds] is the currently rendered rectangle. [windowedBounds] is the reversible, normal
 * windowed rectangle, including the saved rectangle of a widget that was already fullscreen.
 */
data class PresentationSnapshot(
    val existedBeforePresentation: Boolean,
    val visible: Boolean,
    val bounds: PresentationBounds,
    val fullscreen: Boolean,
    val focused: Boolean,
    val zOrder: Int,
    val windowedBounds: PresentationBounds = bounds,
    val minimized: Boolean = !visible,
    val wasFullscreenBeforeMinimize: Boolean = false,
    val pinned: Boolean = true
)

typealias PresentationWidgetSnapshot = PresentationSnapshot

enum class PresentationEndReason {
    EXPIRED,
    REPLACED,
    USER_CANCELLED,
    EXPLICIT_DISMISSAL,
    TARGET_REMOVED
}

data class PresentationSession(
    val token: Long,
    val targetId: String,
    val spec: PresentationSpec,
    val startedAtElapsedMs: Long,
    val originalDurationSeconds: Int,
    val snapshot: PresentationSnapshot
) {
    val durationMs: Long get() = originalDurationSeconds.toLong() * 1_000L
    val expiresAtElapsedMs: Long get() = startedAtElapsedMs + durationMs
}

data class PresentationProgress(
    val targetId: String,
    val elapsedMs: Long,
    val durationMs: Long,
    val fraction: Float
)

fun interface PresentationCancellable {
    fun cancel()
}

fun interface PresentationScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): PresentationCancellable
}

/** Handler-backed scheduling used by the glasses UI thread. */
class HandlerPresentationScheduler(private val handler: Handler) : PresentationScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): PresentationCancellable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
        return PresentationCancellable { handler.removeCallbacks(runnable) }
    }
}

/**
 * Owns exactly one temporary presentation session.
 *
 * The controller is deliberately unaware of Android widgets. The host supplies a stable target
 * snapshot and applies the small set of state transitions required by each expiry policy. This
 * keeps replacement, cancellation, and stale-timer behavior deterministic and easy to test.
 */
class PresentationController(
    private val scheduler: PresentationScheduler,
    private val nowElapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onPresentationStarted: (PresentationSession) -> Unit = {},
    private val onRestorePrevious: (PresentationSession) -> Unit = {},
    private val onLeaveWindowed: (PresentationSession) -> Unit = {},
    private val onClose: (String) -> Unit = {},
    private val onProgressChanged: (PresentationProgress?) -> Unit = {},
    private val onSessionEnded: (PresentationSession, PresentationEndReason) -> Unit = { _, _ -> }
) {
    companion object {
        // A four-Hz ring is visually legible at 640x480 without keeping the full AR scene on a
        // 20-Hz redraw loop for presentations that can last up to two minutes.
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    }

    private var nextToken = 1L
    private var timer: PresentationCancellable? = null

    var activeSession: PresentationSession? = null
        private set

    val isActive: Boolean get() = activeSession != null

    /**
     * Starts a presentation after deterministically restoring any previous session.
     * [snapshotProvider] runs only after the previous session has ended.
     */
    fun start(
        targetId: String,
        spec: PresentationSpec,
        snapshotProvider: () -> PresentationSnapshot?
    ): PresentationSession? {
        require(targetId.isNotBlank()) { "Presentation target ID must not be blank" }
        finish(PresentationEndReason.REPLACED)

        val snapshot = snapshotProvider() ?: return null
        val startedAt = nowElapsedMs()
        val session = PresentationSession(
            token = nextToken++,
            targetId = targetId,
            spec = spec,
            startedAtElapsedMs = startedAt,
            originalDurationSeconds = spec.durationSeconds,
            snapshot = snapshot
        )
        activeSession = session
        onProgressChanged(progressAt(session, startedAt))
        try {
            onPresentationStarted(session)
            scheduleNext(session)
        } catch (failure: Throwable) {
            finish(PresentationEndReason.EXPLICIT_DISMISSAL)
            throw failure
        }
        return session
    }

    /** Cancels the timer and leaves the target exactly as it is at the point of cancellation. */
    fun cancelForUserInput(): Boolean = finish(PresentationEndReason.USER_CANCELLED)

    /** Explicit dismissal has the same non-restoring semantics as user cancellation. */
    fun dismiss(): Boolean = finish(PresentationEndReason.EXPLICIT_DISMISSAL)

    /** Ends a session without applying an expiry policy when its target disappears. */
    fun onTargetRemoved(targetId: String): Boolean {
        if (activeSession?.targetId != targetId) return false
        return finish(PresentationEndReason.TARGET_REMOVED)
    }

    /**
     * Entry point for a scheduled callback. The token makes callbacks from replaced sessions
     * harmless, even if a scheduler cannot cancel a task that is already queued.
     */
    fun onTimerFired(token: Long, atElapsedMs: Long = nowElapsedMs()): Boolean {
        val session = activeSession ?: return false
        if (session.token != token) return false

        if (atElapsedMs >= session.expiresAtElapsedMs) {
            finish(PresentationEndReason.EXPIRED)
        } else {
            onProgressChanged(progressAt(session, atElapsedMs))
            scheduleNext(session, atElapsedMs)
        }
        return true
    }

    fun progressAt(atElapsedMs: Long = nowElapsedMs()): PresentationProgress? =
        activeSession?.let { progressAt(it, atElapsedMs) }

    private fun progressAt(session: PresentationSession, atElapsedMs: Long): PresentationProgress {
        val elapsed = (atElapsedMs - session.startedAtElapsedMs).coerceAtLeast(0L)
        val fraction = (elapsed.toDouble() / session.durationMs.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
        return PresentationProgress(
            targetId = session.targetId,
            elapsedMs = elapsed,
            durationMs = session.durationMs,
            fraction = fraction
        )
    }

    private fun scheduleNext(session: PresentationSession, atElapsedMs: Long = nowElapsedMs()) {
        timer?.cancel()
        if (activeSession?.token != session.token) return
        val remaining = (session.expiresAtElapsedMs - atElapsedMs).coerceAtLeast(0L)
        val delay = min(PROGRESS_UPDATE_INTERVAL_MS, remaining.coerceAtLeast(1L))
        timer = scheduler.schedule(delay) {
            onTimerFired(session.token)
        }
    }

    private fun finish(reason: PresentationEndReason): Boolean {
        val session = activeSession ?: return false
        activeSession = null
        timer?.cancel()
        timer = null
        // Clear progress before applying expiry so the indicator disappears immediately.
        onProgressChanged(null)

        when (reason) {
            PresentationEndReason.EXPIRED -> when (session.spec.onExpiry) {
                PresentationExpiryPolicy.RESTORE_PREVIOUS -> onRestorePrevious(session)
                PresentationExpiryPolicy.LEAVE_WINDOWED -> onLeaveWindowed(session)
                PresentationExpiryPolicy.CLOSE -> onClose(session.targetId)
            }
            PresentationEndReason.REPLACED -> onRestorePrevious(session)
            PresentationEndReason.USER_CANCELLED,
            PresentationEndReason.EXPLICIT_DISMISSAL,
            PresentationEndReason.TARGET_REMOVED -> Unit
        }
        onSessionEnded(session, reason)
        return true
    }
}
