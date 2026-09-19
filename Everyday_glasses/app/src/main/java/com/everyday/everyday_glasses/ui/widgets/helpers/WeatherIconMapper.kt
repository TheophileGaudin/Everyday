package com.everyday.everyday_glasses.ui.widgets.helpers

/**
 * Maps weather descriptions from the phone app to local asset image filenames.
 *
 * The phone now sends filenames directly (e.g., "sunny.png", "rainy.png").
 * This mapper validates the filename and provides a fallback for invalid values.
 *
 * Valid asset filenames:
 * - sunny.png
 * - clear_night.png
 * - light_clouds.png
 * - light_clouds_night.png
 * - medium_clouds.png
 * - medium_clouds_night.png
 * - cloudy.png
 * - fog.png
 * - light_rain.png
 * - rainy.png
 * - heavy_rain.png
 * - storm.png
 * - snow.png
 * - unsettled.png
 * - unsettled_night.png
 * - unsettled_snowy.png
 * - unsettled_snowy_night.png
 */
object WeatherIconMapper {

    private val validAssets = setOf(
        "sunny.png",
        "clear_night.png",
        "light_clouds.png",
        "light_clouds_night.png",
        "medium_clouds.png",
        "medium_clouds_night.png",
        "cloudy.png",
        "fog.png",
        "light_rain.png",
        "rainy.png",
        "heavy_rain.png",
        "storm.png",
        "snow.png",
        "unsettled.png",
        "unsettled_night.png",
        "unsettled_snowy.png",
        "unsettled_snowy_night.png"
    )

    private val nightVariants = mapOf(
        "sunny.png" to "clear_night.png",
        "light_clouds.png" to "light_clouds_night.png",
        "medium_clouds.png" to "medium_clouds_night.png",
        "unsettled.png" to "unsettled_night.png",
        "unsettled_snowy.png" to "unsettled_snowy_night.png"
    )

    fun getIconForDescription(description: String, isNight: Boolean): String {
        val filename = description.trim()
        val resolved = if (filename in validAssets) {
            filename
        } else {
            "unsettled.png"
        }

        return if (isNight) {
            nightVariants[resolved] ?: resolved
        } else {
            resolved
        }
    }
}