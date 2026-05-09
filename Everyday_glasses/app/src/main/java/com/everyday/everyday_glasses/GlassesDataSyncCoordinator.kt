package com.everyday.everyday_glasses

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everyday.shared.sync.CalendarAccount
import com.everyday.shared.sync.CalendarEventSnapshot
import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.NewsSnapshot
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
import java.util.concurrent.atomic.AtomicBoolean

class GlassesDataSyncCoordinator(
    context: Context,
    private val widgetContainer: WidgetContainer,
    private val syncMessengerProvider: () -> SyncMessenger?,
    private val networkStatusProvider: NetworkStatusProvider,
    private val googleAuthCoordinatorProvider: () -> GoogleAuthCoordinator?,
    private val googleCalendarClientProvider: () -> GoogleCalendarClient?,
    private val localLocationProvider: () -> Location?,
    private val notifyContentChanged: () -> Unit
) {
    companion object {
        private const val TAG = "GlassesDataSync"
        private const val GEOCODE_MAPS_CO_API_KEY = "6974a768992ec642181119eby0e50e7"
    }

    private val snapshotStore = SyncSnapshotStore(context.applicationContext)
    private val syncOrchestrator = SyncOrchestrator(
        snapshotCache = snapshotStore,
        requestDispatcher = ::dispatchSyncRequest,
        snapshotApplier = ::applySnapshot
    )
    private val localWeatherInFlight = AtomicBoolean(false)
    private val localNewsInFlight = AtomicBoolean(false)
    private val localFinanceInFlight = AtomicBoolean(false)
    private val localCalendarInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var phoneConnected = false

    fun restoreCachedSnapshots() {
        syncOrchestrator.restoreCachedSnapshots(notify = false)
    }

    fun clearCalendarSnapshot() {
        snapshotStore.clearCalendar()
        postUi {
            widgetContainer.setGoogleCalendarSnapshot(null)
            notifyContentChanged()
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
        if (!phoneConnected && networkStatusProvider.isConnected() && snapshotStore.loadWeather().isStale()) {
            refreshWeatherLocally(force = false)
        }
    }

    fun onFinanceDataRequested(symbol: String, range: String, force: Boolean, reason: String) {
        syncOrchestrator.refresh(
            channels = setOf(SyncChannel.FINANCE),
            force = force,
            reason = reason,
            financeSymbol = symbol,
            financeRange = range
        )
    }

    fun onNewsDataRequested(countryCode: String, force: Boolean, reason: String) {
        syncOrchestrator.refresh(
            channels = setOf(SyncChannel.NEWS),
            force = force,
            reason = reason,
            countryCode = countryCode
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
        syncOrchestrator.cacheAndApplySnapshot(snapshot, notify = true)
    }

    fun onSyncErrorReceived(error: SyncError) {
        postUi {
            when (error.channel) {
                SyncChannel.NEWS -> widgetContainer.applyNewsError(error.message)
                SyncChannel.FINANCE -> widgetContainer.applyFinanceError(error.message)
                else -> Log.w(TAG, "Sync error ${error.channel}: ${error.message}")
            }
            notifyContentChanged()
        }
    }

    private fun dispatchSyncRequest(request: SyncRequest) {
        if (phoneConnected) {
            requestPhoneSync(request)
        } else {
            refreshLocally(request)
        }
    }

    private fun requestPhoneSync(request: SyncRequest) {
        val financeSelection = widgetContainer.currentFinanceSelection()
        val requestCountry = request.countryCode
            ?: widgetContainer.currentNewsCountryCode()
            ?: snapshotStore.loadWeather()?.countryCode
            ?: snapshotStore.loadNews()?.countryCode
        syncMessengerProvider()?.sendSyncRequest(
            request.copy(
                channels = request.channels,
                force = request.force,
                reason = request.reason,
                countryCode = requestCountry,
                financeSymbol = request.financeSymbol ?: financeSelection?.first,
                financeRange = request.financeRange ?: financeSelection?.second
            )
        )
    }

    private fun refreshLocally(request: SyncRequest) {
        if (!networkStatusProvider.isConnected()) {
            applySnapshot(snapshotStore.loadAll(), notify = true)
            return
        }
        if (SyncChannel.WEATHER in request.channels) refreshWeatherLocally(request.force)
        if (SyncChannel.CALENDAR in request.channels) refreshCalendarLocally(request.force)
        if (SyncChannel.NEWS in request.channels) {
            refreshNewsLocally(request.force, request.reason, request.countryCode)
        }
        if (SyncChannel.FINANCE in request.channels) {
            refreshFinanceLocally(
                request.force,
                request.reason,
                request.countryCode,
                request.financeSymbol,
                request.financeRange
            )
        }
    }

    private fun refreshWeatherLocally(force: Boolean) {
        val cached = snapshotStore.loadWeather()
        if (!force && !cached.isStale()) return
        val location = localLocationProvider() ?: return
        if (!localWeatherInFlight.compareAndSet(false, true)) return

        Thread {
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
                        timestampMs = System.currentTimeMillis(),
                        speedMps = null
                    )
                    snapshotStore.saveWeather(snapshot)
                    applyWeather(snapshot)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Local weather refresh failed", error)
            } finally {
                localWeatherInFlight.set(false)
            }
        }.start()
    }

    private fun refreshNewsLocally(force: Boolean, reason: String, countryCode: String?) {
        val resolvedCountry = countryCode
            ?: widgetContainer.currentNewsCountryCode()
            ?: snapshotStore.loadWeather()?.countryCode
            ?: NewsDataProvider.defaultCountryCode()
        val cached = snapshotStore.loadNews()
        if (!force && cached != null && !cached.isStale() && cached.countryCode == resolvedCountry) {
            return
        }
        if (!localNewsInFlight.compareAndSet(false, true)) return

        Thread {
            try {
                val snapshot = NewsDataProvider.fetchNews(resolvedCountry).getOrThrow()
                snapshotStore.saveNews(snapshot)
                applyNews(snapshot)
                Log.d(TAG, "Local news refresh success: reason=$reason country=$resolvedCountry")
            } catch (error: Throwable) {
                Log.w(TAG, "Local news refresh failed", error)
                postUi {
                    widgetContainer.applyNewsError("No news available")
                    notifyContentChanged()
                }
            } finally {
                localNewsInFlight.set(false)
            }
        }.start()
    }

    private fun refreshFinanceLocally(
        force: Boolean,
        reason: String,
        countryCode: String?,
        financeSymbol: String?,
        financeRange: String?
    ) {
        val country = countryCode ?: snapshotStore.loadWeather()?.countryCode
        val selection = widgetContainer.currentFinanceSelection()
        val symbol = financeSymbol
            ?: selection?.first
            ?: snapshotStore.loadFinance()?.symbol
            ?: FinanceDataProvider.defaultIndexForCountry(country).symbol
        val range = FinanceTimeRange.fromRange(financeRange ?: selection?.second ?: snapshotStore.loadFinance()?.range)
        val cached = snapshotStore.loadFinance()
        if (!force && cached != null && !cached.isStale() && cached.symbol == symbol && cached.range == range.range) {
            return
        }
        if (!localFinanceInFlight.compareAndSet(false, true)) return

        Thread {
            try {
                val snapshot = FinanceDataProvider.fetchChart(symbol, range).getOrThrow()
                snapshotStore.saveFinance(snapshot)
                applyFinance(snapshot)
                Log.d(TAG, "Local finance refresh success: reason=$reason symbol=$symbol range=${range.range}")
            } catch (error: Throwable) {
                Log.w(TAG, "Local finance refresh failed", error)
                postUi {
                    widgetContainer.applyFinanceError("No data")
                    notifyContentChanged()
                }
            } finally {
                localFinanceInFlight.set(false)
            }
        }.start()
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

    private fun applySnapshot(snapshot: SyncSnapshot, notify: Boolean) {
        postUi {
            snapshot.weather?.let(::applyWeather)
            snapshot.calendar?.let(::applyCalendar)
            snapshot.news?.let(::applyNews)
            snapshot.finance?.let(::applyFinance)
            if (notify) notifyContentChanged()
        }
    }

    private fun applyWeather(snapshot: WeatherSnapshot) {
        postUi { widgetContainer.applyWeatherSnapshot(snapshot) }
    }

    private fun applyNews(snapshot: NewsSnapshot) {
        postUi { widgetContainer.applyNewsSnapshot(snapshot) }
    }

    private fun applyFinance(snapshot: FinanceSnapshot) {
        postUi { widgetContainer.applyFinanceSnapshot(snapshot) }
    }

    private fun applyCalendar(snapshot: CalendarSnapshot) {
        postUi {
            val googleSnapshot = snapshot.toGoogleCalendarSnapshot()
            widgetContainer.setGoogleCalendarSnapshot(googleSnapshot)
            if (googleSnapshot.sourceMode == GoogleAuthState.AuthMode.PHONE_FALLBACK) {
                googleAuthCoordinatorProvider()?.onPhoneCalendarSnapshotReceived(googleSnapshot)
            }
        }
    }

    private fun postUi(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private fun CalendarSnapshot.toGoogleCalendarSnapshot(): GoogleCalendarSnapshot {
        return GoogleCalendarSnapshot(
            account = account?.let {
                GoogleAccountSummary(email = it.email, displayName = it.displayName)
            },
            events = events.map {
                GoogleCalendarEvent(
                    id = it.id,
                    summary = it.summary,
                    startIso = it.startIso,
                    htmlLink = it.htmlLink
                )
            },
            fetchedAtMs = fetchedAtMs,
            staleAfterMs = staleAfterMs,
            sourceMode = runCatching {
                GoogleAuthState.AuthMode.valueOf(sourceMode)
            }.getOrDefault(GoogleAuthState.AuthMode.PHONE_FALLBACK)
        )
    }

}
