package com.everyday.everyday_glasses.widgets.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.withClip
import com.everyday.everyday_glasses.widgets.map.backend.DestinationSearchFallbackReason
import com.everyday.everyday_glasses.widgets.map.backend.DestinationSearchListener
import com.everyday.everyday_glasses.widgets.map.backend.DevicePose
import com.everyday.everyday_glasses.widgets.map.backend.DevicePoseController
import com.everyday.everyday_glasses.widgets.map.backend.DevicePoseObserver
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingCandidate
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingException
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingFailureKind
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingRanker
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingResolution
import com.everyday.everyday_glasses.widgets.map.backend.GlassesDestinationSearchCoordinator
import com.everyday.everyday_glasses.widgets.map.backend.GlassesDestinationSearchTransport
import com.everyday.everyday_glasses.widgets.map.backend.GlassesPhoneRoutingCoordinator
import com.everyday.everyday_glasses.widgets.map.backend.GlassesRouteFallbackReason
import com.everyday.everyday_glasses.widgets.map.backend.GlassesRouteTransport
import com.everyday.everyday_glasses.widgets.map.backend.GroundProjection
import com.everyday.everyday_glasses.widgets.map.backend.LocalWalkingNavigationService
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.MapPoi
import com.everyday.everyday_glasses.widgets.map.backend.MapPoiCategoryCatalog
import com.everyday.everyday_glasses.widgets.map.backend.MapPoiSelection
import com.everyday.everyday_glasses.widgets.map.backend.MapPoiSelector
import com.everyday.everyday_glasses.widgets.map.backend.MapPoiTier
import com.everyday.everyday_glasses.widgets.map.backend.MapRoad
import com.everyday.everyday_glasses.widgets.map.backend.MapRoadRepository
import com.everyday.everyday_glasses.widgets.map.backend.MapViewMode
import com.everyday.everyday_glasses.widgets.map.backend.MapZoom
import com.everyday.everyday_glasses.widgets.map.backend.NavigationAddressText
import com.everyday.everyday_glasses.widgets.map.backend.NavigationDestination
import com.everyday.everyday_glasses.widgets.map.backend.NavigationOperationResult
import com.everyday.everyday_glasses.widgets.map.backend.NavigationState
import com.everyday.everyday_glasses.widgets.map.backend.NavigationStatus
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfigState
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayMode
import com.everyday.everyday_glasses.widgets.map.backend.RankedGeocodingCandidate
import com.everyday.everyday_glasses.widgets.map.backend.RouteResolutionListener
import com.everyday.everyday_glasses.widgets.map.backend.destinationIndicatorIsOffscreen
import com.everyday.everyday_glasses.widgets.map.backend.formatGuidanceDistance
import com.everyday.everyday_glasses.widgets.map.backend.mapPoiTier
import com.everyday.everyday_glasses.widgets.map.backend.navigationFailureMessage
import com.everyday.everyday_glasses.widgets.map.backend.toCompactBannerText
import com.everyday.everyday_glasses.runtime.MapWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.interaction.user_input.TextEditAction
import com.everyday.everyday_glasses.interaction.user_input.TextZone
import com.everyday.everyday_glasses.interaction.user_input.TextZoneKind
import com.everyday.everyday_glasses.ui.components.GlassesKeyboardView
import com.everyday.everyday_glasses.ui.components.KeyboardOverlayController
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets._common.PresentationSpec
import com.everyday.everyday_glasses.widgets._common.WidgetTheme
import com.everyday.everyday_glasses.widgets.weather_location.WeatherWidget
import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.GuidanceFixStatus
import com.everyday.shared.routing.LiveGuidanceUpdate
import com.everyday.shared.routing.LiveLocationFix
import com.everyday.shared.routing.LiveWalkingGuidanceEngine
import com.everyday.shared.routing.RouteCalculationResult
import com.everyday.shared.routing.RouteMode
import com.everyday.shared.routing.RouteResult
import com.everyday.shared.routing.WalkingRouteDefaults
import com.everyday.shared.sync.MapSnapshot
import com.everyday.shared.transport.DestinationSearchError
import com.everyday.shared.transport.DestinationSearchRequest
import com.everyday.shared.transport.DestinationSearchResult
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportRequest
import com.everyday.shared.transport.RouteTransportResult
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.text.substringAfter

