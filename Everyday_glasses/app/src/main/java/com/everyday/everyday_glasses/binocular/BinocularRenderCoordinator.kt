package com.everyday.everyday_glasses.binocular

import android.content.ComponentCallbacks2
import android.os.SystemClock
import kotlin.math.ceil

enum class BinocularContentClass {
    STATIC,
    INTERACTIVE,
    MEDIA
}

enum class BinocularUpdateType {
    STRUCTURAL,
    TRANSIENT
}

data class BinocularRenderProfile(
    val dominantClass: BinocularContentClass,
    val refreshMode: RefreshMode,
    val continuousFrameIntervalMs: Long,
    val isCharging: Boolean,
    val isBatteryDownshifted: Boolean,
    val hasMemoryPressure: Boolean
)

class BinocularRenderCoordinator(
    private val onProfileChanged: (BinocularRenderProfile) -> Unit = {}
) {

    private val activeSources = linkedMapOf<String, BinocularContentClass>()

    private var isCharging = true
    private var hasMemoryPressure = false
    private var mediaDownshifted = false
    private var lastMediaFrameTimestampMs = 0L
    private var consecutiveMissedMediaFrames = 0
    private var consecutivePixelCopyIssues = 0
    private var currentProfile = computeProfile()

    fun setSourceState(sourceId: String, contentClass: BinocularContentClass, active: Boolean) {
        if (active) {
            activeSources[sourceId] = contentClass
        } else {
            activeSources.remove(sourceId)
        }
        normalizeState()
        publishIfChanged()
    }

    fun setChargingState(charging: Boolean) {
        if (isCharging == charging) return
        isCharging = charging
        if (charging) {
            mediaDownshifted = false
            consecutiveMissedMediaFrames = 0
            consecutivePixelCopyIssues = 0
        }
        publishIfChanged()
    }

    fun onTrimMemory(level: Int) {
        val pressure = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (hasMemoryPressure != pressure) {
            hasMemoryPressure = pressure
            publishIfChanged()
        }
    }

    fun onLowMemory() {
        if (!hasMemoryPressure) {
            hasMemoryPressure = true
            publishIfChanged()
        }
    }

    fun reportMediaFrame(sourceId: String, timestampMs: Long = SystemClock.uptimeMillis()) {
        if (activeSources[sourceId] != BinocularContentClass.MEDIA) return

        val budgetMs = computeMediaFrameIntervalMs()
        if (lastMediaFrameTimestampMs != 0L) {
            val deltaMs = timestampMs - lastMediaFrameTimestampMs
            consecutiveMissedMediaFrames = if (deltaMs > budgetMs * 2) {
                consecutiveMissedMediaFrames + 1
            } else {
                0
            }
        }
        lastMediaFrameTimestampMs = timestampMs

        if (!isCharging && consecutiveMissedMediaFrames >= 3) {
            mediaDownshifted = true
            publishIfChanged()
        }
    }

    fun reportPixelCopyResult(durationMs: Long, success: Boolean) {
        if (!isMediaActive()) return

        val budgetMs = computeMediaFrameIntervalMs()
        consecutivePixelCopyIssues = if (!success || durationMs > budgetMs * 2) {
            consecutivePixelCopyIssues + 1
        } else {
            0
        }

        if (!isCharging && consecutivePixelCopyIssues >= 2) {
            mediaDownshifted = true
            publishIfChanged()
        }
    }

    fun getCurrentProfile(): BinocularRenderProfile = currentProfile

    fun getPixelCopyIntervalMs(): Long {
        return when (currentProfile.dominantClass) {
            BinocularContentClass.STATIC -> DisplayConfig.MIN_PIXELCOPY_INTERVAL_MS
            BinocularContentClass.INTERACTIVE -> 1000L / 30L
            BinocularContentClass.MEDIA -> currentProfile.continuousFrameIntervalMs
        }.coerceAtLeast(DisplayConfig.MIN_PIXELCOPY_INTERVAL_MS)
    }

    fun getSummary(): String {
        val activeSummary = activeSources.entries.joinToString(
            prefix = "[",
            postfix = "]"
        ) { "${it.key}:${it.value.name}" }
        return "class=${currentProfile.dominantClass.name}," +
            " refresh=${currentProfile.refreshMode.name}," +
            " intervalMs=${currentProfile.continuousFrameIntervalMs}," +
            " charging=${currentProfile.isCharging}," +
            " batteryDownshift=${currentProfile.isBatteryDownshifted}," +
            " memoryPressure=${currentProfile.hasMemoryPressure}," +
            " sources=$activeSummary"
    }

    private fun publishIfChanged() {
        val newProfile = computeProfile()
        if (newProfile == currentProfile) return
        currentProfile = newProfile
        onProfileChanged(newProfile)
    }

    private fun computeProfile(): BinocularRenderProfile {
        val dominantClass = activeSources.values.maxOrNull() ?: BinocularContentClass.INTERACTIVE
        val batteryDownshift = dominantClass == BinocularContentClass.MEDIA &&
            !isCharging &&
            (mediaDownshifted || hasMemoryPressure)

        val refreshMode = when (dominantClass) {
            BinocularContentClass.STATIC -> RefreshMode.IDLE
            BinocularContentClass.INTERACTIVE -> RefreshMode.NORMAL
            BinocularContentClass.MEDIA -> RefreshMode.NORMAL
        }

        val intervalMs = when (dominantClass) {
            BinocularContentClass.STATIC -> Long.MAX_VALUE
            BinocularContentClass.INTERACTIVE -> 1000L / 30L
            BinocularContentClass.MEDIA -> if (batteryDownshift) {
                ceil(1000.0 / 24.0).toLong()
            } else {
                1000L / 30L
            }
        }

        return BinocularRenderProfile(
            dominantClass = dominantClass,
            refreshMode = refreshMode,
            continuousFrameIntervalMs = intervalMs,
            isCharging = isCharging,
            isBatteryDownshifted = batteryDownshift,
            hasMemoryPressure = hasMemoryPressure
        )
    }

    private fun computeMediaFrameIntervalMs(): Long {
        return if (!isCharging && (mediaDownshifted || hasMemoryPressure)) {
            ceil(1000.0 / 24.0).toLong()
        } else {
            1000L / 30L
        }
    }

    private fun isMediaActive(): Boolean {
        return activeSources.values.any { it == BinocularContentClass.MEDIA }
    }

    private fun normalizeState() {
        if (!isMediaActive()) {
            mediaDownshifted = false
            hasMemoryPressure = false
            lastMediaFrameTimestampMs = 0L
            consecutiveMissedMediaFrames = 0
            consecutivePixelCopyIssues = 0
        }
    }
}
