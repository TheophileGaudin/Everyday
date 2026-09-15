package com.everyday.everyday_glasses.agent_tools

import com.everyday.everyday_glasses.controllers.AppCommandResult
import com.everyday.everyday_glasses.managers.AppCommandDefinition
import com.everyday.everyday_glasses.managers.AppCommandRegistry
import com.everyday.everyday_glasses.managers.CommandArgumentKind
import com.everyday.everyday_glasses.managers.CommandAvailability
import com.everyday.everyday_glasses.managers.CommandValidationResult
import com.everyday.shared.command.AppCommandArguments
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.command.CommandScrollDirection
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

private fun AppCommandResult.toAssistantExecution(): AssistantToolExecution = when (this) {
    is AppCommandResult.Success -> AssistantToolExecution(true, AssistantToolExecutionCodes.SUCCESS, "Command completed")
    is AppCommandResult.Failure -> AssistantToolExecution(false, code.name, message)
}

private fun externalName(id: String): String = id.replace(Regex("[^A-Za-z0-9_]"), "_")

private fun commandSchema(kind: CommandArgumentKind): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    when (kind) {
        CommandArgumentKind.NONE -> Unit
        CommandArgumentKind.BRIGHTNESS -> {
            properties.put("value", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1))
            required.put("value")
        }
        CommandArgumentKind.SCROLL -> {
            properties.put(
                "direction",
                JSONObject().put("type", "string").put("enum", JSONArray(listOf("up", "down")))
            )
            properties.put("amount", JSONObject().put("type", "number").put("minimum", 1).put("maximum", 2000))
            required.put("direction")
        }
        CommandArgumentKind.URL -> {
            properties.put("url", JSONObject().put("type", "string"))
            required.put("url")
        }
        CommandArgumentKind.TEXT -> {
            properties.put("text", JSONObject().put("type", "string").put("maxLength", 10000))
            required.put("text")
        }
    }
    return JSONObject().put("type", "object").put("properties", properties).put("required", required)
}

private fun decodeArguments(kind: CommandArgumentKind, arguments: JSONObject): AppCommandArguments = when (kind) {
    CommandArgumentKind.NONE -> AppCommandArguments.None
    CommandArgumentKind.BRIGHTNESS -> AppCommandArguments.Brightness(arguments.getDouble("value").toFloat())
    CommandArgumentKind.SCROLL -> AppCommandArguments.Scroll(
        when (arguments.getString("direction").lowercase()) {
            "up" -> CommandScrollDirection.UP
            "down" -> CommandScrollDirection.DOWN
            else -> error("Invalid scroll direction")
        },
        arguments.optDouble("amount", 300.0).toFloat()
    )
    CommandArgumentKind.URL -> AppCommandArguments.Url(arguments.getString("url"))
    CommandArgumentKind.TEXT -> AppCommandArguments.Text(arguments.getString("text"))
}

private interface AppCommandInvocationDecoder {
    fun decodeInvocation(arguments: JSONObject): Result<AppCommandInvocation>
}

/** Adapts one existing deterministic app command into the semantic tool contract. */
private class AppCommandSemanticToolHandler(
    private val registry: AppCommandRegistry,
    private val command: AppCommandDefinition,
    private val executeCommand: (AppCommandInvocation) -> AppCommandResult,
    override val isAdvertised: Boolean
) : AssistantSemanticToolHandler, AppCommandInvocationDecoder {
    override val definition = AssistantToolDefinition(
        externalName(command.id),
        command.description,
        commandSchema(command.argumentKind)
    )
    override fun validate(arguments: JSONObject): Result<Unit> = decodeInvocation(arguments).map { Unit }

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = assistantCompletedFuture(
        executeCommand(decodeInvocation(arguments).getOrThrow()).toAssistantExecution()
    )

    override fun decodeInvocation(arguments: JSONObject): Result<AppCommandInvocation> = runCatching {
        val invocation = AppCommandInvocation(command.id, decodeArguments(command.argumentKind, arguments))
        when (val validation = registry.validate(invocation)) {
            is CommandValidationResult.Valid -> invocation
            is CommandValidationResult.Invalid -> error(validation.reason)
        }
    }
}

/** Combines dynamic layout names behind the legacy `load_layout` function schema. */
private class AppCommandLayoutSemanticToolHandler(
    private val registry: AppCommandRegistry,
    private val layoutNames: List<String>,
    private val executeCommand: (AppCommandInvocation) -> AppCommandResult,
    override val isAdvertised: Boolean
) : AssistantSemanticToolHandler, AppCommandInvocationDecoder {
    override val definition = AssistantToolDefinition(
        "load_layout",
        "Load one of the available dashboard layouts",
        JSONObject().put("type", "object").put(
            "properties", JSONObject().put(
                "name", JSONObject().put("type", "string").put("enum", JSONArray(layoutNames))
            )
        ).put("required", JSONArray().put("name"))
    )

    override fun validate(arguments: JSONObject): Result<Unit> = decodeInvocation(arguments).map { Unit }

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = assistantCompletedFuture(
        executeCommand(decodeInvocation(arguments).getOrThrow()).toAssistantExecution()
    )

    override fun decodeInvocation(arguments: JSONObject): Result<AppCommandInvocation> = runCatching {
        val name = arguments.getString("name")
        require(name in layoutNames) { "Unknown layout" }
        val definition = registry.assistantDefinitions().firstOrNull { registry.layoutName(it) == name }
            ?: error("Unknown layout")
        registry.invocation(definition)
    }
}

