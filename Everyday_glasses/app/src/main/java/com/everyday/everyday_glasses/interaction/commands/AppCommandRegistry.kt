package com.everyday.everyday_glasses.interaction.commands

import com.everyday.everyday_glasses.runtime.WidgetCommand
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetMenu
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts
import com.everyday.everyday_glasses.widgets.WidgetPlugins
import com.everyday.shared.command.AppCommandArguments
import com.everyday.shared.command.AppCommandInvocation
import java.net.URI

enum class CommandArgumentKind { NONE, BRIGHTNESS, SCROLL, URL, TEXT }
enum class CommandAvailability { ALWAYS, APP_READY, TEXT_FOCUSED, BROWSER_AVAILABLE }

data class AppCommandDefinition(
    val id: String,
    val label: String,
    val shortLabel: String,
    val description: String,
    val argumentKind: CommandArgumentKind = CommandArgumentKind.NONE,
    val availability: CommandAvailability = CommandAvailability.APP_READY,
    val shortcutEligible: Boolean = false
)

data class WidgetVisibilityTarget(
    val widgetId: WidgetTypeId,
    val visible: Boolean
)

sealed class CommandValidationResult {
    data class Valid(val definition: AppCommandDefinition, val invocation: AppCommandInvocation) : CommandValidationResult()
    data class Invalid(val reason: String) : CommandValidationResult()
}

data class CommandToolDeclaration(
    val name: String,
    val description: String,
    val argumentKind: String,
    val required: Boolean
)

/** The sole owner of stable command identity and declarative metadata. */
class AppCommandRegistry(private val savedLayoutNames: () -> List<String> = { emptyList() }) {
    val toggleLayoutLock = definition("layout:toggle_lock", "Lock", "Lock", "Toggle widget layout locking")
    val saveLayout = definition("layout:save", "Save", "Save", "Save the active layout")
    val saveLayoutAs = definition("layout:save_as", "Save As...", "Save As", "Save the active layout with a new name")
    val openSettings = definition("settings:open", "Settings", "Settings", "Open dashboard settings")
    val closeApp = definition("app:close", "Close", "Close", "Request application close")
    val setBrightness = definition(
        "display:set_brightness", "Set brightness", "Brightness", "Set display brightness from 0 to 1",
        CommandArgumentKind.BRIGHTNESS
    )
    val scroll = definition("ui:scroll", "Scroll", "Scroll", "Scroll the active UI", CommandArgumentKind.SCROLL)
    val enterText = definition(
        "text:enter", "Enter text", "Text", "Enter text into the focused field", CommandArgumentKind.TEXT,
        CommandAvailability.TEXT_FOCUSED
    )

    /** The dashboard's own commands. The widget types add theirs, below. */
    private val appCommands = listOf(
        toggleLayoutLock, saveLayout, saveLayoutAs, openSettings, closeApp, setBrightness, scroll, enterText
    )

    /** Every widget type's commands, each with the type's descriptor. */
    private val typeCommands: List<Pair<WidgetDescriptor, WidgetCommand>> =
        WidgetPlugins.all.flatMap { type -> type.commands.map { command -> type.descriptor to command } }

    /** The single-instance toggles, in descriptor order. */
    private val toggles = typeCommands.filter { it.second.menu == WidgetMenu.TOGGLE }.sortedBy { it.first.order }

    /** The types' other commands, in menu order (each type's in its own order). */
    private val otherWidgetCommands = typeCommands.filter { it.second.menu != WidgetMenu.TOGGLE }.sortedBy { it.first.menuOrder }

    private val fixed = toggles.map { it.second.definition } + otherWidgetCommands.map { it.second.definition } + appCommands

    /** Each toggle, by the type it shows or hides. */
    private val widgetCommands = toggles.associate { (descriptor, command) -> descriptor.type to command.definition }

    /** The type that runs each of the other widget commands. */
    private val commandTypes = otherWidgetCommands.associate { (descriptor, command) -> command.definition to descriptor.type }
    private val commandWidgets = widgetCommands.entries.associate { (widget, command) -> command to widget }
    private data class AssistantVisibilityCommand(
        val definition: AppCommandDefinition,
        val target: WidgetVisibilityTarget
    )

    /** Idempotent visibility commands reserved for the assistant tool catalog. */
    private val assistantVisibilityCommands: List<AssistantVisibilityCommand> =
        WidgetDescriptors.all.filter { it.policy == WidgetInstancePolicy.SINGLETON }.flatMap { descriptor ->
            val widgetId = descriptor.type
            val label = descriptor.label
            listOf(
                AssistantVisibilityCommand(
                    definition(
                        "show:${widgetId.persistedId}",
                        "Show: $label",
                        "Show $label",
                        "Ensure the $label widget is shown; no-op if it is already shown"
                    ),
                    WidgetVisibilityTarget(widgetId, visible = true)
                ),
                AssistantVisibilityCommand(
                    definition(
                        "hide:${widgetId.persistedId}",
                        "Hide: $label",
                        "Hide $label",
                        "Ensure the $label widget is hidden; no-op if it is already hidden"
                    ),
                    WidgetVisibilityTarget(widgetId, visible = false)
                )
            )
        }
    private val assistantVisibilityById = assistantVisibilityCommands.associateBy { it.definition.id }

