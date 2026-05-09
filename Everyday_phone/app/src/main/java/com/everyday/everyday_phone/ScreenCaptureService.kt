package com.everyday.everyday_phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service required for MediaProjection on Android 10+.
 * This service keeps the screen capture alive even when app is in background.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val EXTRA_INCLUDE_AUDIO_CAPTURE = "extra_include_audio_capture"
        
        const val ACTION_START = "com.everyday.everyday_phone.START_CAPTURE"
        // Action to stop the service
        const val ACTION_STOP = "com.everyday.everyday_phone.STOP_CAPTURE"
        
        fun start(context: Context, includeAudioCapture: Boolean = false): Boolean {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INCLUDE_AUDIO_CAPTURE, includeAudioCapture)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                fileLog("Unable to request screen capture foreground service: ${e.message}")
                false
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fileLog("Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fileLog("Service started action=${intent?.action}")
        
        if (intent?.action == ACTION_STOP) {
            fileLog("Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START) {
            fileLog("Ignoring non-explicit capture service start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        
        // Start as foreground service with notification
        val notification = createNotification()
        val includeAudioCapture = intent.getBooleanExtra(EXTRA_INCLUDE_AUDIO_CAPTURE, false)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (includeAudioCapture && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    foregroundServiceType = foregroundServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                fileLog("Starting foreground with type=$foregroundServiceType includeAudioCapture=$includeAudioCapture")
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    foregroundServiceType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            fileLog("Unable to start media projection foreground service: ${e.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fileLog("Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when phone media is being captured for glasses"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // Intent to open app when notification is tapped
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Intent to stop mirroring
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Glasses Media Capture")
            .setContentText("Sharing phone media with AR glasses")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
