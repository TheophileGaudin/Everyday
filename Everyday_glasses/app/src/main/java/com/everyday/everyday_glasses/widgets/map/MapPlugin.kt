package com.everyday.everyday_glasses.widgets.map

import android.content.Context
import android.location.Location
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.MapWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.NavigationOperationResult
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig

/**
 * Puts the map on the dashboard and connects it to the location sources and to the phone's
 * routing and destination search.
 */
class MapPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val context: Context get() = services.androidContext
    private val current: () -> MapWidget? = { services.singleton(WidgetTypeId.MAP) as? MapWidget }
    private val requestRepaint: () -> Unit = { services.requestRepaint() }
    override val lifecycle = WidgetLifecycleRegistration.singleton(
        WidgetTypeId.MAP, MapWidget::class, DashboardPersistence.MapWidgetState::class,
        current = current,
        create = ::create,
        restore = ::restore,
        capture = { services.captureRestore.getMapWidgetState(it) },
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

    private fun restore(state: DashboardPersistence.MapWidgetState): MapWidget {
        val widget = build(state.x, state.y, state.width, state.height)
        services.captureRestore.restoreMapWidgetPosition(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
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
                    data.requests?.requestMapData(point, config)
                }
                override fun onAddressFocusChanged(focused: Boolean) = this@MapPlugin.services.onKeyFocusChanged(map, focused)
                override fun onNavigationResult(result: NavigationOperationResult) = services.notifyTransientChange()
                override fun onStopNavigationRequested() = this@MapPlugin.stopNavigation()
            }
            data.latest(AppChannels.location)?.let { setLocation(Location(it)) }
            val place = data.latest(AppChannels.place)
            setLocationMetadata(place?.town.orEmpty(), place?.countryCode.orEmpty())
        }

    private companion object {
        const val SIZE = 210f
    }
}