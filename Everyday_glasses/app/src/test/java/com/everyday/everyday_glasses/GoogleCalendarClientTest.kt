package com.everyday.everyday_glasses

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class GoogleCalendarClientTest {
    @Test
    fun `calendar client clears expired token and retries once`() {
        val tokenProvider = FakeGoogleCalendarTokenProvider(
            tokens = ArrayDeque(listOf("stale-token", "fresh-token"))
        )
        val transport = FakeGoogleCalendarTransport()
        val client = GoogleCalendarClient(
            tokenProvider = tokenProvider,
            transport = transport,
            executor = Executor { runnable -> runnable.run() },
            resultPoster = { runnable -> runnable.run() }
        )

        val latch = CountDownLatch(1)
        var result: Result<List<GoogleCalendarEvent>>? = null

        client.fetchUpcomingEvents(activity = null) {
            result = it
            latch.countDown()
        }

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertTrue(result!!.isSuccess)
        assertEquals(2, transport.callCount)
        assertEquals(listOf("stale-token"), tokenProvider.clearedTokens)
        assertEquals("Event after retry", result!!.getOrThrow().single().summary)
    }
}

private class FakeGoogleCalendarTokenProvider(
    private val tokens: ArrayDeque<String>
) : GoogleCalendarTokenProvider {
    val clearedTokens = mutableListOf<String>()

    override fun requestCalendarAccessToken(
        activity: Activity?,
        allowUi: Boolean,
        callback: (Result<String>) -> Unit
    ) {
        callback(Result.success(tokens.removeFirst()))
    }

    override fun clearAccessToken(token: String, callback: (Result<Unit>) -> Unit) {
        clearedTokens += token
        callback(Result.success(Unit))
    }
}

private class FakeGoogleCalendarTransport : GoogleCalendarTransport {
    var callCount = 0

    override fun fetchUpcomingEvents(
        accessToken: String,
        maxResults: Int,
        timeMinIso: String
    ): List<GoogleCalendarEvent> {
        callCount += 1
        if (callCount == 1) {
            throw GoogleCalendarUnauthorizedException()
        }

        return listOf(
            GoogleCalendarEvent(
                id = "event-1",
                summary = "Event after retry",
                startIso = timeMinIso,
                htmlLink = null
            )
        )
    }
}
