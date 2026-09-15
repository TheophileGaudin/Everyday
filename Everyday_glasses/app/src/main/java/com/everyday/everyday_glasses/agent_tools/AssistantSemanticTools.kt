package com.everyday.everyday_glasses.agent_tools

import com.everyday.everyday_glasses.controllers.PresentationExpiryPolicy
import com.everyday.everyday_glasses.controllers.PresentationMode
import com.everyday.everyday_glasses.controllers.PresentationSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

/** A screenshot captured only after an explicit assistant request. */
data class AssistantScreenshot(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/png"
) {
    init {
        require(bytes.isNotEmpty()) { "Screenshot data must not be empty" }
        require(width > 0) { "Screenshot width must be positive" }
        require(height > 0) { "Screenshot height must be positive" }
        require(mimeType.isNotBlank()) { "Screenshot MIME type must not be blank" }
    }
}

/**
 * Runtime dependencies available to semantic handlers.
 *
 * UI inspection and screenshot capture are callbacks rather than fields on the normal
 * request context. This keeps those potentially sensitive operations explicitly on-demand.
 */
data class AssistantToolExecutionContext(
    val uiContext: AssistantUiContext,
    val captureUiContext: () -> CompletableFuture<AssistantUiContext> = {
        assistantCompletedFuture(uiContext)
    },
    val requestScreenshot: () -> CompletableFuture<AssistantScreenshot> = {
        assistantFailedFuture(UnsupportedOperationException("Screenshot capture is unavailable"))
    },
    val presentText: (AssistantTextPresentationRequest) -> CompletableFuture<AssistantToolExecution> = {
        assistantCompletedFuture(
            AssistantToolExecution(
                false,
                AssistantToolExecutionCodes.EXECUTION_FAILED,
                "Text presentation is unavailable"
            )
        )
    }
)

/** Compatibility name for callers that use the shorter context terminology. */
typealias AssistantToolContext = AssistantToolExecutionContext

/** Compatibility name for callers that use the shorter handler terminology. */
typealias AssistantToolHandler = AssistantSemanticToolHandler

/**
 * Extensible semantic assistant action.
 *
 * A handler owns its provider-facing definition, JSON argument validation, asynchronous
 * execution, and conversion to [AssistantToolExecution]. The catalog only selects a handler
 * by name; it does not know the handler's argument shape or behavior.
 */
interface AssistantSemanticToolHandler {
    val definition: AssistantToolDefinition

    /** Whether this handler is currently advertised; it may still route stale calls. */
    val isAdvertised: Boolean get() = true

    fun validate(arguments: JSONObject): Result<Unit>

    fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution>

    fun invoke(
        call: AssistantToolCall,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> {
        val validation = runCatching { validate(call.arguments) }.getOrElse { failure ->
            return assistantCompletedFuture(
                AssistantToolExecution(false, AssistantToolExecutionCodes.INVALID_ARGUMENTS, failure.message ?: "Invalid arguments")
            )
        }
        if (validation.isFailure) {
            return assistantCompletedFuture(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.INVALID_ARGUMENTS,
                    validation.exceptionOrNull()?.message ?: "Invalid arguments"
                )
            )
        }

        return try {
            execute(call.arguments, context).handle { result, failure ->
                if (failure == null && result != null) {
                    result
                } else {
                    val cause = failure?.cause ?: failure
                    AssistantToolExecution(
                        false,
                        AssistantToolExecutionCodes.EXECUTION_FAILED,
                        cause?.message ?: "Tool execution failed"
                    )
                }
            }
        } catch (failure: Throwable) {
            assistantCompletedFuture(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.EXECUTION_FAILED,
                    failure.message ?: "Tool execution failed"
                )
            )
        }
    }

    fun handle(
        call: AssistantToolCall,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = invoke(call, context)
}

object AssistantToolExecutionCodes {
    const val SUCCESS = "SUCCESS"
    const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
    const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    const val GROUNDING_REQUIRED = "GROUNDING_REQUIRED"
    const val EXECUTION_FAILED = "EXECUTION_FAILED"
}

fun <T> assistantCompletedFuture(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

fun <T> assistantFailedFuture(failure: Throwable): CompletableFuture<T> = CompletableFuture<T>().also {
    it.completeExceptionally(failure)
}

private fun emptyObjectSchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put("properties", JSONObject())

private fun validateNoArguments(arguments: JSONObject): Result<Unit> =
    if (arguments.length() == 0) {
        Result.success(Unit)
    } else {
        Result.failure(IllegalArgumentException("This tool does not accept arguments"))
    }

private fun presentationSchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put(
        "properties", JSONObject()
            .put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("fullscreen").put("windowed")))
            .put(
                "duration_seconds",
                JSONObject().put("type", "integer").put("minimum", PresentationSpec.MIN_DURATION_SECONDS)
                    .put("maximum", PresentationSpec.MAX_DURATION_SECONDS)
            )
            .put(
                "on_expiry",
                JSONObject().put("type", "string").put(
                    "enum",
                    JSONArray().put("restore_previous").put("leave_windowed").put("close")
                )
            )
    )
    .put(
        "required",
        JSONArray().put("mode").put("duration_seconds").put("on_expiry")
    )

