package com.everyday.everyday_glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class GlassesService : Service() {

    // Head-up wake manager runs in the service
    private var headUpWakeManager: HeadUpWakeManager? = null

    // Track if MainActivity is in foreground
    private var isActivityInForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Initialize head-up wake manager
        setupHeadUpWakeManager()

        Log.d(TAG, "GlassesService created, HeadUpWakeManager initialized")
    }

    override fun onDestroy() {
        super.onDestroy()
        headUpWakeManager?.release()
        headUpWakeManager = null
        Log.d(TAG, "GlassesService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVITY_RESUMED -> {
                Log.d(TAG, "MainActivity resumed - disabling head-up wake in service")
                isActivityInForeground = true
                // Tell the manager that the screen is "on" (activity is visible)
                headUpWakeManager?.onScreenStateChanged(false)
                // Disable head-up detection when activity is in foreground
                // (Activity handles its own wake/sleep logic)
                headUpWakeManager?.disable()
            }
            ACTION_ACTIVITY_PAUSED -> {
                Log.d(TAG, "MainActivity paused - enabling head-up wake in service")
                isActivityInForeground = false
                // Enable head-up detection when activity goes to background
                // Reload settings in case they changed
                reloadSettings()
                headUpWakeManager?.enable()
                // Tell the manager that the screen is "off" from our perspective
                // (the OS lock screen has taken over)
                headUpWakeManager?.onScreenStateChanged(true)
            }
        }
        return START_STICKY
    }

    private fun setupHeadUpWakeManager() {
        headUpWakeManager = HeadUpWakeManager(this).apply {
            // Load settings from SharedPreferences
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            headUpHoldTimeMs = prefs.getLong(KEY_HEAD_UP_TIME, HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS)
            wakeDurationMs = prefs.getLong(KEY_WAKE_DURATION, HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS)
            pitchChangeThreshold = prefs.getFloat(KEY_ANGLE_THRESHOLD, HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD)
            isHeadsUpGestureEnabled = prefs.getBoolean(KEY_HEADSUP_ENABLED, true)

            Log.d(TAG, "HeadUpWakeManager settings: headUpTime=${headUpHoldTimeMs}ms, wakeDuration=${wakeDurationMs}ms, angle=${pitchChangeThreshold}°, enabled=$isHeadsUpGestureEnabled")

            // When head-up is detected, launch MainActivity
            onWakeRequested = {
                Log.d(TAG, "Head-up wake detected in service - launching MainActivity")
                launchMainActivity()
            }

            // Sleep callback is handled by the activity's own wake/sleep manager
            // once it's launched, so we don't need to do anything here
            onSleepRequested = {
                Log.d(TAG, "Head-up wake timeout in service (activity should handle this)")
            }

            // Start disabled - will be enabled when activity goes to background
            // This prevents double-handling when activity is in foreground
        }
    }

    private fun reloadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        headUpWakeManager?.apply {
            headUpHoldTimeMs = prefs.getLong(KEY_HEAD_UP_TIME, HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS)
            wakeDurationMs = prefs.getLong(KEY_WAKE_DURATION, HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS)
            pitchChangeThreshold = prefs.getFloat(KEY_ANGLE_THRESHOLD, HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD)
            isHeadsUpGestureEnabled = prefs.getBoolean(KEY_HEADSUP_ENABLED, true)
            Log.d(TAG, "Reloaded settings: headUpTime=${headUpHoldTimeMs}ms, wakeDuration=${wakeDurationMs}ms, angle=${pitchChangeThreshold}°, enabled=$isHeadsUpGestureEnabled")
        }
    }

    private fun launchMainActivity() {
        Log.d(TAG, "Attempting to launch MainActivity via full-screen intent...")

        // First, wake up the device screen using PowerManager
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "Everyday_glasses::HeadUpWakeLock"
            )
            wakeLock.acquire(3000L) // Hold for 3 seconds
            Log.d(TAG, "Wake lock acquired to turn on screen")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }

        val wakeDuration = headUpWakeManager?.wakeDurationMs ?: HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS

        // Set static flags BEFORE launching - intent extras don't work reliably with full-screen intents
        pendingHeadUpWake = true
        pendingHeadUpWakeDuration = wakeDuration
        Log.d(TAG, "Set pending head-up wake flags: wake=true, duration=${wakeDuration}ms")

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("head_up_wake", true)
            putExtra("head_up_wake_duration", wakeDuration)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(), // Unique request code
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Create a high-priority notification with full-screen intent
        // This is the approved way to launch activities from background
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Create a separate high-importance channel for wake notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val wakeChannel = NotificationChannel(
                "head_up_wake_channel",
                "Head-up Wake",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for head-up screen wake"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(wakeChannel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "head_up_wake_channel")
                .setContentTitle("Everyday")
                .setContentText("Head-up wake")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true) // This is the key!
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Everyday")
                .setContentText("Head-up wake")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()
        }

        // Show the notification - this will trigger the full-screen intent
        notificationManager.notify(WAKE_NOTIFICATION_ID, notification)
        Log.d(TAG, "Full-screen intent notification posted")

        // Cancel it shortly after (it should have already launched the activity)
        android.os.Handler(mainLooper).postDelayed({
            notificationManager.cancel(WAKE_NOTIFICATION_ID)
            Log.d(TAG, "Wake notification cancelled")
        }, 2000)
    }

    companion object {
        private const val TAG = "GlassesService"
        private const val CHANNEL_ID = "glasses_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_NOTIFICATION_ID = 1002

        // SharedPreferences keys (must match MainActivity)
        private const val PREFS_NAME = "everyday_settings"
        private const val KEY_HEAD_UP_TIME = "head_up_time_ms"
        private const val KEY_WAKE_DURATION = "wake_duration_ms"
        private const val KEY_ANGLE_THRESHOLD = "angle_threshold_degrees"
        private const val KEY_HEADSUP_ENABLED = "headsup_enabled"

        // Action to notify service that MainActivity is in foreground
        const val ACTION_ACTIVITY_RESUMED = "com.everyday.everyday_glasses.ACTIVITY_RESUMED"
        const val ACTION_ACTIVITY_PAUSED = "com.everyday.everyday_glasses.ACTIVITY_PAUSED"

        // Static flag to communicate head-up wake to MainActivity
        // (Intent extras don't work reliably with full-screen intents)
        @Volatile
        var pendingHeadUpWake: Boolean = false
        @Volatile
        var pendingHeadUpWakeDuration: Long = HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS

        fun start(context: Context) {
            val intent = Intent(context, GlassesService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlassesService::class.java))
        }

        fun notifyActivityResumed(context: Context) {
            val intent = Intent(context, GlassesService::class.java).apply {
                action = ACTION_ACTIVITY_RESUMED
            }
            context.startService(intent)
        }

        fun notifyActivityPaused(context: Context) {
            val intent = Intent(context, GlassesService::class.java).apply {
                action = ACTION_ACTIVITY_PAUSED
            }
            context.startService(intent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Everyday Glasses",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Everyday running"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Everyday")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Everyday")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }
}