package com.everyday.everyday_phone

import android.content.pm.ApplicationInfo
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global file logger utility for debugging when logcat is not accessible.
 * All logs are written to app_debug.log in the external files directory.
 */
object FileLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var isInitialized = false
    private var isLoggingEnabled = false

    /**
     * Initialize the file logger. Must be called before using fileLog().
     * Typically called from MainActivity.onCreate()
     */
    fun initialize(context: Context) {
        if (isInitialized) return

        isLoggingEnabled =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isLoggingEnabled) return

        try {
            // Use external files directory so we can access it via ADB
            val logDir = context.getExternalFilesDir(null)
            logFile = File(logDir, "app_debug.log")

            // Clear the file on initialization
            logFile?.writeText("")

            isInitialized = true
            fileLog("=== Log file initialized ===")
            fileLog("App started at ${dateFormat.format(Date())}")
        } catch (e: Exception) {
            // Can't log the error since logging failed to initialize
            e.printStackTrace()
        }
    }

    /**
     * Log a message to the file.
     * Thread-safe and safe to call even if initialization failed.
     */
    fun fileLog(message: String) {
        if (!isInitialized || !isLoggingEnabled) return

        try {
            logFile?.let { file ->
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] $message\n"
                synchronized(this) {
                    file.appendText(logEntry)
                }
            }
        } catch (e: Exception) {
            // Silently fail - can't do much if logging itself fails
            e.printStackTrace()
        }
    }
}

/**
 * Global function for convenient file logging throughout the app.
 */
fun fileLog(message: String) {
    FileLogger.fileLog(message)
}
