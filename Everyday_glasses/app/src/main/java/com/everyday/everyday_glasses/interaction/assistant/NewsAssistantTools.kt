package com.everyday.everyday_glasses.interaction.assistant

import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.NewsSourceSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CompletableFuture

data class NewsSourceUpdateResult(
    val applied: Boolean,
    val message: String,
    val sourceSpec: NewsSourceSpec? = null
)

/** Semantic assistant tool for changing the existing singleton News widget's source. */
class SetNewsSourceSemanticToolHandler(
    private val updateSource: (NewsSourceSpec) -> CompletableFuture<NewsSourceUpdateResult>
) : AssistantSemanticToolHandler {

    override val definition = AssistantToolDefinition(
        name = "set_news_source",
        description = "Change the source of the existing News widget without creating another widget",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put(
                        "kind", JSONObject()
                            .put("type", "string")
                            .put(
                                "enum",
                                JSONArray().put("default_location").put("query").put("custom_rss")
                            )
                    )
                    .put(
                        "intent", JSONObject()
                            .put("type", "string")
                            .put("maxLength", 512)
                    )
                    .put(
                        "url", JSONObject()
                            .put("type", "string")
                            .put("maxLength", 2048)
                    )
            )
            .put("required", JSONArray().put("kind"))
    )

    override fun validate(arguments: JSONObject): Result<Unit> = parse(arguments).map { }

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> {
        val source = parse(arguments).getOrElse { failure ->
            return assistantCompletedFuture(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.INVALID_ARGUMENTS,
                    failure.message ?: "Invalid news source"
                )
            )
        }

        return updateSource(source).thenApply { result ->
            AssistantToolExecution(
                success = result.applied,
                code = if (result.applied) {
                    AssistantToolExecutionCodes.SUCCESS
                } else {
                    AssistantToolExecutionCodes.EXECUTION_FAILED
                },
                message = result.message
            )
        }
    }

    private fun parse(arguments: JSONObject): Result<NewsSourceSpec> = runCatching {
        when (arguments.getString("kind").trim().lowercase(Locale.US)) {
            "default_location", "default" -> NewsSourceSpec.DEFAULT_LOCATION
            "query" -> {
                val intent = arguments.getString("intent").trim()
                require(intent.isNotBlank()) { "intent is required for a query source" }
                require(intent.length <= 512) { "intent is too long" }
                NewsSourceSpec.QUERY(intent)
            }
            "custom_rss", "rss" -> {
                val url = arguments.getString("url").trim()
                require(url.length <= 2048) { "url is too long" }
                require(NewsDataProvider.isSupportedHttpUrl(url)) {
                    "url must be an HTTP(S) URL without credentials"
                }
                NewsSourceSpec.CUSTOM_RSS(url)
            }
            else -> error("Unknown news source kind")
        }
    }
}
