package com.everyday.everyday_glasses

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.everyday.shared.command.AppCommandInvocation

/**
 * Persists the ordered command IDs assigned to the lemon hover control.
 *
 * Storage location mirrors [WidgetPersistence]: app-specific external Documents directory
 * when available, falling back to `filesDir`.
 */
object ShortcutsStore {
    private const val TAG = "ShortcutsStore"
    private const val FILENAME = "everyday_shortcuts.json"
    private const val KEY_LEMON = "lemon"

    fun load(context: Context, registry: AppCommandRegistry): List<AppCommandInvocation> {
        val file = file(context)
        if (!file.exists()) return registry.defaultShortcutInvocations

        return try {
            val text = file.readText()
            if (text.isBlank()) return registry.defaultShortcutInvocations
            decode(text, registry)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading shortcuts", e)
            registry.defaultShortcutInvocations
        }
    }

    fun save(context: Context, registry: AppCommandRegistry, invocations: List<AppCommandInvocation>): Boolean {
        return try {
            val deduped = mutableListOf<AppCommandInvocation>()
            for (invocation in invocations) {
                val definition = registry.find(invocation.commandId) ?: continue
                if (!definition.shortcutEligible || registry.validate(invocation) !is CommandValidationResult.Valid) continue
                if (deduped.none { it.commandId == invocation.commandId }) deduped.add(invocation)
                if (deduped.size >= AppCommandRegistry.MAX_LEMON_COMMANDS) break
            }
            val target = file(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(encode(deduped))
            if (!tmp.renameTo(target)) {
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing shortcuts", e)
            false
        }
    }

    internal fun encode(invocations: List<AppCommandInvocation>): String = JSONObject().apply {
        put(KEY_LEMON, JSONArray().apply { invocations.forEach { put(it.commandId) } })
    }.toString(2)

    internal fun decode(raw: String, registry: AppCommandRegistry): List<AppCommandInvocation> {
        val array = JSONObject(raw).optJSONArray(KEY_LEMON) ?: return registry.defaultShortcutInvocations
        val result = mutableListOf<AppCommandInvocation>()
        for (i in 0 until array.length()) {
            val id = array.optString(i) ?: continue
            val definition = registry.find(id) ?: continue
            if (!definition.shortcutEligible || result.any { it.commandId == id }) continue
            result += registry.invocation(definition)
            if (result.size >= AppCommandRegistry.MAX_LEMON_COMMANDS) break
        }
        return result.ifEmpty { registry.defaultShortcutInvocations }
    }

    private fun file(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(dir, FILENAME)
    }
}
