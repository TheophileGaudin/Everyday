package com.everyday.everyday_glasses.interaction.assistant

import android.util.Log

/** Android diagnostics that remain harmless when local JVM tests stub android.util.Log. */
object AssistantDiagnostics {
    fun debug(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
    }

    fun error(tag: String, message: String, failure: Throwable? = null) {
        runCatching {
            if (failure == null) Log.e(tag, message) else Log.e(tag, message, failure)
        }
    }
}