    val defaultShortcutInvocations: List<AppCommandInvocation>
        get() = BuiltInLayouts.NAMES.map { invocation(layoutCommandId(it)) } +
            widgetMenuCommands(WidgetMenu.CREATE).map { invocation(it.definition) }

    fun definitions(): List<AppCommandDefinition> = fixed + layoutDefinitions().sortedBy { it.id }

    /**
     * Model-facing definitions. Legacy toggle commands remain available to menus and shortcuts,
     * but singleton widget toggles are replaced here by idempotent show/hide commands.
     */
    fun assistantDefinitions(): List<AppCommandDefinition> =
        fixed.filterNot { widgetId(it) != null } +
            assistantVisibilityCommands.map { it.definition } +
            layoutDefinitions().sortedBy { it.id }

    fun shortcutDefinitions(): List<AppCommandDefinition> =
        fixed.filter { it.shortcutEligible } + layoutDefinitions()

    fun find(id: String): AppCommandDefinition? = fixed.firstOrNull { it.id == id }
        ?: assistantVisibilityById[id]?.definition
        ?: layoutDefinitions().firstOrNull { it.id == id }

    fun widgetDefinition(widgetId: WidgetTypeId): AppCommandDefinition =
        requireNotNull(widgetCommands[widgetId]) { "No toggle command for widget type $widgetId" }

    fun widgetId(definition: AppCommandDefinition): WidgetTypeId? = commandWidgets[definition]

    /** The widget type that runs [definition], for a widget command other than a toggle. */
    fun widgetCommandType(definition: AppCommandDefinition): WidgetTypeId? = commandTypes[definition]

    /** The widget commands listed on [menu]'s page, in menu order. */
    fun widgetMenuCommands(menu: WidgetMenu): List<WidgetCommand> =
        (toggles + otherWidgetCommands).map { it.second }.filter { it.menu == menu }

    fun widgetVisibility(definition: AppCommandDefinition): WidgetVisibilityTarget? =
        assistantVisibilityById[definition.id]?.target

    fun validate(invocation: AppCommandInvocation): CommandValidationResult {
        val definition = find(invocation.commandId)
            ?: return CommandValidationResult.Invalid("Unknown command: ${invocation.commandId}")
        val valid = when (definition.argumentKind) {
            CommandArgumentKind.NONE -> invocation.arguments === AppCommandArguments.None
            CommandArgumentKind.BRIGHTNESS -> (invocation.arguments as? AppCommandArguments.Brightness)
                ?.value?.let { it.isFinite() && it in 0f..1f } == true
            CommandArgumentKind.SCROLL -> (invocation.arguments as? AppCommandArguments.Scroll)
                ?.amount?.let { it.isFinite() && it > 0f && it <= 2_000f } == true
            CommandArgumentKind.URL -> (invocation.arguments as? AppCommandArguments.Url)?.url?.let(::isSafeUrl) == true
            CommandArgumentKind.TEXT -> (invocation.arguments as? AppCommandArguments.Text)?.text?.length?.let { it <= 10_000 } == true
        }
        return if (valid) CommandValidationResult.Valid(definition, invocation)
        else CommandValidationResult.Invalid("Invalid arguments for ${definition.id}")
    }

    fun toolDeclarations(): List<CommandToolDeclaration> = definitions().map {
        CommandToolDeclaration(it.id, it.description, it.argumentKind.name.lowercase(), it.argumentKind != CommandArgumentKind.NONE)
    }

    fun invocation(definition: AppCommandDefinition): AppCommandInvocation = invocation(definition.id)
    fun invocation(id: String): AppCommandInvocation = AppCommandInvocation(id)

    fun layoutName(definition: AppCommandDefinition): String? =
        definition.id.takeIf { it.startsWith(LAYOUT_PREFIX) }?.removePrefix(LAYOUT_PREFIX)?.takeIf { it.isNotBlank() }

    private fun layoutDefinitions(): List<AppCommandDefinition> =
        (BuiltInLayouts.NAMES + savedLayoutNames()).filter { it.isNotBlank() }.distinct().map { name ->
            definition(layoutCommandId(name), "Layout: $name", name, "Load the $name layout", shortcut = true)
        }

    private fun layoutCommandId(name: String) = "$LAYOUT_PREFIX$name"

    private fun isSafeUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.isAbsolute && uri.host != null && uri.scheme.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private fun definition(
        id: String,
        label: String,
        shortLabel: String,
        description: String,
        argumentKind: CommandArgumentKind = CommandArgumentKind.NONE,
        availability: CommandAvailability = CommandAvailability.APP_READY,
        shortcut: Boolean = false
    ) = AppCommandDefinition(id, label, shortLabel, description, argumentKind, availability, shortcut)

    companion object {
        const val MAX_LEMON_COMMANDS = 10
        private const val LAYOUT_PREFIX = "layout:"
    }
}