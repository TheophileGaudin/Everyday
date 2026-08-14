package com.everyday.everyday_glasses

import kotlin.math.ceil

/** A compact source reference returned with a grounded assistant answer. */
data class AssistantSource(
    val name: String,
    val url: String
) {
    init {
        require(name.isNotBlank() || url.isNotBlank()) { "Assistant source must have a name or URL" }
    }
}

/** The semantic request shared by the present_text handler and the widget host. */
data class AssistantTextPresentationRequest(
    val content: String,
    val spec: PresentationSpec,
    val targetWidgetId: String? = null,
    val sources: List<AssistantSource> = emptyList()
) {
    init {
        require(content.isNotBlank()) { "Presented text must not be blank" }
        require(targetWidgetId == null || targetWidgetId.isNotBlank()) {
            "Presentation target ID must not be blank"
        }
    }
}

/** Keeps grounded answers readable without silently dropping factual content. */
object AssistantTextContentFormatter {
    fun render(content: String, sources: List<AssistantSource>): String {
        val answer = content.trim()
        val uniqueSources = sources
            .asSequence()
            .map { it.copy(name = it.name.trim(), url = it.url.trim()) }
            .filter { it.name.isNotBlank() || it.url.isNotBlank() }
            .distinctBy { it.url.ifBlank { it.name } }
            .toList()
        if (uniqueSources.isEmpty()) return answer

        val sourceLines = uniqueSources.joinToString("\n") { source ->
            val name = source.name.ifBlank { source.url }
            if (source.url.isBlank() || source.url == name) {
                "- $name"
            } else {
                "- $name (${source.url})"
            }
        }
        return "$answer\n\nSources:\n$sourceLines"
    }
}

/** Policy for automatic fallback presentation when Gemini returns text without a function call. */
object AssistantTextPresentationPolicy {
    private const val CHARACTERS_PER_SECOND = 18.0

    fun forAnswer(answer: AssistantProviderAnswer): AssistantTextPresentationRequest {
        val readableContent = AssistantTextContentFormatter.render(answer.text, answer.sources)
        val durationSeconds = ceil(readableContent.length / CHARACTERS_PER_SECOND)
            .toInt()
            .coerceIn(PresentationSpec.MIN_DURATION_SECONDS, PresentationSpec.MAX_DURATION_SECONDS)
        return AssistantTextPresentationRequest(
            content = answer.text,
            spec = PresentationSpec(
                mode = PresentationMode.FULLSCREEN,
                durationSeconds = durationSeconds,
                onExpiry = PresentationExpiryPolicy.CLOSE
            ),
            sources = answer.sources
        )
    }
}

/** Output that is useful to a person rather than a provider diagnostic or tool payload. */
internal object AssistantOutputPolicy {
    private val diagnosticOutputs = setOf(
        "no command recognized",
        "no command recognized.",
        "no answer",
        "no answer."
    )

    fun isPresentable(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        if (normalized.lowercase() in diagnosticOutputs) return false

        // A JSON object/array in a text step is normally a malformed tool payload, not prose.
        if (normalized.startsWith("{") || normalized.startsWith("[")) return false
        return true
    }
}
