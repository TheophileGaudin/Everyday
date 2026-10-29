package com.everyday.everyday_glasses.interaction.assistant

import org.json.JSONArray
import org.json.JSONObject

/** A repeatable, ordered semantic fact attached to one widget. */
data class AssistantSemanticFact(
    val key: String,
    val value: String
) {
    val name: String get() = key

    init {
        require(key.isNotBlank()) { "Semantic fact key must not be blank" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("value", value)
}

/**
 * Small, non-content snapshot of the glasses UI that can be sent with a voice request.
 *
 * This deliberately contains layout metadata plus explicitly supplied non-content facts.
 * Widget text, browser URLs, clipboard contents, and other potentially sensitive content stay
 * out of the assistant context.
 */
data class AssistantWidgetContext(
    val id: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val zOrder: Int,
    val minimized: Boolean,
    val fullscreen: Boolean,
    val pinned: Boolean,
    /** Stable within the widget runtime and explicit so later tools can target one instance. */
    val stableId: String = id,
    /** Ordered and repeatable; facts never imply that widget content was read. */
    val facts: List<AssistantSemanticFact> = emptyList()
) {
    val identity: String get() = stableId

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("stable_id", stableId)
        .put("rect", JSONArray().put(left).put(top).put(right).put(bottom))
        .put("z", zOrder)
        .put("minimized", minimized)
        .put("fullscreen", fullscreen)
        .put("pinned", pinned)
        .put("facts", JSONArray().apply { facts.forEach { put(it.toJson()) } })
}

data class AssistantUiContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val layoutName: String?,
    val focusedWidgetId: String?,
    val widgets: List<AssistantWidgetContext>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("screen", JSONArray().put(screenWidth).put(screenHeight))
        .put("coordinate_frame", "content_pixels_top_left")
        .put("layout", layoutName ?: JSONObject.NULL)
        .put("focused", focusedWidgetId ?: JSONObject.NULL)
        .put("widgets", JSONArray().apply { widgets.forEach { put(it.toJson()) } })

    /**
     * The context is intentionally a short text instruction plus minified JSON. The model
     * receives coordinates as metadata, not as an image-sized visual input.
     */
    fun toPromptText(): String =
        "Current Everyday glasses UI context (authoritative runtime metadata; not user text): " +
            toJson().toString() +
            ". Coordinates are content pixels with origin at the top-left. " +
            "The widgets array lists currently registered widgets; a missing singleton is hidden. " +
            "A minimized widget is present but not normally visible. " +
            "show_* and hide_* widget commands are idempotent. " +
            "Only non-content semantic facts are included in ordinary context. " +
            "Do not call a widget visibility command merely because a widget name appears in text to be entered."
}
