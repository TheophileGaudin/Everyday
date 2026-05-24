package com.everyday.everyday_glasses

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the ordered list of [ShortcutAction]s assigned to the lemon hover control.
 *
 * Storage location mirrors [WidgetPersistence]: app-specific external Documents directory
 * when available, falling back to `filesDir`.
 */
object ShortcutsStore {
    private const val TAG = "ShortcutsStore"
    private const val FILENAME = "everyday_shortcuts.json"
    private const val KEY_LEMON = "lemon"

    fun load(context: Context): List<ShortcutAction> {
        val file = file(context)
        if (!file.exists()) return ShortcutAction.DEFAULT_LEMON_SHORTCUTS

        return try {
            val text = file.readText()
            if (text.isBlank()) return ShortcutAction.DEFAULT_LEMON_SHORTCUTS
            val root = JSONObject(text)
            val array = root.optJSONArray(KEY_LEMON) ?: return ShortcutAction.DEFAULT_LEMON_SHORTCUTS
            val result = mutableListOf<ShortcutAction>()
            for (i in 0 until array.length()) {
                val id = array.optString(i) ?: continue
                val action = ShortcutAction.fromId(id) ?: continue
                if (result.none { it.id == action.id }) result.add(action)
            }
            if (result.isEmpty()) ShortcutAction.DEFAULT_LEMON_SHORTCUTS else result
        } catch (e: Exception) {
            Log.e(TAG, "Error reading shortcuts", e)
            ShortcutAction.DEFAULT_LEMON_SHORTCUTS
        }
    }

    fun save(context: Context, actions: List<ShortcutAction>): Boolean {
        return try {
            val deduped = mutableListOf<ShortcutAction>()
            for (action in actions) {
                if (deduped.none { it.id == action.id }) deduped.add(action)
                if (deduped.size >= ShortcutAction.MAX_LEMON_SLICES) break
            }
            val root = JSONObject().apply {
                put(KEY_LEMON, JSONArray().apply { deduped.forEach { put(it.id) } })
            }
            val target = file(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(root.toString(2))
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

    private fun file(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(dir, FILENAME)
    }
}
