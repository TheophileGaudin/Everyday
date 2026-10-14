package com.everyday.everyday_glasses.connectivity

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.AppData
import com.everyday.everyday_glasses.runtime.AppQueries
import com.everyday.everyday_glasses.runtime.Place
import com.everyday.everyday_glasses.auth_permissions.GoogleAuthCoordinator
import com.everyday.everyday_glasses.auth_permissions.GoogleAuthState
import com.everyday.everyday_glasses.widgets.calendar.GoogleCalendarClient
import com.everyday.everyday_glasses.auth_permissions.NetworkStatusProvider
import com.everyday.everyday_glasses.settings.UnitSettings
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.shared.sync.CalendarAccount
import com.everyday.shared.sync.CalendarEventSnapshot
import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.LocationFix
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.SyncCachePolicy
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncOrchestrator
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SyncSnapshotStore
import com.everyday.shared.sync.WeatherDataProvider
import com.everyday.shared.sync.WeatherSnapshot
import com.everyday.shared.sync.isStale
import com.everyday.shared.transport.SyncMessenger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GlassesDataSyncCoordinator(
    context: Context,
    private val appData: AppData,
    private val syncMessengerProvider: () -> SyncMessenger?,
    private val networkStatusProvider: NetworkStatusProvider,
    private val googleAuthCoordinatorProvider: () -> GoogleAuthCoordinator?,
    private val googleCalendarClientProvider: () -> GoogleCalendarClient?,
    private val localLocationProvider: () -> Location?,
    private val onPhoneLocationFix: (LocationFix) -> Unit
) {
    companion object {
        private const val TAG = "GlassesDataSync"
        private const val GEOCODE_MAPS_CO_API_KEY = "6974a768992ec642181119eby0e50e7"
        private const val UNIT_COUNTRY_RETRY_MS = 60_000L
    }

    private val snapshotStore = SyncSnapshotStore(context.applicationContext)
    private val syncOrchestrator = SyncOrchestrator(
        snapshotCache = snapshotStore,
        requestDispatcher = ::dispatchSyncRequest,
        snapshotApplier = { snapshot, _ -> applySnapshot(snapshot) }
    )
    private val localWeatherInFlight = AtomicBoolean(false)
    private val localLocationContextInFlight = AtomicBoolean(false)
    private val localNewsInFlight = AtomicBoolean(false)
    private val localCalendarInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshExecutor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4)
    )
    @Volatile private var released = false
    private var phoneConnected = false
    private var lastAppliedMapLatitude = Double.NaN
    private var lastAppliedMapLongitude = Double.NaN
    private var lastAppliedMapTimestampMs = Long.MIN_VALUE
    private var hasSessionLocationCountry = false
    private var lastUnitCountryRefreshAttemptMs = 0L
    private var pendingNewsSourceRequest: PendingNewsSourceRequest? = null

    private data class PendingNewsSourceRequest(
        val sourceSpec: NewsSourceSpec,
        val callback: (Result<NewsSnapshot>) -> Unit
    )

    fun restoreCachedSnapshots() {
        syncOrchestrator.restoreCachedSnapshots(notify = false)
    }

    fun clearCalendarSnapshot() {
        snapshotStore.clearCalendar()
        postUi {
            appData.publish(AppChannels.calendar, null)
        }
    }

    fun onPhoneConnectionChanged(connected: Boolean) {
        phoneConnected = connected
        if (connected) {
            syncOrchestrator.refresh(
                channels = SyncChannel.ALL,
                force = false,
                reason = "phone_connected"
            )
        } else {
            completePendingNewsSourceRequest(Result.failure(IllegalStateException("Phone disconnected")))
            syncOrchestrator.refresh(
                channels = SyncChannel.ALL,
                force = false,
                reason = "phone_disconnected"
            )
        }
    }

    fun onAppResumed() {
        syncOrchestrator.refresh(
            channels = SyncChannel.ALL,
            force = false,
            reason = "app_resumed"
        )
    }

    fun onDisplayWake(reason: String = "display_wake") {
        syncOrchestrator.refresh(
            channels = SyncChannel.ALL,
            force = false,
            reason = reason
        )
    }

    fun onLocalLocationChanged() {
        val now = System.currentTimeMillis()
        val needsInitialCountry = !hasSessionLocationCountry &&
            now - lastUnitCountryRefreshAttemptMs >= UNIT_COUNTRY_RETRY_MS
        if (phoneConnected || !networkStatusProvider.isConnected()) return
        val weatherIsStale = snapshotStore.loadWeather().isStale()
        when {
            needsInitialCountry && weatherIsStale -> {
                lastUnitCountryRefreshAttemptMs = now
                refreshWeatherLocally(force = true)
            }
            needsInitialCountry -> {
                lastUnitCountryRefreshAttemptMs = now
                refreshLocalLocationContext()
            }
            weatherIsStale -> refreshWeatherLocally(force = false)
        }
    }

    private fun refreshLocalLocationContext() {
        val location = localLocationProvider() ?: return
        if (!localLocationContextInFlight.compareAndSet(false, true)) return
        executeRefresh("location context", { localLocationContextInFlight.set(false) }) {
            try {
                WeatherDataProvider
                    .resolveLocationName(location.latitude, location.longitude, GEOCODE_MAPS_CO_API_KEY)
                    .onSuccess { locationName ->
                        postUi {
                            if (observeLiveCountry(locationName.countryCode)) {
                                hasSessionLocationCountry = true
                            }
                            appData.publish(AppChannels.place, Place(locationName.town, locationName.countryCode))
                        }
                    }
                    .onFailure { error -> Log.w(TAG, "Local location context refresh failed", error) }
            } finally {
                localLocationContextInFlight.set(false)
            }
        }
    }

    fun onFinanceDataRequested(
        visibleConfigs: List<FinanceDashboardTileConfig>,
        refreshTileIds: List<String>,
        force: Boolean,
        reason: String
    ) {
        syncOrchestrator.refresh(
            channels = setOf(SyncChannel.FINANCE),
            force = force,
            reason = reason,
            financeTiles = visibleConfigs,
            financeRefreshTileIds = refreshTileIds,
            financeLiveEnabled = visibleConfigs.isNotEmpty()
        )
    }

    fun onFinanceVisibilityChanged(visible: Boolean) {
        val visibleConfigs = if (visible) appData.ask(AppQueries.financeTilesShown).orEmpty() else emptyList()
        syncOrchestrator.refresh(
            channels = setOf(SyncChannel.FINANCE),
            force = false,
            reason = if (visible) "finance_visible" else "finance_hidden",
            financeTiles = visibleConfigs,
            financeLiveEnabled = visible && visibleConfigs.isNotEmpty()
        )
    }

    fun onNewsDataRequested(countryCode: String, force: Boolean, reason: String) {
        syncOrchestrator.refresh(
            channels = setOf(SyncChannel.NEWS),
            force = force,
            reason = reason,
            countryCode = countryCode,
            newsSource = appData.ask(AppQueries.newsSource)
        )
    }

    fun requestNewsSource(
        sourceSpec: NewsSourceSpec,
        countryCode: String,
        callback: (Result<NewsSnapshot>) -> Unit
    ) {
        val request = SyncRequest(
            channels = setOf(SyncChannel.NEWS),
            force = true,
            reason = "news_source_changed",
            countryCode = countryCode,
            newsSource = sourceSpec
        )
        pendingNewsSourceRequest?.callback?.invoke(
            Result.failure(IllegalStateException("News source request was replaced"))
        )
        pendingNewsSourceRequest = PendingNewsSourceRequest(sourceSpec, callback)

        if (phoneConnected) {
            if (!requestPhoneSync(request)) {
                completePendingNewsSourceRequest(
                    Result.failure(IllegalStateException("Phone is unavailable"))
                )
            }
            return
        }

        if (!networkStatusProvider.isConnected()) {
            completePendingNewsSourceRequest(
                Result.failure(IllegalStateException("Network is unavailable"))
            )
            return
        }

        refreshNewsLocally(
            force = true,
            reason = "news_source_changed",
            countryCode = countryCode,
            sourceSpec = sourceSpec,
            completion = { result -> completePendingNewsSourceRequest(result) }
        )
    }

    fun onMapDataRequested(point: MapGeoPoint, poiConfig: PoiDisplayConfig = PoiDisplayConfig.DEFAULT) {
        if (!phoneConnected) {
            Log.d(TAG, "Map proxy skipped: phone is not connected")
            return
        }
        Log.d(TAG, "Requesting phone map proxy for ${point.lat},${point.lon} mode=${poiConfig.mode} config=${poiConfig.state}")
        val defaultProfile = poiConfig
            .takeUnless(PoiDisplayConfig::isDefault)
            ?.takeIf(PoiDisplayConfig::isDefaultMode)
        val nearestProfile = poiConfig.takeUnless(PoiDisplayConfig::isDefaultMode)
        requestPhoneSync(
            SyncRequest(
                channels = setOf(SyncChannel.MAP), force = true, reason = "map_proxy",
                mapLat = point.lat,
                mapLon = point.lon,
                mapPoiMode = poiConfig.mode.name,
                // The phone's empty-category request is the legacy superset query used by every
                // older phone build. Current builds also receive the Default profile's query
                // categories/radii so changing an important radius remains effective through the
                // phone proxy.
                mapPoiCategories = nearestProfile?.categories?.sorted().orEmpty(),
                mapPoiRadiusM = nearestProfile?.radiusM,
                mapPoiMaxResults = nearestProfile?.maxResults,
                mapPoiImportantCategories = defaultProfile?.importantCategories?.sorted().orEmpty(),
                mapPoiImportantRadiiM = defaultProfile?.importantRadiiM.orEmpty(),
                mapPoiNormalCategories = defaultProfile?.normalCategories?.sorted().orEmpty(),
                mapPoiNormalMaxResults = defaultProfile?.normalMaxResults,
                mapPoiShowClosest = defaultProfile?.showClosest,
                mapPoiClosestRadiusM = defaultProfile?.closestRadiusM
            )
        )
    }

    fun requestPhoneCalendarSnapshot() {
        syncOrchestrator.refresh(
            channels = setOf(SyncChannel.CALENDAR),
            force = true,
            reason = "calendar_requested"
        )
    }

    fun onSyncSnapshotReceived(snapshot: SyncSnapshot) {
        snapshot.weather?.let { weather ->
            postUi {
                if (observeLiveCountry(weather.countryCode)) {
                    hasSessionLocationCountry = true
                }
            }
        }
        syncOrchestrator.cacheAndApplySnapshot(snapshot, notify = true)
        snapshot.news?.let { news ->
            val pending = pendingNewsSourceRequest
            if (pending != null && pending.sourceSpec == news.sourceSpec) {
                pendingNewsSourceRequest = null
                pending.callback(Result.success(news))
            }
        }
    }

    fun onSyncErrorReceived(error: SyncError) {
        postUi {
            when (error.channel) {
                SyncChannel.NEWS -> {
                    appData.publish(AppChannels.newsError, error.message)
                    completePendingNewsSourceRequest(Result.failure(IllegalStateException(error.message)))
                }
                SyncChannel.FINANCE -> appData.publish(AppChannels.financeError, error.message)
                else -> Log.w(TAG, "Sync error ${error.channel}: ${error.message}")
            }
        }
    }

    private fun completePendingNewsSourceRequest(result: Result<NewsSnapshot>) {
        val pending = pendingNewsSourceRequest ?: return
        pendingNewsSourceRequest = null
        pending.callback(result)
    }

    private fun dispatchSyncRequest(request: SyncRequest) {
        if (phoneConnected) {
            requestPhoneSync(request)
        } else {
            refreshLocally(request)
        }
    }

    private fun requestPhoneSync(request: SyncRequest): Boolean {
        val visibleFinanceConfigs = resolveVisibleFinanceConfigs(request)
        val financeSelection = visibleFinanceConfigs.firstOrNull()
        val requestCountry = request.countryCode
            ?: snapshotStore.loadWeather()?.countryCode
            ?: appData.ask(AppQueries.newsCountry)
            ?: snapshotStore.loadNews()?.countryCode
        val financeLiveEnabled = if (SyncChannel.FINANCE in request.channels) {
            request.financeLiveEnabled ?: visibleFinanceConfigs.isNotEmpty()
        } else {
            request.financeLiveEnabled
        }
        return syncMessengerProvider()?.sendSyncRequest(
            request.copy(
                countryCode = requestCountry,
                newsSource = request.newsSource ?: appData.ask(AppQueries.newsSource)
                    ?: snapshotStore.loadNews()?.sourceSpec,
                financeTiles = visibleFinanceConfigs,
                financeSymbol = request.financeSymbol ?: financeSelection?.symbol,
                financeRange = request.financeRange ?: financeSelection?.range,
                financeAssetType = request.financeAssetType ?: financeSelection?.assetType,
                financeChartType = request.financeChartType ?: financeSelection?.chartType,
                financeTileId = request.financeTileId ?: financeSelection?.id,
                financeLiveEnabled = financeLiveEnabled
            )
        ) == true
    }

    private fun refreshLocally(request: SyncRequest) {
        if (!networkStatusProvider.isConnected()) {
            applySnapshot(snapshotStore.loadAll())
            return
        }
        if (request.financeLiveEnabled == false && SyncChannel.FINANCE in request.channels) {
            return
        }
        if (SyncChannel.WEATHER in request.channels) refreshWeatherLocally(request.force)
        if (SyncChannel.CALENDAR in request.channels) refreshCalendarLocally(request.force)
        if (SyncChannel.NEWS in request.channels) {
            refreshNewsLocally(
                request.force,
                request.reason,
                request.countryCode,
                request.newsSource
            )
        }
        if (SyncChannel.FINANCE in request.channels) {
            refreshFinanceLocally(request)
        }
    }

    private fun refreshWeatherLocally(force: Boolean) {
        val cached = snapshotStore.loadWeather()
        if (!force && !cached.isStale()) return
        val location = localLocationProvider() ?: return
        if (!localWeatherInFlight.compareAndSet(false, true)) return

        executeRefresh("weather", { localWeatherInFlight.set(false) }) {
            try {
                val locationName = WeatherDataProvider
                    .resolveLocationName(location.latitude, location.longitude, GEOCODE_MAPS_CO_API_KEY)
                    .getOrNull()
                val weather = WeatherDataProvider
                    .fetchWeather(location.latitude, location.longitude)
                    .getOrNull()
                if (locationName != null || weather != null) {
                    val snapshot = WeatherSnapshot(
                        town = locationName?.town ?: cached?.town ?: "Current location",
                        countryCode = locationName?.countryCode ?: cached?.countryCode ?: "Unknown",
                        weather = weather ?: cached?.weather,
                        fetchedAtMs = System.currentTimeMillis(),
                        forecastLat = location.latitude,
                        forecastLon = location.longitude
                    )
                    snapshotStore.saveWeather(snapshot)
                    applyWeather(snapshot, updateUnitCountry = true)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Local weather refresh failed", error)
            } finally {
                localWeatherInFlight.set(false)
            }
        }
    }

    private fun refreshNewsLocally(
        force: Boolean,
        reason: String,
        countryCode: String?,
        sourceSpec: NewsSourceSpec? = null,
        completion: ((Result<NewsSnapshot>) -> Unit)? = null
    ) {
        val resolvedCountry = NewsDataProvider.resolveCountryCode(
            requestedCountryCode = countryCode,
            weather = snapshotStore.loadWeather(),
            cachedNews = snapshotStore.loadNews()
        )
        val cached = snapshotStore.loadNews()
        val resolvedSource = sourceSpec
            ?: appData.ask(AppQueries.newsSource)
            ?: cached?.sourceSpec
            ?: NewsSourceSpec.DEFAULT_LOCATION
        if (!force && cached != null && !cached.isStale() &&
            cached.countryCode == resolvedCountry && cached.sourceSpec == resolvedSource
        ) {
            completion?.invoke(Result.success(cached))
            return
        }
        if (!localNewsInFlight.compareAndSet(false, true)) {
            completion?.invoke(Result.failure(IllegalStateException("News refresh already in progress")))
            return
        }

        executeRefresh("news", {
            localNewsInFlight.set(false)
            completion?.invoke(Result.failure(RejectedExecutionException("Refresh queue is full")))
        }) {
            try {
                val snapshot = NewsDataProvider.fetchNews(
                    resolvedSource,
                    resolvedCountry,
                    snapshotStore.loadWeather()?.town
                ).getOrThrow()
                snapshotStore.saveNews(snapshot)
                applyNews(snapshot)
                Log.d(TAG, "Local news refresh success: reason=$reason country=$resolvedCountry")
                completion?.invoke(Result.success(snapshot))
            } catch (error: Throwable) {
                Log.w(TAG, "Local news refresh failed", error)
                postUi {
                    appData.publish(AppChannels.newsError, "No news available")
                }
                completion?.invoke(Result.failure(error))
            } finally {
                localNewsInFlight.set(false)
            }
        }
    }

    private fun refreshFinanceLocally(request: SyncRequest) {
        val visibleConfigs = resolveVisibleFinanceConfigs(request)
        if (visibleConfigs.isEmpty()) return
        val cached = snapshotStore.loadFinance()
        val cachedTilesById = cached?.tiles.orEmpty().associateBy { it.id }
        val refreshIds = request.financeRefreshTileIds.toSet()
        val configsToFetch = visibleConfigs.filter { config ->
            request.force ||
                config.id in refreshIds ||
                cachedTilesById[config.id]?.let {
                    SyncCachePolicy.isStale(it.fetchedAtMs, it.staleAfterMs) ||
                        it.symbol != config.symbol ||
                        it.range != config.range ||
                        it.assetType != config.assetType ||
                        (config.chartType == FinanceChartType.CANDLE && it.candles.isEmpty())
                } ?: true
        }
        if (configsToFetch.isEmpty()) return
        executeRefresh("finance") {
            try {
                val fetchedTiles = configsToFetch.map { config ->
                    FinanceDataProvider.fetchTile(config).getOrThrow()
                }
                val tiles = (visibleConfigs.mapNotNull { config ->
                    fetchedTiles.firstOrNull { it.id == config.id } ?: cachedTilesById[config.id]
                }).ifEmpty { fetchedTiles }
                val snapshot = requireNotNull(FinanceSnapshot.fromTiles(tiles))
                snapshotStore.saveFinance(snapshot)
                applyFinance(snapshot)
                Log.d(TAG, "Local finance refresh success: reason=${request.reason} tiles=${fetchedTiles.size}")
            } catch (error: Throwable) {
                Log.w(TAG, "Local finance refresh failed", error)
                postUi {
                    appData.publish(AppChannels.financeError, "No data")
                }
            }
        }
    }

    private fun resolveVisibleFinanceConfigs(request: SyncRequest): List<FinanceDashboardTileConfig> {
        if (request.financeLiveEnabled == false) return emptyList()
        request.financeTiles.takeIf { it.isNotEmpty() }?.let { return it }
        appData.ask(AppQueries.financeTilesShown).orEmpty().takeIf { it.isNotEmpty() }?.let { return it }
        val symbol = request.financeSymbol
        if (symbol != null) {
            val range = FinanceTimeRange.fromRange(request.financeRange)
            return listOf(
                FinanceDashboardTileConfig(
                    id = request.financeTileId ?: "finance_${symbol}_${range.range}",
                    assetType = request.financeAssetType
                        ?: snapshotStore.loadFinance()?.tiles?.firstOrNull()?.assetType
                        ?: FinanceAssetType.INDEX,
                    symbol = symbol,
                    range = range.range,
                    chartType = request.financeChartType
                        ?: snapshotStore.loadFinance()?.tiles?.firstOrNull()?.chartType
                        ?: FinanceChartType.LINE
                )
            )
        }
        return emptyList()
    }

    private fun refreshCalendarLocally(force: Boolean) {
        val auth = googleAuthCoordinatorProvider() ?: return
        val state = auth.getCurrentState()
        if (state.isPhoneFallbackMode || !state.isCalendarAuthorized) return
        val cached = snapshotStore.loadCalendar()
        if (!force && !cached.isStale()) return
        if (!localCalendarInFlight.compareAndSet(false, true)) return

        googleCalendarClientProvider()?.fetchUpcomingEvents(activity = null) { result ->
            localCalendarInFlight.set(false)
            result.onSuccess { events ->
                val snapshot = CalendarSnapshot(
                    account = state.account?.let {
                        CalendarAccount(email = it.email, displayName = it.displayName)
                    },
                    events = events.map {
                        CalendarEventSnapshot(
                            id = it.id,
                            summary = it.summary,
                            startIso = it.startIso,
                            htmlLink = it.htmlLink
                        )
                    },
                    fetchedAtMs = System.currentTimeMillis(),
                    sourceMode = GoogleAuthState.AuthMode.DIRECT_DEVICE.name
                )
                snapshotStore.saveCalendar(snapshot)
                applyCalendar(snapshot)
            }.onFailure { error ->
                Log.w(TAG, "Local calendar refresh failed", error)
            }
        }
    }

    private fun applySnapshot(snapshot: SyncSnapshot) {
        postUi {
            appData.publishTogether {
                snapshot.location?.let(::applyLocation)
                snapshot.weather?.let { applyWeather(it, updateUnitCountry = false) }
                snapshot.calendar?.let(::applyCalendar)
                snapshot.news?.let(::applyNews)
                snapshot.finance?.let(::applyFinance)
                snapshot.notifications?.let {appData.publish(AppChannels.notifications, it) }
                snapshot.map?.let {
                    Log.d(TAG, "Applying phone map snapshot: roads=${it.roads.size} pois=${it.pois.size}")
                    appData.publish(AppChannels.mapSnapshot, it)
                }
            }
        }
    }

    private fun applyLocation(snapshot: LocationFix) {
        postUi {
            val lat = snapshot.lat
            val lon = snapshot.lon
            onPhoneLocationFix(snapshot)
            if (lat.isFinite() && lon.isFinite()) {
                if (lat == lastAppliedMapLatitude && lon == lastAppliedMapLongitude &&
                    snapshot.timestampMs == lastAppliedMapTimestampMs) {
                    return@postUi
                }
                lastAppliedMapLatitude = lat
                lastAppliedMapLongitude = lon
                lastAppliedMapTimestampMs = snapshot.timestampMs
                val phoneFix = Location("everyday_phone").apply {
                    latitude = lat
                    longitude = lon
                    time = snapshot.timestampMs
                    snapshot.accuracyM?.let { accuracy = it }
                    snapshot.bearingDeg?.let { bearing = it }
                    snapshot.speedMps?.let { speed = it }
                }
                Log.d(TAG, "Forwarding phone location to map: $lat,$lon accuracy=${snapshot.accuracyM}")
                appData.publish(AppChannels.location, phoneFix)
            }
        }
    }

    private fun applyNews(snapshot: NewsSnapshot) {
        postUi { appData.publish(AppChannels.news, snapshot) }
    }

    private fun applyFinance(snapshot: FinanceSnapshot) {
        postUi { appData.publish(AppChannels.finance, snapshot) }
    }

    private fun applyCalendar(snapshot: CalendarSnapshot) {
        postUi {
            appData.publish(AppChannels.calendar, snapshot)
            if (snapshot.sourceMode == GoogleAuthState.AuthMode.PHONE_FALLBACK.name) {
                googleAuthCoordinatorProvider()?.onPhoneCalendarSnapshotReceived(snapshot)
            }
        }
    }

    /** Tells the app the country the user is in right now; true when it's a real country code. */
    private fun observeLiveCountry(countryCode: String): Boolean {
        appData.publish(AppChannels.liveCountry, countryCode)
        return UnitSettings.normalizeCountryCode(countryCode) != null
    }

    private fun postUi(action: () -> Unit) {
        if (released) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { if (!released) action() }
        }
    }

    private fun applyWeather(snapshot: WeatherSnapshot, updateUnitCountry: Boolean) {
        postUi {
            if (updateUnitCountry && observeLiveCountry(snapshot.countryCode)) {
                hasSessionLocationCountry = true
            }
            appData.publish(AppChannels.place, Place(snapshot.town,snapshot.countryCode))
            appData.publish(AppChannels.weather, snapshot.weather)
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        refreshExecutor.shutdownNow()
    }

    private fun executeRefresh(
        label: String,
        onRejected: () -> Unit = {},
        action: () -> Unit
    ) {
        if (released) {
            onRejected()
            return
        }
        try {
            refreshExecutor.execute(action)
        } catch (_: RejectedExecutionException) {
            onRejected()
            Log.w(TAG, "Dropped $label refresh because the bounded refresh executor is full")
        }
    }

}

/** Supplies remotely synchronized data requested by widgets. */
interface WidgetDataProvider {
    fun requestFinanceData(
        visibleConfigs: List<FinanceDashboardTileConfig>,
        refreshTileIds: List<String>,
        force: Boolean,
        reason: String
    )

    fun onFinanceVisibilityChanged(visible: Boolean)
    fun requestNewsData(countryCode: String, force: Boolean, reason: String)
    fun requestNewsSourceData(
        sourceSpec: NewsSourceSpec,
        countryCode: String,
        force: Boolean,
        reason: String,
        completion: (Result<NewsSnapshot>) -> Unit
    )

    fun requestMapData(point: MapGeoPoint, config: PoiDisplayConfig)
}