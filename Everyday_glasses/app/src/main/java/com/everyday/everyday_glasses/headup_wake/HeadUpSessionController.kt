package com.everyday.everyday_glasses.headup_wake

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Owns the temporary wake session created by a head-up gesture. */
class HeadUpSessionController(
    private val onSessionExpired: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    private var active = false
    private var expiryRunnable: Runnable? = null
    private var released = false

    fun start(durationMs: Long) {
        if (released) return
        cancel()
        active = true
        val expiry = Runnable {
            expiryRunnable = null
            if (!active || released) return@Runnable
            active = false
            Log.d(TAG, "Head-up wake session expired")
            onSessionExpired()
        }
        expiryRunnable = expiry
        handler.postDelayed(expiry, durationMs.coerceAtLeast(0L))
    }

    /** User interaction converts a temporary head-up wake into a normal session. */
    fun onUserActivity() = cancel()

    fun cancel() {
        active = false
        expiryRunnable?.let(handler::removeCallbacks)
        expiryRunnable = null
    }

    fun release() {
        if (released) return
        cancel()
        released = true
    }

    private companion object {
        const val TAG = "HeadUpSession"
    }
}