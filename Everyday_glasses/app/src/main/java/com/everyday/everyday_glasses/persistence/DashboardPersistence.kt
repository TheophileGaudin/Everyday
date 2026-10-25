package com.everyday.everyday_glasses.persistence

import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import android.util.Log
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts
import com.everyday.everyday_glasses.widgets.file_browser.ImageWidgetStorage
import com.everyday.everyday_glasses.widgets.file_browser.ImageWidgetState
import org.json.JSONObject
import java.io.File
import kotlin.collections.forEach

/**
 * Stores the active dashboard and the named layouts in durable app storage, and handles storage
 * failures. It also holds the fields every widget saves ([CommonWidgetFields]) and their JSON;
 * each widget type's own fields and JSON live in its widget package, next to its plugin.
 *
 * It does not inspect, construct, register or destroy live widget instances.
 *
 * App-specific external storage does not survive uninstall.
 */
object DashboardPersistence {
    private const val TAG = "DashboardPersistence"
    private const val FILENAME = "everyday_active_dashboard.json"
    private const val LAYOUTS_FILENAME = "everyday_layouts.json"
    const val DEFAULT_LAYOUT_NAME = BuiltInLayouts.STANDARD

    // Avoid spamming logcat if something goes wrong during load.
    @Volatile
    private var hasLoggedLoadError = false
    @Volatile
    private var hasLoggedSaveError = false

    // ==================== Common-field interfaces ====================

    /** Fields shared by every persisted widget state (position + minimize/pin). */
    interface CommonWidgetFields {
        val x: Float
        val y: Float
        val isMinimized: Boolean
        val isFullscreen: Boolean
        val savedMinX: Float
        val savedMinY: Float
        val savedMinWidth: Float
        val savedMinHeight: Float
        val isPinned: Boolean
    }

    /** CommonWidgetFields plus explicit width/height (everything except StatusBar). */
    interface SizedWidgetFields : CommonWidgetFields {
        val width: Float
        val height: Float
    }

    // ==================== Common-field mappers ====================

    /** Write the common fields shared by every widget to a JSONObject. */
    fun JSONObject.putCommonFields(state: CommonWidgetFields, includeSize: Boolean = true) {
        put("x", state.x.toDouble())
        put("y", state.y.toDouble())
        if (includeSize && state is SizedWidgetFields) {
            put("width", state.width.toDouble())
            put("height", state.height.toDouble())
        }
        put("isMinimized", state.isMinimized)
        put("isFullscreen", state.isFullscreen)
        if (state.isMinimized) {
            put("savedMinX", state.savedMinX.toDouble())
            put("savedMinY", state.savedMinY.toDouble())
            put("savedMinWidth", state.savedMinWidth.toDouble())
            put("savedMinHeight", state.savedMinHeight.toDouble())
        }
        put("isPinned", state.isPinned)
    }

    /** Parsed common fields returned by [parseCommonFields]. */
    data class ParsedCommonFields(
        val x: Float,
        val y: Float,
        val isMinimized: Boolean,
        val isFullscreen: Boolean,
        val savedMinX: Float,
        val savedMinY: Float,
        val savedMinWidth: Float,
        val savedMinHeight: Float,
        val isPinned: Boolean
    )

    /**
     * Read the common fields and the size, then the type's own fields through [builder].
     * Null when a field is missing, or when the saved position is negative or the size empty.
     */
    inline fun <T> parseSizedWidgetObject(
        obj: JSONObject,
        crossinline builder: (obj: JSONObject, c: ParsedCommonFields, width: Float, height: Float) -> T
    ): T? {
        return try {
            val c = parseCommonFields(obj)
            val width = obj.getDouble("width").toFloat()
            val height = obj.getDouble("height").toFloat()

            if (c.x < 0 || c.y < 0 || width <= 0 || height <= 0) return null

            builder(obj, c, width, height)
        } catch (_: Exception) {
            null
        }
    }

