package com.everyday.everyday_glasses.runtime

import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.WeatherData

/** One kind of app data, such as the weather or the calendar. The kinds are listed in [AppChannels]. */
class AppChannel<T>(val name: String) {
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
}

/** Where the user is. */
data class Place(val town: String, val countryCode: String)

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
}