/**
 * Composes semantic handlers and routes calls by their registered provider-facing name.
 *
 * The registry constructor is retained for existing callers. It adapts each current app
 * command into a handler and optionally appends new semantic handlers, so command identity,
 * schemas, validation, and behavior stay owned by the existing command registry/executor.
 */
class AssistantToolCatalog private constructor(
    private val handlerList: List<AssistantSemanticToolHandler>
) {
    constructor(handlers: Collection<AssistantSemanticToolHandler>) : this(handlers.toList())

    constructor(
        registry: AppCommandRegistry,
        isAvailable: (CommandAvailability) -> Boolean
    ) : this(registry, isAvailable, { AppCommandResult.Success(it.commandId) }, emptyList())

    constructor(
        registry: AppCommandRegistry,
        isAvailable: (CommandAvailability) -> Boolean,
        executeCommand: (AppCommandInvocation) -> AppCommandResult
    ) : this(registry, isAvailable, executeCommand, emptyList())

    constructor(
        registry: AppCommandRegistry,
        isAvailable: (CommandAvailability) -> Boolean,
        executeCommand: (AppCommandInvocation) -> AppCommandResult,
        extraHandlers: Collection<AssistantSemanticToolHandler>
    ) : this(buildHandlers(registry, isAvailable, executeCommand) + extraHandlers.toList())

    private val handlersByName: Map<String, AssistantSemanticToolHandler> = run {
        handlerList.forEach { handler ->
            require(handler.definition.name.isNotBlank()) { "Assistant tool name must not be blank" }
        }
        val duplicateNames = handlerList.groupBy { it.definition.name }
            .filterValues { it.size > 1 }
            .keys
            .toList()
        require(duplicateNames.isEmpty()) {
            "Duplicate assistant tool name: ${duplicateNames.first()}"
        }
        handlerList.associateBy { it.definition.name }
    }

    fun declarations(): List<AssistantToolDefinition> = handlerList
        .filter { it.isAdvertised }
        .map { it.definition }

    fun hasHandler(name: String): Boolean = handlersByName.containsKey(name)

    fun validate(call: AssistantToolCall): Result<Unit> = runCatching {
        val handler = handlersByName[call.name] ?: error("Unknown tool")
        handler.validate(call.arguments).getOrThrow()
    }

    /** Routes unknown calls to a deterministic provider result without executing anything. */
    fun execute(
        call: AssistantToolCall,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> {
        val handler = handlersByName[call.name] ?: return assistantCompletedFuture(
            AssistantToolExecution(false, AssistantToolExecutionCodes.UNKNOWN_TOOL, "Unknown tool")
        )
        return handler.invoke(call, context)
    }

    /** Alias useful to callers that describe a tool call as an invocation. */
    fun invoke(
        call: AssistantToolCall,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = execute(call, context)

    /**
     * Compatibility decoder for the existing app-command tests and non-voice callers.
     * Semantic handlers that do not represent an app command deliberately cannot be decoded
     * into the low-level command model.
     */
    fun decode(call: AssistantToolCall): Result<AppCommandInvocation> = runCatching {
        val handler = handlersByName[call.name] ?: error("Unknown tool")
        (handler as? AppCommandInvocationDecoder)?.decodeInvocation(call.arguments)?.getOrThrow()
            ?: error("Tool is not an app command")
    }

    companion object {
        private fun buildHandlers(
            registry: AppCommandRegistry,
            isAvailable: (CommandAvailability) -> Boolean,
            executeCommand: (AppCommandInvocation) -> AppCommandResult
        ): List<AssistantSemanticToolHandler> {
            val definitions = registry.assistantDefinitions()
            val fixed = definitions
                .filter { registry.layoutName(it) == null }
                .map { definition ->
                    AppCommandSemanticToolHandler(
                        registry,
                        definition,
                        executeCommand,
                        isAdvertised = isAvailable(definition.availability)
                    )
                }
            val layouts = definitions.mapNotNull { registry.layoutName(it) }.distinct()
            return fixed + if (layouts.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    AppCommandLayoutSemanticToolHandler(
                        registry,
                        layouts,
                        executeCommand,
                        isAdvertised = isAvailable(CommandAvailability.APP_READY)
                    )
                )
            }
        }

    }
}
