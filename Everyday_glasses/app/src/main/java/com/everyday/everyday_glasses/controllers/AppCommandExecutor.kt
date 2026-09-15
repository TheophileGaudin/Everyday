package com.everyday.everyday_glasses.controllers

import com.everyday.everyday_glasses.managers.AppCommandDefinition
import com.everyday.everyday_glasses.managers.AppCommandRegistry
import com.everyday.everyday_glasses.managers.CommandAvailability
import com.everyday.everyday_glasses.managers.CommandValidationResult
import com.everyday.shared.command.AppCommandArguments
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.command.CommandScrollDirection
import com.everyday.shared.command.CommandWidgetId

interface AppCommandCapabilities {
    fun isAvailable(requirement: CommandAvailability): Boolean
    fun toggleWidget(widgetId: CommandWidgetId)
    fun setWidgetVisible(widgetId: CommandWidgetId, visible: Boolean)
    fun createTextWidget()
    fun createBrowserWidget()
    fun openYouTubeHistory()
    fun loadLayout(name: String): Boolean
    fun toggleLayoutLock()
    fun saveLayout()
    fun saveLayoutAs()
    fun openFilePicker()
    fun openSettings()
    fun requestClose()
    fun setBrightness(value: Float)
    fun scroll(direction: CommandScrollDirection, amount: Float)
    fun openUrl(url: String)
    fun enterText(text: String)
    fun commandFinished()
}

fun interface CommandUiDispatcher {fun dispatch(action: () -> Boolean): Boolean?} /** Returns the completed action result, or null when no completed result is available. */

sealed class AppCommandResult {
    data class Success(val commandId: String) : AppCommandResult()
    data class Failure(val commandId: String, val code: Code, val message: String) : AppCommandResult() {
        enum class Code { UNKNOWN, INVALID_ARGUMENTS, UNAVAILABLE, APPLICATION_FAILURE }
    }
}

/** The single glasses-side command dispatch boundary. */
class AppCommandExecutor(
    private val registry: AppCommandRegistry,
    private val capabilities: AppCommandCapabilities,
    private val uiDispatcher: CommandUiDispatcher
) {
    fun execute(invocation: AppCommandInvocation): AppCommandResult {
        val validated = registry.validate(invocation)
        if (validated is CommandValidationResult.Invalid) {
            val code = if (registry.find(invocation.commandId) == null) AppCommandResult.Failure.Code.UNKNOWN
            else AppCommandResult.Failure.Code.INVALID_ARGUMENTS
            return AppCommandResult.Failure(invocation.commandId, code, validated.reason)
        }
        validated as CommandValidationResult.Valid
        if (!capabilities.isAvailable(validated.definition.availability)) {
            return AppCommandResult.Failure(
                invocation.commandId, AppCommandResult.Failure.Code.UNAVAILABLE, "Command is unavailable"
            )
        }
        val completed = uiDispatcher.dispatch {
            try {
                executeValidated(validated.definition, validated.invocation.arguments)
            } catch (_: Throwable) {
                false
            } finally {
                capabilities.commandFinished()
            }
        }
        return if (completed == true) AppCommandResult.Success(invocation.commandId) else AppCommandResult.Failure(
            invocation.commandId, AppCommandResult.Failure.Code.APPLICATION_FAILURE, "Application could not complete command"
        )
    }

    private fun executeValidated(definition: AppCommandDefinition, arguments: AppCommandArguments): Boolean {
        registry.widgetVisibility(definition)?.let { target ->
            capabilities.setWidgetVisible(target.widgetId, target.visible)
            return true
        }
        val widget = registry.widgetId(definition)
        if (widget != null) {
            capabilities.toggleWidget(widget)
            return true
        }
        return when (definition) {
            registry.createText -> true.also { capabilities.createTextWidget() }
            registry.createBrowser -> true.also { capabilities.createBrowserWidget() }
            registry.openYouTubeHistory -> true.also { capabilities.openYouTubeHistory() }
            registry.toggleLayoutLock -> true.also { capabilities.toggleLayoutLock() }
            registry.saveLayout -> true.also { capabilities.saveLayout() }
            registry.saveLayoutAs -> true.also { capabilities.saveLayoutAs() }
            registry.openFile -> true.also { capabilities.openFilePicker() }
            registry.openSettings -> true.also { capabilities.openSettings() }
            registry.closeApp -> true.also { capabilities.requestClose() }
            registry.setBrightness -> true.also { capabilities.setBrightness((arguments as AppCommandArguments.Brightness).value) }
            registry.scroll -> true.also {
                (arguments as AppCommandArguments.Scroll).let { capabilities.scroll(it.direction, it.amount) }
            }
            registry.openUrl -> true.also { capabilities.openUrl((arguments as AppCommandArguments.Url).url) }
            registry.enterText -> true.also { capabilities.enterText((arguments as AppCommandArguments.Text).text) }
            else -> registry.layoutName(definition)?.let(capabilities::loadLayout) ?: false
        }
    }
}
