package com.everyday.everyday_glasses

import com.everyday.shared.command.AppCommandArguments
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.command.CommandWidgetId
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
    val widgetId: CommandWidgetId,
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
    val toggleStatus = definition("toggle:status", "Toggle: System", "System", "Show or hide the system widget", shortcut = true)
    val toggleLocation = definition("toggle:location", "Toggle: Location/Weather", "Weather", "Show or hide location and weather", shortcut = true)
    val toggleMap = definition("toggle:map", "Toggle: Map", "Map", "Show or hide the map")
    val toggleCalendar = definition("toggle:calendar", "Toggle: Calendar", "Calendar", "Show or hide Calendar", shortcut = true)
    val toggleFinance = definition("toggle:finance", "Toggle: Finance", "Finance", "Show or hide Finance", shortcut = true)
    val toggleNews = definition("toggle:news", "Toggle: News", "News", "Show or hide News", shortcut = true)
    val toggleNotifications = definition("toggle:notifications", "Toggle: Notifications", "Alerts", "Show or hide phone notifications", shortcut = true)
    val toggleSpeedometer = definition("toggle:speedometer", "Toggle: Speedometer", "Speed", "Show or hide the speedometer", shortcut = true)
    val toggleSubtitle = definition("toggle:subtitle", "Toggle: Subtitles", "Subs", "Show or hide live subtitles", shortcut = true)
    val toggleMirror = definition("toggle:mirror", "Toggle: Screen Mirror", "Mirror", "Show or hide phone screen mirroring", shortcut = true)
    val createText = definition("create:text", "Create: Text widget", "Text", "Create a text widget", shortcut = true)
    val createBrowser = definition("create:browser", "Open: Browser", "Browser", "Create a browser widget", shortcut = true)
    val openYouTubeHistory = definition("open:youtube", "Open: YouTube history", "YouTube", "Open YouTube history", shortcut = true)
    val toggleLayoutLock = definition("layout:toggle_lock", "Lock", "Lock", "Toggle widget layout locking")
    val saveLayout = definition("layout:save", "Save", "Save", "Save the active layout")
    val saveLayoutAs = definition("layout:save_as", "Save As...", "Save As", "Save the active layout with a new name")
    val openFile = definition("file:open", "Open...", "Open", "Open the file picker")
    val openSettings = definition("settings:open", "Settings", "Settings", "Open dashboard settings")
    val closeApp = definition("app:close", "Close", "Close", "Request application close")
    val setBrightness = definition(
        "display:set_brightness", "Set brightness", "Brightness", "Set display brightness from 0 to 1",
        CommandArgumentKind.BRIGHTNESS
    )
    val scroll = definition("ui:scroll", "Scroll", "Scroll", "Scroll the active UI", CommandArgumentKind.SCROLL)
    val openUrl = definition("browser:open_url", "Open URL", "URL", "Open an HTTP or HTTPS URL", CommandArgumentKind.URL)
    val enterText = definition(
        "text:enter", "Enter text", "Text", "Enter text into the focused field", CommandArgumentKind.TEXT,
        CommandAvailability.TEXT_FOCUSED
    )

    private val fixed = listOf(
        toggleStatus, toggleLocation, toggleMap, toggleCalendar, toggleFinance, toggleNews,
        toggleNotifications, toggleSpeedometer, toggleSubtitle, toggleMirror, createText, createBrowser,
        openYouTubeHistory, toggleLayoutLock, saveLayout, saveLayoutAs, openFile, openSettings, closeApp, setBrightness, scroll,
        openUrl, enterText
    )
    private val widgetCommands = linkedMapOf(
        CommandWidgetId.STATUS to toggleStatus,
        CommandWidgetId.LOCATION to toggleLocation,
        CommandWidgetId.MAP to toggleMap,
        CommandWidgetId.CALENDAR to toggleCalendar,
        CommandWidgetId.FINANCE to toggleFinance,
        CommandWidgetId.NEWS to toggleNews,
        CommandWidgetId.NOTIFICATIONS to toggleNotifications,
        CommandWidgetId.SPEEDOMETER to toggleSpeedometer,
        CommandWidgetId.SUBTITLE to toggleSubtitle,
        CommandWidgetId.MIRROR to toggleMirror
    )
    private val commandWidgets = widgetCommands.entries.associate { (widget, command) -> command to widget }
    private data class AssistantVisibilityCommand(
        val definition: AppCommandDefinition,
        val target: WidgetVisibilityTarget
    )

    /** Idempotent visibility commands reserved for the assistant tool catalog. */
    private val assistantVisibilityCommands: List<AssistantVisibilityCommand> =
        CommandWidgetId.values().flatMap { widgetId ->
            val label = WidgetDescriptors.forCommand(widgetId).label
            listOf(
                AssistantVisibilityCommand(
                    definition(
                        "show:${widgetId.name.lowercase()}",
                        "Show: $label",
                        "Show $label",
                        "Ensure the $label widget is shown; no-op if it is already shown"
                    ),
                    WidgetVisibilityTarget(widgetId, visible = true)
                ),
                AssistantVisibilityCommand(
                    definition(
                        "hide:${widgetId.name.lowercase()}",
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
            listOf(invocation(createText), invocation(createBrowser))

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

    fun widgetDefinition(widgetId: CommandWidgetId): AppCommandDefinition =
        requireNotNull(widgetCommands[widgetId])

    fun widgetId(definition: AppCommandDefinition): CommandWidgetId? = commandWidgets[definition]

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
