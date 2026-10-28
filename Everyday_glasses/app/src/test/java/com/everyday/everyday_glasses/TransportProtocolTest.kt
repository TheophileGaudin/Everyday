package com.everyday.everyday_glasses

import com.everyday.shared.sync.LocationFix
import com.everyday.shared.sync.LocationFixQuality
import com.everyday.shared.sync.WeatherSnapshot
import com.everyday.shared.sync.CalendarAccount
import com.everyday.shared.sync.CalendarEventSnapshot
import com.everyday.shared.sync.CalendarSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleControlAction
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleStatusState
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.usableSpeedMps
import com.everyday.shared.transport.PointerAction
import com.everyday.shared.transport.AssistantControl
import com.everyday.shared.transport.AssistantControlAction
import com.everyday.shared.transport.AssistantSessionOrigin
import com.everyday.shared.transport.AssistantSessionState
import com.everyday.shared.transport.AssistantStatus
import com.everyday.shared.transport.TransportDecodeResult
import com.everyday.shared.transport.TransportGoogleAccount
import com.everyday.shared.transport.TransportGoogleAuthStatus
import com.everyday.shared.transport.TransportMessage
import com.everyday.shared.transport.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportProtocolTest {
    @Test
    fun `every transport message round trips through one envelope`() {
        val specialText = "quotes \" slash \\ Unicode café 東京\nsecond line\rthird"
        val messages = listOf(
            TransportMessage.Pointer(PointerAction.DOWN, pointerCount = 2),
            TransportMessage.Pointer(PointerAction.MOVE, dx = -12, dy = 34, pointerCount = 3),
            TransportMessage.Pointer(PointerAction.UP),
            TransportMessage.Pointer(PointerAction.TAP),
            TransportMessage.Pointer(PointerAction.DOUBLE_TAP),
            TransportMessage.Pointer(PointerAction.TRIPLE_TAP),
            TransportMessage.Pointer(PointerAction.POINTER_COUNT, pointerCount = 2),
            TransportMessage.KeyInput(specialText),
            TransportMessage.LongPress(specialText),
            TransportMessage.ClipboardRequest,
            TransportMessage.ClipboardUpdate(specialText),
            TransportMessage.MirrorRequest(true),
            TransportMessage.MirrorState(true, "192.168.49.1", 5050, wifiDirect = true),
            TransportMessage.MirrorState(
                true, "192.168.49.1", 5050, wifiDirect = true,
                networkName = "DIRECT-Ev-Everyday", passphrase = "secret-pass", lanIp = "192.168.1.20"
            ),
            TransportMessage.MirrorState(false),
            TransportMessage.HttpRelayRequest(
                "req-1", "POST", "https://generativelanguage.googleapis.com/v1beta/interactions",
                mapOf("x-goog-api-key" to "key"), "application/json", "e30="
            ),
            TransportMessage.HttpRelayRequest("req-2", "GET", "https://html.duckduckgo.com/html/?q=x", emptyMap(), null, null),
            TransportMessage.HttpRelayResponse("req-1", 200, "application/json", "e30=", null),
            TransportMessage.HttpRelayResponse("req-2", 0, null, null, "UnknownHostException: no network"),
            TransportMessage.TextFieldFocus(true, "field-1"),
            TransportMessage.GoogleAuthRequest,
            TransportMessage.GoogleAuthDisconnect,
            TransportMessage.GoogleAuthState(
                TransportGoogleAuthStatus.AUTHORIZED,
                TransportGoogleAccount("person+test@example.com", "Test User"),
                "ready"
            ),
            TransportMessage.OpenBrowser("https://example.com/a?q=\"x y\""),
            TransportMessage.DeviceStatus("12:34:56", phoneBatteryPercent = 80, glassesBatteryPercent = 70),
            TransportMessage.SyncRequestMessage(
                SyncRequest(setOf(SyncChannel.NEWS), force = true, reason = "test", requestedAtMs = 10L)
            ),
            TransportMessage.SyncSnapshotMessage(
                SyncSnapshot(
                    location = LocationFix(51.5, -0.1, 4f, 10f, 4.5f, LocationFixQuality.VALID, 15L),
                    weather = WeatherSnapshot("London", "GB", null, 16L),
                    calendar = CalendarSnapshot(
                        account = CalendarAccount("person@example.com", "Person"),
                        events = listOf(CalendarEventSnapshot("1", "Meeting", "2026-07-20T10:00:00Z")),
                        fetchedAtMs = 17L
                    )
                )
            ),
            TransportMessage.SyncErrorMessage(SyncError(SyncChannel.NEWS, "failed", 20L)),
            TransportMessage.SubtitleControlMessage(
                SubtitleControl(SubtitleControlAction.START, requestedAtMs = 40L)
            ),
            TransportMessage.SubtitleStatusMessage(
                SubtitleStatus(SubtitleStatusState.LISTENING, "active", "session", 50L)
            ),
            TransportMessage.SubtitleTranscriptMessage(
                SubtitleTranscript("session", 2L, specialText, true, 60L)
            ),
            TransportMessage.AssistantControlMessage(
                AssistantControl("voice-1", AssistantControlAction.KEEPALIVE, 70L)
            ),
            TransportMessage.AssistantStatusMessage(
                AssistantStatus(true, true, AssistantSessionState.LISTENING, "voice-1", AssistantSessionOrigin.PHONE, "Listening", 80L)
            ),
            TransportMessage.Image("photo \"one\".jpg", "AQIDBA==")
        )

        messages.forEach { message ->
            val decoded = TransportProtocol.decode(TransportProtocol.encode(message))
            assertTrue("Failed round trip for ${message.kind}: $decoded", decoded is TransportDecodeResult.Success)
            val actual = (decoded as TransportDecodeResult.Success).message
            val sentFix = (message as? TransportMessage.SyncSnapshotMessage)?.snapshot?.location
            val readFix = (actual as? TransportMessage.SyncSnapshotMessage)?.snapshot?.location
            if (sentFix != null && readFix != null) {
                // A fix's timestamp is re-expressed on the reader's clock: equal up to the encoding time.
                assertTrue(kotlin.math.abs(readFix.timestampMs - sentFix.timestampMs) < 1_000L)
                assertEquals(message, TransportMessage.SyncSnapshotMessage(
                    actual.snapshot.copy(location = readFix.copy(timestampMs = sentFix.timestampMs))
                ))
            } else {
                assertEquals(message, actual)
            }
        }
    }

    @Test
    fun `a location fix keeps its age when the phone's and glasses' clocks differ`() {
        val now = System.currentTimeMillis()
        val fix = LocationFix(52.6, 1.3, 5f, null, 1.4f, LocationFixQuality.VALID, timestampMs = now - 2_000L)
        val encoded = TransportProtocol.encode(TransportMessage.SyncSnapshotMessage(SyncSnapshot(location = fix)))

        // The phone's clock 15 s behind the glasses' (as on a day without Wi-Fi): both its times shift.
        val envelope = org.json.JSONObject(encoded)
        val location = envelope.getJSONObject("payload").getJSONObject("location")
        location.put("timestampMs", location.getLong("timestampMs") - 15_000L)
        location.put("sentAtMs", location.getLong("sentAtMs") - 15_000L)

        val read = (TransportProtocol.decode(envelope.toString()) as TransportDecodeResult.Success).message
        val readFix = (read as TransportMessage.SyncSnapshotMessage).snapshot.location!!
        val ageMs = System.currentTimeMillis() - readFix.timestampMs
        assertTrue("age $ageMs ms", ageMs in 2_000L until 3_000L)
        assertEquals(1.4f, readFix.usableSpeedMps())
    }

    @Test
    fun `the phone relays assistant requests to the allowed hosts only`() {
        val elsewhere = TransportMessage.HttpRelayRequest("req-3", "GET", "https://example.com/", emptyMap(), null, null)
        assertTrue(TransportProtocol.decode(TransportProtocol.encode(elsewhere)) is TransportDecodeResult.Failure)
        assertTrue(com.everyday.shared.transport.HttpRelayPolicy.isAllowed("https://generativelanguage.googleapis.com/v1beta/models"))
        assertFalse(com.everyday.shared.transport.HttpRelayPolicy.isAllowed("http://generativelanguage.googleapis.com/v1beta/models"))
    }

    @Test
    fun `malformed and unknown envelopes fail without throwing`() {
        val malformedByFamily = listOf(
            "not-json",
            "{}",
            """{"kind":"unknown.kind","payload":{}}""",
            """{"kind":"input.pointer","payload":{"action":"invalid"}}""",
            """{"kind":"input.key","payload":{}}""",
            """{"kind":"clipboard.update","payload":{"content":4}}""",
            """{"kind":"mirror.state","payload":{"enabled":true}}""",
            """{"kind":"text_field.focus","payload":{"focused":"yes"}}""",
            """{"kind":"google_auth.state","payload":{"status":"maybe"}}""",
            """{"kind":"browser.open","payload":{"address":""}}""",
            """{"kind":"device.status","payload":{}}""",
            """{"kind":"sync.request","payload":{}}""",
            """{"kind":"sync.snapshot","payload":{}}""",
            """{"kind":"sync.error","payload":{}}""",
            """{"kind":"subtitle.control","payload":{}}""",
            """{"kind":"subtitle.status","payload":{}}""",
            """{"kind":"subtitle.transcript","payload":{}}""",
            """{"kind":"assistant.control","payload":{"sessionId":"x","action":"bad","timestampMs":1}}""",
            """{"kind":"assistant.status","payload":{"configured":false,"ready":true,"state":"idle","timestampMs":1}}""",
            """{"kind":"file.image","payload":{"fileName":"x"}}"""
        )

        malformedByFamily.forEach { raw ->
            assertTrue("Expected failure for $raw", TransportProtocol.decode(raw) is TransportDecodeResult.Failure)
        }
    }

    @Test
    fun `device status is distinct from pointer input and malformed input does not poison next message`() {
        val statusRaw = TransportProtocol.encode(TransportMessage.DeviceStatus(phoneBatteryPercent = 55))
        val status = (TransportProtocol.decode(statusRaw) as TransportDecodeResult.Success).message
        assertTrue(status is TransportMessage.DeviceStatus)
        assertTrue(status !is TransportMessage.Pointer)

        assertTrue(TransportProtocol.decode("{") is TransportDecodeResult.Failure)
        val pointer = TransportMessage.Pointer(PointerAction.MOVE, 1, 2, 1)
        assertEquals(
            pointer,
            (TransportProtocol.decode(TransportProtocol.encode(pointer)) as TransportDecodeResult.Success).message
        )
    }
}
