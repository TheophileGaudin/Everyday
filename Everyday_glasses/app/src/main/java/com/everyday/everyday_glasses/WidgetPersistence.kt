package com.everyday.everyday_glasses

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Handles persistence of widget state.
 *
 * Uses app-specific storage (no special permissions required):
 * - context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) if available
 * - falls back to context.filesDir
 *
 * Note: app-specific external storage does NOT survive uninstall (unlike public Documents).
 */
object WidgetPersistence {
    private const val TAG = "WidgetPersistence"
    private const val FILENAME = "everyday_widgets.json"

    // JSON keys
    private const val KEY_TEXT_WIDGETS = "text_widgets"
    private const val KEY_BROWSER_WIDGETS = "browser_widgets"
    private const val KEY_IMAGE_WIDGETS = "image_widgets"
    private const val KEY_STATUS_BAR = "status_bar"
    private const val KEY_LOCATION_WIDGET = "location_widget"
    private const val KEY_CALENDAR_WIDGET = "calendar_widget"
    private const val KEY_MIRROR_WIDGET = "mirror_widget"
    private const val KEY_FINANCE_WIDGET = "finance_widget"
    private const val KEY_NEWS_WIDGET = "news_widget"
    private const val KEY_SPEEDOMETER_WIDGET = "speedometer_widget"
    private const val KEY_CLOSED_TEMPLATES = "closed_templates"
    private const val KEY_CLOSED_TEXT_WIDGET = "text_widget"
    private const val KEY_CLOSED_BROWSER_WIDGET = "browser_widget"
    private const val KEY_CLOSED_STATUS_BAR = "status_bar"
    private const val KEY_CLOSED_LOCATION_WIDGET = "location_widget"
    private const val KEY_CLOSED_CALENDAR_WIDGET = "calendar_widget"
    private const val KEY_CLOSED_MIRROR_WIDGET = "mirror_widget"
    private const val KEY_CLOSED_FINANCE_WIDGET = "finance_widget"
    private const val KEY_CLOSED_NEWS_WIDGET = "news_widget"
    private const val KEY_CLOSED_SPEEDOMETER_WIDGET = "speedometer_widget"

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
            put("width", (state as SizedWidgetFields).width.toDouble())
            put("height", (state as SizedWidgetFields).height.toDouble())
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

    data class PersistedState(
        val text: List<TextWidgetState>,
        val browser: List<BrowserWidgetState>,
        val image: List<ImageWidgetState>,
        val status: StatusBarState?,
        val location: LocationWidgetState?,
        val calendar: CalendarWidgetState?,
        val finance: FinanceWidgetState?,
        val news: NewsWidgetState?,
        val speedometer: SpeedometerWidgetState?,
        val mirror: MirrorWidgetState?,
        val closedTemplates: ClosedWidgetTemplates = ClosedWidgetTemplates(),
        val isFirstRun: Boolean = true  // True if no saved state exists (create default widgets)
    )

