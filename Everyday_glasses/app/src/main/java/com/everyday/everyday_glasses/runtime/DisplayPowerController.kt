package com.everyday.everyday_glasses.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/**
 * Application behavior required when physical display power or the app's
 * internal WAKE/SLEEP/OFF state changes.
 */
interface DisplayPowerCallbacks {
    /**
     * Called before visual state is applied. MainActivity uses this to keep
     * HeadUpWakeManager informed about whether the internal display is OFF.
     */
    fun onDisplayStateChanging(state: WakeSleepManager.DisplayState)

    /** Applies cursor and widget visibility for the supplied state. */
    fun applyDisplayState(state: WakeSleepManager.DisplayState)

    /**
     * Reasserts the visible WAKE state and returns true if a stale visual mask
     * had to be repaired.
     */
    fun ensureVisibleWakeState(): Boolean

    /** Called after an actual state transition into WAKE. */
    fun onDisplayWake(reason: String)

    /** Allows WakeSleepManager to decide what SLEEP should display. */
    fun hasPinnedWidgets(): Boolean

    /** Reapplies brightness after physical/window wake recovery. */
    fun reapplyBrightness()
}

/**
 * Owns Android display-power mechanics and the app's WakeSleepManager.
 *
 * Head-up gesture recognition and head-up session duration remain outside this
 * class. Those components request state transitions through this controller.
 */
