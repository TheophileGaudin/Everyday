package com.everyday.everyday_phone

import android.content.Context
import com.everyday.shared.sync.CalendarAccount
import com.everyday.shared.sync.CalendarEventSnapshot
import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.SyncCachePolicy
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncOrchestrator
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SyncSnapshotStore
import com.everyday.shared.sync.WeatherSnapshot
import com.everyday.shared.sync.isStale
import com.everyday.shared.transport.SyncMessenger
import java.util.concurrent.atomic.AtomicBoolean

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
    private val newsRefreshInFlight = AtomicBoolean(false)
    private val financeRefreshInFlight = AtomicBoolean(false)
    private var isGlassesConnected = false

    init {
        LocationWeatherService.onSnapshotChanged = ::onWeatherSnapshotChanged
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
        pushCachedSnapshots(channels = request.channels, pushGoogleState = true)
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

    fun release() {
        LocationWeatherService.onSnapshotChanged = null
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
        if (serviceSnapshot != null) {
            snapshotStore.saveWeather(serviceSnapshot)
        }

        val sharedCalendarSnapshot = googleAuthManager.loadCachedSnapshot()
            ?.toSharedCalendarSnapshot()
            ?.takeIf { SyncChannel.CALENDAR in channels }
        val cachedChannels = channels.toMutableSet()
        if (serviceSnapshot != null) cachedChannels -= SyncChannel.WEATHER
        if (sharedCalendarSnapshot != null) cachedChannels -= SyncChannel.CALENDAR
        val cachedSnapshot = syncOrchestrator.cachedSnapshot(cachedChannels)
        val snapshot = SyncSnapshot(
            weather = serviceSnapshot?.takeIf { SyncChannel.WEATHER in channels }
                ?: cachedSnapshot.weather,
            calendar = sharedCalendarSnapshot ?: cachedSnapshot.calendar,
            news = cachedSnapshot.news,
            finance = cachedSnapshot.finance
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

    private fun refreshFinance(request: SyncRequest) {
        val countryCode = request.countryCode
            ?: snapshotStore.loadWeather()?.countryCode
            ?: snapshotStore.loadFinance()?.country
        val symbol = request.financeSymbol
            ?: snapshotStore.loadFinance()?.symbol
            ?: FinanceDataProvider.defaultIndexForCountry(countryCode).symbol
        val range = FinanceTimeRange.fromRange(request.financeRange ?: snapshotStore.loadFinance()?.range)
        val cached = snapshotStore.loadFinance()
        if (!request.force && cached != null && !cached.isStale() && cached.symbol == symbol && cached.range == range.range) {
            return
        }
        if (!financeRefreshInFlight.compareAndSet(false, true)) return

        Thread {
            try {
                val snapshot = FinanceDataProvider.fetchChart(symbol, range).getOrThrow()
                    .copy(staleAfterMs = SyncCachePolicy.FINANCE_STALE_AFTER_MS)
                snapshotStore.saveFinance(snapshot)
                send(SyncSnapshot(finance = snapshot))
            } catch (error: Throwable) {
                fileLog("Phone sync finance refresh failed: ${error.message}")
                sendError(SyncChannel.FINANCE, "Finance refresh failed")
            } finally {
                financeRefreshInFlight.set(false)
            }
        }.start()
    }

    private fun ensureLocationService(forceRefresh: Boolean) {
        if (!hasLocationPermission()) return
        LocationWeatherService.onSnapshotChanged = ::onWeatherSnapshotChanged
        LocationWeatherService.start(appContext, forceRefresh = forceRefresh)
    }

    private fun onWeatherSnapshotChanged(snapshot: WeatherSnapshot) {
        snapshotStore.saveWeather(snapshot)
        send(SyncSnapshot(weather = snapshot))
    }

    private fun pushGoogleState(state: PhoneGoogleAuthState) {
        googleAuthStateSender(state)
    }

    private fun send(snapshot: SyncSnapshot) {
        if (!isGlassesConnected || snapshot.isEmpty) return
        syncMessengerProvider()?.sendSyncSnapshot(snapshot)
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