    /** Read the common fields shared by every widget from a JSONObject. */
    fun parseCommonFields(obj: JSONObject): ParsedCommonFields {
        return ParsedCommonFields(
            x = obj.getDouble("x").toFloat(),
            y = obj.getDouble("y").toFloat(),
            isMinimized = obj.optBoolean("isMinimized", false),
            isFullscreen = obj.optBoolean("isFullscreen", false),
            savedMinX = obj.optDouble("savedMinX", 0.0).toFloat(),
            savedMinY = obj.optDouble("savedMinY", 0.0).toFloat(),
            savedMinWidth = obj.optDouble("savedMinWidth", 0.0).toFloat(),
            savedMinHeight = obj.optDouble("savedMinHeight", 0.0).toFloat(),
            isPinned = obj.optBoolean("isPinned", false)
        )
    }

    // ==================== Persisted state ====================

    data class HoverControlPlacementState(
        val controlId: String,
        val col: Int,
        val row: Int
    )

    private fun getStorageFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val file = File(dir, FILENAME)
        file.parentFile?.mkdirs()
        return file
    }

    private fun getLayoutsStorageFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val file = File(dir, LAYOUTS_FILENAME)
        file.parentFile?.mkdirs()
        return file
    }

    fun saveDashboard(context: Context, dashboard: WidgetDocument.PersistedDashboard): Boolean {
        try {
            writeTextAtomic(
                getStorageFile(context),
                WidgetDocument.dashboardToJson(dashboard.copy(isFirstRun = false)).toString(2)
            )
            hasLoggedSaveError = false
            return true
        } catch (e: Exception) {
            if (!hasLoggedSaveError) {
                hasLoggedSaveError = true
                Log.e(TAG, "Error saving widgets", e)
            }
            return false
        }
    }

    /**
     * Loads everything in one pass (single file read).
     *
     * Returns isFirstRun=true when there is nothing usable to restore, which makes
     * the app build its default layout. That covers a fresh install and also a save
     * file this version cannot read — documents older than the current schema are not
     * migrated, they are ignored. The file itself is left untouched, so an unreadable
     * document is only replaced when the user saves a layout of their own.
     */
    fun loadDashboard(context: Context): WidgetDocument.PersistedDashboard {
        val rootJson = loadRootJson(context)
        if (rootJson == null || !WidgetDocument.isCurrentSchema(rootJson)) {
            if (rootJson != null) {
                Log.w(
                    TAG,
                    "Ignoring widget document written by an older schema; starting from defaults"
                )
            }
            return WidgetDocument.PersistedDashboard(
                widgets = emptyList(),
                isFirstRun = true,
                activeLayoutName = DEFAULT_LAYOUT_NAME
            )
        }
        return WidgetDocument.dashboardFromJson(rootJson).copy(isFirstRun = false)
    }

    /**
     * Deletes image files no stored dashboard still references.
     *
     * Images are the only widget payload kept outside the documents, so they are the
     * only thing that can be orphaned. Reachability spans everything stored — the
     * active dashboard and every named layout — which is why this lives here rather
     * than with either caller.
     *
     * Called at startup and after deleting a layout: the two moments a reference can
     * disappear. It is deliberately not run on every dashboard save, which would scan
     * storage every time the user finishes dragging a widget.
     */
    fun pruneUnreferencedImages(context: Context) {
        val active = loadDashboard(context)
        if (active.isFirstRun) {
            // Nothing readable was stored, so reachability cannot be established. An
            // ignored document still names the images it used, and unlike widget
            // geometry those cannot be recreated by saving a new layout, so leave them.
            Log.d(TAG, "Skipping image prune: no readable active dashboard")
            return
        }
        val referenced = buildList {
            addAll(imagePaths(active))
            listDashboardLayouts(context).forEach { addAll(imagePaths(it.dashboard)) }
        }
        ImageWidgetStorage.pruneUnreferenced(context, referenced)
    }

    private fun imagePaths(dashboard: WidgetDocument.PersistedDashboard): List<String> =
        dashboard.widgets.mapNotNull { record ->
            if (record.type != WidgetTypeId.IMAGE) return@mapNotNull null
            ImageWidgetState.decode(record.state)?.imagePath
        }

    fun listDashboardLayouts(context: Context): List<WidgetDocument.PersistedDashboardLayoutRecord> {
        val file = getLayoutsStorageFile(context)
        if (!file.exists()) return emptyList()

        return try {
            val jsonText = file.readText()
            if (jsonText.isBlank()) return emptyList()
            val rootJson = JSONObject(jsonText)
            if (!WidgetDocument.isCurrentSchema(rootJson)) {
                // Same rule as the active dashboard: unreadable layouts are ignored
                // rather than migrated, and the file is left for the user to overwrite.
                Log.w(TAG, "Ignoring layouts written by an older schema")
                return emptyList()
            }
            WidgetDocument.dashboardLayoutRecordsFromJson(rootJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading widget layouts", e)
            emptyList()
        }
    }

    fun saveDashboardLayout(
        context: Context,
        name: String,
        dashboard: WidgetDocument.PersistedDashboard
    ): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || isDefaultLayoutName(trimmedName)) return false

        return try {
            val now = System.currentTimeMillis()
            val existing = listDashboardLayouts(context).toMutableList()
            val existingIndex =
                existing.indexOfFirst { it.name.equals(trimmedName, ignoreCase = true) }
            val createdAt = existing.getOrNull(existingIndex)?.createdAt ?: now
            val record = WidgetDocument.PersistedDashboardLayoutRecord(
                name = trimmedName,
                createdAt = createdAt,
                updatedAt = now,
                dashboard = dashboard.copy(isFirstRun = false, activeLayoutName = trimmedName)
            )

            if (existingIndex >= 0) {
                existing[existingIndex] = record
            } else {
                existing.add(record)
            }

            writeDashboardLayoutRecords(context, existing.sortedBy { it.createdAt })
        } catch (e: Exception) {
            Log.e(TAG, "Error saving widget layout '$trimmedName'", e)
            false
        }
    }

    fun loadDashboardLayout(context: Context, name: String): WidgetDocument.PersistedDashboard? {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return null
        return listDashboardLayouts(context)
            .firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
            ?.dashboard
            ?.copy(isFirstRun = false, activeLayoutName = trimmedName)
    }

    fun deleteLayout(context: Context, name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || isDefaultLayoutName(trimmedName)) return false

        return try {
            val remaining = listDashboardLayouts(context)
                .filterNot { it.name.equals(trimmedName, ignoreCase = true) }
            writeDashboardLayoutRecords(context, remaining)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting widget layout '$trimmedName'", e)
            false
        }
    }

    fun isDefaultLayoutName(name: String?): Boolean {
        val trimmed = name?.trim() ?: return false
        return trimmed.equals(DEFAULT_LAYOUT_NAME, ignoreCase = true)
    }

    private fun writeDashboardLayoutRecords(
        context: Context,
        records: List<WidgetDocument.PersistedDashboardLayoutRecord>
    ): Boolean {
        return try {
            val rootJson = WidgetDocument.dashboardLayoutRecordsToJson(records)
            writeTextAtomic(getLayoutsStorageFile(context), rootJson.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing widget layouts", e)
            false
        }
    }

    // ----------------------------
    // Internals: load + parse
    // ----------------------------

    private fun loadRootJson(context: Context): JSONObject? {
        val file = getStorageFile(context)

        if (!file.exists()) {
            return null
        }

        val jsonString = runCatching { file.readText() }.getOrElse { e ->
            if (!hasLoggedLoadError) {
                hasLoggedLoadError = true
                Log.e(TAG, "Error reading widget state from ${file.absolutePath}", e)
            }
            return null
        }

        if (jsonString.isBlank()) return null

        return runCatching { JSONObject(jsonString) }.getOrElse { e ->
            if (!hasLoggedLoadError) {
                hasLoggedLoadError = true
                Log.e(TAG, "Error parsing widget JSON", e)
            }
            null
        }
    }

    private fun writeTextAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
    }
}
