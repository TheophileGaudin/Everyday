package com.everyday.everyday_glasses.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Result of the bounded semantic tool loop, kept separate from Android lifecycle state. */
data class AssistantToolLoopOutcome(
    val anySuccess: Boolean,
    val recognized: Boolean,
    val executedCalls: Int,
    val answer: AssistantProviderAnswer? = null,
    val searchFailed: Boolean = false,
    val textPresented: Boolean = false,
    val ungroundedAnswerRejected: Boolean = false
)

/**
 * Runs the provider's bounded multi-round tool exchange.
 *
 * Keeping this loop pure makes handler routing testable without an Activity while the
 * coordinator remains responsible for recording, status, cancellation, and preferences.
 */
class AssistantToolLoop(
    private val provider: VoiceAssistantProvider,
    private val apiKey: String,
    private val modelId: String,
    private val refreshCatalog: () -> AssistantToolCatalog,
    private val context: AssistantToolExecutionContext,
    private val shouldContinue: () -> Boolean = { true },
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val maxCalls: Int = DEFAULT_MAX_CALLS
) {
    fun run(initialCatalog: AssistantToolCatalog, initialTurn: AssistantProviderTurn): AssistantToolLoopOutcome {
        var catalog = initialCatalog
        var turn = initialTurn
        var executed = 0
        var round = 0
        val seen = mutableSetOf<String>()
        var recognized = false
        var anySuccess = false
        var searchFailed = turn.searchFailed
        var groundingEstablished = turn.searchAttempted
        val groundingSources = linkedMapOf<String, AssistantSource>()
        turn.sources.forEach { groundingSources[it.url] = it }
        var textPresented = false
        var browserTaskCompleted = false

        while (turn.calls.isNotEmpty() && round < maxRounds && executed < maxCalls && shouldContinue()) {
            val results = mutableListOf<Pair<AssistantToolCall, AssistantToolExecution>>()
            for (call in turn.calls.take(maxCalls - executed)) {
                if (!shouldContinue()) break
                // Grounding is meaningful state progress: the same presentation may be retried
                // after Search succeeds, but identical calls in the same state stay bounded.
                val signature = call.name + ":" + call.arguments.toString() +
                    ":groundingEstablished=" + groundingEstablished
                if (!seen.add(signature)) {
                    results += call to AssistantToolExecution(
                        false,
                        "REPEATED",
                        "Repeated call made no state progress"
                    )
                    continue
                }

                AssistantDiagnostics.debug(TAG, "call round=$round name=${call.name}")
                val result = if (call.name == "present_text" && !groundingEstablished) {
                    AssistantToolExecution(
                        false,
                        AssistantToolExecutionCodes.GROUNDING_REQUIRED,
                        "Run search_web before presenting assistant-authored factual text"
                    )
                } else {
                    val executableCall = if (call.name == "present_text" && groundingSources.isNotEmpty()) {
                        call.copy(arguments = withGroundingSources(call.arguments, groundingSources.values))
                    } else call
                    catalog.execute(executableCall, context).get()
                }
                if (result.success && result.groundingEvidence) {
                    groundingEstablished = true
                    result.sources.forEach { groundingSources[it.url] = it }
                }
                if (call.name == "search_web" && !result.success) searchFailed = true
                val accepted = result.code != AssistantToolExecutionCodes.UNKNOWN_TOOL &&
                    result.code != AssistantToolExecutionCodes.INVALID_ARGUMENTS
                if (accepted) {
                    recognized = true
                    executed++
                }
                anySuccess = anySuccess || result.success
                textPresented = textPresented || (call.name == "present_text" && result.success)
                browserTaskCompleted = browserTaskCompleted ||
                    (call.name == "run_browser_task" && result.success)
                AssistantDiagnostics.debug(TAG, "result round=$round name=${call.name} success=${result.success} code=${result.code}")
                results += call to result
            }

            // A successful Page Agent task is already the user-visible result. Do not spend
            // another main-assistant turn asking it to summarize a page action that completed.
            if (browserTaskCompleted || !shouldContinue() || results.isEmpty() || round + 1 >= maxRounds || executed >= maxCalls) break
            catalog = refreshCatalog()
            turn = provider.continueInteraction(
                apiKey,
                modelId,
                turn.interactionId,
                catalog.declarations(),
                results
            ).getOrThrow()
            searchFailed = searchFailed || turn.searchFailed
            groundingEstablished = groundingEstablished || turn.searchAttempted
            turn.sources.forEach { groundingSources[it.url] = it }
            round++
        }

        val answer = if (!textPresented && !searchFailed && turn.calls.isEmpty()) {
            turn.informationalAnswerOrNull(groundingSources.values.toList(), groundingEstablished)
        } else {
            null
        }
        val ungroundedAnswerRejected = !textPresented &&
            !searchFailed &&
            turn.calls.isEmpty() &&
            !groundingEstablished &&
            turn.outputText?.trim()?.takeIf(AssistantOutputPolicy::isPresentable) != null
        return AssistantToolLoopOutcome(
            anySuccess = anySuccess,
            recognized = recognized,
            executedCalls = executed,
            answer = answer,
            searchFailed = searchFailed,
            textPresented = textPresented,
            ungroundedAnswerRejected = ungroundedAnswerRejected
        )
    }

    companion object {
        private const val TAG = "AssistantTools"
        const val DEFAULT_MAX_ROUNDS = 5
        const val DEFAULT_MAX_CALLS = 10
    }
}

private fun withGroundingSources(
    arguments: JSONObject,
    sources: Collection<AssistantSource>
): JSONObject {
    val enriched = JSONObject(arguments.toString())
    val merged = linkedMapOf<String, AssistantSource>()
    val supplied = enriched.optJSONArray("sources")
    if (supplied != null) {
        for (index in 0 until supplied.length()) {
            val item = supplied.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (url.isNotEmpty()) merged[url] = AssistantSource(item.optString("name", url), url)
        }
    }
    sources.forEach { merged[it.url] = it }
    enriched.put("sources", JSONArray().apply {
        merged.values.forEach { source ->
            put(JSONObject().put("name", source.name).put("url", source.url))
        }
    })
    return enriched
}
