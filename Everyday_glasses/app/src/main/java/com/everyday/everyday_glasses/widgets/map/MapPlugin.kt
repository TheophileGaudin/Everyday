package com.everyday.everyday_glasses.widgets.map

import android.content.Context
import android.location.Location
import com.everyday.everyday_glasses.interaction.assistant.AssistantContribution
import com.everyday.everyday_glasses.interaction.assistant.AssistantSemanticFact
import com.everyday.everyday_glasses.interaction.assistant.AssistantSemanticToolHandler
import com.everyday.everyday_glasses.interaction.commands.AppCommandDefinition
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.widgets.map.backend.toMapSyncRequest
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.NavigationOperationResult
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetCommand
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetMenu
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.map.backend.MapAssistantActions
import com.everyday.everyday_glasses.widgets.map.backend.MapViewMode
import com.everyday.everyday_glasses.widgets.map.backend.MapZoom
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfigState
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayMode
import com.everyday.everyday_glasses.widgets.map.backend.ConfigureMapPoisSemanticToolHandler
import com.everyday.everyday_glasses.widgets.map.backend.StartNavigationSemanticToolHandler
import com.everyday.everyday_glasses.widgets.map.backend.StopNavigationSemanticToolHandler
import com.everyday.everyday_glasses.widgets.map.backend.toAssistantSemanticFacts
import org.json.JSONArray
import org.json.JSONObject

/**
 * Puts the map on the dashboard and connects it to the location sources and to the phone's
 * routing and destination search.
 */
