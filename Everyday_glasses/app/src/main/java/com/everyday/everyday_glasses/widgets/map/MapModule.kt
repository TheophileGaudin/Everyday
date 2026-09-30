package com.everyday.everyday_glasses.widgets.map

import android.content.Context
import android.location.Location
import com.everyday.everyday_glasses.connectivity.WidgetDataProvider
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.MapWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.map.backend.GlassesRouteTransport
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.NavigationOperationResult
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.shared.sync.MapSnapshot
import com.everyday.shared.transport.DestinationSearchError
import com.everyday.shared.transport.DestinationSearchResult
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportResult

/**
 * Puts the map on the dashboard and connects it to the location sources and to the phone's
 * routing and destination search.
 */
class MapModule(
    private val services: DashboardServices,
    private val context: Context,
    private val current: () -> MapWidget?,
    private val currentProvider: () -> WidgetDataProvider?,
    private val requestRepaint: () -> Unit,
    private val addressFocusChanged: (MapWidget, Boolean) -> Unit,
    private val dismissPresentation: () -> Unit,
) {
    // The phone route connection, so that a map opened later can route through the phone.
    private var routeTransport: GlassesRouteTransport? = null
    private var routeConnected = false

    // The last location and place received, so that a map opened later shows them right away.
    private var latestLocation: Location? = null
    private var latestLabel = ""
    private var latestCountryCode = ""

    val lifecycle = WidgetLifecycleRegistration.singleton(
        WidgetTypeId.MAP, MapWidget::class, DashboardPersistence.MapWidgetState::class,
        current = current,
        create = ::create,
        restore = ::restore,
        capture = { services.captureRestore.getMapWidgetState(it) },
        beforeRemove = MapWidget::release,
        onFullscreenChanged = { widget, fullscreen -> if (!fullscreen) widget.enforceWindowedMode() },
    )

    fun updateLocation(location: Location) {
        latestLocation = Location(location)
        current()?.setLocation(location)
        services.notifyTransientChange()
    }

    init {
        services.appData.subscribe(AppChannels.place) { place -> applyLocationMetadata(place.town, place.countryCode) }
    }

    private fun applyLocationMetadata(label: String, countryCode: String) {
        latestLabel = label
        latestCountryCode = countryCode
        current()?.setLocationMetadata(label, countryCode)
    }

    fun requestPhoneMapRefresh() {
        current()?.requestPhoneMapRefresh()
    }

    fun applySnapshot(snapshot: MapSnapshot) {
        current()?.applyProxyMap(snapshot)
        services.notifyTransientChange()
    }

    fun configureRouteTransport(transport: GlassesRouteTransport?) {
        routeTransport = transport
        current()?.configurePhoneRouteTransport(transport)
    }

    fun onRouteConnectionChanged(connected: Boolean) {
        routeConnected = connected
        current()?.onPhoneRouteConnectionChanged(connected)
    }

    fun onRouteResult(result: RouteTransportResult) {
        current()?.onPhoneRouteResult(result)
    }

    fun onRouteError(error: RouteTransportError) {
        current()?.onPhoneRouteError(error)
    }

    fun onDestinationSearchResult(result: DestinationSearchResult) {
        current()?.onPhoneDestinationSearchResult(result)
    }

    fun onDestinationSearchError(error: DestinationSearchError) {
        current()?.onPhoneDestinationSearchError(error)
    }

    /**
     * An explicit stop also ends a voice presentation of the map, without restoring an older
     * map rectangle; the current map, POI and orientation state stay visible.
     */
    fun stopNavigation() {
        services.withTransientChanges {
            dismissPresentation()
            current()?.stopNavigation()
            services.notifyTransientChange()
        }
    }

    private fun create(): MapWidget = build(services.viewportWidth - SIZE - 24f, 24f, SIZE, SIZE)

    private fun restore(state: DashboardPersistence.MapWidgetState): MapWidget {
        val widget = build(state.x, state.y, state.width, state.height)
        services.captureRestore.restoreMapWidgetPosition(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }

    private fun build(x: Float, y: Float, width: Float, height: Float): MapWidget =
        MapWidget(context, x, y, width, height).apply {
            invalidate = requestRepaint
            configurePhoneRouteTransport(routeTransport)
            onPhoneRouteConnectionChanged(routeConnected)
            val map = this
            listener = object : MapWidgetListener {
                override fun onMapDataRequested(point: MapGeoPoint, config: PoiDisplayConfig) {
                    currentProvider()?.requestMapData(point, config)
                }
                override fun onAddressFocusChanged(focused: Boolean) = addressFocusChanged(map, focused)
                override fun onNavigationResult(result: NavigationOperationResult) = services.notifyTransientChange()
                override fun onStopNavigationRequested() = this@MapModule.stopNavigation()
            }
            latestLocation?.let(::setLocation)
            setLocationMetadata(latestLabel, latestCountryCode)
        }

    private companion object {
        const val SIZE = 210f
    }
}