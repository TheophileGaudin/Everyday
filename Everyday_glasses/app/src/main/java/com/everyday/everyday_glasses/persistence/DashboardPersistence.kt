package com.everyday.everyday_glasses.persistence

import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import android.util.Log
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts
import com.everyday.everyday_glasses.widgets.file_browser.ImageWidgetStorage
import org.json.JSONObject
import java.io.File
import kotlin.collections.forEach

/**
 * Defines saveable dashboard/widget descriptions and stores them in durable app storage.
 *
 * This object owns JSON encoding/decoding, active-dashboard and named-layout files, and storage
 * failures. It does not inspect, construct, register or destroy live widget instances.
 *
 * App-specific external storage does not survive uninstall.
 */
object DashboardPersistence {
    private const val TAG = "DashboardPersistence"
    private const val FILENAME = "everyday_active_dashboard.json"
    private const val LAYOUTS_FILENAME = "everyday_layouts.json"
    const val DEFAULT_LAYOUT_NAME = BuiltInLayouts.STANDARD

    // Avoid spamming logcat if something goes wrong during load.
    @Volatile private var hasLoggedLoadError = false
    @Volatile private var hasLoggedSaveError = false

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

    // ==================== Widget state DTOs ====================

    data class TextWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val text: String,
        val html: String? = null,  // Rich text content as HTML (takes precedence if present)
        val fontSize: Float = 28f,
        val isTextWrap: Boolean = true,
        val columnCount: Int = 1,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class ImageWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val imagePath: String,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class BrowserWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val url: String,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class VideoWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val uri: String,
        val displayName: String,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false,
    ) : SizedWidgetFields

    data class PdfWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val uri: String,
        val displayName: String,
        val page: Int = 0,
        val zoom: Float = 1f,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false,
    ) : SizedWidgetFields

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

    /**
     * Encodes one state object using the established v1 field representation.
     *
     * The v2 envelope keeps these payloads deliberately unchanged.  Keeping the
     * field-level codecs here means both schemas share one authoritative parser
     * while the runtime migration is in progress.
     */
    internal fun encodeWidgetStatePayload(type: WidgetTypeId, state: Any): JSONObject = when (type) {
        WidgetTypeId.TEXT -> textWidgetToJson(state as TextWidgetState)
        WidgetTypeId.BROWSER -> browserWidgetToJson(state as BrowserWidgetState)
        WidgetTypeId.IMAGE -> (state as ImageWidgetState).let { widget -> JSONObject().apply {
            putCommonFields(widget)
            put("imagePath", widget.imagePath)
        } }
        WidgetTypeId.VIDEO -> (state as VideoWidgetState).let { widget -> JSONObject().apply {
            putCommonFields(widget)
            put("uri", widget.uri)
            put("displayName", widget.displayName)
        } }
        WidgetTypeId.PDF -> (state as PdfWidgetState).let { widget -> JSONObject().apply {
            putCommonFields(widget)
            put("uri", widget.uri)
            put("displayName", widget.displayName)
            put("page", widget.page)
            put("zoom", widget.zoom)
        } }
        // The single-instance types save through their own codec, in their widget package.
        // File browser state is not saved.
        else -> error("${type.persistedId} has no persisted state codec here")
    }

    /** Decodes one v2 payload through the same field-level parser as v1. */
    internal fun decodeWidgetStatePayload(type: WidgetTypeId, payload: JSONObject): Any? = when (type) {
        WidgetTypeId.TEXT -> parseTextWidget(payload)
        WidgetTypeId.BROWSER -> parseBrowserWidget(payload)
        WidgetTypeId.IMAGE -> parseImageWidget(payload)
        WidgetTypeId.VIDEO -> parseVideoWidget(payload)
        WidgetTypeId.PDF -> parsePdfWidget(payload)
        else -> null
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
                Log.w(TAG, "Ignoring widget document written by an older schema; starting from defaults")
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
            (WidgetDocument.codecs[WidgetTypeId.IMAGE]?.decode(record.state)
                as? ImageWidgetState)
                ?.imagePath
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
            val existingIndex = existing.indexOfFirst { it.name.equals(trimmedName, ignoreCase = true) }
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

    private fun textWidgetToJson(widget: TextWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(widget)
            put("text", widget.text)
            widget.html?.let { put("html", it) }
            put("fontSize", widget.fontSize.toDouble())
            put("isTextWrap", widget.isTextWrap)
            put("columnCount", widget.columnCount)
        }

    private fun browserWidgetToJson(widget: BrowserWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(widget)
            put("url", widget.url)
        }

    private fun parseTextWidget(obj: JSONObject): TextWidgetState? {
        val html: String? =
            if (obj.has("html") && !obj.isNull("html")) obj.optString("html") else null

        return try {
            val c = parseCommonFields(obj)
            TextWidgetState(
                x = c.x, y = c.y,
                width = obj.getDouble("width").toFloat(),
                height = obj.getDouble("height").toFloat(),
                text = obj.optString("text", ""),
                html = html,
                fontSize = obj.optDouble("fontSize", 28.0).toFloat(),
                isTextWrap = obj.optBoolean("isTextWrap", true),
                columnCount = obj.optInt("columnCount", 1).coerceIn(1, 3),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBrowserWidget(obj: JSONObject): BrowserWidgetState? {
        return try {
            val c = parseCommonFields(obj)
            BrowserWidgetState(
                x = c.x, y = c.y,
                width = obj.getDouble("width").toFloat(),
                height = obj.getDouble("height").toFloat(),
                url = obj.optString("url", "https://www.google.com"),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        } catch (_: Exception) {
            null
        }
    }

   private fun parseImageWidget(obj: JSONObject): ImageWidgetState? = runCatching {
        val c = parseCommonFields(obj)
        val imagePath = obj.optString("imagePath", "")
        if (imagePath.isBlank()) return null

        ImageWidgetState(
            x = c.x, y = c.y,
            width = obj.getDouble("width").toFloat(),
            height = obj.getDouble("height").toFloat(),
            imagePath = imagePath,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }.getOrNull()

    private fun parseVideoWidget(obj: JSONObject): VideoWidgetState? = runCatching {
        val c = parseCommonFields(obj)
        val uri = obj.optString("uri", "")
        if (uri.isBlank()) return null
        VideoWidgetState(
            x = c.x,
            y = c.y,
            width = obj.getDouble("width").toFloat(),
            height = obj.getDouble("height").toFloat(),
            uri = uri,
            displayName = obj.optString("displayName", "Video"),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX,
            savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth,
            savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned,
        )
    }.getOrNull()

    private fun parsePdfWidget(obj: JSONObject): PdfWidgetState? = runCatching {
        val c = parseCommonFields(obj)
        val uri = obj.optString("uri", "")
        if (uri.isBlank()) return null
        PdfWidgetState(
            x = c.x,
            y = c.y,
            width = obj.getDouble("width").toFloat(),
            height = obj.getDouble("height").toFloat(),
            uri = uri,
            displayName = obj.optString("displayName", "PDF"),
            page = obj.optInt("page", 0).coerceAtLeast(0),
            zoom = obj.optDouble("zoom", 1.0).toFloat(),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX,
            savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth,
            savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned,
        )
    }.getOrNull()
}