class DisplayPowerController(
    private val activity: Activity,
    private val callbacks: DisplayPowerCallbacks
) {

    companion object {
        private const val TAG = "DisplayPowerController"
        private const val WAKE_LOCK_TAG = "Everyday_glasses::DisplayWakeLock"
        private const val DEFAULT_SCREEN_OFF_TIMEOUT_MS = 30_000L
        private const val POWER_BUTTON_REWAKE_DELAY_MS = 400L
    }

    // ---------------------------------------------------------------------
    // Owned Android resources and state
    // ---------------------------------------------------------------------

    private val powerManager =
        activity.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var wakeSleepManager: WakeSleepManager? = null

    private val keepAwakeMediaSources = mutableSetOf<String>()

    private var windowInitialized = false
    private var started = false
    private var released = false
    private var activeTransitionReason = "display_state_change"
    /** When the content was last masked (internal OFF) while the screen itself stayed on; 0 otherwise. */
    private var maskedAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    var isTransitioningDisplayState: Boolean = false
        private set

    val state: WakeSleepManager.DisplayState
        get() = wakeSleepManager?.state ?: WakeSleepManager.DisplayState.WAKE

    fun isAwake(): Boolean =
        wakeSleepManager?.isAwake() ?: true

    // ---------------------------------------------------------------------
    // Two-stage initialization
    // ---------------------------------------------------------------------

    /**
     * Configures the Activity window and acquires the initial wake lock.
     * Call immediately after super.onCreate(), before setContentView().
     */
    fun initializeWindow() {
        if (windowInitialized || released) return
        windowInitialized = true

        configureLockScreenBehavior()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireWakeLock()
    }

    /**
     * Starts the internal state machine and screen receiver.
     * Call only after CursorView and WidgetContainer have been initialized.
     */
    fun start() {
        if (started || released) return
        if (!windowInitialized) initializeWindow()
        started = true

        wakeSleepManager = WakeSleepManager(::applyStateTransition).apply {
            hasPinnedWidgets = callbacks::hasPinnedWidgets
            setKeepAwake(keepAwakeMediaSources.isNotEmpty())
        }

        registerScreenStateReceiver()
        Log.d(TAG, "Display power state management started")
    }

    // ---------------------------------------------------------------------
    // Public state and activity operations
    // ---------------------------------------------------------------------

    /**
     * Wakes the display if necessary. If already WAKE, repairs any stale
     * Android window or application visibility state.
     */
    fun wakeFromUserAction(reason: String) {
        val manager = wakeSleepManager ?: return

        if (manager.state != WakeSleepManager.DisplayState.WAKE) {
            Log.d(TAG, "User action waking display from ${manager.state} ($reason)")
            transitionTo(WakeSleepManager.DisplayState.WAKE, reason)
            return
        }

        ensureVisibleWakeState(reason)
    }

    /**
     * Switches the physical screen on as well, even after the power button turned it off while the
     * app held its wake lock: such a lock only wakes the screen when taken, so it is taken again.
     */
    fun wakeScreen(reason: String) {
        if (!powerManager.isInteractive) {
            Log.d(TAG, "Switching the screen on ($reason)")
            releaseWakeLock()
            acquireWakeLock()
        }
        wakeFromUserAction(reason)
    }

    /** Wakes if needed and resets the inactivity timer. */
    fun onUserActivity() {
        wakeFromUserAction("user_activity")
        wakeSleepManager?.onUserActivity()
    }

    /** Used by head-up wake and the head-up auto-finish timer. */
    fun transitionTo(
        state: WakeSleepManager.DisplayState,
        reason: String = "programmatic"
    ) {
        val manager = wakeSleepManager ?: return
        val previousReason = activeTransitionReason
        activeTransitionReason = reason
        try {
            manager.transitionTo(state)
        } finally {
            activeTransitionReason = previousReason
        }
    }

    /**
     * Tracks individual media sources so one stopping source cannot cancel
     * another active source's keep-awake request.
     */
    fun setMediaKeepAwake(sourceId: String, active: Boolean) {
        if (active) {
            keepAwakeMediaSources += sourceId
        } else {
            keepAwakeMediaSources -= sourceId
        }

        wakeSleepManager?.setKeepAwake(keepAwakeMediaSources.isNotEmpty())
    }

    /** Forward from MainActivity.onResume(). */
    fun onActivityResumed() {
        if (powerManager.isInteractive) {
            handlePhysicalScreenOn("activity_resume")
        }
    }

    /** Releases every Android resource owned by this controller. */
    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)

        screenStateReceiver?.let { receiver ->
            runCatching { activity.unregisterReceiver(receiver) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to unregister screen-state receiver", error)
                }
        }
        screenStateReceiver = null

        wakeSleepManager?.release()
        wakeSleepManager = null
        keepAwakeMediaSources.clear()

        releaseWakeLock()
        Log.d(TAG, "Display power controller released")
    }

    // ---------------------------------------------------------------------
    // Internal state-transition application
    // ---------------------------------------------------------------------

    private fun applyStateTransition(state: WakeSleepManager.DisplayState) {
        Log.d(TAG, "Display state changed to: $state")
        isTransitioningDisplayState = true

        try {
            callbacks.onDisplayStateChanging(state)

            when (state) {
                WakeSleepManager.DisplayState.OFF -> {
                    callbacks.applyDisplayState(state)
                    releaseWakeLock()
                    startMaskWindow()
                }

                WakeSleepManager.DisplayState.WAKE -> {
                    cancelMaskWindow()
                    activity.window.addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    )
                    acquireWakeLock()
                    callbacks.applyDisplayState(state)
                    callbacks.onDisplayWake(activeTransitionReason)
                }

                WakeSleepManager.DisplayState.SLEEP -> {
                    callbacks.applyDisplayState(state)
                }
            }
        } finally {
            isTransitioningDisplayState = false
        }
    }

    private fun ensureVisibleWakeState(reason: String) {
        // Runs on every touch. Setting a window flag, even one already set, makes Android lay
        // the whole window out again through the system (about 18 ms a frame on the glasses).
        if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        acquireWakeLock()

        val recoveredStaleMask = callbacks.ensureVisibleWakeState()
        if (recoveredStaleMask) {
            Log.d(TAG, "Recovered visible wake state after stale internal mask ($reason)")
        }

        callbacks.reapplyBrightness()
    }

    // ---------------------------------------------------------------------
    // Physical screen-on receiver
    // ---------------------------------------------------------------------

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return

        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> handlePhysicalScreenOn("screen_on_broadcast")
                    Intent.ACTION_SCREEN_OFF -> handlePhysicalScreenOff()
                }
            }
        }

        activity.registerReceiver(
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        )
    }

    // ---------------------------------------------------------------------
    // Masked content and the power button
    // ---------------------------------------------------------------------
    // Masking the content (OFF, e.g. when a head-up wake ends) leaves the screen lit, so a press of
    // the power button would only switch it off, and showing content again would take a second
    // press. For one system screen timeout after masking, the app keeps the screen on itself: the
    // system cannot time it out, so a screen going off then can only be that press, and the
    // content is shown instead. Afterwards the screen is left to the system timeout as before.

    private val endMaskWindow = Runnable {
        maskedAtMs = 0L
        if (wakeSleepManager?.state == WakeSleepManager.DisplayState.OFF) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d(TAG, "Masked for a full screen timeout; the screen is left to the system")
        }
    }

    private fun startMaskWindow() {
        mainHandler.removeCallbacks(endMaskWindow)
        maskedAtMs = SystemClock.elapsedRealtime()
        val systemTimeoutMs = Settings.System.getLong(
            activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT_MS
        )
        mainHandler.postDelayed(endMaskWindow, systemTimeoutMs)
    }

    private fun cancelMaskWindow() {
        mainHandler.removeCallbacks(endMaskWindow)
        maskedAtMs = 0L
    }

    private fun handlePhysicalScreenOff() {
        val manager = wakeSleepManager ?: return
        val maskedAt = maskedAtMs
        if (manager.state != WakeSleepManager.DisplayState.OFF || maskedAt == 0L) return
        cancelMaskWindow()
        Log.d(TAG, "Screen off ${SystemClock.elapsedRealtime() - maskedAt}ms into the mask: power button, showing content")
        // A wake-up requested while the screen is still switching off is ignored: wait until it is off.
        mainHandler.postDelayed({
            if (!released && wakeSleepManager?.state == WakeSleepManager.DisplayState.OFF) {
                transitionTo(WakeSleepManager.DisplayState.WAKE, "power_button_while_masked")
            }
        }, POWER_BUTTON_REWAKE_DELAY_MS)
    }

    private fun handlePhysicalScreenOn(reason: String) {
        val manager = wakeSleepManager ?: return
        Log.d(TAG, "Physical screen-on detected ($reason) in state ${manager.state}")
        wakeFromUserAction(reason)
    }

    // ---------------------------------------------------------------------
    // Activity window and wake-lock mechanics
    // ---------------------------------------------------------------------

    private fun configureLockScreenBehavior() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager =
                activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(activity, null)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing != null) {
            if (!existing.isHeld) {
                existing.acquire()
                Log.d(TAG, "Wake lock reacquired")
            }
            return
        }

        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKE_LOCK_TAG
        ).apply {
            acquire()
        }
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "Wake lock released")
            }
        }
    }
}