/** Circular, heading-up minimap with an interactive fullscreen map. */
class MapWidget(
    context: Context,
    x: Float, y: Float, width: Float = 210f, height: Float = 210f
) : BaseWidget(WidgetTypeId.MAP, x, y, width, height) {
    override val minimizeLabel = "M"
    override val minWidth = MIN_WIDGET_SIZE
    override val minHeight = MIN_WIDGET_SIZE

    var listener: MapWidgetListener? = null
    private val onMapDataRequested: ((MapGeoPoint, PoiDisplayConfig) -> Unit)?
        get() = listener?.let { value -> { point, config -> value.onMapDataRequested(point, config) } }
    private val onAddressFocusChanged: ((Boolean) -> Unit)?
        get() = listener?.let { value -> { focused -> value.onAddressFocusChanged(focused) } }
    private val onNavigationResult: ((NavigationOperationResult) -> Unit)?
        get() = listener?.let { value -> { result -> value.onNavigationResult(result) } }
    private val onStopNavigationRequested: (() -> Unit)?
        get() = listener?.let { value -> { value.onStopNavigationRequested() } }
    private val poiSelector = MapPoiSelector()
    private var poiSelection = MapPoiSelection()
    private val repository = MapRoadRepository(context, { r, p ->
        roads = r
        pois = p
        updatePoiSelection()
        invalidate?.invoke()
    }, { point, config -> onMapDataRequested?.invoke(point, config) })
    private var roads = emptyList<MapRoad>()
    private var pois = emptyList<MapPoi>()
    private var position: Location? = null
    private var heading = 0f
    private var pose = DevicePose()
    private val poseRedrawHandler = Handler(Looper.getMainLooper())
    private var poseRedrawPending = false
    private var lastPoseRedrawAtMs = 0L
    private var lastRedrawnHeading = Float.NaN
    private var latestPoseHeading = Float.NaN
    private val projection = GroundProjection()
    private val poseController = DevicePoseController(
        context.applicationContext,
        object : DevicePoseObserver {
            override fun onPoseChanged(pose: DevicePose) {
                this@MapWidget.pose = pose
                heading = pose.headingDeg
                latestPoseHeading = pose.headingDeg
                requestPoseRedraw()
            }

            override fun onStatusChanged(status: String) {
                Log.d(TAG, status)
            }
        }
    )
    var invalidate: (() -> Unit)? = null
    var mode = MapViewMode.TOP_DOWN; private set
    var zoom = MapZoom.MEDIUM; private set
    var poiDisplayConfig: PoiDisplayConfig = PoiDisplayConfig.DEFAULT
        private set
    private var showLocation = true
    private var showCountry = true
    private var locationLabel = ""
    private var countryCode = ""
    private var showOptions = false
    private var addressEntryVisible = false
    private var addressFocused = false
    private var addressText = ""
    private val addressKeyboard = KeyboardOverlayController().apply {
        onKeyPressed = { key ->
            textZoneHost?.takeIf { addressEntryVisible }?.let { host ->
                if (!host.isFocused(addressZone)) {
                    setAddressFocus(true)
                    host.focus(addressZone)
                }
                host.onKey(key)
            }
        }
    }
    private val addressTextBounds = RectF()

    /** The destination field; typing, selection and clipboard are handled by the text editing controller. */
    val addressZone: TextZone = object : TextZone {
        override val kind = TextZoneKind.FIELD
        override val bounds: RectF
            get() = addressTextBounds.apply {
                set(addressFieldBounds.left + 8f, addressFieldBounds.top, addressFieldBounds.right - 8f, addressFieldBounds.bottom)
            }
        override val isScreenSpace = true
        override val fieldTextSize = 13f
        override fun content(): CharSequence = addressText
        override fun onTextEditAction(action: TextEditAction) {
            when (action) {
                is TextEditAction.Changed -> addressText = action.content.toString()
                is TextEditAction.Submitted -> {
                    addressText = action.text
                    submitAddressEntry()
                }
                TextEditAction.Released -> setAddressFocus(false)
                TextEditAction.LayoutChanged -> Unit
            }
            invalidate?.invoke()
        }
    }
    private val navigationService = LocalWalkingNavigationService()
    private val liveGuidance = LiveWalkingGuidanceEngine()
    private val navigationExecutor = Executors.newSingleThreadExecutor()
    private val navigationMain = Handler(Looper.getMainLooper())
    private var navigationGeneration = 0L
    private var pendingNavigationCompletion: ((NavigationOperationResult) -> Unit)? = null
    private var phoneRouteTransport: GlassesRouteTransport? = null
    private var phoneSearchTransport: GlassesDestinationSearchTransport? = null
    private var searchAttribution = ""
    private var navigationChoiceScrollOffset = 0f
    private var activePhoneRouteGeneration: Long? = null
    private var activePhoneRouteRequestId: String? = null
    private var activePhoneRouteRequest: RouteTransportRequest? = null
    private var activePhoneRoutePurpose: RouteRequestPurpose? = null
    private var navigationRouteRequest: RouteTransportRequest? = null
    private var reroutePending = false
    private val phoneRouting = GlassesPhoneRoutingCoordinator({ phoneRouteTransport }).apply {
        listener = object : RouteResolutionListener {
            override fun onRouteResolved(result: RouteTransportResult) {
                navigationMain.post { handlePhoneRouteResult(result) }
            }

            override fun onRouteAmbiguous(request: RouteTransportRequest, error: RouteTransportError) {
                navigationMain.post { handlePhoneRouteAmbiguous(request, error) }
            }

            override fun onRouteFallback(request: RouteTransportRequest, reason: GlassesRouteFallbackReason) {
                navigationMain.post { handlePhoneRouteFallback(request, reason) }
            }
        }
    }
    private val phoneSearch = GlassesDestinationSearchCoordinator({ phoneSearchTransport }).apply {
        listener = object : DestinationSearchListener {
            override fun onDestinationSearchResult(result: DestinationSearchResult) {
                navigationMain.post { handleDestinationSearchResult(result) }
            }

            override fun onDestinationSearchFallback(
                request: DestinationSearchRequest,
                reason: DestinationSearchFallbackReason
            ) {
                runLocalDestinationFallback(request)
            }

            override fun onDestinationSearchFailure(request: DestinationSearchRequest, error: DestinationSearchError?) {
                navigationMain.post {
                    if (navigationState.status == NavigationStatus.SEARCHING && navigationState.query == request.query) {
                        finishNavigationFailure(error?.message ?: "Destination search timed out; try again.", null)
                    }
                }
            }
        }
    }
    var navigationState: NavigationState = NavigationState()
        private set

    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150,205,245); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val major = Paint(road).apply { color = Color.rgb(255,205,105) }
    private val halo = Paint(road).apply { color = Color.argb(55,120,200,255) }
    private val user = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255,245,100); style = Paint.Style.FILL }
    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255,215,80); style = Paint.Style.FILL }
    private val trainBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val trainOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255,215,80); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val trainDetail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val navigationRouteHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 70, 190, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val navigationRoute = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 220, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val navigationCompletedRouteHalo = Paint(navigationRouteHalo).apply {
        color = Color.argb(55, 120, 145, 165)
    }
    private val navigationCompletedRoute = Paint(navigationRoute).apply {
        color = Color.rgb(135, 155, 170)
    }
    private val navigationDestination = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 90, 90)
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    private val northLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 105, 95)
        textSize = 14f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.ColorValue.hoverBorder; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.ColorValue.primaryButton; style = Paint.Style.FILL }
    private val chipHover = Paint(chip).apply { color = WidgetTheme.ColorValue.hoverBorder }
    // POI controls use separate selected and hover colors so a selected category remains
    // visibly selected while the cursor passes over it.
    private val chipSelected = Paint(chip).apply { color = Color.rgb(30, 108, 82) }
    private val chipSelectedHover = Paint(chipSelected).apply { color = Color.rgb(58, 166, 112) }
    private val optionsBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val navigationBannerBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 8, 14, 28) }
    private val addressScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val addressDialogBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(246, 8, 14, 28) }
    private val addressFieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 28, 52) }
    private val addressHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 175, 195) }
    private val addressActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.ColorValue.primaryButton }
    private val addressCancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetTheme.ColorValue.dangerButton }
    private val clip = Path()
    private val goToChip = RectF(); private val settingsChip = RectF(); private val viewChip =
        RectF(); private val zoomChip = RectF()
    private val optionsBounds = RectF(); private val locationOptionBounds = RectF(); private val countryOptionBounds =
        RectF(); private val poiOptionBounds = RectF()
    private val poiConfigBounds = RectF(); private val poiApplyBounds = RectF(); private val poiDefaultBounds =
        RectF(); private val poiCancelBounds = RectF()
    private val poiModeDefaultBounds = RectF(); private val poiModeNearestBounds = RectF()
    private val poiNearestRadiusTrack = RectF(); private val poiNearestCountTrack = RectF()
    private val poiNormalCountTrack = RectF(); private val poiClosestToggleBounds = RectF(); private val poiClosestRadiusTrack =
        RectF()
    private val poiNearestCategoryBounds = mutableMapOf<String, RectF>()
    private val poiImportantCategoryBounds = mutableMapOf<String, RectF>()
    private val poiNormalCategoryBounds = mutableMapOf<String, RectF>()
    private val poiImportantRadiusTracks = mutableMapOf<String, RectF>()
    private var hoverX = Float.NEGATIVE_INFINITY; private var hoverY = Float.NEGATIVE_INFINITY
    private var showPoiConfig = false
    private var pendingPoiConfig = PoiDisplayConfig.DEFAULT
    // Default mode has every supported category in its compatibility query. When the user
    // enters Nearest mode from that profile, the first category tap should establish the new
    // subset instead of merely removing one item from the inherited all-category set.
    private var nearestCategorySelectionStartsFresh = false
    private var poiScrollOffset = 0f
    private var poiContentHeight = 0f
    private val navigationBannerBounds = RectF()
    private val navigationStopBounds = RectF()
    private val addressDialogBounds = RectF()
    private val addressFieldBounds = RectF()
    private val addressSubmitBounds = RectF()
    private val addressCancelBounds = RectF()
    private val navigationChoiceBounds = mutableListOf<RectF>()
    private val navigationChoiceCancelBounds = RectF()
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    private enum class RouteRequestPurpose { INITIAL, REROUTE }

    private enum class PoiSlider {
        NONE,
        IMPORTANT_RADIUS,
        NORMAL_COUNT,
        CLOSEST_RADIUS,
        NEAREST_RADIUS,
        NEAREST_COUNT
    }

    private var activePoiSlider = PoiSlider.NONE
    private var activePoiSliderCategory: String? = null

    init {
        poseController.startTopDown()
    }

    fun setLocation(value: Location) {
        position = Location(value)
        poseController.updateLocation(value)
        updatePoiSelection()
        repository.refresh(MapGeoPoint(value.latitude, value.longitude), poiDisplayConfig)
        updateLiveGuidance(value)
        invalidate?.invoke()
    }

    /** Retries through the phone after its RFCOMM connection becomes available. */
    fun requestPhoneMapRefresh() {
        position?.let { repository.refreshFromPhone(MapGeoPoint(it.latitude, it.longitude), poiDisplayConfig) }
    }

    fun configurePhoneRouteTransport(transport: GlassesRouteTransport?) {
        phoneRouteTransport = transport
        phoneSearchTransport = transport as? GlassesDestinationSearchTransport
    }

    fun onPhoneRouteConnectionChanged(connected: Boolean) {
        phoneRouting.onConnectionChanged(connected)
        phoneSearch.onConnectionChanged(connected)
    }

    fun onPhoneRouteResult(result: RouteTransportResult) {
        phoneRouting.onRouteResult(result)
    }

    fun onPhoneRouteError(error: RouteTransportError) {
        phoneRouting.onRouteError(error)
    }

    fun onPhoneDestinationSearchResult(result: DestinationSearchResult) { phoneSearch.onResult(result) }
    fun onPhoneDestinationSearchError(error: DestinationSearchError) { phoneSearch.onError(error) }

    fun setLocationMetadata(location: String, country: String) {
        locationLabel = location; countryCode = country; invalidate?.invoke()
    }

    fun applyProxyMap(snapshot: MapSnapshot) {
        repository.acceptProxy(
            MapGeoPoint(snapshot.center.lat, snapshot.center.lon),
            snapshot.roads.map {
                MapRoad(
                    it.id,
                    it.highway,
                    it.name,
                    it.points.map { p -> MapGeoPoint(p.lat, p.lon) })
            },
            snapshot.pois.map {
                MapPoi(
                    it.id,
                    it.name,
                    it.category,
                    mapPoiTier(it.category),
                    MapGeoPoint(it.point.lat, it.point.lon)
                )
            },
            poiDisplayConfig
        )
    }

    fun isLocationVisible() = showLocation
    fun isCountryVisible() = showCountry

    fun currentMapLocation(): MapGeoPoint? = position?.let {
        MapGeoPoint(
            it.latitude,
            it.longitude
        )
    }
    /** Giving up the keyboard closes the address search. */
    override fun releaseKeyFocus() {if (addressFocused) closeAddressEntry()}
    fun isAddressKeyboardVisible(): Boolean = addressKeyboard.isVisible
    fun getAddressKeyboard(): GlassesKeyboardView? = addressKeyboard.getKeyboard()
    override val visibleKeyboard: GlassesKeyboardView? get() = if (isAddressKeyboardVisible()) getAddressKeyboard() else null
    /** The address keyboard is drawn with the rest of the address search, in [drawScreenOverlay]. */
    override val drawsOwnKeyboard: Boolean get() = true
    fun hasActiveNavigation(): Boolean = navigationState.status != NavigationStatus.INACTIVE &&
        navigationState.status != NavigationStatus.ERROR

    fun release() {
        poseRedrawHandler.removeCallbacksAndMessages(null)
        poseRedrawPending = false
        navigationGeneration++
        activePhoneRouteGeneration = null
        activePhoneRouteRequestId = null
        activePhoneRouteRequest = null
        activePhoneRoutePurpose = null
        reroutePending = false
        phoneRouting.cancelActive("map_released")
        phoneRouting.release()
        phoneSearch.release()
        navigationService.cancelSearch()
        navigationExecutor.shutdownNow()
        liveGuidance.reset()
        navigationRouteRequest = null
        val completion = pendingNavigationCompletion
        pendingNavigationCompletion = null
        completion?.invoke(NavigationOperationResult.Failure(navigationState, "Navigation stopped"))
        addressKeyboard.hide()
        setAddressFocus(false)
        poseController.stop()
    }

    /**
     * Sensor callbacks can arrive much faster than the display can present a frame. Keep the
     * latest pose for the next draw, but only fan out a dashboard invalidation once per frame and
     * after a meaningful compass change. This prevents unrelated widgets from being redrawn for
     * every sensor sample while preserving the map's absolute heading.
     */
    private fun requestPoseRedraw() {
        val candidateHeading = latestPoseHeading
        if (candidateHeading.isNaN()) return
        if (!lastRedrawnHeading.isNaN() && angularDistanceDeg(candidateHeading, lastRedrawnHeading) < POSE_HEADING_INVALIDATION_THRESHOLD_DEG) {
            return
        }
        if (poseRedrawPending) return

        val nowMs = SystemClock.elapsedRealtime()
        val delayMs = (POSE_FRAME_INTERVAL_MS - (nowMs - lastPoseRedrawAtMs)).coerceAtLeast(0L)
        poseRedrawPending = true
        poseRedrawHandler.postDelayed({
            poseRedrawPending = false
            val headingToDraw = latestPoseHeading
            if (headingToDraw.isNaN()) return@postDelayed
            if (!lastRedrawnHeading.isNaN() && angularDistanceDeg(headingToDraw, lastRedrawnHeading) < POSE_HEADING_INVALIDATION_THRESHOLD_DEG) {
                return@postDelayed
            }
            lastRedrawnHeading = headingToDraw
            lastPoseRedrawAtMs = SystemClock.elapsedRealtime()
            invalidate?.invoke()
        }, delayMs)
    }

    fun restorePreferences(
        modeName: String,
        zoomName: String,
        locationVisible: Boolean = true,
        countryVisible: Boolean = true,
        poiConfig: PoiDisplayConfig? = null
    ) {
        zoom = runCatching { MapZoom.valueOf(zoomName) }.getOrDefault(MapZoom.MEDIUM)
        showLocation = locationVisible
        showCountry = countryVisible
        mode = runCatching { MapViewMode.valueOf(modeName) }.getOrDefault(MapViewMode.TOP_DOWN)
        poiConfig?.let(::restorePoiDisplayConfig)
        if (mode == MapViewMode.PROJECTED && isFullscreen) poseController.startProjected()
        else { mode = MapViewMode.TOP_DOWN; poseController.startTopDown() }
    }

    fun applyPoiDisplayConfig(config: PoiDisplayConfig) {
        poiDisplayConfig = config
        updatePoiSelection()
        // A settings change must invalidate both the selector and the repository request. In
        // particular, Default after a custom profile needs a fresh phone/Overpass response so a
        // station that was not part of the custom query can return immediately.
        position?.let { repository.refreshNow(MapGeoPoint(it.latitude, it.longitude), config) }
        invalidate?.invoke()
    }

    fun restorePoiDisplayConfig(config: PoiDisplayConfig) {
        poiDisplayConfig = config
        updatePoiSelection()
        position?.let { repository.refresh(MapGeoPoint(it.latitude, it.longitude), config) }
    }

    override val hasScreenModal: Boolean get() = addressEntryVisible || navigationState.choices.isNotEmpty() /** Address search and ambiguous destination choices. */
    override fun contentSignature(): List<Any?> = listOf(mode, zoom, isLocationVisible(), isCountryVisible(), poiDisplayConfig)
    override fun onModalTap(px: Float, py: Float): Boolean = (hasScreenModal || showPoiConfig) && onContentTap(px, py) /** The POI settings panel is modal too, including over a fullscreen map. */
    override fun isBorderHit(px: Float, py: Float): Boolean = false /** Double-taps on the map's border reach the map (address search), not the app context menu. */

    override fun onScroll(dy: Float) {
        if (navigationState.choices.isNotEmpty()) {
            val maxScroll = ((navigationState.choices.size - VISIBLE_SEARCH_ROWS).coerceAtLeast(0) * SEARCH_ROW_HEIGHT)
            navigationChoiceScrollOffset = (navigationChoiceScrollOffset + dy).coerceIn(0f, maxScroll)
            invalidate?.invoke()
            return
        }
        if (!showPoiConfig) return
        val viewportBottom = poiConfigBounds.bottom - 52f
        val maxScroll = (poiContentHeight - viewportBottom).coerceAtLeast(0f)
        poiScrollOffset = (poiScrollOffset + dy).coerceIn(0f, maxScroll)
        invalidate?.invoke()
    }

    /** Starts a POI slider drag using the same expanded hitbox as the Brightness slider. */
    override fun onModalPress(px: Float, py: Float): Boolean {
        if (!showPoiConfig) return false
        activePoiSlider = poiSliderAt(px, py)
        if (activePoiSlider != PoiSlider.NONE) {
            updatePoiSliderFromTouch(px, activePoiSlider)
        }
        // The POI panel is modal while open, including the area outside its card.
        return true
    }

    /** Updates the active POI slider while the cursor is held down. */
    override fun onPressMove(px: Float, py: Float): Boolean {
        if (!showPoiConfig) return false
        val slider = activePoiSlider
        if (slider != PoiSlider.NONE) {
            if (!poiSliderHitbox(slider).contains(px, py)) {
                activePoiSlider = PoiSlider.NONE
                return true
            }
            updatePoiSliderFromTouch(px, slider)
            return true
        }
        return true
    }

    /** Ends a POI slider drag. */
    override fun onPressRelease(px: Float, py: Float) {
        activePoiSlider = PoiSlider.NONE
        activePoiSliderCategory = null
    }

    override fun onDestroy() { release() }

    override fun onPause() {
        poseController.stop()
    }

    override fun onResume() {
        if (mode == MapViewMode.PROJECTED && isFullscreen) {
            poseController.startProjected()
        } else {
            poseController.startTopDown()
        }
        updatePoiSelection()
        invalidate?.invoke()
    }

    override fun updateHover(px: Float, py: Float) {
        super.updateHover(px, py)
        hoverX = px
        hoverY = py
        addressKeyboard.updateHover(px, py)
    }

    /**
     * Address search and ambiguous destination choices are screen-level modal surfaces.  They
     * must remain tappable even when the map itself is a small window in the dashboard.
     */
    override fun containsPoint(px: Float, py: Float): Boolean {
        if (hasScreenModal) return true
        return super.containsPoint(px, py)
    }

    override fun baseHitTest(px: Float, py: Float): BaseHitArea {
        val hit = super.baseHitTest(px, py)
        if (!showsWindowedNavigationFooter()) return hit
        return when (hit) {
            BaseHitArea.CLOSE_BUTTON,
            BaseHitArea.FULLSCREEN_BUTTON,
            BaseHitArea.MINIMIZE_BUTTON,
            BaseHitArea.PIN_BUTTON,
            BaseHitArea.RESIZE_HANDLE -> BaseHitArea.BORDER
            else -> hit
        }
    }

    override fun shouldShowBorderButtons(): Boolean =
        !showsWindowedNavigationFooter() && super.shouldShowBorderButtons()

    override fun onDoubleTap(px: Float, py: Float): Boolean {
        if (!containsPoint(px, py)) return false
        if (addressEntryVisible) {
            closeAddressEntry()
            return true
        }
        if (hasActiveNavigation()) {
            openAddressEntry()
            return true
        }
        if (!isFullscreen) {
            openAddressEntry()
            return true
        }
        // Fullscreen double-tap is reserved for leaving the fullscreen map.  Settings are now
        // an explicit control so it cannot conflict with this gesture.
        showOptions = false
        showPoiConfig = false
        toggleFullscreen()
        return true
    }

    override fun onContentTap(px: Float, py: Float): Boolean {
        if (addressEntryVisible) {
            when {
                addressSubmitBounds.contains(px, py) -> {
                    submitAddressEntry()
                    return true
                }
                addressCancelBounds.contains(px, py) -> {
                    closeAddressEntry()
                    return true
                }
                addressFieldBounds.contains(px, py) -> {
                    setAddressFocus(true)
                    textZoneHost?.tap(addressZone, px, py)
                    addressKeyboard.show()
                    updateAddressKeyboardLayout()
                    updateAddressLayout()
                    invalidate?.invoke()
                    return true
                }
                else -> {
                    // The address editor is modal.  A tap outside its panel dismisses it instead
                    // of passing through to the map or another dashboard widget.
                    closeAddressEntry()
                    return true
                }
            }
        }
        if (showsWindowedNavigationFooter() && isHovering()) {
            updateWindowedCancelNavigationBounds(contentBounds, contentBounds.bottom - 40f)
        }
        if (navigationStopBounds.contains(px, py)) {
            onStopNavigationRequested?.invoke() ?: stopNavigation()
            return true
        }
        navigationChoiceBounds.forEachIndexed { index, bounds ->
            if (bounds.contains(px, py)) {
                val first = (navigationChoiceScrollOffset / SEARCH_ROW_HEIGHT).toInt()
                selectNavigationChoice(first + index)
                return true
            }
        }
        if (navigationChoiceCancelBounds.contains(px, py)) { stopNavigation(); return true }
        if (showPoiConfig) {handlePoiConfigTap(px, py); return true}
        if (showOptions) {
            when {
                locationOptionBounds.contains(px, py) -> showLocation = !showLocation
                countryOptionBounds.contains(px, py) -> showCountry = !showCountry
                poiOptionBounds.contains(px, py) -> openPoiConfig()
                !optionsBounds.contains(px, py) -> showOptions = false
                else -> return true
            }
            invalidate?.invoke(); return true
        }
        if (isFullscreen) {
            if (goToChip.contains(px, py)) {
                openAddressEntry()
                return true
            }
            if (settingsChip.contains(px, py)) {
                showOptions = !showOptions
                showPoiConfig = false
                invalidate?.invoke()
                return true
            }
            if (viewChip.contains(px, py)) {
                mode = if (mode == MapViewMode.TOP_DOWN) MapViewMode.PROJECTED else MapViewMode.TOP_DOWN
                if (mode == MapViewMode.PROJECTED) poseController.startProjected() else poseController.startTopDown()
                invalidate?.invoke(); return true
            }
            if (zoomChip.contains(px, py)) { zoom = zoom.next(); updatePoiSelection(); invalidate?.invoke(); return true }
        } else if (baseHitTest(px, py) == BaseHitArea.CONTENT) {
            toggleFullscreen(); return true
        }
        return false
    }

    /** Windowed projected maps are deliberately disallowed; zoom is retained. */
    fun enforceWindowedMode() { if (!isFullscreen) { mode = MapViewMode.TOP_DOWN; poseController.startTopDown() } }

    /** Opens the shared address-search modal used by windowed and fullscreen map interaction. */
    fun openAddressEntry(): Boolean {
        if (isMinimized) return false
        showOptions = false
        showPoiConfig = false
        if (navigationState.choices.isNotEmpty()) {
            navigationState = navigationState.copy(choices = emptyList())
        }
        addressText = ""
        addressEntryVisible = true
        updateAddressLayout()
        setAddressFocus(true)
        textZoneHost?.focus(addressZone)
        addressKeyboard.hide()
        invalidate?.invoke()
        return true
    }

    /** Closes the address field and releases the phone/custom-keyboard focus contract. */
    fun closeAddressEntry() {
        val wasOpen = addressEntryVisible || addressFocused || addressKeyboard.isVisible
        addressEntryVisible = false
        addressText = ""
        addressKeyboard.hide()
        setAddressFocus(false)
        navigationChoiceBounds.clear()
        navigationChoiceScrollOffset = 0f
        searchAttribution = ""
        if (wasOpen) invalidate?.invoke()
    }

    /** Starts phone-first address resolution/route calculation with one bounded local fallback. */
    @Suppress("UNUSED_PARAMETER")
    fun startNavigation(
        address: String,
        modeName: String,
        presentationSpec: PresentationSpec? = null,
        completion: ((NavigationOperationResult) -> Unit)? = null
    ): Boolean {
        replacePendingNavigation()
        val query = NavigationAddressText.normalize(address)
        val mode = modeName.trim().uppercase(Locale.ROOT)
        if (query.isBlank()) {
            finishNavigationFailure("Enter a destination address.", completion)
            return false
        }
        if (mode != "WALKING") {
            finishNavigationFailure("Only WALKING navigation is supported; requested mode $mode.", completion)
            return false
        }
        val origin = currentMapLocation()
        if (origin == null) {
            finishNavigationFailure("Current location is unavailable; map and POIs were left unchanged.", completion)
            return false
        }

        closeAddressEntry()
        showOptions = false
        showPoiConfig = false
        ++navigationGeneration
        pendingNavigationCompletion = completion
        navigationState = NavigationState(
            status = NavigationStatus.SEARCHING,
            query = query
        )
        invalidate?.invoke()
        phoneSearch.request(
            DestinationSearchRequest(
                requestId = UUID.randomUUID().toString(),
                query = query,
                origin = GeoPoint(origin.lat, origin.lon),
                languageTag = Locale.getDefault().toLanguageTag(),
                requestedAtMs = System.currentTimeMillis()
            )
        )
        return true
    }

    /** Removes route geometry and guidance while retaining the map, POIs, orientation and zoom. */
    fun stopNavigation() {
        navigationGeneration++
        phoneSearch.cancelActive("navigation_stopped")
        navigationService.cancelSearch()
        activePhoneRouteGeneration = null
        phoneRouting.cancelActive("navigation_stopped")
        activePhoneRouteRequestId = null
        activePhoneRouteRequest = null
        activePhoneRoutePurpose = null
        reroutePending = false
        navigationRouteRequest = null
        liveGuidance.reset()
        closeAddressEntry()
        navigationState = NavigationState()
        val completion = pendingNavigationCompletion
        pendingNavigationCompletion = null
        completion?.invoke(
            NavigationOperationResult.Failure(
                navigationState,
                "Navigation stopped"
            )
        )
        invalidate?.invoke()
    }

    private fun submitAddressEntry() {
        val query = NavigationAddressText.normalize(addressText)
        if (query.isBlank()) {
            finishNavigationFailure("Enter a destination address.", null)
            return
        }
        closeAddressEntry()
        startNavigation(query, "WALKING")
    }

    private fun selectNavigationChoice(index: Int) {
        val choice = navigationState.choices.getOrNull(index) ?: return
        val origin = currentMapLocation()
        if (origin == null) {
            finishNavigationFailure("Current location is unavailable; map and POIs were left unchanged.", null)
            return
        }
        val query = navigationState.query ?: choice.label
        val destination = NavigationDestination(query, choice.label, choice.point)
        val generation = ++navigationGeneration
        navigationState = NavigationState(
            status = NavigationStatus.CALCULATING,
            query = query,
            destination = destination
        )
        navigationChoiceScrollOffset = 0f
        invalidate?.invoke()
        beginPhoneOrLocalNavigation(generation, query, origin, selectedDestination = choice)
    }

    private fun handleDestinationSearchResult(result: DestinationSearchResult) {
        val origin = currentMapLocation() ?: return
        val query = navigationState.query ?: result.query
        val candidates = result.candidates.mapIndexed { index, item ->
            GeocodingCandidate(
                MapGeoPoint(item.point.lat, item.point.lon), item.displayName,
                addressText = item.secondaryText, sourceIndex = index
            )
        }
        when (val resolution = GeocodingRanker.resolve(query, candidates, origin)) {
            GeocodingResolution.NoResults -> {
                finishNavigationFailure("No destination was found for \"$query\".", null)
                return
            }
            is GeocodingResolution.Accepted -> beginNavigationToResolvedCandidate(query, origin, resolution.candidate)
            is GeocodingResolution.Ambiguous -> {
                navigationChoiceScrollOffset = 0f
                searchAttribution = result.attribution
                navigationState = NavigationState(
                    status = NavigationStatus.CHOOSING,
                    query = query,
                    choices = resolution.choices
                )
                completeNavigation(NavigationOperationResult.Ambiguous(navigationState), null)
                invalidate?.invoke()
            }
        }
    }

    private fun beginNavigationToResolvedCandidate(
        query: String,
        origin: MapGeoPoint,
        candidate: RankedGeocodingCandidate
    ) {
        val destination = NavigationDestination(query, candidate.label, candidate.point)
        val generation = ++navigationGeneration
        navigationState = NavigationState(
            status = NavigationStatus.CALCULATING,
            query = query,
            destination = destination
        )
        navigationChoiceScrollOffset = 0f
        beginPhoneOrLocalNavigation(generation, query, origin, selectedDestination = candidate)
        invalidate?.invoke()
    }

    private fun runLocalDestinationFallback(request: DestinationSearchRequest) {
        val generation = navigationGeneration
        navigationExecutor.execute {
            try {
                val origin = MapGeoPoint(request.origin.lat, request.origin.lon)
                val category = localSearchCategory(request.query)
                val cached = category?.let { wanted ->
                    pois.filter { it.category == wanted }.mapIndexed { index, poi ->
                        GeocodingCandidate(
                            poi.point,
                            poi.name?.takeIf { it.isNotBlank() } ?: wanted.replace('_', ' '),
                            type = wanted,
                            sourceIndex = index)
                    }.let { GeocodingRanker.rank(request.query, it, origin) }
                }.orEmpty()
                val ranked = cached.takeIf { it.isNotEmpty() }
                    ?: navigationService.searchAddressCandidates(request.query, origin)
                if (ranked.isNotEmpty() && phoneSearch.acceptFallback(request.requestId)) {
                    navigationMain.post {
                        if (generation != navigationGeneration) return@post
                        when (val resolution = GeocodingRanker.resolve(
                            request.query,
                            ranked.map { it.candidate },
                            origin
                        )) {
                            GeocodingResolution.NoResults -> finishNavigationFailure(
                                "No destination was found for \"${request.query}\".",
                                null
                            )
                            is GeocodingResolution.Accepted -> beginNavigationToResolvedCandidate(
                                request.query,
                                origin,
                                resolution.candidate
                            )
                            is GeocodingResolution.Ambiguous -> {
                                navigationChoiceScrollOffset = 0f
                                searchAttribution = "© OpenStreetMap contributors"
                                navigationState = NavigationState(
                                    NavigationStatus.CHOOSING,
                                    request.query,
                                    choices = resolution.choices
                                )
                                completeNavigation(NavigationOperationResult.Ambiguous(navigationState), null)
                                invalidate?.invoke()
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // The phone request remains eligible to win until the coordinator's global deadline.
            }
        }
    }

    private fun localSearchCategory(query: String): String? {
        val key = Normalizer.normalize(query, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
        return when (key) {
            "train station", "railway station", "rail station", "gare", "gare ferroviaire" -> MapPoiCategoryCatalog.STATION
            "supermarket", "supermarche" -> MapPoiCategoryCatalog.SUPERMARKET
            "hospital", "hopital" -> MapPoiCategoryCatalog.HOSPITAL
            "pharmacy", "pharmacie" -> MapPoiCategoryCatalog.PHARMACY
            "restaurant" -> MapPoiCategoryCatalog.RESTAURANT
            "cafe" -> MapPoiCategoryCatalog.CAFE
            "library", "bibliotheque" -> MapPoiCategoryCatalog.LIBRARY
            else -> null
        }
    }

    private fun beginPhoneOrLocalNavigation(
        generation: Long,
        query: String,
        origin: MapGeoPoint,
        selectedDestination: RankedGeocodingCandidate?
    ) {
        val request = RouteTransportRequest(
            requestId = UUID.randomUUID().toString(),
            origin = GeoPoint(origin.lat, origin.lon),
            destinationAddress = query,
            destination = selectedDestination?.let { GeoPoint(it.point.lat, it.point.lon) },
            destinationDisplayName = selectedDestination?.label,
            mode = RouteMode.WALKING,
            requestedAtMs = System.currentTimeMillis(),
            maxStraightLineDistanceMeters = WalkingRouteDefaults.MAX_STRAIGHT_LINE_DISTANCE_METERS,
            maxSnapDistanceMeters = WalkingRouteDefaults.DEFAULT_MAX_SNAP_DISTANCE_METERS,
            walkingSpeedMps = NavigationState.WALKING_SPEED_MPS
        )
        activePhoneRouteGeneration = generation
        activePhoneRouteRequestId = request.requestId
        activePhoneRouteRequest = request
        activePhoneRoutePurpose = RouteRequestPurpose.INITIAL
        // The coordinator invokes the local path synchronously only for an unavailable/send-failed
        // phone, and otherwise waits for exactly one matching result/error/timeout callback.
        phoneRouting.request(request)
    }

    private fun handlePhoneRouteResult(result: RouteTransportResult) {
        if (activePhoneRouteRequestId != result.requestId) return
        val generation = activePhoneRouteGeneration ?: return
        val purpose = activePhoneRoutePurpose ?: return
        val request = activePhoneRouteRequest ?: return
        activePhoneRouteGeneration = null
        activePhoneRouteRequestId = null
        activePhoneRouteRequest = null
        activePhoneRoutePurpose = null
        postNavigation(generation) {
            val destinationPoint =
                MapGeoPoint(result.destination.point.lat, result.destination.point.lon)
            val destination = NavigationDestination(
                address = result.destination.query.ifBlank { navigationState.query.orEmpty() },
                label = result.destination.displayName,
                point = destinationPoint
            )
            if (purpose == RouteRequestPurpose.REROUTE) {
                installReplacementRoute(request, destination, result.route)
            } else {
                installInitialRoute(request, destination, result.route)
                completeNavigation(NavigationOperationResult.Active(navigationState), null)
            }
            invalidate?.invoke()
        }
    }

    private fun handlePhoneRouteAmbiguous(
        request: RouteTransportRequest,
        error: RouteTransportError
    ) {
        if (activePhoneRouteRequestId != request.requestId) return
        val generation = activePhoneRouteGeneration ?: return
        val purpose = activePhoneRoutePurpose ?: return
        activePhoneRouteGeneration = null
        activePhoneRouteRequestId = null
        activePhoneRouteRequest = null
        activePhoneRoutePurpose = null
        if (purpose == RouteRequestPurpose.REROUTE) {
            postNavigation(generation) {
                runLocalRouteFallback(generation, request, reroute = true)
            }
            return
        }
        postNavigation(generation) {
            val choices = error.choices.take(GeocodingRanker.MAX_CHOICES).mapIndexed { index, choice ->
                RankedGeocodingCandidate(
                    candidate = GeocodingCandidate(
                        point = MapGeoPoint(choice.point.lat, choice.point.lon),
                        displayName = choice.displayName,
                        addressText = choice.query,
                        sourceIndex = index
                    ),
                    textualScore = 1.0,
                    proximityMeters = 0.0,
                    score = (GeocodingRanker.MAX_CHOICES - index).toDouble()
                )
            }
            navigationState = NavigationState(
                status = NavigationStatus.RESOLVING,
                query = navigationState.query ?: request.destinationAddress,
                choices = choices
            )
            completeNavigation(NavigationOperationResult.Ambiguous(navigationState), null)
            invalidate?.invoke()
        }
    }

    private fun handlePhoneRouteFallback(
        request: RouteTransportRequest,
        @Suppress("UNUSED_PARAMETER") reason: GlassesRouteFallbackReason
    ) {
        if (activePhoneRouteRequestId != request.requestId) return
        val generation = activePhoneRouteGeneration ?: return
        val purpose = activePhoneRoutePurpose ?: return
        activePhoneRouteGeneration = null
        activePhoneRouteRequestId = null
        activePhoneRouteRequest = null
        activePhoneRoutePurpose = null
        postNavigation(generation) {
            runLocalRouteFallback(generation, request, reroute = purpose == RouteRequestPurpose.REROUTE)
        }
    }

    private fun runLocalRouteFallback(
        generation: Long,
        request: RouteTransportRequest,
        reroute: Boolean = false
    ) {
        if (generation != navigationGeneration) return
        try {
            navigationExecutor.execute {
                try {
                    val selectedPoint = request.destination
                    if (selectedPoint != null) {
                        val destination = NavigationDestination(
                            address = request.destinationAddress
                                ?: request.destinationDisplayName.orEmpty(),
                            label = request.destinationDisplayName?.takeIf { it.isNotBlank() }
                                ?: request.destinationAddress.orEmpty(),
                            point = MapGeoPoint(selectedPoint.lat, selectedPoint.lon)
                        )
                        if (!reroute) {
                            postNavigation(generation) {
                                navigationState = NavigationState(
                                    status = NavigationStatus.CALCULATING,
                                    query = navigationState.query ?: request.destinationAddress,
                                    destination = destination
                                )
                                invalidate?.invoke()
                            }
                        }
                        calculateLocalRoute(
                            generation,
                            destination,
                            MapGeoPoint(request.origin.lat, request.origin.lon),
                            reroute = reroute
                        )
                        return@execute
                    }

                    val query = NavigationAddressText.normalize(request.destinationAddress.orEmpty())
                    when (val resolution = navigationService.resolveAddress(
                        query,
                        MapGeoPoint(request.origin.lat, request.origin.lon)
                    )) {
                        GeocodingResolution.NoResults -> postNavigation(generation) {
                            finishNavigationFailure(
                                "No destination was found for \"$query\"; map and POIs were left unchanged.",
                                null
                            )
                        }
                        is GeocodingResolution.Ambiguous -> postNavigation(generation) {
                            navigationState = NavigationState(
                                status = NavigationStatus.RESOLVING,
                                query = query,
                                choices = resolution.choices
                            )
                            completeNavigation(NavigationOperationResult.Ambiguous(navigationState), null)
                            invalidate?.invoke()
                        }
                        is GeocodingResolution.Accepted -> {
                            val ranked = resolution.candidate
                            val destination =
                                NavigationDestination(query, ranked.label, ranked.point)
                            postNavigation(generation) {
                                navigationState = NavigationState(
                                    status = NavigationStatus.CALCULATING,
                                    query = query,
                                    destination = destination
                                )
                                invalidate?.invoke()
                            }
                            calculateLocalRoute(
                                generation,
                                destination,
                                MapGeoPoint(request.origin.lat, request.origin.lon),
                                reroute = false
                            )
                        }
                    }
                } catch (failure: Throwable) {
                    val message = when (failure) {
                        is GeocodingException -> when (failure.kind) {
                            GeocodingFailureKind.NETWORK -> "Destination search is unavailable; map and POIs were left unchanged."
                            GeocodingFailureKind.MALFORMED_RESPONSE -> "Destination search returned malformed data; map and POIs were left unchanged."
                            GeocodingFailureKind.INVALID_QUERY -> "The destination address is invalid; map and POIs were left unchanged."
                        }
                        else -> failure.message ?: "Navigation could not be started; map and POIs were left unchanged."
                    }
                    postNavigation(generation) {
                        if (reroute) finishRerouteFailure(message)
                        else finishNavigationFailure(message, null)
                    }
                }
            }
        } catch (failure: RuntimeException) {
            postNavigation(generation) {
                if (reroute) finishRerouteFailure(failure.message ?: "Navigation is unavailable")
                else finishNavigationFailure(failure.message ?: "Navigation is unavailable", null)
            }
        }
    }

    private fun calculateLocalRoute(
        generation: Long,
        destination: NavigationDestination,
        origin: MapGeoPoint,
        reroute: Boolean = false
    ) {
        try {
            when (val calculation = navigationService.calculateWalkingRoute(origin, destination.point)) {
                is RouteCalculationResult.Success -> postNavigation(generation) {
                    val request = routeRequestForDestination(destination, origin)
                    if (reroute) {
                        installReplacementRoute(request, destination, calculation.route)
                    } else {
                        installInitialRoute(request, destination, calculation.route)
                        completeNavigation(NavigationOperationResult.Active(navigationState), null)
                    }
                    invalidate?.invoke()
                }
                is RouteCalculationResult.Failure -> postNavigation(generation) {
                    if (reroute) {
                        finishRerouteFailure(navigationFailureMessage(calculation.failure))
                    } else {
                        finishNavigationFailure(navigationFailureMessage(calculation.failure), null, destination)
                    }
                }
            }
        } catch (failure: Throwable) {
            postNavigation(generation) {
                val message = failure.message ?: "Navigation could not be calculated; map and POIs were left unchanged."
                if (reroute) finishRerouteFailure(message)
                else finishNavigationFailure(message, null, destination)
            }
        }
    }

    private fun routeRequestForDestination(
        destination: NavigationDestination,
        origin: MapGeoPoint
    ): RouteTransportRequest = RouteTransportRequest(
        requestId = UUID.randomUUID().toString(),
        origin = GeoPoint(origin.lat, origin.lon),
        destinationAddress = destination.address.takeIf { it.isNotBlank() },
        destination = GeoPoint(destination.point.lat, destination.point.lon),
        destinationDisplayName = destination.label,
        mode = RouteMode.WALKING,
        requestedAtMs = System.currentTimeMillis(),
        maxStraightLineDistanceMeters = WalkingRouteDefaults.MAX_STRAIGHT_LINE_DISTANCE_METERS,
        maxSnapDistanceMeters = WalkingRouteDefaults.DEFAULT_MAX_SNAP_DISTANCE_METERS,
        walkingSpeedMps = NavigationState.WALKING_SPEED_MPS
    )

    private fun installInitialRoute(
        request: RouteTransportRequest,
        destination: NavigationDestination,
        route: RouteResult
    ) {
        liveGuidance.startRoute(route)
        navigationRouteRequest = request.copy(
            destination = GeoPoint(destination.point.lat, destination.point.lon),
            destinationDisplayName = destination.label
        )
        reroutePending = false
        navigationState = NavigationState(
            status = NavigationStatus.ACTIVE,
            query = navigationState.query ?: destination.address,
            destination = destination,
            route = route,
            guidance = liveGuidance.currentSnapshot()
        )
        position?.let(::updateLiveGuidance)
    }

    private fun installReplacementRoute(
        request: RouteTransportRequest,
        destination: NavigationDestination,
        route: RouteResult
    ) {
        liveGuidance.replaceRoute(route)
        navigationRouteRequest = request.copy(
            destination = GeoPoint(destination.point.lat, destination.point.lon),
            destinationDisplayName = destination.label
        )
        reroutePending = false
        navigationState = navigationState.copy(
            status = NavigationStatus.ACTIVE,
            destination = destination,
            route = route,
            errorMessage = null,
            choices = emptyList(),
            guidance = liveGuidance.currentSnapshot()
        )
        position?.let(::updateLiveGuidance)
    }

    private fun finishRerouteFailure(message: String) {
        liveGuidance.finishReroute()
        reroutePending = false
        navigationState = navigationState.copy(
            status = NavigationStatus.DEGRADED,
            errorMessage = message,
            guidance = liveGuidance.currentSnapshot()
        )
        invalidate?.invoke()
    }

    private fun updateLiveGuidance(location: Location) {
        if (navigationState.route == null || navigationState.status == NavigationStatus.INACTIVE) return
        val nowTimestampMs = System.currentTimeMillis()
        val nowMonotonicTimeMs = SystemClock.elapsedRealtime()
        val sourceTimestampMs = location.time.takeIf { it > 0L } ?: nowTimestampMs
        val sourceMonotonicTimeMs = location.elapsedRealtimeNanos
            .takeIf { it > 0L }
            ?.div(1_000_000L)
            ?: nowMonotonicTimeMs
        val accuracy = location.accuracy
            .takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f }
            ?.toDouble()
        val fix = runCatching {
            LiveLocationFix(
                point = GeoPoint(location.latitude, location.longitude),
                accuracyMeters = accuracy,
                timestampMs = sourceTimestampMs,
                monotonicTimeMs = sourceMonotonicTimeMs
            )
        }.getOrNull() ?: return
        val update = runCatching {
            liveGuidance.update(fix, nowTimestampMs, nowMonotonicTimeMs)
        }.getOrNull() ?: return
        applyGuidanceUpdate(update)
        if (update.arrivedNow) {
            clearPendingReroute()
        } else if (update.shouldRequestReroute) {
            beginReroute(fix.point, nowMonotonicTimeMs)
        }
        invalidate?.invoke()
    }

    private fun applyGuidanceUpdate(update: LiveGuidanceUpdate) {
        if (update.arrivedNow || update.fixStatus == GuidanceFixStatus.ARRIVED) {
            navigationState = navigationState.copy(
                status = NavigationStatus.ARRIVED,
                errorMessage = null,
                guidance = update.snapshot
            )
            return
        }
        val status = when {
            reroutePending -> NavigationStatus.RECALCULATING
            update.fixStatus == GuidanceFixStatus.ACCEPTED && !update.snapshot.isDegraded -> NavigationStatus.ACTIVE
            update.fixStatus == GuidanceFixStatus.ACCEPTED || update.fixStatus in setOf(
                GuidanceFixStatus.STALE,
                GuidanceFixStatus.INACCURATE,
                GuidanceFixStatus.IMPOSSIBLE,
                GuidanceFixStatus.OFF_ROUTE
            ) -> NavigationStatus.DEGRADED
            else -> NavigationStatus.ACTIVE
        }
        val notice = when (update.fixStatus) {
            GuidanceFixStatus.STALE -> "GPS stale; route held"
            GuidanceFixStatus.INACCURATE -> "GPS accuracy poor; route held"
            GuidanceFixStatus.IMPOSSIBLE -> "GPS jump ignored; route held"
            GuidanceFixStatus.OFF_ROUTE -> "Off route; recalculation pending"
            else -> null
        }
        navigationState = navigationState.copy(
            status = status,
            errorMessage = if (status == NavigationStatus.ACTIVE || status == NavigationStatus.RECALCULATING) null else notice,
            guidance = update.snapshot
        )
    }

    private fun beginReroute(origin: GeoPoint, monotonicTimeMs: Long) {
        if (reroutePending || navigationState.status == NavigationStatus.ARRIVED) return
        val destination = navigationState.destination ?: return
        val baseRequest = navigationRouteRequest ?: return
        liveGuidance.markRerouteRequested(monotonicTimeMs)
        reroutePending = true
        val request = RouteTransportRequest(
            requestId = UUID.randomUUID().toString(),
            origin = origin,
            destinationAddress = baseRequest.destinationAddress?.takeIf { it.isNotBlank() }
                ?: destination.address.takeIf { it.isNotBlank() },
            destination = GeoPoint(destination.point.lat, destination.point.lon),
            destinationDisplayName = destination.label,
            mode = RouteMode.WALKING,
            requestedAtMs = System.currentTimeMillis(),
            maxStraightLineDistanceMeters = baseRequest.maxStraightLineDistanceMeters,
            maxSnapDistanceMeters = baseRequest.maxSnapDistanceMeters,
            walkingSpeedMps = NavigationState.WALKING_SPEED_MPS
        )
        activePhoneRouteGeneration = navigationGeneration
        activePhoneRouteRequestId = request.requestId
        activePhoneRouteRequest = request
        activePhoneRoutePurpose = RouteRequestPurpose.REROUTE
        navigationState = navigationState.copy(
            status = NavigationStatus.RECALCULATING,
            errorMessage = null,
            guidance = liveGuidance.currentSnapshot()
        )
        phoneRouting.request(request)
    }

    private fun clearPendingReroute() {
        val hadRequest = activePhoneRouteRequestId != null
        activePhoneRouteGeneration = null
        activePhoneRouteRequestId = null
        activePhoneRouteRequest = null
        activePhoneRoutePurpose = null
        reroutePending = false
        liveGuidance.finishReroute()
        if (hadRequest) phoneRouting.cancelActive("arrived")
    }

    private fun finishNavigationFailure(
        message: String,
        completion: ((NavigationOperationResult) -> Unit)?,
        destination: NavigationDestination? = null
    ) {
        phoneSearch.cancelActive("search_failed")
        navigationService.cancelSearch()
        navigationState = NavigationState(
            status = NavigationStatus.ERROR,
            query = navigationState.query,
            destination = destination,
            errorMessage = message
        )
        val result = NavigationOperationResult.Failure(navigationState, message)
        completeNavigation(result, completion)
        invalidate?.invoke()
    }

    private fun replacePendingNavigation() {
        activePhoneRouteGeneration = null
        activePhoneRouteRequestId = null
        activePhoneRouteRequest = null
        activePhoneRoutePurpose = null
        reroutePending = false
        navigationRouteRequest = null
        liveGuidance.reset()
        phoneRouting.cancelActive("navigation_replaced")
        phoneSearch.cancelActive("navigation_replaced")
        navigationService.cancelSearch()
        val completion = pendingNavigationCompletion ?: return
        pendingNavigationCompletion = null
        completion(
            NavigationOperationResult.Failure(
                navigationState,
                "Navigation request replaced"
            )
        )
    }

    private fun completeNavigation(
        result: NavigationOperationResult,
        completion: ((NavigationOperationResult) -> Unit)?
    ) {
        val callback = completion ?: pendingNavigationCompletion
        pendingNavigationCompletion = null
        onNavigationResult?.invoke(result)
        callback?.invoke(result)
    }

    private fun postNavigation(generation: Long, action: () -> Unit) {
        navigationMain.post {
            if (generation == navigationGeneration) action()
        }
    }

    private fun setAddressFocus(focused: Boolean) {
        if (!focused) textZoneHost?.release(addressZone)
        if (addressFocused == focused) return
        addressFocused = focused
        onAddressFocusChanged?.invoke(focused)
    }

    private fun updateAddressKeyboardLayout() {
        if (!addressKeyboard.isVisible) return
        val c = contentBounds
        val width = viewportWidth.takeIf { it > 0f } ?: c.width()
        val height = viewportHeight.takeIf { it > 0f } ?: c.height()
        val left = if (viewportWidth > 0f) 0f else c.left
        val top = if (viewportHeight > 0f) 0f else c.top
        // The address editor is a screen-level modal, so its keyboard must not inherit the
        // small window's dimensions when the map is windowed.
        addressKeyboard.updateLayout(width, height, left, top)
    }

    private fun updateAddressLayout() {
        val c = contentBounds
        val screenWidth = viewportWidth.takeIf { it > 0f } ?: c.right.coerceAtLeast(c.width())
        val screenHeight = viewportHeight.takeIf { it > 0f } ?: c.bottom.coerceAtLeast(c.height())
        val panelWidth = (screenWidth * ADDRESS_DIALOG_WIDTH_FRACTION)
            .coerceAtLeast(240f)
            .coerceAtMost((screenWidth - 24f).coerceAtLeast(120f))
        val panelHeight = ADDRESS_DIALOG_HEIGHT.coerceAtMost((screenHeight - 24f).coerceAtLeast(96f))
        val keyboardTop = addressKeyboard.getBounds()?.top ?: screenHeight
        val availableTop = 12f
        val availableBottom = (keyboardTop - 12f).coerceAtLeast(availableTop + panelHeight)
        val maxPanelTop = (screenHeight - panelHeight - 12f).coerceAtLeast(availableTop)
        val panelTop = ((availableTop + availableBottom - panelHeight) / 2f)
            .coerceIn(availableTop, maxPanelTop)
        val panelLeft = (screenWidth - panelWidth) / 2f
        addressDialogBounds.set(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight)

        val fieldTop = panelTop + 58f
        val fieldBottom = (fieldTop + 38f).coerceAtMost(panelTop + panelHeight - 42f)
        val gap = 8f
        val goWidth = 58f
        val cancelWidth = 76f
        val fieldLeft = panelLeft + 16f
        val fieldRight = addressDialogBounds.right - 16f - cancelWidth - gap - goWidth - gap
        addressFieldBounds.set(fieldLeft, fieldTop, fieldRight, fieldBottom)
        addressSubmitBounds.set(fieldRight + gap, fieldTop, fieldRight + gap + goWidth, fieldBottom)
        addressCancelBounds.set(addressSubmitBounds.right + gap, fieldTop, addressDialogBounds.right - 16f, fieldBottom)
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) { drawMinimized(canvas); return }
        viewportWidth = canvas.width.toFloat()
        viewportHeight = canvas.height.toFloat()
        val c = contentBounds
        navigationStopBounds.setEmpty()
        navigationBannerBounds.setEmpty()
        if (addressEntryVisible) {
            updateAddressKeyboardLayout()
            updateAddressLayout()
        }
        val showNavigationFooter = showsWindowedNavigationFooter()
        val labelSpace = when {
            !isFullscreen && (showNavigationFooter || showLocation || showCountry) -> 40f
            else -> 0f
        }
        val mapBottom = c.bottom - labelSpace
        val cx = c.centerX(); val cy = (c.top + mapBottom) / 2f
        val radius = (min(c.width(), mapBottom - c.top) * (if (isFullscreen) .42f else .5f) - 3f).coerceAtLeast(8f)
        val pendingLabels = mutableListOf<PoiLabel>()
        clip.reset(); clip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.withClip(clip) {
            position?.let {
                if (mode == MapViewMode.TOP_DOWN) {
                    drawTopDown(this, it, cx, cy, radius, pendingLabels)
                } else {
                    drawProjected(this, it, c)
                }
            } ?: drawText(
                "Waiting for location",
                cx,
                cy,
                label.apply { textAlign = Paint.Align.CENTER }
            )
        }
        canvas.drawCircle(cx, cy, radius, rim)
        if (position != null && mode == MapViewMode.TOP_DOWN) drawNorthIndicator(canvas, cx, cy, radius)
        position?.let {
            drawOffscreenImportantPois(canvas, it, cx, cy, radius, pendingLabels)
            drawOffscreenNearestPois(canvas, it, cx, cy, radius, pendingLabels)
            drawOffscreenNavigationDestination(canvas, it, cx, cy, radius)
        }
        drawPoiLabels(canvas, pendingLabels)
        if (!addressEntryVisible) {
            if (showNavigationFooter) drawWindowedNavigationFooter(canvas, c, cx)
            else drawLocationFooter(canvas, c, cx)
        }
        if (isFullscreen) drawControls(canvas, c)
        if (showPoiConfig) drawPoiConfigPanel(canvas)
        else if (showOptions) drawOptions(canvas)
        if (isFullscreen && navigationState.status != NavigationStatus.INACTIVE &&
            navigationState.choices.isEmpty() && !addressEntryVisible) {
            drawNavigationBanner(canvas, c)
        } else if (showNavigationFooter && isHovering() &&
            !addressEntryVisible && navigationState.choices.isEmpty()) {
            drawWindowedCancelNavigation(canvas, c, mapBottom)
        }
        if (shouldShowBorder()) canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        if (shouldShowBorderButtons()) { drawBorderButtons(canvas); drawResizeHandle(canvas) }
    }

    /** Draws modal destination UI head-locked at screen scale, above dashboard transforms. */
    override fun drawScreenOverlay(canvas: Canvas) {
        viewportWidth = canvas.width.toFloat()
        viewportHeight = canvas.height.toFloat()
        if (addressEntryVisible) {
            updateAddressKeyboardLayout()
            updateAddressLayout()
        }
        drawNavigationChoices(canvas)
        if (addressEntryVisible) drawAddressEntry(canvas)
        addressKeyboard.draw(canvas)
    }

    private fun drawTopDown(canvas: Canvas, loc: Location, cx: Float, cy: Float, radius: Float, pendingLabels: MutableList<PoiLabel>) {
        val mapRadiusM = displayRadiusM()
        for (r in roads) drawRoad(canvas, r, loc, cx, cy, radius, mapRadiusM)
        drawNavigationRoute(canvas, loc, cx, cy, radius, mapRadiusM)
        for (p in poiSelection.visibleInMap.filter { distance(loc, it.point) <= mapRadiusM }) {
            val q = mapPoint(loc, p.point, cx, cy, radius, mapRadiusM) ?: continue
            val important = p.tier != MapPoiTier.NORMAL
            if (p.tier != MapPoiTier.STATION) {
                canvas.drawCircle(q.x, q.y, if (important) 5f else 3f, poiPaint)
            }
            pendingLabels += PoiLabel(p, q, important)
        }
        // Screen-stable forward pointer, identical in orientation to MapTest:
        // geometry rotates around it while forward remains at the screen top.
        val marker = Path().apply { moveTo(cx, cy-11); lineTo(cx-7,cy+8); lineTo(cx,cy+4); lineTo(cx+7,cy+8); close() }
        canvas.drawPath(marker, user)
    }

    private fun drawNorthIndicator(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val angle = Math.toRadians(compassRelativeBearingDeg(0f, heading).toDouble())
        val markerRadius = (radius - 10f).coerceAtLeast(0f)
        val x = cx + sin(angle).toFloat() * markerRadius
        val y = cy - cos(angle).toFloat() * markerRadius
        val baseline = y - (northLabel.ascent() + northLabel.descent()) / 2f
        canvas.drawText("N", x, baseline, northLabel)
    }

    private fun drawRoad(canvas: Canvas, r: MapRoad, loc: Location, cx: Float, cy: Float, radius: Float, mapRadiusM: Float) {
        val p = Path(); var started = false
        for (point in r.points) { val q = mapPoint(loc, point, cx, cy, radius, mapRadiusM) ?: continue; if (!started) { p.moveTo(q.x,q.y); started=true } else p.lineTo(q.x,q.y) }
        if (!started) return
        val isMajor = r.highway in setOf("motorway","trunk","primary","secondary")
        halo.strokeWidth = if (isMajor) 10f else 6f; canvas.drawPath(p, halo)
        val paint = if (isMajor) major else road; paint.strokeWidth = if (isMajor) 4f else 2.5f; canvas.drawPath(p, paint)
    }

    private fun mapPoint(loc: Location, point: MapGeoPoint, cx: Float, cy: Float, radius: Float, mapRadiusM: Float = zoom.radiusM): PointF? {
        val d = distance(loc, point); if (d > mapRadiusM * 1.2f) return null
        val relative = Math.toRadians(compassRelativeBearingDeg(bearing(loc, point), heading).toDouble())
        val scale = radius / mapRadiusM
        return PointF(
            cx + sin(relative).toFloat() * d * scale,
            cy - cos(relative).toFloat() * d * scale
        )
    }

    /** Navigation never changes the user's map zoom; off-screen destinations use an edge arrow. */
    private fun displayRadiusM(): Float = zoom.radiusM.coerceAtLeast(1f)

    /** Route is painted after roads and before POIs/labels so guidance remains legible. */
    private fun drawNavigationRoute(
        canvas: Canvas,
        loc: Location,
        cx: Float,
        cy: Float,
        radius: Float,
        mapRadiusM: Float
    ) {
        val route = navigationState.route ?: return
        fun drawSegment(points: List<GeoPoint>, haloPaint: Paint, routePaint: Paint) {
            if (points.isEmpty()) return
            val path = Path()
            var started = false
            points.forEach { point ->
                val q = mapPoint(loc, MapGeoPoint(point.lat, point.lon), cx, cy, radius, mapRadiusM)
                    ?: return@forEach
                if (!started) {
                    path.moveTo(q.x, q.y)
                    started = true
                } else {
                    path.lineTo(q.x, q.y)
                }
            }
            if (started) {
                haloPaint.strokeWidth = 13f
                routePaint.strokeWidth = 6f
                canvas.drawPath(path, haloPaint)
                canvas.drawPath(path, routePaint)
            }
        }
        val guidance = navigationState.guidance
        if (guidance != null) {
            drawSegment(guidance.completedPolyline, navigationCompletedRouteHalo, navigationCompletedRoute)
            drawSegment(guidance.remainingPolyline, navigationRouteHalo, navigationRoute)
        } else {
            drawSegment(route.polyline.points, navigationRouteHalo, navigationRoute)
        }
        val destination = navigationState.destination?.point
            ?.let { mapPoint(loc, it, cx, cy, radius, mapRadiusM) }
        destination?.let {
            canvas.drawCircle(it.x, it.y, 9f, navigationRouteHalo.apply { style = Paint.Style.FILL })
            canvas.drawCircle(it.x, it.y, 5f, navigationDestination)
            navigationRouteHalo.style = Paint.Style.STROKE
        }
    }

    private fun drawProjected(canvas: Canvas, loc: Location, c: RectF) {
        projection.update(
            c.width().toInt(),
            c.height().toInt(),
            pose,
            MapGeoPoint(loc.latitude, loc.longitude)
        )
        for (r in roads) {
            val path = Path(); var started=false
            for (point in r.points) {
                if (distance(loc, point) > zoom.radiusM) continue
                val projected = projection.projectGeo(point) ?: continue
                val sx = c.left + projected.x
                val sy = c.top + projected.y
                if(!started){path.moveTo(sx,sy);started=true}else path.lineTo(sx,sy)
            }
            if(started){road.strokeWidth=3f;canvas.drawPath(path,road)}
        }
        navigationState.route?.let { route ->
            fun drawSegment(points: List<GeoPoint>, haloPaint: Paint, routePaint: Paint) {
                if (points.isEmpty()) return
                val path = Path()
                var started = false
                points.forEach { point ->
                    val projected = projection.projectGeo(MapGeoPoint(point.lat, point.lon)) ?: return@forEach
                    val sx = c.left + projected.x
                    val sy = c.top + projected.y
                    if (!started) {
                        path.moveTo(sx, sy)
                        started = true
                    } else {
                        path.lineTo(sx, sy)
                    }
                }
                if (started) {
                    haloPaint.strokeWidth = 13f
                    routePaint.strokeWidth = 6f
                    canvas.drawPath(path, haloPaint)
                    canvas.drawPath(path, routePaint)
                }
            }
            val guidance = navigationState.guidance
            if (guidance != null) {
                drawSegment(guidance.completedPolyline, navigationCompletedRouteHalo, navigationCompletedRoute)
                drawSegment(guidance.remainingPolyline, navigationRouteHalo, navigationRoute)
            } else {
                drawSegment(route.polyline.points, navigationRouteHalo, navigationRoute)
            }
            navigationState.destination?.point?.let { destination ->
                projection.projectGeo(destination)?.let { projected ->
                    canvas.drawCircle(c.left + projected.x, c.top + projected.y, 6f, navigationDestination)
                }
            }
        }
        canvas.drawCircle(c.centerX(), c.bottom-22f, 7f, user)
    }

    private fun drawOffscreenNavigationDestination(
        canvas: Canvas,
        loc: Location,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val destination = navigationState.destination?.point ?: return
        if (!destinationIndicatorIsOffscreen(distance(loc, destination), displayRadiusM())) return
        val angle = Math.toRadians(
            compassRelativeBearingDeg(bearing(loc, destination), heading).toDouble()
        )
        val directionX = sin(angle).toFloat()
        val directionY = -cos(angle).toFloat()
        val perpendicularX = -directionY
        @Suppress("UnnecessaryVariable", "RedundantSuppression") val perpendicularY = directionX
        val tipX = cx + directionX * (radius - 4f)
        val tipY = cy + directionY * (radius - 4f)
        val baseX = tipX - directionX * 17f
        val baseY = tipY - directionY * 17f
        val arrow = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseX + perpendicularX * 8f, baseY + perpendicularY * 8f)
            lineTo(baseX - perpendicularX * 8f, baseY - perpendicularY * 8f)
            close()
        }
        navigationRouteHalo.strokeWidth = 5f
        canvas.drawPath(arrow, navigationRouteHalo)
        canvas.drawPath(arrow, navigationDestination)
    }

    private fun drawOffscreenImportantPois(canvas: Canvas, loc: Location, cx: Float, cy: Float, radius: Float, pendingLabels: MutableList<PoiLabel>) {
        val mapRadiusM = displayRadiusM()
        for (poi in poiSelection.important.filter { distance(loc, it.point) > mapRadiusM }) {
            val a = Math.toRadians(compassRelativeBearingDeg(bearing(loc, poi.point), heading).toDouble())
            val ax=cx+ sin(a).toFloat()*(radius-4); val ay=cy- cos(a).toFloat()*(radius-4)
            val arrow=
                Path().apply { moveTo(ax,ay); lineTo(ax+ sin(a + 2.5).toFloat()*13,ay- cos(a + 2.5).toFloat()*13); lineTo(ax+ sin(
                    a - 2.5
                ).toFloat()*13,ay- cos(a - 2.5).toFloat()*13); close() }
            canvas.drawPath(arrow,poiPaint)
            val labelAnchor = if (poi.tier == MapPoiTier.STATION) {
                PointF(ax - sin(a).toFloat() * 18f, ay + cos(a).toFloat() * 18f)
            } else {
                PointF(ax, ay)
            }
            pendingLabels += PoiLabel(poi, labelAnchor, true)
        }
    }

    /**
     * Nearest mode may deliberately select a POI beyond the current map zoom (for example a
     * supermarket five kilometres away while the map is zoomed to 320 m). Keep those selected
     * results discoverable with the same directional treatment used by important markers.
     */
    private fun drawOffscreenNearestPois(
        canvas: Canvas,
        loc: Location,
        cx: Float,
        cy: Float,
        radius: Float,
        pendingLabels: MutableList<PoiLabel>
    ) {
        if (poiDisplayConfig.isDefaultMode || poiDisplayConfig.balanceCategories) return
        val mapRadiusM = displayRadiusM()
        for (poi in poiSelection.displayed.filter { distance(loc, it.point) > mapRadiusM }) {
            val a = Math.toRadians(compassRelativeBearingDeg(bearing(loc, poi.point), heading).toDouble())
            val ax = cx + sin(a).toFloat() * (radius - 4f)
            val ay = cy - cos(a).toFloat() * (radius - 4f)
            val arrow = Path().apply {
                moveTo(ax, ay)
                lineTo(ax + sin(a + 2.5).toFloat() * 13f, ay - cos(a + 2.5).toFloat() * 13f)
                lineTo(ax + sin(a - 2.5).toFloat() * 13f, ay - cos(a - 2.5).toFloat() * 13f)
                close()
            }
            canvas.drawPath(arrow, poiPaint)
            pendingLabels += PoiLabel(poi, PointF(ax, ay), poi == poiSelection.immediate)
        }
    }

    private fun drawPoiLabels(canvas: Canvas, pending: List<PoiLabel>) {
        val occupied = mutableListOf<RectF>()
        for (item in pending.sortedByDescending { it.important }) {
            val isStation = item.poi.tier == MapPoiTier.STATION
            if (isStation) {
                drawTrainIcon(canvas, item.anchor.x, item.anchor.y)
                occupied.add(RectF(
                    item.anchor.x - 11f,
                    item.anchor.y - 13f,
                    item.anchor.x + 11f,
                    item.anchor.y + 14f
                ))
            }
            val text = item.poi.name?.takeIf(String::isNotBlank)
                ?: item.poi.category.substringAfter('=').replace('_', ' ').replaceFirstChar { it.uppercase() }
            label.textAlign = Paint.Align.LEFT
            label.textSize = if (item.important) 14f else 12f
            val maxWidth = (canvas.width - 8).coerceAtLeast(1).toFloat()
            while (label.measureText(text) > maxWidth && label.textSize > 8f) label.textSize -= 1f
            val width = label.measureText(text)
            val fm = label.fontMetrics
            val height = fm.descent - fm.ascent
            val markerGap = if (isStation) 13f else 7f
            val xOffsets = listOf(markerGap, -width - markerGap, markerGap, -width - markerGap, -width / 2f)
            val yOffsets = listOf(-4f, -4f, height + 8f, height + 8f, -12f)
            var chosen: RectF? = null
            var baseline = item.anchor.y
            for (index in xOffsets.indices) {
                val left = (item.anchor.x + xOffsets[index]).coerceIn(4f, (canvas.width - width - 4f).coerceAtLeast(4f))
                val candidateBaseline = (item.anchor.y + yOffsets[index]).coerceIn(4f - fm.ascent, canvas.height - 4f - fm.descent)
                val rect = RectF(
                    left - 2f,
                    candidateBaseline + fm.ascent - 2f,
                    left + width + 2f,
                    candidateBaseline + fm.descent + 2f
                )
                if (occupied.none { RectF.intersects(it, rect) }) { chosen = rect; baseline = candidateBaseline; break }
            }
            if (chosen == null) {
                var candidateBaseline = (item.anchor.y - 4f).coerceIn(4f - fm.ascent, canvas.height - 4f - fm.descent)
                val left = (item.anchor.x + 7f).coerceIn(4f, (canvas.width - width - 4f).coerceAtLeast(4f))
                var attempt = 0
                while (attempt < pending.size) {
                    val rect = RectF(
                        left - 2f,
                        candidateBaseline + fm.ascent - 2f,
                        left + width + 2f,
                        candidateBaseline + fm.descent + 2f
                    )

                    if (occupied.none { RectF.intersects(it, rect) }) {
                        chosen = rect
                        baseline = candidateBaseline
                        break
                    }

                    candidateBaseline =
                        (candidateBaseline + height + 5f)
                            .coerceAtMost(canvas.height - 4f - fm.descent)

                    attempt++
                }
                if (chosen == null) { baseline = candidateBaseline; chosen =
                    RectF(left, baseline + fm.ascent, left + width, baseline + fm.descent)
                }
            }
            occupied += chosen
            canvas.drawText(text, chosen.left + 2f, baseline, label)
        }
    }

    /** Front-facing train silhouette designed to remain legible around 18x22 px. */
    private fun drawTrainIcon(canvas: Canvas, cx: Float, cy: Float) {
        val body = RectF(cx - 8f, cy - 10f, cx + 8f, cy + 7f)
        canvas.drawRoundRect(body, 3f, 3f, trainBody)
        canvas.drawRoundRect(body, 3f, 3f, trainOutline)
        canvas.drawRect(cx - 5.5f, cy - 7f, cx - 0.8f, cy - 2f, trainDetail)
        canvas.drawRect(cx + 0.8f, cy - 7f, cx + 5.5f, cy - 2f, trainDetail)
        canvas.drawRect(cx - 5.5f, cy + 1f, cx + 5.5f, cy + 3f, trainDetail)
        canvas.drawCircle(cx - 5f, cy + 8f, 2.3f, trainDetail)
        canvas.drawCircle(cx + 5f, cy + 8f, 2.3f, trainDetail)
        trainOutline.strokeWidth = 2f
        canvas.drawLine(cx - 7f, cy + 12f, cx + 7f, cy + 12f, trainOutline)
        canvas.drawLine(cx - 5f, cy + 9f, cx - 8f, cy + 12f, trainOutline)
        canvas.drawLine(cx + 5f, cy + 9f, cx + 8f, cy + 12f, trainOutline)
    }
    private fun drawLocationFooter(canvas: Canvas, c: RectF, cx: Float) {
        val debugBaseline = c.bottom - 3f
        val locationBaseline = debugBaseline - 18f

        val place = locationLabel
            .take(28)
            .takeIf { showLocation && it.isNotBlank() }
            .orEmpty()

        val flag = countryCode
            .takeIf { showCountry && it.length == 2 }
            ?.let(WeatherWidget.Companion::countryCodeToFlag)
            .orEmpty()

        // Existing town and nationality line
        if (place.isNotEmpty() || flag.isNotEmpty()) {
            label.textAlign = Paint.Align.LEFT
            label.textSize = 14f
            val placeWidth = label.measureText(place)

            label.textSize = 19f
            val flagWidth = label.measureText(flag)

            val gap = if (place.isNotEmpty() && flag.isNotEmpty()) 5f else 0f
            var drawX = cx - (placeWidth + gap + flagWidth) / 2f

            if (place.isNotEmpty()) {
                label.textSize = 14f
                canvas.drawText(place, drawX, locationBaseline, label)
                drawX += placeWidth + gap
            }

            if (flag.isNotEmpty()) {
                label.textSize = 19f
                canvas.drawText(flag, drawX, locationBaseline, label)
            }
        }
    }

    private fun showsWindowedNavigationFooter(): Boolean =
        !isFullscreen && navigationState.route != null && navigationState.destination != null

    private fun drawWindowedNavigationFooter(canvas: Canvas, c: RectF, cx: Float) {
        val destination = navigationState.destination?.label?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val distance = formatGuidanceDistance(navigationState.remainingDistanceMeters)
        val availableWidth = (c.width() - 12f).coerceAtLeast(20f)
        label.textAlign = Paint.Align.CENTER
        label.textSize = 12f
        val suffix = " · $distance"
        var name = destination.ifBlank { "Destination" }
        while (name.length > 3 && label.measureText(name + suffix) > availableWidth) {
            name = name.dropLast(1)
        }
        if (name != destination && name.length > 3) name = name.trimEnd() + "…"
        canvas.drawText(name + suffix, cx, c.bottom - 13f, label)
        label.textAlign = Paint.Align.LEFT
    }

    private fun drawWindowedCancelNavigation(canvas: Canvas, c: RectF, mapBottom: Float) {
        updateWindowedCancelNavigationBounds(c, mapBottom)
        drawAddressAction(canvas, navigationStopBounds, "Cancel navigation", addressCancelPaint)
    }

    private fun updateWindowedCancelNavigationBounds(c: RectF, mapBottom: Float) {
        val width = 126f.coerceAtMost((c.width() - 16f).coerceAtLeast(72f))
        val height = 30f
        navigationStopBounds.set(
            c.centerX() - width / 2f,
            (mapBottom - height - 8f).coerceAtLeast(c.top + 8f),
            c.centerX() + width / 2f,
            (mapBottom - 8f).coerceAtLeast(c.top + 8f + height)
        )
    }

    private fun drawAddressEntry(canvas: Canvas) {
        if (!addressEntryVisible) return
        if (addressDialogBounds.isEmpty) updateAddressLayout()

        // Draw a toast-like, centered modal at screen scale.  This keeps address entry usable
        // from a small windowed map as well as from the fullscreen map.
        canvas.drawRect(0f, 0f, viewportWidth, viewportHeight, addressScrim)
        canvas.drawRoundRect(addressDialogBounds, 12f, 12f, addressDialogBackground)
        canvas.drawRoundRect(addressDialogBounds, 12f, 12f, rim)

        label.textAlign = Paint.Align.CENTER
        label.textSize = 18f
        canvas.drawText("Go to", addressDialogBounds.centerX(), addressDialogBounds.top + 27f, label)
        label.textSize = 11f
        canvas.drawText("Type a destination address", addressDialogBounds.centerX(), addressDialogBounds.top + 45f, label)

        canvas.drawRoundRect(addressFieldBounds, 7f, 7f, addressFieldPaint)
        addressHintPaint.style = Paint.Style.STROKE
        canvas.drawRoundRect(addressFieldBounds, 7f, 7f, if (addressFocused) rim else addressHintPaint.apply { style = Paint.Style.STROKE })
        addressHintPaint.style = Paint.Style.FILL
        canvas.withClip(addressZone.bounds) {
            if (textZoneHost?.drawZone(this, addressZone) != true) {
                val text = addressText.takeIf { it.isNotBlank() } ?: "Destination address"
                val paint = if (addressText.isBlank()) addressHintPaint else label
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 13f
                drawText(text.take(36), addressFieldBounds.left + 8f, addressFieldBounds.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
        drawAddressAction(canvas, addressSubmitBounds, "Go", addressActionPaint)
        drawAddressAction(canvas, addressCancelBounds, "Cancel", addressCancelPaint)
        label.textAlign = Paint.Align.LEFT
    }

    private fun drawAddressAction(canvas: Canvas, bounds: RectF, text: String, paint: Paint) {
        canvas.drawRoundRect(bounds, 7f, 7f, if (bounds.contains(hoverX, hoverY)) chipHover else paint)
        label.textAlign = Paint.Align.CENTER
        label.textSize = 12f
        canvas.drawText(text, bounds.centerX(), bounds.centerY() - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun drawNavigationChoices(canvas: Canvas) {
        navigationChoiceBounds.clear()
        val choices = navigationState.choices.take(GeocodingRanker.MAX_CHOICES)
        navigationChoiceCancelBounds.setEmpty()
        if (choices.isEmpty()) return
        val c = RectF(
            0f,
            0f,
            viewportWidth.takeIf { it > 0f } ?: contentBounds.right,
            viewportHeight.takeIf { it > 0f } ?: contentBounds.bottom
        )
        val rowHeight = SEARCH_ROW_HEIGHT
        val panelWidth = (c.width() * ADDRESS_DIALOG_WIDTH_FRACTION)
            .coerceAtLeast(240f)
            .coerceAtMost((c.width() - 24f).coerceAtLeast(120f))
        val visibleCount = choices.size.coerceAtMost(VISIBLE_SEARCH_ROWS)
        val headerHeight = 31f
        val footerHeight = 36f
        val panelHeight = headerHeight + visibleCount * rowHeight + footerHeight
        val panelTop = (c.centerY() - panelHeight / 2f).coerceIn(
            c.top + 12f,
            (c.bottom - panelHeight - 12f).coerceAtLeast(c.top + 12f)
        )
        val panel = RectF(
            c.centerX() - panelWidth / 2f,
            panelTop,
            c.centerX() + panelWidth / 2f,
            panelTop + panelHeight
        )
        canvas.drawRoundRect(panel, 9f, 9f, optionsBackground)
        canvas.drawRoundRect(panel, 9f, 9f, rim)
        label.textAlign = Paint.Align.LEFT
        label.textSize = 12f
        canvas.drawText("Choose destination (${choices.size})", panel.left + 9f, panel.top + 20f, label)
        val first = (navigationChoiceScrollOffset / rowHeight).toInt()
            .coerceIn(0, (choices.size - visibleCount).coerceAtLeast(0))
        choices.drop(first).take(visibleCount).forEachIndexed { visibleIndex, choice ->
            val top = panel.top + headerHeight + visibleIndex * rowHeight
            val row = RectF(panel.left + 4f, top + 3f, panel.right - 10f, top + rowHeight - 3f)
            navigationChoiceBounds += row
            canvas.drawRoundRect(row, 6f, 6f, if (row.contains(hoverX, hoverY)) chipHover else chip)
            label.textAlign = Paint.Align.LEFT
            label.textSize = 11f
            val prefix = "${first + visibleIndex + 1}. "
            canvas.drawText(prefix + choice.label.take(42), row.left + 7f, row.top + 18f, label)
            label.textSize = 9f
            val detail = choice.candidate.addressText.orEmpty().ifBlank { "Destination" }.take(52)
            val distance = if (choice.proximityMeters < 1_000) "${choice.proximityMeters.roundToInt()} m"
                else String.format(Locale.getDefault(), "%.1f km", choice.proximityMeters / 1_000.0)
            canvas.drawText("$distance  ·  $detail", row.left + 7f, row.bottom - 8f, label)
        }
        if (choices.size > visibleCount) {
            val track = RectF(
                panel.right - 7f, panel.top + headerHeight + 4f, panel.right - 4f,
                panel.top + headerHeight + visibleCount * rowHeight - 4f
            )
            canvas.drawRoundRect(track, 2f, 2f, addressFieldPaint)
            val fraction = visibleCount.toFloat() / choices.size
            val thumbH = track.height() * fraction
            val progress = first.toFloat() / (choices.size - visibleCount)
            canvas.drawRoundRect(
                RectF(
                    track.left, track.top + (track.height() - thumbH) * progress,
                    track.right, track.top + (track.height() - thumbH) * progress + thumbH
                ), 2f, 2f, chipHover)
        }
        navigationChoiceCancelBounds.set(panel.right - 67f, panel.bottom - 30f, panel.right - 8f, panel.bottom - 6f)
        drawAddressAction(canvas, navigationChoiceCancelBounds, "Cancel", addressCancelPaint)
        label.textSize = 8f
        label.textAlign = Paint.Align.LEFT
        val footer = searchAttribution.replace(Regex("\\s+"), " ").ifBlank { "Destination search" }.take(55)
        canvas.drawText(footer, panel.left + 8f, panel.bottom - 13f, label)
        label.textAlign = Paint.Align.LEFT
    }

    private fun drawNavigationBanner(canvas: Canvas, c: RectF) {
        val compact = !isFullscreen
        val top = c.top + if (compact) 2f else 8f
        val bottom = top + if (compact) 66f else 82f
        navigationBannerBounds.set(c.left + 6f, top, c.right - 6f, bottom)
        canvas.drawRoundRect(navigationBannerBounds, 9f, 9f, navigationBannerBackground)
        val canStop = navigationState.status in setOf(
            NavigationStatus.SEARCHING,
            NavigationStatus.CHOOSING,
            NavigationStatus.RESOLVING,
            NavigationStatus.CALCULATING,
            NavigationStatus.RECALCULATING,
            NavigationStatus.ACTIVE,
            NavigationStatus.DEGRADED,
            NavigationStatus.ARRIVED
        )
        val stopWidth = if (canStop) 58f else 0f
        if (canStop) {
            navigationStopBounds.set(
                navigationBannerBounds.right - stopWidth - 6f,
                navigationBannerBounds.top + 7f,
                navigationBannerBounds.right - 6f,
                navigationBannerBounds.bottom - 7f
            )
            drawAddressAction(canvas, navigationStopBounds, "Stop", addressCancelPaint)
        } else {
            navigationStopBounds.setEmpty()
        }
        label.textAlign = Paint.Align.LEFT
        label.textSize = if (compact) 11f else 15f
        val bannerText = navigationState.toCompactBannerText()
        val textRight = if (canStop) navigationStopBounds.left - 7f else navigationBannerBounds.right - 8f
        val textWidth = (textRight - navigationBannerBounds.left - 16f).coerceAtLeast(20f)
        fun fit(text: String, size: Float): String {
            label.textSize = size
            if (label.measureText(text) <= textWidth) return text
            var value = text
            while (value.length > 3 && label.measureText("$value…") > textWidth) value = value.dropLast(1)
            return "$value…"
        }
        canvas.drawText(
            fit(bannerText.title.replace(Regex("\\s+"), " ").trim(), if (compact) 11f else 15f),
            navigationBannerBounds.left + 9f,
            navigationBannerBounds.top + if (compact) 16f else 20f,
            label
        )

        label.textSize = if (compact) 9f else 12f
        canvas.drawText(
            fit(bannerText.maneuverLine, if (compact) 9f else 12f),
            navigationBannerBounds.left + 9f,
            navigationBannerBounds.top + if (compact) 34f else 45f,
            label
        )
        label.textSize = if (compact) 9f else 11f
        canvas.drawText(
            fit(bannerText.progressLine, if (compact) 9f else 11f),
            navigationBannerBounds.left + 9f,
            navigationBannerBounds.bottom - if (compact) 10f else 13f,
            label
        )
        label.textAlign = Paint.Align.LEFT
    }

    private fun updatePoiSelection() {
        val loc = position ?: return
        poiSelection = poiSelector.select(
            MapGeoPoint(loc.latitude, loc.longitude),
            pois,
            poiDisplayConfig,
            zoom.radiusM
        )
    }

    private data class PoiLabel(val poi: MapPoi, val anchor: PointF, val important: Boolean)

    private fun drawControls(canvas: Canvas, c: RectF) {
        val h = 38f
        val gap = 10f
        val totalWidth = (c.width() - 16f).coerceAtLeast(4 * 96f + 3 * gap)
        val w = ((totalWidth - 3 * gap) / 4f).coerceAtMost((c.width() - 3 * gap) / 4f)
        val left = c.centerX() - (w * 4f + gap * 3f) / 2f
        val top = c.bottom - h
        goToChip.set(left, top, left + w, c.bottom)
        settingsChip.set(goToChip.right + gap, top, goToChip.right + gap + w, c.bottom)
        viewChip.set(settingsChip.right + gap, top, settingsChip.right + gap + w, c.bottom)
        zoomChip.set(viewChip.right + gap, top, viewChip.right + gap + w, c.bottom)
        fun draw(rect: RectF, text: String) {
            canvas.drawRoundRect(rect, 10f, 10f, if (rect.contains(hoverX, hoverY)) chipHover else chip)
            label.textAlign = Paint.Align.CENTER
            label.textSize = 13f
            canvas.drawText(text, rect.centerX(), rect.centerY() - (label.ascent() + label.descent()) / 2f, label)
        }
        draw(goToChip, "Go To")
        draw(settingsChip, "Settings")
        draw(viewChip, if (mode == MapViewMode.TOP_DOWN) "View: Top down" else "View: Projected")
        draw(zoomChip, "Zoom: ${zoom.label}")
        label.textAlign = Paint.Align.LEFT
    }

    private fun drawOptions(canvas: Canvas) {
        val hasPoiOption = isFullscreen
        val menuWidth = (if (hasPoiOption) 230f else 170f).coerceAtMost((widgetWidth - 20f).coerceAtLeast(120f))
        val rowH = 38f
        val rowCount = if (hasPoiOption) 3 else 2
        val headerH = 28f
        val menuTop = contentBounds.top + 8f
        optionsBounds.set(
            widgetBounds.centerX() - menuWidth / 2f,
            menuTop,
            widgetBounds.centerX() + menuWidth / 2f,
            menuTop + headerH + 8f + rowCount * rowH
        )
        label.textAlign = Paint.Align.LEFT
        label.textSize = 16f
        canvas.drawText("Settings", optionsBounds.left + 12f, optionsBounds.top + 21f, label)
        val rowsTop = optionsBounds.top + headerH
        locationOptionBounds.set(optionsBounds.left + 6f, rowsTop, optionsBounds.right - 6f, rowsTop + rowH)
        countryOptionBounds.set(optionsBounds.left + 6f, locationOptionBounds.bottom, optionsBounds.right - 6f, locationOptionBounds.bottom + rowH)
        if (hasPoiOption) {
            poiOptionBounds.set(optionsBounds.left + 6f, countryOptionBounds.bottom, optionsBounds.right - 6f, countryOptionBounds.bottom + rowH)
        } else {
            poiOptionBounds.set(-1f, -1f, -1f, -1f)
        }
        canvas.drawRoundRect(optionsBounds, 10f, 10f, optionsBackground)
        canvas.drawRoundRect(optionsBounds, 10f, 10f, rim)
        fun row(bounds: RectF, name: String, enabled: Boolean) {
            canvas.drawRoundRect(bounds, 7f, 7f, if (bounds.contains(hoverX, hoverY)) chipHover else chip)
            label.textSize = 15f
            label.textAlign = Paint.Align.LEFT
            canvas.drawText(name, bounds.left + 9f, bounds.centerY() + 5f, label)
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(if (enabled) "On" else "Off", bounds.right - 9f, bounds.centerY() + 5f, label)
        }
        row(locationOptionBounds, "Location", showLocation)
        row(countryOptionBounds, "Country", showCountry)
        if (hasPoiOption) row(poiOptionBounds, "POIs", true)
        label.textAlign = Paint.Align.LEFT
    }

    private fun openPoiConfig() {
        // The previous voice profile used category balancing for compatibility. The settings
        // menu's Nearest mode is intentionally the simpler pure-distance profile requested here.
        nearestCategorySelectionStartsFresh = false
        pendingPoiConfig = if (poiDisplayConfig.isDefaultMode) {
            poiDisplayConfig
        } else {
            poiDisplayConfig.asNearestMode()
        }
        poiScrollOffset = 0f
        poiContentHeight = 0f
        showOptions = false
        showPoiConfig = true
        invalidate?.invoke()
    }

    private fun drawPoiConfigPanel(canvas: Canvas) {
        val c = contentBounds
        val panelLeft = c.left + 18f
        val panelRight = c.right - 18f
        val panelTop = c.top + 10f
        val panelBottom = (panelTop + 456f).coerceAtMost(c.bottom - 8f)
        poiConfigBounds.set(panelLeft, panelTop, panelRight, panelBottom)
        canvas.drawRoundRect(poiConfigBounds, 12f, 12f, optionsBackground)
        canvas.drawRoundRect(poiConfigBounds, 12f, 12f, rim)

        label.textAlign = Paint.Align.LEFT
        label.textSize = 17f
        canvas.drawText("Configure map POIs", panelLeft + 14f, panelTop + 23f, label)
        label.textSize = 11f
        canvas.drawText("Scroll with two fingers to see all algorithm settings", panelLeft + 14f, panelTop + 39f, label)

        val modeTop = panelTop + 46f
        val modeWidth = (poiConfigBounds.width() - 30f) / 2f
        poiModeDefaultBounds.set(panelLeft + 12f, modeTop, panelLeft + 12f + modeWidth, modeTop + 28f)
        poiModeNearestBounds.set(poiModeDefaultBounds.right + 6f, modeTop, panelRight - 12f, modeTop + 28f)
        drawPoiModeButton(canvas, poiModeDefaultBounds, "Default mode", pendingPoiConfig.isDefaultMode)
        drawPoiModeButton(canvas, poiModeNearestBounds, "Nearest mode", !pendingPoiConfig.isDefaultMode)

        val viewportTop = modeTop + 36f
        val viewportBottom = panelBottom - 52f
        poiImportantCategoryBounds.clear()
        poiNormalCategoryBounds.clear()
        poiNearestCategoryBounds.clear()
        poiImportantRadiusTracks.clear()
        // Only controls drawn for the current mode may remain interactive. The nearest-mode
        // tracks can otherwise overlap the fixed action row after switching to Default mode.
        poiNormalCountTrack.setEmpty()
        poiClosestToggleBounds.setEmpty()
        poiClosestRadiusTrack.setEmpty()
        poiNearestRadiusTrack.setEmpty()
        poiNearestCountTrack.setEmpty()
        canvas.withClip(panelLeft + 6f, viewportTop, panelRight - 6f, viewportBottom) {
            var y = viewportTop + 18f - poiScrollOffset
            y = if (pendingPoiConfig.isDefaultMode) {
                drawDefaultPoiSettings(this, panelLeft, panelRight, y)
            } else {
                drawNearestPoiSettings(this, panelLeft, panelRight, y)
            }
            poiContentHeight = y + poiScrollOffset + 14f
        }

        val maxScroll = (poiContentHeight - viewportBottom).coerceAtLeast(0f)
        poiScrollOffset = poiScrollOffset.coerceIn(0f, maxScroll)
        if (maxScroll > 0f) {
            val track = RectF(panelRight - 5f, viewportTop, panelRight - 2f, viewportBottom)
            canvas.drawRoundRect(track, 2f, 2f, chip)
            val thumbHeight = (track.height() * (viewportBottom - viewportTop) / poiContentHeight).coerceAtLeast(24f)
            val thumbTop = track.top + (track.height() - thumbHeight) * (poiScrollOffset / maxScroll)
            canvas.drawRoundRect(RectF(track.left, thumbTop, track.right, thumbTop + thumbHeight), 2f, 2f, poiPaint)
        }

        val actionTop = panelBottom - 44f
        val actionGap = 8f
        val actionWidth = (poiConfigBounds.width() - 24f - actionGap * 2f) / 3f
        val actionLeft = panelLeft + 12f
        val actionRight = panelRight - 12f
        poiDefaultBounds.set(actionLeft, actionTop, actionLeft + actionWidth, actionTop + 36f)
        poiCancelBounds.set(
            poiDefaultBounds.right + actionGap,
            actionTop,
            poiDefaultBounds.right + actionGap + actionWidth,
            actionTop + 36f
        )
        poiApplyBounds.set(actionRight - actionWidth, actionTop, actionRight, actionTop + 36f)
        drawPoiAction(canvas, poiDefaultBounds, "Reset default")
        drawPoiAction(canvas, poiCancelBounds, "Cancel")
        drawPoiAction(canvas, poiApplyBounds, "Apply")
        label.textAlign = Paint.Align.LEFT
    }

    private fun drawDefaultPoiSettings(canvas: Canvas, left: Float, right: Float, startY: Float): Float {
        var y = startY
        label.textAlign = Paint.Align.LEFT
        label.textSize = 14f
        canvas.drawText("Default algorithm", left + 14f, y, label)
        label.textSize = 10f
        canvas.drawText("I = one nearest important marker; N = ordinary marker pool; off = hidden", left + 14f, y + 16f, label)
        y += 38f

        val importantLeft = right - 126f
        val normalLeft = right - 66f
        label.textSize = 10f
        label.textAlign = Paint.Align.CENTER
        canvas.drawText("Important", importantLeft + 27f, y - 7f, label)
        canvas.drawText("Normal", normalLeft + 27f, y - 7f, label)
        label.textAlign = Paint.Align.LEFT
        val categoryLabelLeft = left + 18f
        val categoryLabelMaxWidth = (importantLeft - categoryLabelLeft - 10f).coerceAtLeast(1f)
        MapPoiCategoryCatalog.definitions.forEach { definition ->
            val row = RectF(left + 10f, y, right - 8f, y + 28f)
            label.textAlign = Paint.Align.LEFT
            label.textSize = 11f
            var categoryLabel = definition.label
            while (label.measureText(categoryLabel) > categoryLabelMaxWidth && label.textSize > 8f) {
                label.textSize -= 0.5f
            }
            if (label.measureText(categoryLabel) > categoryLabelMaxWidth) {
                while (categoryLabel.length > 1 && label.measureText("$categoryLabel…") > categoryLabelMaxWidth) {
                    categoryLabel = categoryLabel.dropLast(1).trimEnd()
                }
                categoryLabel += "…"
            }
            canvas.drawText(categoryLabel, categoryLabelLeft, row.centerY() + 4f, label)
            val important = RectF(importantLeft, y + 2f, importantLeft + 54f, y + 26f)
            val normal = RectF(normalLeft, y + 2f, normalLeft + 54f, y + 26f)
            poiImportantCategoryBounds[definition.id] = important
            poiNormalCategoryBounds[definition.id] = normal
            drawPoiRoleButton(canvas, important, "I", definition.id in pendingPoiConfig.importantCategories)
            drawPoiRoleButton(canvas, normal, "N", definition.id in pendingPoiConfig.normalCategories)
            y += 31f
        }

        y += 8f
        label.textSize = 12f
        canvas.drawText("Important search distance", left + 14f, y, label)
        y += 17f
        val selectedImportant = MapPoiCategoryCatalog.definitions.filter {
            it.id in pendingPoiConfig.importantCategories
        }
        if (selectedImportant.isEmpty()) {
            label.textSize = 10f
            canvas.drawText("No important marker categories selected", left + 14f, y + 10f, label)
            y += 28f
        } else {
            selectedImportant.forEach { definition ->
                val radius = pendingPoiConfig.importantRadiiM[definition.id]
                    ?: if (definition.id == MapPoiCategoryCatalog.STATION) {
                        PoiDisplayConfig.DEFAULT_STATION_RADIUS_M
                    } else {
                        PoiDisplayConfig.DEFAULT_LOCAL_IMPORTANT_RADIUS_M
                    }
                label.textSize = 11f
                label.textAlign = Paint.Align.LEFT
                canvas.drawText(definition.label, left + 14f, y + 11f, label)
                label.textAlign = Paint.Align.RIGHT
                canvas.drawText("$radius m", right - 14f, y + 11f, label)
                val track = RectF(left + 126f, y + 18f, right - 14f, y + 29f)
                poiImportantRadiusTracks[definition.id] = track
                drawPoiTrack(canvas, track, radius, PoiDisplayConfig.MIN_IMPORTANT_RADIUS_M, PoiDisplayConfig.MAX_IMPORTANT_RADIUS_M)
                y += 39f
            }
        }

        label.textAlign = Paint.Align.LEFT
        label.textSize = 12f
        canvas.drawText("Normal markers maximum: ${pendingPoiConfig.normalMaxResults}", left + 14f, y + 13f, label)
        poiNormalCountTrack.set(left + 126f, y + 21f, right - 14f, y + 32f)
        drawPoiTrack(
            canvas,
            poiNormalCountTrack,
            pendingPoiConfig.normalMaxResults,
            PoiDisplayConfig.MIN_NORMAL_RESULTS,
            PoiDisplayConfig.MAX_NORMAL_RESULTS
        )
        y += 48f

        val closestText = "Closest normal POI: ${if (pendingPoiConfig.showClosest) "On" else "Off"}"
        poiClosestToggleBounds.set(left + 14f, y, right - 14f, y + 30f)
        drawPoiOptionButton(canvas, poiClosestToggleBounds, closestText, pendingPoiConfig.showClosest)
        y += 39f
        if (pendingPoiConfig.showClosest) {
            label.textSize = 12f
            label.textAlign = Paint.Align.LEFT
            canvas.drawText("Closest distance: ${pendingPoiConfig.closestRadiusM} m", left + 14f, y + 13f, label)
            poiClosestRadiusTrack.set(left + 126f, y + 21f, right - 14f, y + 32f)
            drawPoiTrack(
                canvas,
                poiClosestRadiusTrack,
                pendingPoiConfig.closestRadiusM,
                PoiDisplayConfig.MIN_RADIUS_M,
                PoiDisplayConfig.MAX_RADIUS_M
            )
            y += 48f
        }
        return y
    }

    private fun drawNearestPoiSettings(canvas: Canvas, left: Float, right: Float, startY: Float): Float {
        var y = startY
        label.textAlign = Paint.Align.LEFT
        label.textSize = 14f
        canvas.drawText("Nearest mode", left + 14f, y, label)
        label.textSize = 10f
        canvas.drawText("Tap a category to start a subset; nearest N results are shown", left + 14f, y + 16f, label)
        y += 38f

        val columns = 2
        val categoryWidth = (right - left - 28f) / columns
        val categoryRowHeight = 29f
        MapPoiCategoryCatalog.definitions.forEachIndexed { index, definition ->
            val row = index / columns
            val column = index % columns
            val bounds = RectF(
                left + 14f + column * categoryWidth,
                y + row * categoryRowHeight,
                left + 14f + (column + 1) * categoryWidth - 8f,
                y + row * categoryRowHeight + categoryRowHeight - 4f
            )
            poiNearestCategoryBounds[definition.id] = bounds
            drawPoiRoleButton(canvas, bounds, definition.label, definition.id in pendingPoiConfig.categories)
        }
        y += ((MapPoiCategoryCatalog.definitions.size + columns - 1) / columns) * categoryRowHeight + 12f

        label.textSize = 12f
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("Radius: ${pendingPoiConfig.radiusM} m", left + 14f, y + 13f, label)
        poiNearestRadiusTrack.set(left + 126f, y + 21f, right - 14f, y + 32f)
        drawPoiTrack(canvas, poiNearestRadiusTrack, pendingPoiConfig.radiusM, PoiDisplayConfig.MIN_RADIUS_M, PoiDisplayConfig.MAX_RADIUS_M)
        y += 48f

        canvas.drawText("Results: ${pendingPoiConfig.maxResults}", left + 14f, y + 13f, label)
        poiNearestCountTrack.set(left + 126f, y + 21f, right - 14f, y + 32f)
        drawPoiTrack(canvas, poiNearestCountTrack, pendingPoiConfig.maxResults, PoiDisplayConfig.MIN_RESULTS, PoiDisplayConfig.MAX_RESULTS)
        return y + 48f
    }

    private fun drawPoiTrack(canvas: Canvas, bounds: RectF, currentValue: Int, minValue: Int, maxValue: Int) {
        canvas.drawRoundRect(bounds, 7f, 7f, chip)
        val fraction = ((currentValue - minValue).toFloat() / (maxValue - minValue).toFloat()).coerceIn(0f, 1f)
        val knobX = bounds.left + fraction * bounds.width()
        canvas.drawCircle(knobX, bounds.centerY(), 8f, if (bounds.contains(hoverX, hoverY)) chipHover else poiPaint)
    }

    private fun drawPoiModeButton(canvas: Canvas, bounds: RectF, text: String, selected: Boolean) {
        canvas.drawRoundRect(bounds, 8f, 8f, poiButtonPaint(bounds, selected))
        label.textSize = 12f
        label.textAlign = Paint.Align.CENTER
        canvas.drawText(text, bounds.centerX(), bounds.centerY() - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun drawPoiRoleButton(canvas: Canvas, bounds: RectF, text: String, selected: Boolean) {
        canvas.drawRoundRect(bounds, 6f, 6f, poiButtonPaint(bounds, selected))
        val baseTextSize = if (text.length > 5) 10f else 11f
        label.textSize = baseTextSize
        val availableWidth = (bounds.width() - 14f).coerceAtLeast(1f)
        var fittedText = text
        while (label.measureText(fittedText) > availableWidth && label.textSize > 8f) {
            label.textSize -= 0.5f
        }
        if (label.measureText(fittedText) > availableWidth) {
            while (fittedText.length > 1 && label.measureText("$fittedText…") > availableWidth) {
                fittedText = fittedText.dropLast(1).trimEnd()
            }
            fittedText += "…"
        }
        val centered = text.length <= 2
        label.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        val textX = if (centered) bounds.centerX() else bounds.left + 7f
        canvas.drawText(fittedText, textX, bounds.centerY() - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun drawPoiOptionButton(canvas: Canvas, bounds: RectF, text: String, selected: Boolean) {
        canvas.drawRoundRect(bounds, 7f, 7f, poiButtonPaint(bounds, selected))
        label.textSize = 11f
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(text, bounds.left + 8f, bounds.centerY() - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun poiButtonPaint(bounds: RectF, selected: Boolean): Paint = when {
        selected && bounds.contains(hoverX, hoverY) -> chipSelectedHover
        selected -> chipSelected
        bounds.contains(hoverX, hoverY) -> chipHover
        else -> chip
    }


    private fun drawPoiAction(canvas: Canvas, bounds: RectF, text: String) {
        canvas.drawRoundRect(bounds, 8f, 8f, if (bounds.contains(hoverX, hoverY)) chipHover else chip)
        label.textSize = 14f
        label.textAlign = Paint.Align.CENTER
        canvas.drawText(text, bounds.centerX(), bounds.centerY() + 5f, label)
    }

    private fun handlePoiConfigTap(px: Float, py: Float) {
        if (!poiConfigBounds.contains(px, py)) {
            showPoiConfig = false
            invalidate?.invoke()
            return
        }
        // The fixed action row must win over any expanded slider hitbox near the bottom edge.
        if (poiCancelBounds.contains(px, py)) {
            // The panel edits a pending profile; Cancel leaves the active map configuration unchanged.
            pendingPoiConfig = poiDisplayConfig
            nearestCategorySelectionStartsFresh = false
            activePoiSlider = PoiSlider.NONE
            activePoiSliderCategory = null
            poiScrollOffset = 0f
            showPoiConfig = false
            invalidate?.invoke()
            return
        }
        if (poiDefaultBounds.contains(px, py)) {
            pendingPoiConfig = PoiDisplayConfig.DEFAULT
            poiScrollOffset = 0f
            invalidate?.invoke()
            return
        }
        if (poiApplyBounds.contains(px, py)) {
            applyPoiDisplayConfig(pendingPoiConfig)
            showPoiConfig = false
            invalidate?.invoke()
            return
        }
        if (poiModeDefaultBounds.contains(px, py)) {
            pendingPoiConfig = pendingPoiConfig.asDefaultMode()
            nearestCategorySelectionStartsFresh = false
            poiScrollOffset = 0f
            invalidate?.invoke()
            return
        }
        if (poiModeNearestBounds.contains(px, py)) {
            val enteringNearestFromDefault = pendingPoiConfig.isDefaultMode
            pendingPoiConfig = pendingPoiConfig.asNearestMode()
            nearestCategorySelectionStartsFresh = enteringNearestFromDefault
            poiScrollOffset = 0f
            invalidate?.invoke()
            return
        }
        if (!pendingPoiConfig.isDefaultMode) {
            poiNearestCategoryBounds.forEach { (category, bounds) ->
                if (bounds.contains(px, py)) {
                    val categories = if (nearestCategorySelectionStartsFresh) {
                        linkedSetOf(category)
                    } else {
                        pendingPoiConfig.categories.toMutableSet().apply {
                            if (category in this && size > 1) remove(category)
                            else if (category !in this) add(category)
                        }
                    }
                    nearestCategorySelectionStartsFresh = false
                    pendingPoiConfig = pendingPoiConfig.copy(
                        categories = categories,
                        state = PoiDisplayConfigState.CUSTOM,
                        mode = PoiDisplayMode.NEAREST
                    )
                    invalidate?.invoke()
                    return
                }
            }
        }
        if (pendingPoiConfig.isDefaultMode) {
            poiImportantCategoryBounds.forEach { (category, bounds) ->
                if (bounds.contains(px, py)) {
                    val important = pendingPoiConfig.importantCategories.toMutableSet()
                    val normal = pendingPoiConfig.normalCategories.toMutableSet()
                    if (category in important) {
                        important.remove(category)
                        pendingPoiConfig = pendingPoiConfig.copy(
                            importantCategories = important,
                            importantRadiiM = pendingPoiConfig.importantRadiiM - category,
                            state = PoiDisplayConfigState.CUSTOM,
                            mode = PoiDisplayMode.DEFAULT
                        )
                    } else {
                        important.add(category)
                        normal.remove(category)
                        val radii = pendingPoiConfig.importantRadiiM.toMutableMap()
                        radii.putIfAbsent(
                            category,
                            if (category == MapPoiCategoryCatalog.STATION) {
                                PoiDisplayConfig.DEFAULT_STATION_RADIUS_M
                            } else {
                                PoiDisplayConfig.DEFAULT_LOCAL_IMPORTANT_RADIUS_M
                            }
                        )
                        pendingPoiConfig = pendingPoiConfig.copy(
                            importantCategories = important,
                            normalCategories = normal,
                            importantRadiiM = radii,
                            state = PoiDisplayConfigState.CUSTOM,
                            mode = PoiDisplayMode.DEFAULT
                        )
                    }
                    invalidate?.invoke()
                    return
                }
            }
            poiNormalCategoryBounds.forEach { (category, bounds) ->
                if (bounds.contains(px, py)) {
                    val important = pendingPoiConfig.importantCategories.toMutableSet()
                    val normal = pendingPoiConfig.normalCategories.toMutableSet()
                    if (category in normal) {
                        normal.remove(category)
                    } else {
                        normal.add(category)
                        important.remove(category)
                    }
                    pendingPoiConfig = pendingPoiConfig.copy(
                        importantCategories = important,
                        normalCategories = normal,
                        importantRadiiM = pendingPoiConfig.importantRadiiM - category,
                        state = PoiDisplayConfigState.CUSTOM,
                        mode = PoiDisplayMode.DEFAULT
                    )
                    invalidate?.invoke()
                    return
                }
            }
            poiImportantRadiusTracks.forEach { (category, bounds) ->
                if (bounds.contains(px, py)) {
                    val radii = pendingPoiConfig.importantRadiiM.toMutableMap()
                    radii[category] = sliderValue(px, bounds, PoiDisplayConfig.MIN_IMPORTANT_RADIUS_M, PoiDisplayConfig.MAX_IMPORTANT_RADIUS_M, 50)
                    pendingPoiConfig = pendingPoiConfig.copy(
                        importantRadiiM = radii,
                        state = PoiDisplayConfigState.CUSTOM,
                        mode = PoiDisplayMode.DEFAULT
                    )
                    invalidate?.invoke()
                    return
                }
            }
            if (poiNormalCountTrack.contains(px, py)) {
                pendingPoiConfig = pendingPoiConfig.copy(
                    normalMaxResults = sliderValue(px, poiNormalCountTrack, PoiDisplayConfig.MIN_NORMAL_RESULTS, PoiDisplayConfig.MAX_NORMAL_RESULTS, 1),
                    state = PoiDisplayConfigState.CUSTOM,
                    mode = PoiDisplayMode.DEFAULT
                )
                invalidate?.invoke()
                return
            }
            if (poiClosestToggleBounds.contains(px, py)) {
                pendingPoiConfig = pendingPoiConfig.copy(
                    showClosest = !pendingPoiConfig.showClosest,
                    state = PoiDisplayConfigState.CUSTOM,
                    mode = PoiDisplayMode.DEFAULT
                )
                invalidate?.invoke()
                return
            }
            if (poiClosestRadiusTrack.contains(px, py)) {
                pendingPoiConfig = pendingPoiConfig.copy(
                    closestRadiusM = sliderValue(px, poiClosestRadiusTrack, PoiDisplayConfig.MIN_RADIUS_M, PoiDisplayConfig.MAX_RADIUS_M, 50),
                    state = PoiDisplayConfigState.CUSTOM,
                    mode = PoiDisplayMode.DEFAULT
                )
                invalidate?.invoke()
                return
            }
        } else {
            if (poiNearestRadiusTrack.contains(px, py)) {
                pendingPoiConfig = pendingPoiConfig.copy(
                    radiusM = sliderValue(px, poiNearestRadiusTrack, PoiDisplayConfig.MIN_RADIUS_M, PoiDisplayConfig.MAX_RADIUS_M, 50),
                    state = PoiDisplayConfigState.CUSTOM,
                    mode = PoiDisplayMode.NEAREST
                )
                invalidate?.invoke()
                return
            }
            if (poiNearestCountTrack.contains(px, py)) {
                pendingPoiConfig = pendingPoiConfig.copy(
                    maxResults = sliderValue(px, poiNearestCountTrack, PoiDisplayConfig.MIN_RESULTS, PoiDisplayConfig.MAX_RESULTS, 1),
                    state = PoiDisplayConfigState.CUSTOM,
                    mode = PoiDisplayMode.NEAREST
                )
                invalidate?.invoke()
                return
            }
        }
        return
    }

    private fun sliderValue(px: Float, bounds: RectF, minValue: Int, maxValue: Int, step: Int): Int {
        val fraction = ((px - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val raw = minValue + fraction * (maxValue - minValue)
        return (round((raw - minValue) / step) * step + minValue).toInt().coerceIn(minValue, maxValue)
    }

    private fun poiSliderAt(px: Float, py: Float): PoiSlider {
        activePoiSliderCategory = null
        if (pendingPoiConfig.isDefaultMode) {
            poiImportantRadiusTracks.forEach { (category, bounds) ->
                if (expandRect(bounds).contains(px, py)) {
                    activePoiSliderCategory = category
                    return PoiSlider.IMPORTANT_RADIUS
                }
            }
            if (expandRect(poiNormalCountTrack).contains(px, py)) return PoiSlider.NORMAL_COUNT
            if (expandRect(poiClosestRadiusTrack).contains(px, py)) return PoiSlider.CLOSEST_RADIUS
        } else {
            if (expandRect(poiNearestRadiusTrack).contains(px, py)) return PoiSlider.NEAREST_RADIUS
            if (expandRect(poiNearestCountTrack).contains(px, py)) return PoiSlider.NEAREST_COUNT
        }
        return PoiSlider.NONE
    }

    private fun poiSliderHitbox(slider: PoiSlider): RectF {
        val track = when (slider) {
            PoiSlider.IMPORTANT_RADIUS -> activePoiSliderCategory?.let(poiImportantRadiusTracks::get)
            PoiSlider.NORMAL_COUNT -> poiNormalCountTrack
            PoiSlider.CLOSEST_RADIUS -> poiClosestRadiusTrack
            PoiSlider.NEAREST_RADIUS -> poiNearestRadiusTrack
            PoiSlider.NEAREST_COUNT -> poiNearestCountTrack
            PoiSlider.NONE -> null
        } ?: return RectF()
        return expandRect(track)
    }

    private fun expandRect(rect: RectF): RectF = RectF(
        rect.left - MARGIN,
        rect.top - MARGIN,
        rect.right + MARGIN,
        rect.bottom + MARGIN
    )

    private fun updatePoiSliderFromTouch(px: Float, slider: PoiSlider) {
        when (slider) {
            PoiSlider.IMPORTANT_RADIUS -> {
                val category = activePoiSliderCategory ?: return
                val track = poiImportantRadiusTracks[category] ?: return
                val radii = pendingPoiConfig.importantRadiiM.toMutableMap()
                radii[category] = sliderValue(
                    px,
                    track,
                    PoiDisplayConfig.MIN_IMPORTANT_RADIUS_M,
                    PoiDisplayConfig.MAX_IMPORTANT_RADIUS_M,
                    50
                )
                pendingPoiConfig = pendingPoiConfig.copy(
                    importantRadiiM = radii,
                    state = PoiDisplayConfigState.CUSTOM,
                    mode = PoiDisplayMode.DEFAULT
                )
            }
            PoiSlider.NORMAL_COUNT -> pendingPoiConfig = pendingPoiConfig.copy(
                normalMaxResults = sliderValue(
                    px,
                    poiNormalCountTrack,
                    PoiDisplayConfig.MIN_NORMAL_RESULTS,
                    PoiDisplayConfig.MAX_NORMAL_RESULTS,
                    1
                ),
                state = PoiDisplayConfigState.CUSTOM,
                mode = PoiDisplayMode.DEFAULT
            )
            PoiSlider.CLOSEST_RADIUS -> pendingPoiConfig = pendingPoiConfig.copy(
                closestRadiusM = sliderValue(
                    px,
                    poiClosestRadiusTrack,
                    PoiDisplayConfig.MIN_RADIUS_M,
                    PoiDisplayConfig.MAX_RADIUS_M,
                    50
                ),
                state = PoiDisplayConfigState.CUSTOM,
                mode = PoiDisplayMode.DEFAULT
            )
            PoiSlider.NEAREST_RADIUS -> pendingPoiConfig = pendingPoiConfig.copy(
                radiusM = sliderValue(
                    px,
                    poiNearestRadiusTrack,
                    PoiDisplayConfig.MIN_RADIUS_M,
                    PoiDisplayConfig.MAX_RADIUS_M,
                    50
                ),
                state = PoiDisplayConfigState.CUSTOM,
                mode = PoiDisplayMode.NEAREST
            )
            PoiSlider.NEAREST_COUNT -> pendingPoiConfig = pendingPoiConfig.copy(
                maxResults = sliderValue(
                    px,
                    poiNearestCountTrack,
                    PoiDisplayConfig.MIN_RESULTS,
                    PoiDisplayConfig.MAX_RESULTS,
                    1
                ),
                state = PoiDisplayConfigState.CUSTOM,
                mode = PoiDisplayMode.NEAREST
            )
            PoiSlider.NONE -> return
        }
        invalidate?.invoke()
    }

    private fun distance(loc: Location, p: MapGeoPoint)=FloatArray(1).also{ Location.distanceBetween(loc.latitude,loc.longitude,p.lat,p.lon,it)}[0]
    private fun bearing(loc: Location, p: MapGeoPoint)=FloatArray(2).also{ Location.distanceBetween(loc.latitude,loc.longitude,p.lat,p.lon,it)}[1]

    companion object {
        private const val VISIBLE_SEARCH_ROWS = 5
        private const val SEARCH_ROW_HEIGHT = 50f
        private const val TAG = "MapWidget"
        private const val ADDRESS_DIALOG_WIDTH_FRACTION = 0.66f
        private const val ADDRESS_DIALOG_HEIGHT = 148f
        private const val POSE_FRAME_INTERVAL_MS = 16L
        private const val POSE_HEADING_INVALIDATION_THRESHOLD_DEG = 0.25f
        private const val MARGIN = 20.0f
    }
}

private fun angularDistanceDeg(first: Float, second: Float): Float {
    val delta = abs((first - second) % 360f)
    return min(delta, 360f - delta)
}

/** Clockwise bearing on the display relative to the direction the glasses face. */
internal fun compassRelativeBearingDeg(absoluteBearingDeg: Float, headingDeg: Float): Float {
    var relative = (absoluteBearingDeg - headingDeg) % 360f
    if (relative < 0f) relative += 360f
    return relative
}