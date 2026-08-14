package com.everyday.shared.transport

import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleProtocol
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncProtocol
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import org.json.JSONObject

enum class PointerAction(val wireName: String) {
    DOWN("down"),
    MOVE("move"),
    UP("up"),
    TAP("tap"),
    DOUBLE_TAP("double_tap"),
    TRIPLE_TAP("triple_tap"),
    POINTER_COUNT("pointer_count");

    companion object {
        fun fromWireName(value: String): PointerAction? = values().firstOrNull { it.wireName == value }
    }
}

enum class TransportGoogleAuthStatus(val wireName: String) {
    SIGNED_OUT("signed_out"),
    AUTHORIZING("authorizing"),
    AUTHORIZED("authorized"),
    ERROR("error");

    companion object {
        fun fromWireName(value: String): TransportGoogleAuthStatus? = values().firstOrNull { it.wireName == value }
    }
}

data class TransportGoogleAccount(val email: String, val displayName: String? = null)

enum class AssistantControlAction(val wireName: String) {
    START("start"), KEEPALIVE("keepalive"), STOP("stop"), CANCEL("cancel");

    companion object {
        fun fromWireName(value: String): AssistantControlAction? = values().firstOrNull { it.wireName == value }
    }
}

enum class AssistantSessionState(val wireName: String) {
    UNCONFIGURED("unconfigured"), IDLE("idle"), LISTENING("listening"), PROCESSING("processing"),
    SUCCESS("success"), ERROR("error");

    companion object {
        fun fromWireName(value: String): AssistantSessionState? = values().firstOrNull { it.wireName == value }
    }
}

enum class AssistantSessionOrigin(val wireName: String) {
    GLASSES("glasses"), PHONE("phone");

    companion object {
        fun fromWireName(value: String): AssistantSessionOrigin? = values().firstOrNull { it.wireName == value }
    }
}

data class AssistantControl(
    val sessionId: String,
    val action: AssistantControlAction,
    val timestampMs: Long
)

data class AssistantStatus(
    val configured: Boolean,
    val ready: Boolean,
    val state: AssistantSessionState,
    val sessionId: String? = null,
    val origin: AssistantSessionOrigin? = null,
    val detail: String? = null,
    val timestampMs: Long
)

sealed class TransportMessage(val kind: String) {
    data class Pointer(val action: PointerAction, val dx: Int = 0, val dy: Int = 0, val pointerCount: Int = 1) :
        TransportMessage("input.pointer")
    data class KeyInput(val key: String) : TransportMessage("input.key")
    data class LongPress(val clipboard: String?) : TransportMessage("input.long_press")
    object ClipboardRequest : TransportMessage("clipboard.request")
    data class ClipboardUpdate(val content: String) : TransportMessage("clipboard.update")
    data class MirrorRequest(val start: Boolean) : TransportMessage("mirror.request")
    data class MirrorState(val enabled: Boolean, val ip: String? = null, val port: Int? = null, val wifiDirect: Boolean = false) :
        TransportMessage("mirror.state")
    data class TextFieldFocus(val focused: Boolean, val fieldId: String? = null) : TransportMessage("text_field.focus")
    object GoogleAuthRequest : TransportMessage("google_auth.request")
    object GoogleAuthDisconnect : TransportMessage("google_auth.disconnect")
    data class GoogleAuthState(
        val status: TransportGoogleAuthStatus,
        val account: TransportGoogleAccount? = null,
        val detail: String? = null
    ) : TransportMessage("google_auth.state")
    data class OpenBrowser(val address: String) : TransportMessage("browser.open")
    data class DeviceStatus(
        val time: String? = null,
        val phoneBatteryPercent: Int? = null,
        val glassesBatteryPercent: Int? = null
    ) : TransportMessage("device.status")
    data class SyncRequestMessage(val request: SyncRequest) : TransportMessage("sync.request")
    data class SyncSnapshotMessage(val snapshot: SyncSnapshot) : TransportMessage("sync.snapshot")
    data class SyncErrorMessage(val error: SyncError) : TransportMessage("sync.error")
    data class SubtitleControlMessage(val control: SubtitleControl) : TransportMessage("subtitle.control")
    data class SubtitleStatusMessage(val status: SubtitleStatus) : TransportMessage("subtitle.status")
    data class SubtitleTranscriptMessage(val transcript: SubtitleTranscript) : TransportMessage("subtitle.transcript")
    data class AssistantControlMessage(val control: AssistantControl) : TransportMessage("assistant.control")
    data class AssistantStatusMessage(val status: AssistantStatus) : TransportMessage("assistant.status")
    data class Image(val fileName: String, val base64Data: String) : TransportMessage("file.image")
    data class RouteRequestMessage(val request: RouteTransportRequest) : TransportMessage("route.request")
    data class RouteResultMessage(val result: RouteTransportResult) : TransportMessage("route.result")
    data class RouteErrorMessage(val error: RouteTransportError) : TransportMessage("route.error")
    data class RouteCancellationMessage(val cancellation: RouteTransportCancellation) : TransportMessage("route.cancel")
    data class DestinationSearchRequestMessage(val request: DestinationSearchRequest) : TransportMessage("destination.search.request")
    data class DestinationSearchResultMessage(val result: DestinationSearchResult) : TransportMessage("destination.search.result")
    data class DestinationSearchErrorMessage(val error: DestinationSearchError) : TransportMessage("destination.search.error")
    data class DestinationSearchCancellationMessage(val cancellation: DestinationSearchCancellation) : TransportMessage("destination.search.cancel")
}