class MapPlugin(
    private val services: WidgetContext,
) : WidgetPlugin, AssistantContribution {
    private val context: Context get() = services.androidContext
    private val current: () -> MapWidget? = { services.singleton(TYPE) as? MapWidget }
    private val requestRepaint: () -> Unit = { services.requestRepaint() }
    override val lifecycle = WidgetLifecycleRegistration.singleton(
        MapWidget::class, MapWidgetState.Codec,
        current = current,
        create = ::create,
        restore = ::restore,
        capture = ::capture,
        beforeRemove = MapWidget::release,
        onFullscreenChanged = { widget, fullscreen -> if (!fullscreen) widget.enforceWindowedMode() },
    )

    init {
        val data = services.appData
        data.subscribe(AppChannels.place) { place -> current()?.setLocationMetadata(place.town, place.countryCode) }
        data.subscribe(AppChannels.location) { location ->
            current()?.setLocation(location)
            services.notifyTransientChange()
        }
        data.subscribe(AppChannels.mapSnapshot) { snapshot ->
            current()?.applyProxyMap(snapshot)
            services.notifyTransientChange()
        }
        data.subscribe(AppChannels.phoneRoutes) { transport -> current()?.configurePhoneRouteTransport(transport) }
        data.subscribe(AppChannels.phoneConnected) { connected ->
            current()?.let { map ->
                map.onPhoneRouteConnectionChanged(connected)
                // A map created before the phone link came up asks again now that the phone is reachable.
                if (connected) map.requestPhoneMapRefresh()
            }
        }
        data.subscribe(AppChannels.routeResult) { result -> current()?.onPhoneRouteResult(result) }
        data.subscribe(AppChannels.routeError) { error -> current()?.onPhoneRouteError(error) }
        data.subscribe(AppChannels.destinationResult) { result -> current()?.onPhoneDestinationSearchResult(result) }
        data.subscribe(AppChannels.destinationError) { error -> current()?.onPhoneDestinationSearchError(error) }
    }

    /**
     * An explicit stop also ends a voice presentation of the map, without restoring an older
     * map rectangle; the current map, POI and orientation state stay visible.
     */
    fun stopNavigation() {
        services.withTransientChanges {
            current()?.let { map ->
                services.dismissPresentationOf(map)
                map.stopNavigation()
            }
            services.notifyTransientChange()
        }
    }

    private fun create(): MapWidget = build(services.viewportWidth - SIZE - 24f, 24f, SIZE, SIZE)

    /**
     * Restores the common fields, then the view preferences. The projected mode is only kept for a
     * fullscreen map, so the preferences must come after the fullscreen state.
     */
    private fun restore(state: MapWidgetState): MapWidget {
        val widget = build(state.x, state.y, state.width, state.height)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight)
        widget.restorePreferences(state.mode, state.zoom, state.showLocation, state.showCountry, state.poiConfig)
        return widget
    }

    private fun capture(widget: MapWidget): MapWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return MapWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            mode = widget.mode.name,
            zoom = widget.zoom.name,
            showLocation = widget.isLocationVisible(),
            showCountry = widget.isCountryVisible(),
            poiConfig = widget.poiDisplayConfig,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    private fun build(x: Float, y: Float, width: Float, height: Float): MapWidget =
        MapWidget(context, x, y, width, height).apply {
            invalidate = requestRepaint
            // A map opened later starts from the latest phone link, location and place.
            val data = this@MapPlugin.services.appData
            configurePhoneRouteTransport(data.latest(AppChannels.phoneRoutes))
            onPhoneRouteConnectionChanged(data.latest(AppChannels.phoneConnected) == true)

            val map = this
            listener = object : MapWidgetListener {
                override fun onMapDataRequested(point: MapGeoPoint, config: PoiDisplayConfig) {
                    data.requests?.requestMapData(config.toMapSyncRequest(point))
                }
                override fun onAddressFocusChanged(focused: Boolean) = this@MapPlugin.services.onKeyFocusChanged(map, focused)
                override fun onNavigationResult(result: NavigationOperationResult) = services.notifyTransientChange()
                override fun onStopNavigationRequested() = this@MapPlugin.stopNavigation()
            }
            data.latest(AppChannels.location)?.let { setLocation(Location(it)) }
            val place = data.latest(AppChannels.place)
            setLocationMetadata(place?.town.orEmpty(), place?.countryCode.orEmpty())
        }

    // ==================== What the assistant can do and read ====================

    private val assistantActions by lazy {
        MapAssistantActions(
            ui = services.ui,
            showMap = { services.show(TYPE) as? MapWidget },
            currentMap = current,
            present = { widget, spec -> services.present(widget, spec) },
            dismissPresentation = { current()?.let(services::dismissPresentationOf) },
            withTransientChanges = { action -> services.withTransientChanges(action) },
            notifyTransientChange = services::notifyTransientChange,
            notifyPersistentChange = services::notifyPersistentChange,
        )
    }

    override fun assistantTools(): List<AssistantSemanticToolHandler> = listOf(
        ConfigureMapPoisSemanticToolHandler(applyConfiguration = assistantActions::configurePois, isAvailable = ::isReady),
        StartNavigationSemanticToolHandler(startNavigation = assistantActions::startNavigation, isAvailable = ::isReady),
        StopNavigationSemanticToolHandler(
            stopNavigation = assistantActions::stopNavigation,
            isAvailable = { isReady() && current()?.hasActiveNavigation() == true },
        ),
    )

    override fun assistantFacts(widget: BaseWidget, stableId: String): List<AssistantSemanticFact> {
        val map = widget as? MapWidget ?: return emptyList()
        return map.poiDisplayConfig.toAssistantSemanticFacts() + map.navigationState.toAssistantSemanticFacts()
    }

    private fun isReady(): Boolean = services.viewportWidth > 0 && services.viewportHeight > 0

    /** The map as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("map")
        override val descriptor = WidgetDescriptor(
            TYPE,
            "Map",
            WidgetInstancePolicy.SINGLETON,
            order = 2,
            storesClosedTemplate = true
        )
        override val codec get() = MapWidgetState.Codec
        override fun create(context: WidgetContext) = MapPlugin(context)
        override val commands = listOf(
            WidgetCommand(
                AppCommandDefinition(
                    "toggle:map",
                    "Toggle: Map",
                    "Map",
                    "Show or hide the map"
                ), WidgetMenu.TOGGLE
            ),
        )
        private const val SIZE = 210f
    }
}

/** What a saved dashboard keeps of the map: its view mode, zoom, labels and points of interest. */
data class MapWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val mode: String = MapViewMode.TOP_DOWN.name,
    val zoom: String = MapZoom.MEDIUM.name,
    val showLocation: Boolean = true,
    val showCountry: Boolean = true,
    val poiConfig: PoiDisplayConfig = PoiDisplayConfig.DEFAULT,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<MapWidgetState>(MapPlugin.TYPE, MapWidgetState::class) {
        override fun toJson(state: MapWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("mode", state.mode)
            put("zoom", state.zoom)
            put("showLocation", state.showLocation)
            put("showCountry", state.showCountry)
            put("poiConfig", poiConfigToJson(state.poiConfig))
        }

        override fun fromJson(payload: JSONObject): MapWidgetState? =
            parseSizedWidgetObject(payload) { obj, c, w, h ->
                MapWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    mode = obj.optString("mode", MapViewMode.TOP_DOWN.name),
                    zoom = obj.optString("zoom", MapZoom.MEDIUM.name),
                    showLocation = obj.optBoolean("showLocation", true),
                    showCountry = obj.optBoolean("showCountry", true),
                    poiConfig = parsePoiConfig(obj.optJSONObject("poiConfig")),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }

        private fun poiConfigToJson(config: PoiDisplayConfig): JSONObject = JSONObject().apply {
            put("state", config.state.name)
            put("mode", config.mode.name)
            put("categories", JSONArray().apply { config.categories.sorted().forEach(::put) })
            put("radiusM", config.radiusM)
            put("maxResults", config.maxResults)
            put("importantCategories", JSONArray().apply {
                config.importantCategories.sorted().forEach(::put)
            })
            put("importantRadiiM", JSONObject().apply {
                config.importantRadiiM.toSortedMap().forEach { (category, radius) -> put(category, radius) }
            })
            put("normalCategories", JSONArray().apply {
                config.normalCategories.sorted().forEach(::put)
            })
            put("normalMaxResults", config.normalMaxResults)
            put("showClosest", config.showClosest)
            put("closestRadiusM", config.closestRadiusM)
            put("balanceCategories", config.balanceCategories)
        }

        private fun parsePoiConfig(obj: JSONObject?): PoiDisplayConfig {
            if (obj == null) return PoiDisplayConfig.DEFAULT
            return runCatching {
                val state = runCatching {
                    PoiDisplayConfigState.valueOf(obj.optString("state", PoiDisplayConfigState.CUSTOM.name))
                }.getOrDefault(PoiDisplayConfigState.CUSTOM)
                // Legacy Default records have no mode/profile fields and can be restored directly.
                // New records are parsed below so a caller that copied Default and changed a single
                // variable cannot have that change discarded merely because its old state flag stayed
                // DEFAULT.
                if (state == PoiDisplayConfigState.DEFAULT && !obj.has("mode")) {
                    return@runCatching PoiDisplayConfig.DEFAULT
                }
                val categoriesJson = obj.optJSONArray("categories") ?: error("POI categories missing")
                val categories = buildSet {
                    for (index in 0 until categoriesJson.length()) add(categoriesJson.getString(index))
                }
                // Files written before the two-mode menu only contain the nearest-profile fields.
                // Recreate them through the compatibility factory so the previous voice behavior
                // remains stable.
                if (!obj.has("mode")) {
                    return@runCatching PoiDisplayConfig.custom(
                        categories = categories,
                        radiusM = obj.optInt("radiusM", PoiDisplayConfig.DEFAULT_RADIUS_M),
                        maxResults = obj.optInt("maxResults", PoiDisplayConfig.DEFAULT_MAX_RESULTS)
                    )
                }
                val mode = runCatching {
                    PoiDisplayMode.valueOf(obj.optString("mode", PoiDisplayMode.NEAREST.name))
                }.getOrDefault(PoiDisplayMode.NEAREST)
                fun stringSet(key: String, fallback: Set<String>): Set<String> {
                    val json = obj.optJSONArray(key) ?: return fallback
                    return buildSet {
                        for (index in 0 until json.length()) add(json.getString(index))
                    }
                }
                val importantCategories = stringSet(
                    "importantCategories",
                    PoiDisplayConfig.DEFAULT_IMPORTANT_CATEGORIES
                )
                val importantRadii = buildMap {
                    val json = obj.optJSONObject("importantRadiiM")
                    if (json == null) {
                        putAll(PoiDisplayConfig.DEFAULT_IMPORTANT_RADII_M)
                    } else {
                        json.keys().forEach { category -> put(category, json.getInt(category)) }
                    }
                }
                val normalCategories = stringSet(
                    "normalCategories",
                    PoiDisplayConfig.DEFAULT_NORMAL_CATEGORIES
                )
                val candidate = PoiDisplayConfig(
                    categories = categories,
                    radiusM = obj.optInt("radiusM", PoiDisplayConfig.DEFAULT_RADIUS_M),
                    maxResults = obj.optInt(
                        "maxResults",
                        PoiDisplayConfig.DEFAULT_MAX_RESULTS
                    ),
                    state = PoiDisplayConfigState.CUSTOM,
                    mode = mode,
                    importantCategories = importantCategories,
                    importantRadiiM = importantRadii,
                    normalCategories = normalCategories,
                    normalMaxResults = obj.optInt(
                        "normalMaxResults",
                        PoiDisplayConfig.DEFAULT_NORMAL_MAX_RESULTS
                    ),
                    showClosest = obj.optBoolean(
                        "showClosest",
                        PoiDisplayConfig.DEFAULT_SHOW_CLOSEST
                    ),
                    closestRadiusM = obj.optInt(
                        "closestRadiusM",
                        PoiDisplayConfig.DEFAULT_CLOSEST_RADIUS_M
                    ),
                    balanceCategories = obj.optBoolean("balanceCategories", false)
                )
                if (
                    candidate.mode == PoiDisplayMode.DEFAULT &&
                    candidate.categories == PoiDisplayConfig.DEFAULT.categories &&
                    candidate.radiusM == PoiDisplayConfig.DEFAULT.radiusM &&
                    candidate.maxResults == PoiDisplayConfig.DEFAULT.maxResults &&
                    candidate.importantCategories == PoiDisplayConfig.DEFAULT.importantCategories &&
                    candidate.importantRadiiM == PoiDisplayConfig.DEFAULT.importantRadiiM &&
                    candidate.normalCategories == PoiDisplayConfig.DEFAULT.normalCategories &&
                    candidate.normalMaxResults == PoiDisplayConfig.DEFAULT.normalMaxResults &&
                    candidate.showClosest == PoiDisplayConfig.DEFAULT.showClosest &&
                    candidate.closestRadiusM == PoiDisplayConfig.DEFAULT.closestRadiusM &&
                    !candidate.balanceCategories
                ) {
                    PoiDisplayConfig.DEFAULT
                } else {
                    candidate
                }
            }.getOrDefault(PoiDisplayConfig.DEFAULT)
        }
    }
}