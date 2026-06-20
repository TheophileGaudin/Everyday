package com.everyday.everyday_phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.everyday.shared.sync.CalendarAccount
import com.everyday.shared.sync.CalendarEventSnapshot
import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.FinanceTileSnapshot
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SyncCachePolicy
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncOrchestrator
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SyncSnapshotStore
import com.everyday.shared.sync.SpeedSnapshot
import com.everyday.shared.sync.WeatherSnapshot
import com.everyday.shared.sync.isStale
import com.everyday.shared.transport.SyncMessenger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private var isGlassesConnected = false
    @Volatile private var visibleFinanceConfigs: List<FinanceDashboardTileConfig> = emptyList()

    init {
        LocationWeatherService.onSnapshotChanged = ::onWeatherSnapshotChanged
        LocationWeatherService.onSpeedSnapshotChanged = ::onSpeedSnapshotChanged
        PhoneNotificationRepository.addObserver(notificationObserver, appContext)
    }

    fun onForeground() {
        if (!isGlassesConnected) return
        ensureLocationService(forceRefresh = true)
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
            binanceStreamManager.stopAll()
            return
        }

        ensureLocationService(forceRefresh = true)
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

    fun onCalendarSnapshotChanged(snapshot: PhoneGoogleCalendarSnapshot?) {
        snapshot ?: return
        val sharedSnapshot = snapshot.toSharedCalendarSnapshot()
        snapshotStore.saveCalendar(sharedSnapshot)
        send(SyncSnapshot(calendar = sharedSnapshot))
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
            "Finance refresh" to { done -> refreshFinanceForManual { done(it.exceptionOrNull()) } }
        )
        val remaining = AtomicInteger(operations.size)

        fun completeOne() {
            if (remaining.decrementAndGet() != 0) return

            pushCachedSnapshots(pushGoogleState = true)
            val resultErrors = synchronized(errors) { errors.toList() }
            manualRefreshInFlight.set(false)
            mainHandler.post {
                onComplete(PhoneManualRefreshResult(resultErrors))
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
        LocationWeatherService.onSnapshotChanged = null
        LocationWeatherService.onSpeedSnapshotChanged = null
        PhoneNotificationRepository.removeObserver(notificationObserver)
        binanceStreamManager.stopAll()
    }

    fun pushCachedSnapshots(
        channels: Set<SyncChannel> = SyncChannel.ALL,
        pushGoogleState: Boolean
    ) {
        if (!isGlassesConnected) return
        if (pushGoogleState) {
            pushGoogleState(googleAuthManager.loadState())
        }

        val serviceSnapshot = LocationWeatherService.instance?.getCurrentLocationData()
        val speedSnapshot = LocationWeatherService.instance?.getCurrentSpeedSnapshot()
        if (serviceSnapshot != null) {
            snapshotStore.saveWeather(serviceSnapshot)
        }
        if (speedSnapshot != null) {
            sendSpeed(speedSnapshot)
        }

        val sharedCalendarSnapshot = googleAuthManager.loadCachedSnapshot()
            ?.toSharedCalendarSnapshot()
            ?.takeIf { SyncChannel.CALENDAR in channels }
        val cachedChannels = channels.toMutableSet()
        if (serviceSnapshot != null) cachedChannels -= SyncChannel.WEATHER
        if (sharedCalendarSnapshot != null) cachedChannels -= SyncChannel.CALENDAR
        val cachedSnapshot = syncOrchestrator.cachedSnapshot(cachedChannels)
        val notificationSnapshot = PhoneNotificationRepository.current(appContext)
            .takeIf { SyncChannel.NOTIFICATIONS in channels }
        val snapshot = SyncSnapshot(
            weather = serviceSnapshot?.takeIf { SyncChannel.WEATHER in channels }
                ?: cachedSnapshot.weather,
            calendar = sharedCalendarSnapshot ?: cachedSnapshot.calendar,
            news = cachedSnapshot.news,
            finance = cachedSnapshot.finance,
            notifications = notificationSnapshot
        )
        send(snapshot)
    }

    private fun refreshChannels(request: SyncRequest) {
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
    }

    private fun refreshWeather(force: Boolean) {
        ensureLocationService(forceRefresh = force)
        val service = LocationWeatherService.instance
        if (service == null) {
            sendError(SyncChannel.WEATHER, "Location service is not ready")
            return
        }
        if (force || snapshotStore.loadWeather().isStale()) {
            service.forceUpdate()
        }
    }

    private fun refreshWeatherForManual(callback: (Result<WeatherSnapshot>) -> Unit) {
        if (!hasLocationPermission()) {
            callback(Result.failure(SecurityException("Location permission is not granted")))
            return
        }

        ensureLocationService(forceRefresh = false)

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
            val shared = cached.toSharedCalendarSnapshot()
            snapshotStore.saveCalendar(shared)
            send(SyncSnapshot(calendar = shared))
        }

        if (state.status != PhoneGoogleAuthState.Status.AUTHORIZED) {
            return
        }
        if (!force && !cached.toSharedCalendarSnapshotOrNull().isStale()) {
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
        val countryCode = request.countryCode
            ?: snapshotStore.loadWeather()?.countryCode
            ?: snapshotStore.loadNews()?.countryCode
            ?: NewsDataProvider.defaultCountryCode()
        val cached = snapshotStore.loadNews()
        if (!request.force && cached != null && !cached.isStale() && cached.countryCode == countryCode) {
            return
        }
        if (!newsRefreshInFlight.compareAndSet(false, true)) return

        Thread {
            try {
                val snapshot = NewsDataProvider.fetchNews(countryCode).getOrThrow()
                    .copy(staleAfterMs = SyncCachePolicy.NEWS_STALE_AFTER_MS)
                snapshotStore.saveNews(snapshot)
                send(SyncSnapshot(news = snapshot))
            } catch (error: Throwable) {
                fileLog("Phone sync news refresh failed: ${error.message}")
                sendError(SyncChannel.NEWS, "News refresh failed")
            } finally {
                newsRefreshInFlight.set(false)
            }
        }.start()
    }

    private fun refreshNewsForManual(callback: (Result<Unit>) -> Unit) {
        val countryCode = snapshotStore.loadWeather()?.countryCode
            ?: snapshotStore.loadNews()?.countryCode
            ?: NewsDataProvider.defaultCountryCode()

        Thread {
            try {
                val snapshot = NewsDataProvider.fetchNews(countryCode).getOrThrow()
                    .copy(staleAfterMs = SyncCachePolicy.NEWS_STALE_AFTER_MS)
                snapshotStore.saveNews(snapshot)
                send(SyncSnapshot(news = snapshot))
                callback(Result.success(Unit))
            } catch (error: Throwable) {
                fileLog("Manual news refresh failed: ${error.message}")
                sendError(SyncChannel.NEWS, "News refresh failed")
                callback(Result.failure(error))
            }
        }.start()
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
        Thread {
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
                val tile = tiles.first()
                val snapshot = FinanceSnapshot(
                    symbol = tile.symbol,
                    displayName = tile.displayName,
                    country = tile.country,
                    range = tile.range,
                    rangeLabel = tile.rangeLabel,
                    points = tile.points,
                    percentChange = tile.percentChange,
                    assetType = tile.assetType,
                    chartType = tile.chartType,
                    tiles = tiles,
                    fetchedAtMs = tile.fetchedAtMs,
                    staleAfterMs = tile.staleAfterMs
                )
                snapshotStore.saveFinance(snapshot)
                send(SyncSnapshot(finance = snapshot))
            } catch (error: Throwable) {
                fileLog("Phone sync finance refresh failed: ${error.message}")
                sendError(SyncChannel.FINANCE, "Finance refresh failed")
            }
        }.start()
    }

    private fun resolveVisibleFinanceConfigs(request: SyncRequest): List<FinanceDashboardTileConfig> {
        request.financeTiles.takeIf { it.isNotEmpty() }?.let { return it }
        val symbol = request.financeSymbol ?: return emptyList()
        val range = FinanceTimeRange.fromRange(request.financeRange)
        val cached = snapshotStore.loadFinance()
        return listOf(
            FinanceDashboardTileConfig(
                id = request.financeTileId ?: "phone_${symbol}_${range.range}",
                assetType = request.financeAssetType ?: cached?.assetType ?: FinanceAssetType.INDEX,
                symbol = symbol,
                range = range.range,
                chartType = request.financeChartType ?: cached?.chartType ?: FinanceChartType.LINE
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

        Thread {
            try {
                val tiles = configs.map { config ->
                    FinanceDataProvider.fetchTile(config).getOrThrow()
                        .copy(staleAfterMs = SyncCachePolicy.FINANCE_STALE_AFTER_MS)
                }
                val tile = tiles.first()
                val snapshot = FinanceSnapshot(
                    symbol = tile.symbol,
                    displayName = tile.displayName,
                    country = tile.country,
                    range = tile.range,
                    rangeLabel = tile.rangeLabel,
                    points = tile.points,
                    percentChange = tile.percentChange,
                    assetType = tile.assetType,
                    chartType = tile.chartType,
                    tiles = tiles,
                    fetchedAtMs = tile.fetchedAtMs,
                    staleAfterMs = tile.staleAfterMs
                )
                snapshotStore.saveFinance(snapshot)
                send(SyncSnapshot(finance = snapshot))
                callback(Result.success(Unit))
            } catch (error: Throwable) {
                fileLog("Manual finance refresh failed: ${error.message}")
                sendError(SyncChannel.FINANCE, "Finance refresh failed")
                callback(Result.failure(error))
            }
        }.start()
    }

    private fun ensureLocationService(forceRefresh: Boolean) {
        if (!hasLocationPermission()) return
        LocationWeatherService.onSnapshotChanged = ::onWeatherSnapshotChanged
        LocationWeatherService.onSpeedSnapshotChanged = ::onSpeedSnapshotChanged
        LocationWeatherService.start(appContext, forceRefresh = forceRefresh)
    }

    private fun onWeatherSnapshotChanged(snapshot: WeatherSnapshot) {
        snapshotStore.saveWeather(snapshot)
        send(SyncSnapshot(weather = snapshot))
    }

    private fun onSpeedSnapshotChanged(snapshot: SpeedSnapshot) {
        sendSpeed(snapshot)
    }

    private fun onNotificationsSnapshotChanged(snapshot: PhoneNotificationsSnapshot) {
        send(SyncSnapshot(notifications = snapshot))
    }

    private fun pushGoogleState(state: PhoneGoogleAuthState) {
        googleAuthStateSender(state)
    }

    private fun send(snapshot: SyncSnapshot) {
        if (!isGlassesConnected || snapshot.isEmpty) return
        syncMessengerProvider()?.sendSyncSnapshot(snapshot)
    }

    private fun sendSpeed(snapshot: SpeedSnapshot) {
        if (!isGlassesConnected) return
        syncMessengerProvider()?.sendSpeedSnapshot(snapshot)
    }

    private fun sendError(channel: SyncChannel, message: String) {
        if (!isGlassesConnected) return
        syncMessengerProvider()?.sendSyncError(SyncError(channel, message))
    }

    private fun PhoneGoogleCalendarSnapshot.toSharedCalendarSnapshot(): CalendarSnapshot {
        return CalendarSnapshot(
            account = CalendarAccount(
                email = account.email,
                displayName = account.displayName
            ),
            events = events.map { event ->
                CalendarEventSnapshot(
                    id = event.id,
                    summary = event.summary,
                    startIso = event.startIso,
                    htmlLink = event.htmlLink
                )
            },
            fetchedAtMs = fetchedAtMs,
            staleAfterMs = staleAfterMs,
            sourceMode = "PHONE_FALLBACK"
        )
    }

    private fun PhoneGoogleCalendarSnapshot?.toSharedCalendarSnapshotOrNull(): CalendarSnapshot? =
        this?.toSharedCalendarSnapshot()

}
