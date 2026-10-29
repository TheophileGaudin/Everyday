package com.everyday.everyday_phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the glasses control the phone while its screen is mirrored: their touches on the mirror
 * are replayed here as gestures. Android only allows that from an accessibility service, which the
 * user turns on once (Settings › Accessibility › Everyday remote control).
 */
class MirrorControlService : AccessibilityService() {

    companion object {
        /** The running service, while the user has it turned on. */
        @Volatile
        var instance: MirrorControlService? = null
            private set
    }

    val injector by lazy { MirrorTouchInjector(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        fileLog("Mirror remote control service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        fileLog("Mirror remote control service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}

/**
 * Replays one touch from the glasses as one continuous gesture on the phone: the finger goes down
 * on [down], follows each [move], and lifts on [up]. Each piece is dispatched as a continuation of
 * the previous one once it has been played, so the phone sees a live drag (scrolling, flinging,
 * long-pressing and dragging work as with a finger). Android 7 can't continue a gesture, so there
 * the whole touch is played once the finger lifts. Coordinates are phone screen pixels.
 * Main thread only.
 */
class MirrorTouchInjector(private val service: AccessibilityService) {

    private companion object {
        const val FIRST_SEGMENT_MS = 1L
        const val MIN_SEGMENT_MS = 8L
        const val MAX_SEGMENT_MS = 250L
        const val SCROLL_MS = 250L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The last stroke played while the finger stays down; the next piece continues it. */
    private var stroke: GestureDescription.StrokeDescription? = null
    private var touching = false
    private var inFlight = false
    private var lastX = 0f
    private var lastY = 0f
    private val pending = ArrayList<PointF>()
    private var pendingSinceMs = 0L
    private var pendingUp = false

    /** Android 7 only: the whole touch, played when the finger lifts. */
    private val canContinue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    private val wholeTouch = Path()
    private var touchStartMs = 0L

    fun down(x: Float, y: Float) {
        if (touching || inFlight) {
            // A new touch while the previous one is still playing: end that one where it is.
            fileLog("Mirror input: new touch before the previous ended")
        }
        stroke = null
        pending.clear()
        pendingUp = false
        touching = true
        lastX = x
        lastY = y
        if (!canContinue) {
            wholeTouch.reset()
            wholeTouch.moveTo(x, y)
            touchStartMs = SystemClock.uptimeMillis()
            return
        }
        play(Path().apply { moveTo(x, y) }, FIRST_SEGMENT_MS, willContinue = true)
    }

    fun move(x: Float, y: Float) {
        if (!touching) return
        if (!canContinue) return wholeTouch.lineTo(x, y)
        add(x, y)
        pump()
    }

    fun up(x: Float, y: Float) {
        if (!touching) return
        if (!canContinue) {
            touching = false
            wholeTouch.lineTo(x, y)
            val duration = (SystemClock.uptimeMillis() - touchStartMs).coerceIn(1L, GestureDescription.getMaxGestureDuration())
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(wholeTouch, 0, duration)).build(),
                null, handler
            )
            return
        }
        add(x, y)
        pendingUp = true
        pump()
    }

    /** A two-finger scroll on the glasses: a swipe of [dy] pixels from ([x], [y]), when no touch is under way. */
    fun scroll(x: Float, y: Float, dy: Float) {
        if (touching || inFlight) return
        val path = Path().apply { moveTo(x, y); lineTo(x, y + dy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_MS))
            .build()
        service.dispatchGesture(gesture, null, handler)
    }

    private fun add(x: Float, y: Float) {
        if (pending.isEmpty()) pendingSinceMs = SystemClock.uptimeMillis()
        pending.add(PointF(x, y))
    }

    /** Plays what arrived since the last piece, unless a piece is still playing (its end calls this again). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pump() {
        if (inFlight || !touching) return
        if (pending.isEmpty() && !pendingUp) return
        val path = Path().apply {
            moveTo(lastX, lastY)
            pending.forEach { lineTo(it.x, it.y) }
        }
        pending.lastOrNull()?.let { lastX = it.x; lastY = it.y }
        val duration = (SystemClock.uptimeMillis() - pendingSinceMs).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        pending.clear()
        val finish = pendingUp
        if (finish) {
            touching = false
            pendingUp = false
        }
        play(path, duration, willContinue = !finish)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun play(path: Path, durationMs: Long, willContinue: Boolean) {
        val previous = stroke
        val next = try {
            previous?.continueStroke(path, 0, durationMs, willContinue)
                ?: GestureDescription.StrokeDescription(path, 0, durationMs, willContinue)
        } catch (e: IllegalArgumentException) {
            fileLog("Mirror input: bad stroke: ${e.message}")
            reset()
            return
        }
        stroke = if (willContinue) next else null
        inFlight = true
        val started = service.dispatchGesture(
            GestureDescription.Builder().addStroke(next).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    inFlight = false
                    pump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    fileLog("Mirror input: gesture cancelled")
                    reset()
                }
            },
            handler
        )
        if (!started) {
            fileLog("Mirror input: gesture refused")
            reset()
        }
    }

    private fun reset() {
        inFlight = false
        touching = false
        stroke = null
        pending.clear()
        pendingUp = false
    }
}