sealed class TransportDecodeResult {
    data class Success(val message: TransportMessage) : TransportDecodeResult()
    data class Failure(val reason: String, val kind: String? = null) : TransportDecodeResult()
}

object TransportProtocol {
    /** Maximum line size accepted before JSON parsing allocates nested payload objects. */
    const val MAX_MESSAGE_CHARS = 8_000_000
    const val MAX_ROUTE_MESSAGE_CHARS = 2_000_000

    fun encode(message: TransportMessage): String = JSONObject()
        .put("kind", message.kind)
        .put("payload", message.toPayload())
        .toString()

    fun decode(raw: String): TransportDecodeResult {
        if (raw.length > MAX_MESSAGE_CHARS) {
            return TransportDecodeResult.Failure("message too large")
        }
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return TransportDecodeResult.Failure("invalid JSON")
        }
        val kind = root.optString("kind").takeIf { it.isNotBlank() }
            ?: return TransportDecodeResult.Failure("missing kind")
        if (kind.startsWith("route.") && raw.length > MAX_ROUTE_MESSAGE_CHARS) {
            return TransportDecodeResult.Failure("route message too large", kind)
        }
        val payload = root.optJSONObject("payload")
            ?: return TransportDecodeResult.Failure("missing payload", kind)
        return try {
            decodePayload(kind, payload)?.let(TransportDecodeResult::Success)
                ?: TransportDecodeResult.Failure("unknown kind", kind)
        } catch (error: InvalidPayload) {
            TransportDecodeResult.Failure(error.message ?: "malformed payload", kind)
        } catch (_: Exception) {
            TransportDecodeResult.Failure("malformed payload", kind)
        }
    }

    private fun decodePayload(kind: String, payload: JSONObject): TransportMessage? = when (kind) {
        "input.pointer" -> TransportMessage.Pointer(
            action = PointerAction.fromWireName(payload.requiredString("action"))
                ?: throw InvalidPayload("invalid pointer action"),
            dx = payload.optionalInt("dx") ?: 0,
            dy = payload.optionalInt("dy") ?: 0,
            pointerCount = (payload.optionalInt("pointerCount") ?: 1)
                .takeIf { it >= 1 } ?: throw InvalidPayload("invalid pointer count")
        )
        "input.key" -> TransportMessage.KeyInput(payload.requiredStringAllowEmpty("key"))
        "input.long_press" -> TransportMessage.LongPress(payload.optionalStringAllowEmpty("clipboard"))
        "clipboard.request" -> TransportMessage.ClipboardRequest
        "clipboard.update" -> TransportMessage.ClipboardUpdate(payload.requiredStringAllowEmpty("content"))
        "mirror.request" -> TransportMessage.MirrorRequest(payload.requiredBoolean("start"))
        "mirror.state" -> {
            val enabled = payload.requiredBoolean("enabled")
            val ip = payload.optionalString("ip")
            if (enabled && ip.isNullOrBlank()) throw InvalidPayload("enabled mirror missing ip")
            TransportMessage.MirrorState(
                enabled = enabled,
                ip = ip,
                port = payload.optionalInt("port"),
                wifiDirect = payload.optionalBoolean("wifiDirect") ?: false
            )
        }
        "text_field.focus" -> TransportMessage.TextFieldFocus(
            payload.requiredBoolean("focused"), payload.optionalStringAllowEmpty("fieldId")
        )
        "google_auth.request" -> TransportMessage.GoogleAuthRequest
        "google_auth.disconnect" -> TransportMessage.GoogleAuthDisconnect
        "google_auth.state" -> {
            val status = TransportGoogleAuthStatus.fromWireName(payload.requiredString("status"))
                ?: throw InvalidPayload("invalid Google auth status")
            val account = payload.optJSONObject("account")?.let {
                TransportGoogleAccount(it.requiredString("email"), it.optionalStringAllowEmpty("displayName"))
            }
            TransportMessage.GoogleAuthState(status, account, payload.optionalStringAllowEmpty("detail"))
        }
        "browser.open" -> TransportMessage.OpenBrowser(payload.requiredString("address"))
        "device.status" -> {
            val status = TransportMessage.DeviceStatus(
                time = payload.optionalString("time"),
                phoneBatteryPercent = payload.optionalBattery("phoneBatteryPercent"),
                glassesBatteryPercent = payload.optionalBattery("glassesBatteryPercent")
            )
            if (status.time == null && status.phoneBatteryPercent == null && status.glassesBatteryPercent == null) {
                throw InvalidPayload("empty device status")
            }
            status
        }
        "sync.request" -> {
            if (payload.optJSONArray("channels") == null || payload.opt("force") !is Boolean ||
                payload.opt("reason") !is String || payload.opt("requestedAtMs") !is Number) {
                throw InvalidPayload("invalid sync request")
            }
            TransportMessage.SyncRequestMessage(
                SyncProtocol.requestFromJson(payload) ?: throw InvalidPayload("invalid sync request")
            )
        }
        "sync.snapshot" -> {
            if (SyncProtocol.CHANNEL_CODECS.none { payload.optJSONObject(it.channel.wireName) != null }) {
                throw InvalidPayload("empty sync snapshot")
            }
            TransportMessage.SyncSnapshotMessage(SyncProtocol.snapshotFromJson(payload))
        }
        "sync.error" -> {
            payload.requiredString("channel")
            payload.requiredStringAllowEmpty("message")
            if (payload.opt("timestampMs") !is Number) throw InvalidPayload("invalid timestampMs")
            TransportMessage.SyncErrorMessage(
                SyncProtocol.errorFromJson(payload) ?: throw InvalidPayload("invalid sync error")
            )
        }
        "subtitle.control" -> {
            payload.requiredString("action")
            if (payload.opt("requestedAtMs") !is Number) throw InvalidPayload("invalid requestedAtMs")
            TransportMessage.SubtitleControlMessage(
                SubtitleProtocol.controlFromJson(payload) ?: throw InvalidPayload("invalid subtitle control")
            )
        }
        "subtitle.status" -> {
            payload.requiredString("state")
            if (payload.opt("timestampMs") !is Number) throw InvalidPayload("invalid timestampMs")
            TransportMessage.SubtitleStatusMessage(
                SubtitleProtocol.statusFromJson(payload) ?: throw InvalidPayload("invalid subtitle status")
            )
        }
        "subtitle.transcript" -> {
            payload.requiredString("sessionId")
            payload.requiredStringAllowEmpty("text")
            payload.requiredBoolean("isFinal")
            if (payload.opt("segmentId") !is Number || payload.opt("timestampMs") !is Number) {
                throw InvalidPayload("invalid subtitle transcript")
            }
            TransportMessage.SubtitleTranscriptMessage(
                SubtitleProtocol.transcriptFromJson(payload) ?: throw InvalidPayload("invalid subtitle transcript")
            )
        }
        "assistant.control" -> {
            val sessionId = payload.requiredString("sessionId")
            if (sessionId.length > 128) throw InvalidPayload("invalid sessionId")
            val action = AssistantControlAction.fromWireName(payload.requiredString("action"))
                ?: throw InvalidPayload("invalid assistant action")
            val timestampMs = payload.requiredLong("timestampMs")
            TransportMessage.AssistantControlMessage(AssistantControl(sessionId, action, timestampMs))
        }
        "assistant.status" -> {
            val configured = payload.requiredBoolean("configured")
            val ready = payload.requiredBoolean("ready")
            if (ready && !configured) throw InvalidPayload("ready assistant is not configured")
            val state = AssistantSessionState.fromWireName(payload.requiredString("state"))
                ?: throw InvalidPayload("invalid assistant state")
            val sessionId = payload.optionalString("sessionId")?.also {
                if (it.length > 128) throw InvalidPayload("invalid sessionId")
            }
            val origin = payload.optionalString("origin")?.let {
                AssistantSessionOrigin.fromWireName(it) ?: throw InvalidPayload("invalid assistant origin")
            }
            val detail = payload.optionalStringAllowEmpty("detail")?.take(240)
            val timestampMs = payload.requiredLong("timestampMs")
            TransportMessage.AssistantStatusMessage(
                AssistantStatus(configured, ready, state, sessionId, origin, detail, timestampMs)
            )
        }
        "file.image" -> {
            val base64Data = payload.requiredString("base64Data")
            if (base64Data.length % 4 != 0 || !base64Data.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) {
                throw InvalidPayload("invalid base64Data")
            }
            TransportMessage.Image(payload.requiredString("fileName"), base64Data)
        }
        "route.request" -> TransportMessage.RouteRequestMessage(RouteTransportProtocol.requestFromJson(payload))
        "route.result" -> TransportMessage.RouteResultMessage(RouteTransportProtocol.resultFromJson(payload))
        "route.error" -> TransportMessage.RouteErrorMessage(RouteTransportProtocol.errorFromJson(payload))
        "route.cancel" -> TransportMessage.RouteCancellationMessage(RouteTransportProtocol.cancellationFromJson(payload))
        "destination.search.request" -> TransportMessage.DestinationSearchRequestMessage(DestinationSearchProtocol.requestFromJson(payload))
        "destination.search.result" -> TransportMessage.DestinationSearchResultMessage(DestinationSearchProtocol.resultFromJson(payload))
        "destination.search.error" -> TransportMessage.DestinationSearchErrorMessage(DestinationSearchProtocol.errorFromJson(payload))
        "destination.search.cancel" -> TransportMessage.DestinationSearchCancellationMessage(DestinationSearchProtocol.cancellationFromJson(payload))
        else -> null
    }

    private fun TransportMessage.toPayload(): JSONObject = when (this) {
        is TransportMessage.Pointer -> JSONObject().put("action", action.wireName).put("dx", dx).put("dy", dy)
            .put("pointerCount", pointerCount)
        is TransportMessage.KeyInput -> JSONObject().put("key", key)
        is TransportMessage.LongPress -> JSONObject().putNullable("clipboard", clipboard)
        TransportMessage.ClipboardRequest -> JSONObject()
        is TransportMessage.ClipboardUpdate -> JSONObject().put("content", content)
        is TransportMessage.MirrorRequest -> JSONObject().put("start", start)
        is TransportMessage.MirrorState -> JSONObject().put("enabled", enabled).putNullable("ip", ip)
            .putNullable("port", port).put("wifiDirect", wifiDirect)
        is TransportMessage.TextFieldFocus -> JSONObject().put("focused", focused).putNullable("fieldId", fieldId)
        TransportMessage.GoogleAuthRequest, TransportMessage.GoogleAuthDisconnect -> JSONObject()
        is TransportMessage.GoogleAuthState -> JSONObject().put("status", status.wireName)
            .putNullable("account", account?.let { JSONObject().put("email", it.email).putNullable("displayName", it.displayName) })
            .putNullable("detail", detail)
        is TransportMessage.OpenBrowser -> JSONObject().put("address", address)
        is TransportMessage.DeviceStatus -> JSONObject().putNullable("time", time)
            .putNullable("phoneBatteryPercent", phoneBatteryPercent)
            .putNullable("glassesBatteryPercent", glassesBatteryPercent)
        is TransportMessage.SyncRequestMessage -> SyncProtocol.requestToJson(request)
        is TransportMessage.SyncSnapshotMessage -> SyncProtocol.snapshotToJson(snapshot)
        is TransportMessage.SyncErrorMessage -> SyncProtocol.errorToJson(error)
        is TransportMessage.SubtitleControlMessage -> SubtitleProtocol.controlToJson(control)
        is TransportMessage.SubtitleStatusMessage -> SubtitleProtocol.statusToJson(status)
        is TransportMessage.SubtitleTranscriptMessage -> SubtitleProtocol.transcriptToJson(transcript)
        is TransportMessage.AssistantControlMessage -> JSONObject()
            .put("sessionId", control.sessionId)
            .put("action", control.action.wireName)
            .put("timestampMs", control.timestampMs)
        is TransportMessage.AssistantStatusMessage -> JSONObject()
            .put("configured", status.configured)
            .put("ready", status.ready)
            .put("state", status.state.wireName)
            .putNullable("sessionId", status.sessionId)
            .putNullable("origin", status.origin?.wireName)
            .putNullable("detail", status.detail)
            .put("timestampMs", status.timestampMs)
        is TransportMessage.Image -> JSONObject().put("fileName", fileName).put("base64Data", base64Data)
        is TransportMessage.RouteRequestMessage -> RouteTransportProtocol.requestToJson(request)
        is TransportMessage.RouteResultMessage -> RouteTransportProtocol.resultToJson(result)
        is TransportMessage.RouteErrorMessage -> RouteTransportProtocol.errorToJson(error)
        is TransportMessage.RouteCancellationMessage -> RouteTransportProtocol.cancellationToJson(cancellation)
        is TransportMessage.DestinationSearchRequestMessage -> DestinationSearchProtocol.requestToJson(request)
        is TransportMessage.DestinationSearchResultMessage -> DestinationSearchProtocol.resultToJson(result)
        is TransportMessage.DestinationSearchErrorMessage -> DestinationSearchProtocol.errorToJson(error)
        is TransportMessage.DestinationSearchCancellationMessage -> DestinationSearchProtocol.cancellationToJson(cancellation)
    }

    private class InvalidPayload(message: String) : IllegalArgumentException(message)

    private fun JSONObject.requiredString(name: String): String =
        requiredStringAllowEmpty(name).takeIf { it.isNotBlank() } ?: throw InvalidPayload("missing $name")

    private fun JSONObject.requiredStringAllowEmpty(name: String): String {
        if (!has(name) || isNull(name) || opt(name) !is String) throw InvalidPayload("missing $name")
        return getString(name)
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null
        else (opt(name) as? String)?.takeIf { it.isNotBlank() }
            ?: throw InvalidPayload("invalid $name")

    private fun JSONObject.optionalStringAllowEmpty(name: String): String? =
        if (!has(name) || isNull(name)) null else (opt(name) as? String) ?: throw InvalidPayload("invalid $name")

    private fun JSONObject.requiredBoolean(name: String): Boolean {
        if (!has(name) || opt(name) !is Boolean) throw InvalidPayload("missing $name")
        return getBoolean(name)
    }

    private fun JSONObject.optionalBoolean(name: String): Boolean? =
        if (!has(name) || isNull(name)) null else (opt(name) as? Boolean) ?: throw InvalidPayload("invalid $name")

    private fun JSONObject.optionalInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name) as? Number ?: throw InvalidPayload("invalid $name")
        val long = value.toLong()
        if (value.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) throw InvalidPayload("invalid $name")
        return long.toInt()
    }

    private fun JSONObject.requiredLong(name: String): Long {
        if (!has(name) || isNull(name)) throw InvalidPayload("missing $name")
        val value = opt(name) as? Number ?: throw InvalidPayload("invalid $name")
        val long = value.toLong()
        if (value.toDouble() != long.toDouble()) throw InvalidPayload("invalid $name")
        return long
    }

    private fun JSONObject.optionalBattery(name: String): Int? = optionalInt(name)?.takeIf { it in 0..100 }
        ?: if (has(name) && !isNull(name)) throw InvalidPayload("invalid $name") else null

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
}