    data class ClosedWidgetTemplates(
        val text: TextWidgetState? = null,
        val browser: BrowserWidgetState? = null,
        val status: StatusBarState? = null,
        val location: LocationWidgetState? = null,
        val calendar: CalendarWidgetState? = null,
        val finance: FinanceWidgetState? = null,
        val news: NewsWidgetState? = null,
        val speedometer: SpeedometerWidgetState? = null,
        val mirror: MirrorWidgetState? = null
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

    data class StatusBarState(
        override val x: Float,
        override val y: Float,
        val showTime: Boolean = true,
        val showDate: Boolean = true,
        val showPhoneBattery: Boolean = true,
        val showGlassesBattery: Boolean = true,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : CommonWidgetFields

    data class LocationWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val showWeather: Boolean = true,
        val showTemperature: Boolean = true,
        val showLocation: Boolean = true,
        val showCountry: Boolean = true,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class MirrorWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class CalendarWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val fontScale: Float = 1f,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class FinanceWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val selectedSymbol: String = "^GSPC",
        val selectedRange: String = "1D",
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class NewsWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val countryCode: String = "US",
        val selectedIndex: Int = 0,
        val fontScale: Float = 1f,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class SpeedometerWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    private fun getStorageFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val file = File(dir, FILENAME)
        file.parentFile?.mkdirs()
        return file
    }

    /**
     * Save all widget states to file.
     */
    fun saveWidgets(
        context: Context,
        textWidgets: List<TextWidgetState>,
        browserWidgets: List<BrowserWidgetState>,
        imageWidgets: List<ImageWidgetState>,
        statusBarState: StatusBarState?,
        locationWidgetState: LocationWidgetState? = null,
        calendarWidgetState: CalendarWidgetState? = null,
        mirrorWidgetState: MirrorWidgetState? = null,
        financeWidgetState: FinanceWidgetState? = null,
        newsWidgetState: NewsWidgetState? = null,
        speedometerWidgetState: SpeedometerWidgetState? = null,
        closedTemplates: ClosedWidgetTemplates = ClosedWidgetTemplates()
    ): Boolean {
        try {
            val rootJson = JSONObject()

            // Text widgets
            val widgetsArray = JSONArray()
            for (widget in textWidgets) {
                val obj = JSONObject().apply {
                    putCommonFields(widget)
                    put("text", widget.text)
                    widget.html?.let { put("html", it) }
                    put("fontSize", widget.fontSize.toDouble())
                    put("isTextWrap", widget.isTextWrap)
                    put("columnCount", widget.columnCount)
                }
                widgetsArray.put(obj)
            }
            rootJson.put(KEY_TEXT_WIDGETS, widgetsArray)

            // Browser widgets
            val browserArray = JSONArray()
            for (widget in browserWidgets) {
                val obj = JSONObject().apply {
                    putCommonFields(widget)
                    put("url", widget.url)
                }
                browserArray.put(obj)
            }
            rootJson.put(KEY_BROWSER_WIDGETS, browserArray)

            // Image widgets
            val imageArray = JSONArray()
            for (widget in imageWidgets) {
                val obj = JSONObject().apply {
                    putCommonFields(widget)
                    put("imagePath", widget.imagePath)
                }
                imageArray.put(obj)
            }
            rootJson.put(KEY_IMAGE_WIDGETS, imageArray)

            // Status bar (no width/height)
            statusBarState?.let { s ->
                rootJson.put(KEY_STATUS_BAR, JSONObject().apply {
                    putCommonFields(s, includeSize = false)
                    put("showTime", s.showTime)
                    put("showDate", s.showDate)
                    put("showPhoneBattery", s.showPhoneBattery)
                    put("showGlassesBattery", s.showGlassesBattery)
                })
            }

            // Location widget
            locationWidgetState?.let { l ->
                rootJson.put(KEY_LOCATION_WIDGET, JSONObject().apply {
                    putCommonFields(l)
                    put("showWeather", l.showWeather)
                    put("showTemperature", l.showTemperature)
                    put("showLocation", l.showLocation)
                    put("showCountry", l.showCountry)
                })
            }

            // Calendar widget
            calendarWidgetState?.let { c ->
                rootJson.put(KEY_CALENDAR_WIDGET, JSONObject().apply {
                    putCommonFields(c)
                    put("fontScale", c.fontScale)
                })
            }

            // Mirror widget
            mirrorWidgetState?.let { m ->
                rootJson.put(KEY_MIRROR_WIDGET, JSONObject().apply { putCommonFields(m) })
            }

            // Finance widget
            financeWidgetState?.let { f ->
                rootJson.put(KEY_FINANCE_WIDGET, JSONObject().apply {
                    putCommonFields(f)
                    put("selectedSymbol", f.selectedSymbol)
                    put("selectedRange", f.selectedRange)
                })
            }

            // News widget
            newsWidgetState?.let { n ->
                rootJson.put(KEY_NEWS_WIDGET, JSONObject().apply {
                    putCommonFields(n)
                    put("countryCode", n.countryCode)
                    put("selectedIndex", n.selectedIndex)
                    put("fontScale", n.fontScale)
                })
            }

            // Speedometer widget
            speedometerWidgetState?.let { s ->
                rootJson.put(KEY_SPEEDOMETER_WIDGET, JSONObject().apply { putCommonFields(s) })
            }

            val closedTemplatesJson = JSONObject()
            closedTemplates.text?.let { closedTemplatesJson.put(KEY_CLOSED_TEXT_WIDGET, textWidgetToJson(it)) }
            closedTemplates.browser?.let { closedTemplatesJson.put(KEY_CLOSED_BROWSER_WIDGET, browserWidgetToJson(it)) }
            closedTemplates.status?.let { closedTemplatesJson.put(KEY_CLOSED_STATUS_BAR, statusBarToJson(it)) }
            closedTemplates.location?.let { closedTemplatesJson.put(KEY_CLOSED_LOCATION_WIDGET, locationWidgetToJson(it)) }
            closedTemplates.calendar?.let { closedTemplatesJson.put(KEY_CLOSED_CALENDAR_WIDGET, calendarWidgetToJson(it)) }
            closedTemplates.mirror?.let { closedTemplatesJson.put(KEY_CLOSED_MIRROR_WIDGET, mirrorWidgetToJson(it)) }
            closedTemplates.finance?.let { closedTemplatesJson.put(KEY_CLOSED_FINANCE_WIDGET, financeWidgetToJson(it)) }
            closedTemplates.news?.let { closedTemplatesJson.put(KEY_CLOSED_NEWS_WIDGET, newsWidgetToJson(it)) }
            closedTemplates.speedometer?.let { closedTemplatesJson.put(KEY_CLOSED_SPEEDOMETER_WIDGET, speedometerWidgetToJson(it)) }
            if (closedTemplatesJson.length() > 0) {
                rootJson.put(KEY_CLOSED_TEMPLATES, closedTemplatesJson)
            }

            val jsonText = rootJson.toString(2)
            val file = getStorageFile(context)
            file.writeText(jsonText)

            // Optional: reset spam guards on success
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
     * Public API: load everything in one pass (single file read).
     * Returns isFirstRun=true if no save file exists (fresh install),
     * isFirstRun=false if a save file was loaded (returning user).
     */
    fun loadAll(context: Context): PersistedState {
        val rootJson = loadRootJson(context)
            ?: return PersistedState(
                emptyList(),
                emptyList(),
                emptyList(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                closedTemplates = ClosedWidgetTemplates(),
                isFirstRun = true
            )

        return PersistedState(
            text = parseTextWidgets(rootJson),
            browser = parseBrowserWidgets(rootJson),
            image = parseImageWidgets(rootJson),
            status = parseStatusBar(rootJson),
            location = parseLocation(rootJson),
            calendar = parseCalendar(rootJson),
            finance = parseFinance(rootJson),
            news = parseNews(rootJson),
            speedometer = parseSpeedometer(rootJson),
            mirror = parseMirror(rootJson),
            closedTemplates = parseClosedTemplates(rootJson),
            isFirstRun = false  // File exists, this is a returning user
        )
    }

    /**
     * Clear all saved widget data.
     */
    fun clearAll(context: Context) {
        try {
            val file = getStorageFile(context)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Cleared saved widget data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing widget data", e)
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

    private fun statusBarToJson(state: StatusBarState): JSONObject =
        JSONObject().apply {
            putCommonFields(state, includeSize = false)
            put("showTime", state.showTime)
            put("showDate", state.showDate)
            put("showPhoneBattery", state.showPhoneBattery)
            put("showGlassesBattery", state.showGlassesBattery)
        }

    private fun locationWidgetToJson(state: LocationWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("showWeather", state.showWeather)
            put("showTemperature", state.showTemperature)
            put("showLocation", state.showLocation)
            put("showCountry", state.showCountry)
        }

    private fun calendarWidgetToJson(state: CalendarWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("fontScale", state.fontScale)
        }

    private fun mirrorWidgetToJson(state: MirrorWidgetState): JSONObject =
        JSONObject().apply { putCommonFields(state) }

    private fun financeWidgetToJson(state: FinanceWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("selectedSymbol", state.selectedSymbol)
            put("selectedRange", state.selectedRange)
        }

    private fun newsWidgetToJson(state: NewsWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("countryCode", state.countryCode)
            put("selectedIndex", state.selectedIndex)
            put("fontScale", state.fontScale)
        }

    private fun speedometerWidgetToJson(state: SpeedometerWidgetState): JSONObject =
        JSONObject().apply { putCommonFields(state) }

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

    private fun parseStatusBarObject(obj: JSONObject): StatusBarState? {
        return try {
            val c = parseCommonFields(obj)
            if (c.x < 0 || c.y < 0) return null

            StatusBarState(
                x = c.x, y = c.y,
                showTime = obj.optBoolean("showTime", true),
                showDate = obj.optBoolean("showDate", true),
                showPhoneBattery = obj.optBoolean("showPhoneBattery", true),
                showGlassesBattery = obj.optBoolean("showGlassesBattery", true),
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

    private inline fun <T> parseSizedWidgetObject(
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

    private fun parseClosedTemplates(rootJson: JSONObject): ClosedWidgetTemplates {
        val obj = rootJson.optJSONObject(KEY_CLOSED_TEMPLATES) ?: return ClosedWidgetTemplates()

        return ClosedWidgetTemplates(
            text = obj.optJSONObject(KEY_CLOSED_TEXT_WIDGET)?.let(::parseTextWidget),
            browser = obj.optJSONObject(KEY_CLOSED_BROWSER_WIDGET)?.let(::parseBrowserWidget),
            status = obj.optJSONObject(KEY_CLOSED_STATUS_BAR)?.let(::parseStatusBarObject),
            location = obj.optJSONObject(KEY_CLOSED_LOCATION_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    LocationWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        showWeather = item.optBoolean("showWeather", true),
                        showTemperature = item.optBoolean("showTemperature", true),
                        showLocation = item.optBoolean("showLocation", true),
                        showCountry = item.optBoolean("showCountry", true),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            calendar = obj.optJSONObject(KEY_CLOSED_CALENDAR_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    CalendarWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        fontScale = item.optDouble("fontScale", 1.0).toFloat(),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            finance = obj.optJSONObject(KEY_CLOSED_FINANCE_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    FinanceWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        selectedSymbol = item.optString("selectedSymbol", "^GSPC"),
                        selectedRange = item.optString("selectedRange", "1D"),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            news = obj.optJSONObject(KEY_CLOSED_NEWS_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    NewsWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        countryCode = item.optString("countryCode", "US"),
                        selectedIndex = item.optInt("selectedIndex", 0),
                        fontScale = item.optDouble("fontScale", 1.0).toFloat(),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            speedometer = obj.optJSONObject(KEY_CLOSED_SPEEDOMETER_WIDGET)?.let {
                parseSizedWidgetObject(it) { _, c, w, h ->
                    SpeedometerWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            mirror = obj.optJSONObject(KEY_CLOSED_MIRROR_WIDGET)?.let {
                parseSizedWidgetObject(it) { _, c, w, h ->
                    MirrorWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            }
        )
    }

    private fun parseTextWidgets(rootJson: JSONObject): List<TextWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_TEXT_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<TextWidgetState>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            parseTextWidget(obj)?.let(widgets::add)
        }

        return widgets
    }

    private fun parseImageWidgets(rootJson: JSONObject): List<ImageWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_IMAGE_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<ImageWidgetState>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            try {
                val c = parseCommonFields(obj)
                val imagePath = obj.optString("imagePath", "")
                if (imagePath.isBlank()) continue

                widgets.add(
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
                )
            } catch (_: Exception) {
                // ignore bad entry
            }
        }

        return widgets
    }

    private fun parseBrowserWidgets(rootJson: JSONObject): List<BrowserWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_BROWSER_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<BrowserWidgetState>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            parseBrowserWidget(obj)?.let(widgets::add)
        }

        return widgets
    }

    private fun parseStatusBar(rootJson: JSONObject): StatusBarState? {
        val statusBarJson = rootJson.optJSONObject(KEY_STATUS_BAR) ?: return null
        return parseStatusBarObject(statusBarJson)
    }

    /** Parse a sized widget (has x/y/width/height + common state fields). */
    private inline fun <T> parseSizedWidget(
        rootJson: JSONObject,
        key: String,
        crossinline builder: (obj: JSONObject, c: ParsedCommonFields, width: Float, height: Float) -> T
    ): T? {
        val obj = rootJson.optJSONObject(key) ?: return null
        return parseSizedWidgetObject(obj, builder)
    }

    private fun parseLocation(rootJson: JSONObject): LocationWidgetState? =
        parseSizedWidget(rootJson, KEY_LOCATION_WIDGET) { obj, c, w, h ->
            LocationWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                showWeather = obj.optBoolean("showWeather", true),
                showTemperature = obj.optBoolean("showTemperature", true),
                showLocation = obj.optBoolean("showLocation", true),
                showCountry = obj.optBoolean("showCountry", true),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseCalendar(rootJson: JSONObject): CalendarWidgetState? =
        parseSizedWidget(rootJson, KEY_CALENDAR_WIDGET) { obj, c, w, h ->
            CalendarWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                fontScale = obj.optDouble("fontScale", 1.0).toFloat(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseMirror(rootJson: JSONObject): MirrorWidgetState? =
        parseSizedWidget(rootJson, KEY_MIRROR_WIDGET) { _, c, w, h ->
            MirrorWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseFinance(rootJson: JSONObject): FinanceWidgetState? =
        parseSizedWidget(rootJson, KEY_FINANCE_WIDGET) { obj, c, w, h ->
            FinanceWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                selectedSymbol = obj.optString("selectedSymbol", "^GSPC"),
                selectedRange = obj.optString("selectedRange", "1D"),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseNews(rootJson: JSONObject): NewsWidgetState? =
        parseSizedWidget(rootJson, KEY_NEWS_WIDGET) { obj, c, w, h ->
            NewsWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                countryCode = obj.optString("countryCode", "US"),
                selectedIndex = obj.optInt("selectedIndex", 0),
                fontScale = obj.optDouble("fontScale", 1.0).toFloat(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseSpeedometer(rootJson: JSONObject): SpeedometerWidgetState? =
        parseSizedWidget(rootJson, KEY_SPEEDOMETER_WIDGET) { _, c, w, h ->
            SpeedometerWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
}
