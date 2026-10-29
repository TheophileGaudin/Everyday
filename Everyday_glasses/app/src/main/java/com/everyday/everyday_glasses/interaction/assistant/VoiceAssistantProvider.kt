package com.everyday.everyday_glasses.interaction.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AssistantToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject
) {
    fun toGeminiJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", description)
        .put("parameters", parameters)
}

data class AssistantToolCall(val id: String, val name: String, val arguments: JSONObject)

/** A video file a tool hands to the model to watch (the provider uploads it). */
data class AssistantVideo(val file: File, val mimeType: String = "video/mp4")

data class AssistantToolExecution(
    val success: Boolean,
    val code: String,
    val message: String,
    val screenshots: List<AssistantScreenshot> = emptyList(),
    val sources: List<AssistantSource> = emptyList(),
    val groundingEvidence: Boolean = false,
    val videos: List<AssistantVideo> = emptyList(),
    /** The action was the whole request (a photo just taken): once it succeeds, no further model turn. */
    val completesRequest: Boolean = false,
) {
    val screenshot: AssistantScreenshot? get() = screenshots.firstOrNull()

    fun toJson(): JSONObject = JSONObject()
        .put("success", success)
        .put("code", code)
        .put("message", message)
        .apply {
            if (groundingEvidence) put("grounding_evidence", true)
            if (sources.isNotEmpty()) {
                put("sources", JSONArray().apply {
                    sources.forEach { source ->
                        put(JSONObject().put("name", source.name).put("url", source.url))
                    }
                })
            }
            if (screenshots.isNotEmpty()) {
                put("screenshots", JSONArray().apply {
                    screenshots.forEach { screenshot ->
                        put(JSONObject()
                            .put("width", screenshot.width)
                            .put("height", screenshot.height)
                            .put("mime_type", screenshot.mimeType))
                    }
                })
            }
        }
}

data class AssistantProviderTurn(
    val interactionId: String,
    val calls: List<AssistantToolCall>,
    val outputText: String? = null,
    val uploadedFileName: String? = null,
    val sources: List<AssistantSource> = emptyList(),
    val searchAttempted: Boolean = false,
    val searchFailed: Boolean = false
) {
    /** Returns only a final, human-readable answer from a turn with no custom function call. */
    fun informationalAnswerOrNull(
        additionalSources: List<AssistantSource> = emptyList(),
        groundedByTool: Boolean = false
    ): AssistantProviderAnswer? {
        // Free-form factual output is never displayed merely because it looks readable. Tool
        // calls have their own validation; informational answers must carry Search evidence.
        if (calls.isNotEmpty() || searchFailed || (!searchAttempted && !groundedByTool)) return null
        val text = outputText?.trim()?.takeIf(AssistantOutputPolicy::isPresentable) ?: return null
        return AssistantProviderAnswer(
            text = text,
            sources = (sources + additionalSources).distinctBy { it.url },
            grounded = true
        )
    }
}

data class AssistantProviderAnswer(
    val text: String,
    val sources: List<AssistantSource> = emptyList(),
    val grounded: Boolean = false
)

data class AssistantUploadedFile(val name: String, val uri: String)

interface VoiceAssistantProvider {
    fun listModels(apiKey: String): Result<List<AssistantModel>>
    fun probeAudioTools(apiKey: String, modelId: String): Result<Unit>
    fun startInteraction(
        apiKey: String,
        modelId: String,
        audioFile: File,
        tools: List<AssistantToolDefinition>,
        uiContext: AssistantUiContext
    ): Result<AssistantProviderTurn>

    fun continueInteraction(
        apiKey: String,
        modelId: String,
        previousInteractionId: String,
        tools: List<AssistantToolDefinition>,
        results: List<Pair<AssistantToolCall, AssistantToolExecution>>
    ): Result<AssistantProviderTurn>

    fun deleteUploadedFile(apiKey: String, name: String)

    /** Deletes what tools uploaded during the session (videos), once it is over. */
    fun deleteToolUploads(apiKey: String) = Unit
}