private fun sourceSchema(): JSONObject = JSONObject()
    .put("type", "array")
    .put(
        "items", JSONObject().put("type", "object").put(
            "properties", JSONObject()
                .put("name", JSONObject().put("type", "string"))
                .put("url", JSONObject().put("type", "string"))
        )
    )

private fun parsePresentation(arguments: JSONObject): Result<PresentationSpec> = runCatching {
    val raw = arguments.optJSONObject("presentation")
        ?: arguments.optJSONObject("presentation_spec")
        ?: error("presentation is required")
    PresentationSpec(
        mode = when (raw.getString("mode").lowercase()) {
            "fullscreen" -> PresentationMode.FULLSCREEN
            "windowed" -> PresentationMode.WINDOWED
            else -> error("Unknown presentation mode")
        },
        durationSeconds = raw.getInt("duration_seconds"),
        onExpiry = when (raw.getString("on_expiry").lowercase()) {
            "restore_previous" -> PresentationExpiryPolicy.RESTORE_PREVIOUS
            "leave_windowed" -> PresentationExpiryPolicy.LEAVE_WINDOWED
            "close" -> PresentationExpiryPolicy.CLOSE
            else -> error("Unknown presentation expiry policy")
        }
    )
}

private fun parseSources(arguments: JSONObject): Result<List<AssistantSource>> = runCatching {
    val raw = arguments.optJSONArray("sources") ?: return@runCatching emptyList()
    buildList {
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: error("Source must be an object")
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            require(name.isNotBlank() || url.isNotBlank()) { "Source must have a name or URL" }
            add(AssistantSource(name, url))
        }
    }
}

/** Presents assistant-authored prose through the shared temporary-presentation host. */
class PresentTextSemanticToolHandler : AssistantSemanticToolHandler {
    override val definition = AssistantToolDefinition(
        name = "present_text",
        description = "Present concise assistant text on the Everyday glasses display",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("content", JSONObject().put("type", "string"))
                    .put("presentation", presentationSchema())
                    .put("target_widget_id", JSONObject().put("type", "string"))
                    .put("sources", sourceSchema())
            )
            .put("required", JSONArray().put("content").put("presentation"))
    )

    override fun validate(arguments: JSONObject): Result<Unit> = runCatching {
        require(arguments.has("content")) { "content is required" }
        require(arguments.getString("content").isNotBlank()) { "content must not be blank" }
        parsePresentation(arguments).getOrThrow()
        parseSources(arguments).getOrThrow()
        arguments.optString("target_widget_id").takeIf { it.isNotBlank() }
    }.map { Unit }

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> {
        val request = runCatching {
            AssistantTextPresentationRequest(
                content = arguments.getString("content"),
                spec = parsePresentation(arguments).getOrThrow(),
                targetWidgetId = arguments.optString("target_widget_id").takeIf { it.isNotBlank() },
                sources = parseSources(arguments).getOrThrow()
            )
        }.getOrElse { failure ->
            return assistantCompletedFuture(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.INVALID_ARGUMENTS,
                    failure.message ?: "Invalid present_text arguments"
                )
            )
        }
        return context.presentText(request)
    }
}

/** Returns the current semantic UI state when the model explicitly asks to inspect it. */
class InspectUiSemanticToolHandler : AssistantSemanticToolHandler {
    override val definition = AssistantToolDefinition(
        name = "inspect_ui",
        description = "Inspect the current Everyday glasses widget state and semantic facts",
        parameters = emptyObjectSchema()
    )

    override fun validate(arguments: JSONObject): Result<Unit> = validateNoArguments(arguments)

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = context.captureUiContext().thenApply { uiContext ->
        AssistantToolExecution(
            true,
            AssistantToolExecutionCodes.SUCCESS,
            uiContext.toJson().toString()
        )
    }
}

/** Captures and returns the glasses view only when the model explicitly requests it. */
class RequestScreenshotSemanticToolHandler : AssistantSemanticToolHandler {
    override val definition = AssistantToolDefinition(
        name = "request_screenshot",
        description = "Capture the current glasses view for visual inspection; use only when needed",
        parameters = emptyObjectSchema()
    )

    override fun validate(arguments: JSONObject): Result<Unit> = validateNoArguments(arguments)

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = context.requestScreenshot().thenApply { screenshot ->
        AssistantToolExecution(
            true,
            AssistantToolExecutionCodes.SUCCESS,
            "Screenshot captured for visual inspection",
            screenshots = listOf(screenshot)
        )
    }
}
