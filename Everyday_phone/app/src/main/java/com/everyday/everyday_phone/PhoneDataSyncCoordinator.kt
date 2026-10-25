package com.everyday.everyday_phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.FinanceTileSnapshot
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SyncCachePolicy
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncOrchestrator
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SyncSnapshotStore
import com.everyday.shared.sync.LocationFix
import com.everyday.shared.sync.WeatherSnapshot
import com.everyday.shared.sync.MapDataProvider
import com.everyday.shared.sync.isStale
import com.everyday.shared.transport.SyncMessenger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class PhoneManualRefreshResult(
    val errors: List<String>
) {
    fun errorMessage(): String = errors.joinToString(separator = "\n\n")
}

class PhoneDataSyncCoordinator(
    context: Context,
    private val syncMessengerProvider: () -> SyncMessenger?,
    private val googleAuthStateSender: (PhoneGoogleAuthState) -> Unit,
    private val googleAuthManager: PhoneGoogleAuthManager,
    private val hasLocationPermission: () -> Boolean
) {
    private val appContext = context.applicationContext
    private val snapshotStore = SyncSnapshotStore(appContext)
    private val syncOrchestrator = SyncOrchestrator(
        snapshotCache = snapshotStore,
        requestDispatcher = ::refreshChannels
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val newsRefreshInFlight = AtomicBoolean(false)
    private val manualRefreshInFlight = AtomicBoolean(false)
    // Bluetooth writes can block behind a large map packet.  Keep them off the
    // phone UI thread and serialize them to preserve protocol framing/order.
    private val outboundSyncExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val refreshExecutor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8)
    )
    private val binanceStreamManager = BinanceFinanceStreamManager(
        onSnapshot = { snapshot ->
            snapshotStore.saveFinance(snapshot)
            send(SyncSnapshot(finance = snapshot))
        },
        log = ::fileLog
    )
    private val notificationObserver: (PhoneNotificationsSnapshot) -> Unit = { snapshot ->
        onNotificationsSnapshotChanged(snapshot)
    }
    private val locationWeatherObserver = object : LocationWeatherService.Observer {
        override fun onLocationFixChanged(locationFix: LocationFix) {
            this@PhoneDataSyncCoordinator.onLocationFixChanged(locationFix)
        }

        override fun onWeatherSnapshotChanged(weatherSnapshot: WeatherSnapshot) {
            this@PhoneDataSyncCoordinator.onWeatherSnapshotChanged(weatherSnapshot)
        }
    }
    @Volatile private var isGlassesConnected = false
    @Volatile private var released = false
    @Volatile private var visibleFinanceConfigs: List<FinanceDashboardTileConfig> = emptyList()
    /** The glasses' last map request, so a manual refresh fetches the same area and points of interest. */
    @Volatile private var lastMapRequest: SyncRequest? = null

    init {
        LocationWeatherService.observer = locationWeatherObserver
        PhoneNotificationRepository.addObserver(notificationObserver, appContext)
    }

    fun onForeground() {
        if (!isGlassesConnected) return
        ensureLocationService()
        pushCachedSnapshots(pushGoogleState = true)
        syncOrchestrator.refresh(
            channels = SyncChannel.ALL,
            force = false,
            reason = "phone_foreground"
        )
    }

    fun onConnectionChanged(connected: Boolean) {
        isGlassesConnected = connected
        if (!connected) {
            LocationWeatherService.stop(appContext)
            visibleFinanceConfigs = emptyList()
            lastMapRequest = null
            binanceStreamManager.stopAll()
            return
        }

        ensureLocationService()
        pushCachedSnapshots(pushGoogleState = true)
        syncOrchestrator.refresh(
            channels = SyncChannel.ALL,
            force = false,
            reason = "phone_connected"
        )
    }

    fun onSyncRequest(request: SyncRequest) {
        if (!isGlassesConnected) return
        val cachedChannels = if (SyncChannel.FINANCE in request.channels && request.financeLiveEnabled == false) {
            request.channels - SyncChannel.FINANCE
        } else {
            request.channels
        }
        pushCachedSnapshots(channels = cachedChannels, pushGoogleState = true)
        syncOrchestrator.dispatch(request)
    }

    fun onGoogleAuthStateChanged(state: PhoneGoogleAuthState) {
        pushGoogleState(state)
    }

    fun onCalendarSnapshotChanged(snapshot: CalendarSnapshot?) {
        snapshot ?: return
        snapshotStore.saveCalendar(snapshot)
        send(SyncSnapshot(calendar = snapshot))
    }

    fun onGoogleDisconnected() {
        snapshotStore.clearCalendar()
        pushGoogleState(googleAuthManager.loadState())
    }

    fun refreshAllExternalApis(onComplete: (PhoneManualRefreshResult) -> Unit): Boolean {
        if (!manualRefreshInFlight.compareAndSet(false, true)) {
            return false
        }

        val errors = mutableListOf<String>()

        fun addError(label: String, error: Throwable) {
            synchronized(errors) {
                errors += buildString {
                    append(label)
                    append(" failed:\n")
                    append(error.stackTraceToString())
                }
            }
        }

        // Each entry runs one external refresh and reports its outcome (a failure
        // Throwable, or null on success) to the supplied callback. The completion
        // count is derived from this list, so adding/removing a refresh here stays
        // self-consistent.
        val operations: List<Pair<String, ((Throwable?) -> Unit) -> Unit>> = listOf(
            "Location/weather refresh" to { done -> refreshWeatherForManual { done(it.exceptionOrNull()) } },
            "Calendar refresh" to { done -> refreshCalendarForManual { done(it.exceptionOrNull()) } },
            "News refresh" to { done -> refreshNewsForManual { done(it.exceptionOrNull()) } },
            "Finance refresh" to { done -> refreshFinanceForManual { done(it.exceptionOrNull()) } },
            "Map refresh" to { done -> refreshMapForManual { done(it.exceptionOrNull()) } }
        )
        val remaining = AtomicInteger(operations.size)

        fun completeOne() {
            if (remaining.decrementAndGet() != 0) return

            pushCachedSnapshots(pushGoogleState = true)
            val resultErrors = synchronized(errors) { errors.toList() }
            manualRefreshInFlight.set(false)
            mainHandler.post {
                if (!released) onComplete(PhoneManualRefreshResult(resultErrors))
            }
        }

        operations.forEach { (label, run) ->
            run { error ->
                if (error != null) addError(label, error)
                completeOne()
            }
        }

        return true
    }

    fun release() {
        released = true
        isGlassesConnected = false
        if (LocationWeatherService.observer === locationWeatherObserver) {
            LocationWeatherService.observer = null
        }
        PhoneNotificationRepository.removeObserver(notificationObserver)
        binanceStreamManager.stopAll()
        mainHandler.removeCallbacksAndMessages(null)
        refreshExecutor.shutdownNow()
        outboundSyncExecutor.shutdownNow()
    }

    fun pushCachedSnapshots(
        channels: Set<SyncChannel> = SyncChannel.ALL,
        pushGoogleState: Boolean
    ) {
        if (!isGlassesConnected) return
        if (pushGoogleState) {
            pushGoogleState(googleAuthManager.loadState())
        }

        val serviceLocation = LocationWeatherService.instance?.getCurrentLocationFix()
        val serviceWeather = LocationWeatherService.instance?.getCurrentWeatherSnapshot()
        if (serviceLocation != null) snapshotStore.saveLocation(serviceLocation)
        if (serviceWeather != null) snapshotStore.saveWeather(serviceWeather)

        val sharedCalendarSnapshot = googleAuthManager.loadCachedSnapshot()
            ?.takeIf { SyncChannel.CALENDAR in channels }
        val cachedChannels = channels.toMutableSet()
        if (serviceLocation != null) cachedChannels -= SyncChannel.LOCATION
        if (serviceWeather != null) cachedChannels -= SyncChannel.WEATHER
        if (sharedCalendarSnapshot != null) cachedChannels -= SyncChannel.CALENDAR
        val cachedSnapshot = syncOrchestrator.cachedSnapshot(cachedChannels)
        val notificationSnapshot = PhoneNotificationRepository.current(appContext)
            .takeIf { SyncChannel.NOTIFICATIONS in channels }
        val snapshot = SyncSnapshot(
            location = serviceLocation?.takeIf { SyncChannel.LOCATION in channels } ?: cachedSnapshot.location,
            weather = serviceWeather?.takeIf { SyncChannel.WEATHER in channels } ?: cachedSnapshot.weather,
            calendar = sharedCalendarSnapshot ?: cachedSnapshot.calendar,
            news = cachedSnapshot.news,
            finance = cachedSnapshot.finance,
            notifications = notificationSnapshot
        )
        send(snapshot)
    }

    private fun refreshChannels(request: SyncRequest) {
        if (SyncChannel.LOCATION in request.channels) {
            ensureLocationService()
            LocationWeatherService.instance?.getCurrentLocationFix()?.let(::onLocationFixChanged)
        }
        if (SyncChannel.WEATHER in request.channels) {
            refreshWeather(request.force)
        }
        if (SyncChannel.CALENDAR in request.channels) {
            refreshCalendar(request.force)
        }
        if (SyncChannel.NEWS in request.channels) {
            refreshNews(request)
        }
        if (SyncChannel.FINANCE in request.channels) {
            refreshFinance(request)
        }
        if (SyncChannel.NOTIFICATIONS in request.channels) {
            send(SyncSnapshot(notifications = PhoneNotificationRepository.current(appContext)))
        }
        if (SyncChannel.MAP in request.channels) {
            if (request.mapLat != null && request.mapLon != null) {
                lastMapRequest = request
                executeRefresh("map", {
                    sendError(SyncChannel.MAP, "Map fetch skipped: refresh queue is full")
                }) { fetchAndSendMap(request) }
            }
        }
    }

    /** Fetches the map area of [request] and sends it to the glasses; a failure is reported to them too. */
    private fun fetchAndSendMap(request: SyncRequest): Result<Unit> {
        val lat = request.mapLat ?: return Result.success(Unit)
        val lon = request.mapLon ?: return Result.success(Unit)
        fileLog("Fetching phone map proxy for $lat,$lon")
        return runCatching {
            MapDataProvider.fetch(
                lat = lat,
                lon = lon,
                poiMode = request.mapPoiMode,
                poiCategories = request.mapPoiCategories.toSet(),
                poiRadiusM = request.mapPoiRadiusM,
                importantCategories = request.mapPoiImportantCategories.toSet(),
                importantRadiiM = request.mapPoiImportantRadiiM,
                normalCategories = request.mapPoiNormalCategories.toSet()
            )
        }
            .onSuccess {
                fileLog("Phone map proxy ready: roads=${it.roads.size} pois=${it.pois.size}")
                send(SyncSnapshot(map = it))
            }
            .onFailure {
                fileLog("Phone map proxy failed: ${it.message}")
                sendError(SyncChannel.MAP, "Map fetch failed: ${it.message}")
            }
            .map { }
    }

    /** Fetches again the map area the glasses last asked for. Nothing to do when they haven't asked for one. */
    private fun refreshMapForManual(callback: (Result<Unit>) -> Unit) {
        val request = lastMapRequest
        if (request == null) {
            callback(Result.success(Unit))
            return
        }
        executeRefresh("manual map", {
            callback(Result.failure(RejectedExecutionException("Refresh queue is full")))
        }) {
            callback(fetchAndSendMap(request))
        }
    }

    private fun refreshWeather(force: Boolean) {
        ensureLocationService()
        val service = LocationWeatherService.instance
        if (service == null) {
            sendError(SyncChannel.WEATHER, "Location service is not ready")
            return
        }
        if (force || snapshotStore.loadWeather().isStale()) {
            service.refreshWeatherNow(forceWeatherFetch = force)
        }
    }

    private fun refreshWeatherForManual(callback: (Result<WeatherSnapshot>) -> Unit) {
        if (!hasLocationPermission()) {
            callback(Result.failure(SecurityException("Location permission is not granted")))
            return
        }

        ensureLocationService()

        fun refreshFromService() {
            val service = LocationWeatherService.instance
            if (service == null) {
                callback(Result.failure(IllegalStateException("Location service is not ready")))
                return
            }
            service.forceUpdateDetailed(callback)
        }

        if (LocationWeatherService.instance == null) {
            mainHandler.postDelayed({ refreshFromService() }, 500L)
        } else {
            refreshFromService()
        }
    }

    private fun refreshCalendar(force: Boolean) {
        val state = googleAuthManager.loadState()
        pushGoogleState(state)

        val cached = googleAuthManager.loadCachedSnapshot()
        if (cached != null) {
            snapshotStore.saveCalendar(cached)
            send(SyncSnapshot(calendar = cached))
        }

        if (state.status != PhoneGoogleAuthState.Status.AUTHORIZED) {
            return
        }
        if (!force && !cached.isStale()) {
            return
        }

        googleAuthManager.refreshSnapshotSilently { result ->
            result.onSuccess { snapshot ->
                onCalendarSnapshotChanged(snapshot)
            }.onFailure { error ->
                if (error.message != "Google Calendar refresh already in progress") {
                    fileLog("Phone sync calendar refresh failed: ${error.message}")
                    sendError(SyncChannel.CALENDAR, "Calendar refresh failed")
                }
            }
        }
    }

    private fun refreshCalendarForManual(callback: (Result<Unit>) -> Unit) {
        val state = googleAuthManager.loadState()
        pushGoogleState(state)
        if (state.status != PhoneGoogleAuthState.Status.AUTHORIZED) {
            callback(Result.success(Unit))
            return
        }

        googleAuthManager.refreshSnapshotSilently { result ->
            result.onSuccess { snapshot ->
                onCalendarSnapshotChanged(snapshot)
                callback(Result.success(Unit))
            }.onFailure { error ->
                callback(Result.failure(error))
            }
        }
    }

    private fun refreshNews(request: SyncRequest) {
        val cached = snapshotStore.loadNews()
        val sourceSpec = request.newsSource
            ?: cached?.sourceSpec
            ?: NewsSourceSpec.DEFAULT_LOCATION
        val countryCode = NewsDataProvider.resolveCountryCode(
            request.countryCode,
            snapshotStore.loadWeather(),
            cached
        )
        if (!request.force && cached != null && !cached.isStale() &&
            cached.countryCode == countryCode && cached.sourceSpec == sourceSpec
        ) {
            return
        }
        if (!newsRefreshInFlight.compareAndSet(false, true)) return

        executeRefresh("news", { newsRefreshInFlight.set(false) }) {
            try {
                val snapshot = NewsDataProvider.fetchNews(
                    sourceSpec,
                    countryCode,
                    snapshotStore.loadWeather()?.town
                ).getOrThrow()
                    .copy(staleAfterMs = SyncCachePolicy.NEWS_STALE_AFTER_MS)
                snapshotStore.saveNews(snapshot)
                send(SyncSnapshot(news = snapshot))
            } catch (error: Throwable) {
                fileLog("Phone sync news refresh failed: ${error.message}")
                sendError(SyncChannel.NEWS, "News refresh failed: ${error.message}")
            } finally {
                newsRefreshInFlight.set(false)
            }
        }
    }

    private fun refreshNewsForManual(callback: (Result<Unit>) -> Unit) {
        val cached = snapshotStore.loadNews()
        val sourceSpec = cached?.sourceSpec ?: NewsSourceSpec.DEFAULT_LOCATION
        val countryCode = NewsDataProvider.resolveCountryCode(
            requestedCountryCode = null,
            weather = snapshotStore.loadWeather(),
            cachedNews = cached
        )

        executeRefresh("manual news", {
            callback(Result.failure(RejectedExecutionException("Refresh queue is full")))
        }) {
            try {
                val snapshot = NewsDataProvider.fetchNews(
                    sourceSpec,
                    countryCode,
                    snapshotStore.loadWeather()?.town
                ).getOrThrow()
                    .copy(staleAfterMs = SyncCachePolicy.NEWS_STALE_AFTER_MS)
                snapshotStore.saveNews(snapshot)
                send(SyncSnapshot(news = snapshot))
                callback(Result.success(Unit))
            } catch (error: Throwable) {
                fileLog("Manual news refresh failed: ${error.message}")
                sendError(SyncChannel.NEWS, "News refresh failed: ${error.message}")
                callback(Result.failure(error))
            }
        }
    }

    private fun refreshFinance(request: SyncRequest) {
        if (request.financeLiveEnabled == false) {
            visibleFinanceConfigs = emptyList()
            binanceStreamManager.stopAll()
            return
        }
        val visibleConfigs = resolveVisibleFinanceConfigs(request)
        if (visibleConfigs.isEmpty()) {
            visibleFinanceConfigs = emptyList()
            binanceStreamManager.stopAll()
            return
        }
        visibleFinanceConfigs = visibleConfigs
        val cached = snapshotStore.loadFinance()
        val cachedTilesById = cached?.tiles.orEmpty().associateBy { it.id }
        binanceStreamManager.reconcileVisible(visibleConfigs, cachedTilesById)

        val refreshIds = request.financeRefreshTileIds.toSet()
        val configsToFetch = visibleConfigs.filter { config ->
            request.force ||
                config.id in refreshIds ||
                cachedTilesById[config.id]?.let { tileNeedsRefresh(it, config) } ?: true
        }
        if (configsToFetch.isEmpty()) {
            return
        }
        executeRefresh("finance") {
            try {
                val fetchedTiles = configsToFetch.map { config ->
                    FinanceDataProvider.fetchTile(config).getOrThrow()
                        .copy(staleAfterMs = SyncCachePolicy.FINANCE_STALE_AFTER_MS)
                }
                binanceStreamManager.reconcileVisible(
                    visibleConfigs,
                    cachedTilesById + fetchedTiles.associateBy { it.id }
                )
                val tiles = (visibleConfigs.mapNotNull { config ->
                    fetchedTiles.firstOrNull { it.id == config.id } ?: cachedTilesById[config.id]
                }).ifEmpty { fetchedTiles }
                val snapshot = requireNotNull(FinanceSnapshot.fromTiles(tiles))
                snapshotStore.saveFinance(snapshot)
                send(SyncSnapshot(finance = snapshot))
            } catch (error: Throwable) {
                fileLog("Phone sync finance refresh failed: ${error.message}")
                sendError(SyncChannel.FINANCE, "Finance refresh failed")
            }
        }
    }

    private fun resolveVisibleFinanceConfigs(request: SyncRequest): List<FinanceDashboardTileConfig> {
        request.financeTiles.takeIf { it.isNotEmpty() }?.let { return it }
        val symbol = request.financeSymbol ?: return emptyList()
        val range = FinanceTimeRange.fromRange(request.financeRange)
        val cached = snapshotStore.loadFinance()
        return listOf(
            FinanceDashboardTileConfig(
                id = request.financeTileId ?: "phone_${symbol}_${range.range}",
                assetType = request.financeAssetType ?: cached?.tiles?.firstOrNull()?.assetType ?: FinanceAssetType.INDEX,
                symbol = symbol,
                range = range.range,
                chartType = request.financeChartType ?: cached?.tiles?.firstOrNull()?.chartType ?: FinanceChartType.LINE
            )
        )
    }

    private fun tileNeedsRefresh(tile: FinanceTileSnapshot, config: FinanceDashboardTileConfig): Boolean =
        SyncCachePolicy.isStale(tile.fetchedAtMs, tile.staleAfterMs) ||
            tile.symbol != config.symbol ||
            tile.range != config.range ||
            tile.assetType != config.assetType ||
            (config.chartType == FinanceChartType.CANDLE && tile.candles.isEmpty())

    private fun refreshFinanceForManual(callback: (Result<Unit>) -> Unit) {
        val configs = visibleFinanceConfigs
        if (configs.isEmpty()) {
            callback(Result.success(Unit))
            return
        }

        executeRefresh("manual finance", {
            callback(Result.failure(RejectedExecutionException("Refresh queue is full")))
        }) {
            try {
                val tiles = configs.map { config ->
                    FinanceDataProvider.fetchTile(config).getOrThrow()
                        .copy(staleAfterMs = SyncCachePolicy.FINANCE_STALE_AFTER_MS)
                }
                val snapshot = requireNotNull(FinanceSnapshot.fromTiles(tiles))
                snapshotStore.saveFinance(snapshot)
                send(SyncSnapshot(finance = snapshot))
                callback(Result.success(Unit))
            } catch (error: Throwable) {
                fileLog("Manual finance refresh failed: ${error.message}")
                sendError(SyncChannel.FINANCE, "Finance refresh failed")
                callback(Result.failure(error))
            }
        }
    }

    private fun ensureLocationService() {
        if (!hasLocationPermission()) return
        LocationWeatherService.observer = locationWeatherObserver
        LocationWeatherService.start(appContext)
    }

    private fun onLocationFixChanged(snapshot: LocationFix) {
        snapshotStore.saveLocation(snapshot)
        send(SyncSnapshot(location = snapshot))
    }

    private fun onWeatherSnapshotChanged(snapshot: WeatherSnapshot) {
        snapshotStore.saveWeather(snapshot)
        send(SyncSnapshot(weather = snapshot))
    }

    private fun onNotificationsSnapshotChanged(snapshot: PhoneNotificationsSnapshot) {
        send(SyncSnapshot(notifications = snapshot))
    }

    private fun pushGoogleState(state: PhoneGoogleAuthState) {
        outboundSyncExecutor.execute {
            if (isGlassesConnected) googleAuthStateSender(state)
        }
    }

    private fun send(snapshot: SyncSnapshot) {
        if (!isGlassesConnected || snapshot.isEmpty) return
        outboundSyncExecutor.execute {
            if (isGlassesConnected) syncMessengerProvider()?.sendSyncSnapshot(snapshot)
        }
    }


    private fun sendError(channel: SyncChannel, message: String) {
        if (!isGlassesConnected) return
        outboundSyncExecutor.execute {
            if (isGlassesConnected) syncMessengerProvider()?.sendSyncError(SyncError(channel, message))
        }
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
            fileLog("Dropped $label refresh because the bounded refresh executor is full")
            onRejected()
        }
    }

}
