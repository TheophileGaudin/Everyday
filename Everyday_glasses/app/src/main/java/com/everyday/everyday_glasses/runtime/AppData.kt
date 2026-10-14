package com.everyday.everyday_glasses.runtime

import android.location.Location
import com.everyday.everyday_glasses.connectivity.WidgetDataProvider
import com.everyday.everyday_glasses.widgets.map.backend.GlassesRouteTransport
import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.MapSnapshot
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.WeatherData
import com.everyday.shared.transport.DestinationSearchError
import com.everyday.shared.transport.DestinationSearchResult
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportResult

/** One kind of app data, such as the weather or the calendar. The kinds are listed in [AppChannels]. */
class AppChannel<T>(val name: String) {
    override fun toString(): String = name
}

/** A question the app can ask the widgets, such as "which finance tiles are on screen". Listed in [AppQueries]. */
class AppQuery<T>(val name: String) {
    override fun toString(): String = name
}

/**
 * The latest value of each kind of app data, and who wants to hear about it.
 *
 * Sources (the phone link, the glasses' GPS and status monitor) publish; widget modules subscribe.
 * A module that needs a value later, for a widget opened after the data arrived, reads [latest].
 * Everything runs on the main thread, like the widgets it feeds.
 */
class AppData {
    private val latestValues = HashMap<AppChannel<*>, Any?>()
    private val subscribers = HashMap<AppChannel<*>, MutableList<(Any?) -> Unit>>()
    private val answerers = HashMap<AppQuery<*>, () -> Any?>()

    /** Where widgets send their requests for data: the phone sync, plugged in by the activity. */
    var requests: WidgetDataProvider? = null

    /**
     * How the dashboard groups its reactions to several publishes into one screen update. The dashboard
     * sets it; until then, publishes are simply run.
     */
    var grouping: (() -> Unit) -> Unit = { publishes -> publishes() }

    /** Stores [value] as the latest on [channel], then tells its subscribers, in the order they subscribed. */
    fun <T> publish(channel: AppChannel<T>, value: T) {
        latestValues[channel] = value
        subscribers[channel]?.toList()?.forEach { it(value) }
    }

    /** The last value published on [channel]; null when nothing was published yet. */
    @Suppress("UNCHECKED_CAST")
    fun <T> latest(channel: AppChannel<T>): T? = latestValues[channel] as T?

    /** Calls [onValue] with every value published on [channel] from now on. */
    @Suppress("UNCHECKED_CAST")
    fun <T> subscribe(channel: AppChannel<T>, onValue: (T) -> Unit) {
        subscribers.getOrPut(channel) { mutableListOf() }.add { value -> onValue(value as T) }
    }

    /** Publishes several values as one change: the dashboard redraws once, after all of them. */
    fun publishTogether(publishes: () -> Unit) = grouping(publishes)

    /** Makes [answer] the way to answer [query]. The module that knows the answer registers it. */
    fun <T> answer(query: AppQuery<T>, answer: () -> T) {
        answerers[query] = answer
    }

    /** The current answer to [query]; null when nothing answers it. */
    @Suppress("UNCHECKED_CAST")
    fun <T> ask(query: AppQuery<T>): T? = answerers[query]?.invoke() as T?
}

/** Where the user is. */
data class Place(val town: String, val countryCode: String)

/** The phone's screen-mirroring state: whether it's on, and where to receive the stream. */
data class PhoneMirror(val enabled: Boolean, val phoneIp: String?)

/**
 * The kinds of app data widgets can listen to. A new kind of data is added here, together with the
 * source that publishes it; widgets never publish on these channels.
 */
object AppChannels {
    val phoneConnected     = AppChannel<Boolean>("phone connected")                  /** Whether the phone is connected to the glasses. */
    val status             = AppChannel<StatusSnapshot>("status")                    /** The clock, the batteries and the phone connection, from the glasses' status monitor. */
    val speed              = AppChannel<SpeedState?>("speed")                        /** The current speed; null when location access is refused or no speed is available. */
    val place              = AppChannel<Place>("place")                              /** The town and country the user is in. */
    val locationDenied     = AppChannel<Unit>("location denied")                     /** Location access was refused, so neither the place nor the weather can be known. */
    val weather            = AppChannel<WeatherData?>("weather")
    val calendar           = AppChannel<CalendarSnapshot?>("calendar")               /** The phone's calendar; null once calendar access is withdrawn. */
    val notifications      = AppChannel<PhoneNotificationsSnapshot>("notifications")
    val subtitleStatus     = AppChannel<SubtitleStatus>("subtitle status")
    val subtitleTranscript = AppChannel<SubtitleTranscript>("subtitle transcript")
    val finance            = AppChannel<FinanceSnapshot>("finance")
    val financeError       = AppChannel<String>("finance error")                     /** A finance fetch failed; the text says why. */
    val news               = AppChannel<NewsSnapshot>("news")
    val newsError          = AppChannel<String>("news error")                        /** A news fetch failed; the text says why. */
    val location           = AppChannel<Location>("location")                        /** Where the glasses are: their own GPS fix, or the phone's. */
    val mapSnapshot        = AppChannel<MapSnapshot>("map snapshot")                 /** Roads and points of interest around the user, from the phone. */
    val phoneRoutes        = AppChannel<GlassesRouteTransport?>("phone routes")      /** The phone's route and destination search service. */
    val routeResult        = AppChannel<RouteTransportResult>("route result")
    val routeError         = AppChannel<RouteTransportError>("route error")
    val destinationResult  = AppChannel<DestinationSearchResult>("destination result")
    val destinationError   = AppChannel<DestinationSearchError>("destination error")
    val liveCountry        = AppChannel<String>("live country")                      /** The country from a live location (not from saved data); it sets the automatic units. */
    val unitsChanged       = AppChannel<Unit>("units changed")                       /** km/h or mph, °C or °F changed; read them from the widget context's units. */
    val mirroring          = AppChannel<PhoneMirror>("mirroring")                    /** The phone started or stopped mirroring its screen. */
}

/** The questions the app's data sources can ask the widgets. */
object AppQueries {
    val financeTilesShown  = AppQuery<List<FinanceDashboardTileConfig>>("finance tiles shown") /** The finance tiles on screen, so only those are fetched; empty when none. */
    val newsCountry        = AppQuery<String?>("news country")                       /** The country the news is for. */
    val newsSource         = AppQuery<NewsSourceSpec?>("news source")                /** The news source the widget shows. */
}