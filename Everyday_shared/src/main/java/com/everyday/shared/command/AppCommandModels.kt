package com.everyday.shared.command

import org.json.JSONObject

enum class CommandScrollDirection { UP, DOWN }

sealed class AppCommandArguments {
    object None : AppCommandArguments()

    data class Widget(val widgetId: String) : AppCommandArguments() /** widgetId is a widget type's id, such as "map". */
    data class Layout(val layoutId: String) : AppCommandArguments()
    data class Brightness(val value: Float) : AppCommandArguments()
    data class Scroll(val direction: CommandScrollDirection, val amount: Float) : AppCommandArguments()
    data class Url(val url: String) : AppCommandArguments()
    data class Text(val text: String) : AppCommandArguments()
}

data class AppCommandInvocation(
    val commandId: String,
    val arguments: AppCommandArguments = AppCommandArguments.None
)

/** Safe JSON representation ready to be embedded in the typed transport later. */
object AppCommandProtocol {
    fun encode(invocation: AppCommandInvocation): JSONObject = JSONObject()
        .put("commandId", invocation.commandId)
        .put("arguments", invocation.arguments.toJson())

    fun decode(root: JSONObject): AppCommandInvocation? = runCatching {
        val commandId = root.getString("commandId").takeIf { it.isNotBlank() } ?: return null
        AppCommandInvocation(commandId, root.getJSONObject("arguments").toArguments())
    }.getOrNull()

    private fun AppCommandArguments.toJson(): JSONObject = when (this) {
        AppCommandArguments.None -> JSONObject().put("type", "none")
        is AppCommandArguments.Widget -> JSONObject().put("type", "widget").put("widgetId", widgetId)
        is AppCommandArguments.Layout -> JSONObject().put("type", "layout").put("layoutId", layoutId)
        is AppCommandArguments.Brightness -> JSONObject().put("type", "brightness").put("value", value.toDouble())
        is AppCommandArguments.Scroll -> JSONObject().put("type", "scroll")
            .put("direction", direction.name).put("amount", amount.toDouble())
        is AppCommandArguments.Url -> JSONObject().put("type", "url").put("url", url)
        is AppCommandArguments.Text -> JSONObject().put("type", "text").put("text", text)
    }

    private fun JSONObject.toArguments(): AppCommandArguments = when (getString("type")) {
        "none" -> AppCommandArguments.None
        "widget" -> AppCommandArguments.Widget(getString("widgetId"))
        "layout" -> AppCommandArguments.Layout(getString("layoutId"))
        "brightness" -> AppCommandArguments.Brightness(getDouble("value").toFloat())
        "scroll" -> AppCommandArguments.Scroll(
            CommandScrollDirection.valueOf(getString("direction")), getDouble("amount").toFloat()
        )
        "url" -> AppCommandArguments.Url(getString("url"))
        "text" -> AppCommandArguments.Text(getString("text"))
        else -> error("Unknown command argument type")
    }
}
