package com.everyday.shared.sync

import org.json.JSONObject

/**
 * Describes everything channel-specific about a single sync feed: how to pull it
 * out of a [SyncSnapshot], how to put it back, and how to (de)serialize it.
 *
 * Adding a new sync feed used to mean editing a parallel branch in
 * [SyncProtocol], [SyncSnapshotStore] and a handful of coordinators. With codecs
 * those layers iterate [SyncProtocol.CHANNEL_CODECS] instead, so a new feed is
 * primarily "add a model + add a codec".
 *
 * The wire/prefs key for a channel is its [SyncChannel.wireName], matching the
 * keys used before codecs existed (so existing JSON and cached prefs still load).
 */
abstract class SyncChannelCodec<T : Any>(val channel: SyncChannel) {
    /** Pull this channel's snapshot out of the aggregate, or null when absent. */
    abstract fun extract(snapshot: SyncSnapshot): T?

    /** Return a copy of [into] with this channel's value set to [value]. */
    abstract fun place(into: SyncSnapshot, value: T?): SyncSnapshot

    abstract fun toJson(value: T): JSONObject

    abstract fun fromJson(json: JSONObject): T

    /**
     * Whether [value] should be written to persistent storage. Defaults to true;
     * channels with ephemeral/streaming variants can opt out.
     */
    open fun shouldPersist(value: T): Boolean = true

    // ---- Typed convenience used where the concrete T is known ----

    fun extractJson(snapshot: SyncSnapshot): JSONObject? = extract(snapshot)?.let(::toJson)

    fun placeFromJson(into: SyncSnapshot, json: JSONObject): SyncSnapshot =
        place(into, fromJson(json))

    // ---- Erased bridges used when iterating List<SyncChannelCodec<*>> ----

    fun extractAny(snapshot: SyncSnapshot): Any? = extract(snapshot)

    @Suppress("UNCHECKED_CAST")
    fun placeAny(into: SyncSnapshot, value: Any?): SyncSnapshot = place(into, value as T?)

    @Suppress("UNCHECKED_CAST")
    fun toJsonAny(value: Any): JSONObject = toJson(value as T)

    @Suppress("UNCHECKED_CAST")
    fun shouldPersistAny(value: Any): Boolean = shouldPersist(value as T)
}
