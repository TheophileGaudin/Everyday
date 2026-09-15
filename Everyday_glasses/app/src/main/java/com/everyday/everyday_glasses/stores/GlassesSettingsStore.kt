package com.everyday.everyday_glasses.stores

import android.content.Context
import com.everyday.everyday_glasses.managers.HeadUpWakeManager

data class HeadUpSettings(
    val holdTimeMs: Long,
    val wakeDurationMs: Long,
    val angleThresholdDegrees: Float,
    val enabled: Boolean
)

/** Persistence for activity-level settings that are shared by UI and hardware controllers. */
class GlassesSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun loadHeadUpSettings(): HeadUpSettings = HeadUpSettings(
        holdTimeMs = preferences.getLong(
            KEY_HEAD_UP_TIME,
            HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS
        ),
        wakeDurationMs = preferences.getLong(
            KEY_WAKE_DURATION,
            HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS
        ),
        angleThresholdDegrees = preferences.getFloat(
            KEY_ANGLE_THRESHOLD,
            HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD
        ),
        enabled = preferences.getBoolean(KEY_HEADSUP_ENABLED, true)
    )

    fun saveHeadUpSettings(settings: HeadUpSettings) {
        preferences.edit()
            .putLong(KEY_HEAD_UP_TIME, settings.holdTimeMs)
            .putLong(KEY_WAKE_DURATION, settings.wakeDurationMs)
            .putFloat(KEY_ANGLE_THRESHOLD, settings.angleThresholdDegrees)
            .putBoolean(KEY_HEADSUP_ENABLED, settings.enabled)
            .apply()
    }

    fun loadSmartAlignmentEnabled(): Boolean =
        preferences.getBoolean(KEY_SMART_ALIGNMENT, DEFAULT_SMART_ALIGNMENT)

    fun saveSmartAlignmentEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SMART_ALIGNMENT, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "everyday_settings"
        const val KEY_HEAD_UP_TIME = "head_up_time_ms"
        const val KEY_WAKE_DURATION = "wake_duration_ms"
        const val KEY_ANGLE_THRESHOLD = "angle_threshold_degrees"
        const val KEY_HEADSUP_ENABLED = "headsup_enabled"
        const val KEY_SMART_ALIGNMENT = "smart_alignment_enabled"
        const val DEFAULT_SMART_ALIGNMENT = true
    }
}
