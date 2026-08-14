package com.everyday.everyday_glasses

import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RouteCalculationResult
import com.everyday.shared.routing.RouteFailure
import com.everyday.shared.routing.RouteGraphFetchResult
import com.everyday.shared.routing.RouteGraphProvider
import com.everyday.shared.routing.RouteMode
import com.everyday.shared.routing.RouteRequest
import com.everyday.shared.routing.WalkingRouteDefaults
import com.everyday.shared.routing.WalkingRouteEngine
import java.net.SocketTimeoutException

/** Pure orchestration for the glasses-side geocode -> graph fetch -> walking route flow. */
class LocalWalkingNavigationService(
    private val geocoder: ForwardGeocoder = MapsCoForwardGeocoder(),
    private val graphProvider: RouteGraphProvider = com.everyday.shared.routing.OverpassRouteDataSource(),
    private val routeEngine: WalkingRouteEngine = WalkingRouteEngine()
) {

    fun resolveAddress(address: String, currentLocation: MapGeoPoint): GeocodingResolution {
        val query = NavigationAddressText.normalize(address)
        require(query.isNotBlank()) { "Address must not be blank" }
        val candidates = geocoder.search(query, currentLocation).getOrElse { throw it }
        return GeocodingRanker.resolve(query, candidates, currentLocation)
    }

    fun searchAddressCandidates(address: String, currentLocation: MapGeoPoint): List<RankedGeocodingCandidate> {
        val query = NavigationAddressText.normalize(address)
        require(query.isNotBlank()) { "Address must not be blank" }
        return GeocodingRanker.rank(query, geocoder.search(query, currentLocation).getOrElse { throw it }, currentLocation)
            .take(GeocodingRanker.MAX_CHOICES)
    }

    fun cancelSearch() { (geocoder as? CancellableForwardGeocoder)?.cancelActive() }

    /**
     * Performs the bounded local route calculation. A wider Overpass area is requested only for
     * endpoint snap/disconnected failures, matching the retry contract of Work Package 09.
     */
    fun calculateWalkingRoute(
        origin: MapGeoPoint,
        destination: MapGeoPoint
    ): RouteCalculationResult {
        val request = RouteRequest(
            origin = GeoPoint(origin.lat, origin.lon),
            destination = GeoPoint(destination.lat, destination.lon),
            mode = RouteMode.WALKING,
            walkingSpeedMps = NavigationState.WALKING_SPEED_MPS
        )
        routeEngine.validateRequest(request)?.let { failure ->
            return RouteCalculationResult.Failure(failure, attempts = 0)
        }

        val firstFetch = fetchGraph(request, WalkingRouteDefaults.INITIAL_QUERY_BUFFER_METERS)
        val firstCalculation = calculateFromFetch(request, firstFetch, attempts = 1, buffer = WalkingRouteDefaults.INITIAL_QUERY_BUFFER_METERS)
        if (!shouldRetry(firstCalculation)) return firstCalculation

        val retryFetch = fetchGraph(request, WalkingRouteDefaults.RETRY_QUERY_BUFFER_METERS)
        return calculateFromFetch(request, retryFetch, attempts = 2, buffer = WalkingRouteDefaults.RETRY_QUERY_BUFFER_METERS)
    }

    private fun fetchGraph(request: RouteRequest, bufferMeters: Int): RouteGraphFetchResult = try {
        graphProvider.fetch(request, bufferMeters)
    } catch (failure: Throwable) {
        RouteGraphFetchResult.Failure(
            if (failure is SocketTimeoutException) {
                RouteFailure.Timeout(failure.message ?: "Route graph request timed out")
            } else {
                RouteFailure.NetworkFailure(failure.message ?: "Route graph request failed")
            }
        )
    }

    private fun calculateFromFetch(
        request: RouteRequest,
        fetch: RouteGraphFetchResult,
        attempts: Int,
        buffer: Int
    ): RouteCalculationResult = when (fetch) {
        is RouteGraphFetchResult.Success -> when (val calculated = routeEngine.calculate(request, fetch.data)) {
            is RouteCalculationResult.Success -> calculated.copy(attempts = attempts, queryBufferMeters = buffer)
            is RouteCalculationResult.Failure -> calculated.copy(attempts = attempts, queryBufferMeters = buffer)
        }
        is RouteGraphFetchResult.Failure -> RouteCalculationResult.Failure(
            fetch.failure,
            attempts = attempts,
            queryBufferMeters = buffer
        )
    }

    private fun shouldRetry(result: RouteCalculationResult): Boolean = when (result) {
        is RouteCalculationResult.Success -> false
        is RouteCalculationResult.Failure ->
            result.failure is RouteFailure.NoNearbyGraphNode ||
                result.failure is RouteFailure.DisconnectedGraph
    }
}
