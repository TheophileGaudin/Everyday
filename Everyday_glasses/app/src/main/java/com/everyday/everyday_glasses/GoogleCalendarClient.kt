package com.everyday.everyday_glasses

import android.app.Activity
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class GoogleCalendarEvent(
    val id: String,
    val summary: String,
    val startIso: String,
    val htmlLink: String?
)

interface GoogleCalendarTransport {
    @Throws(IOException::class, GoogleCalendarUnauthorizedException::class)
    fun fetchUpcomingEvents(accessToken: String, maxResults: Int, timeMinIso: String): List<GoogleCalendarEvent>
}

class GoogleCalendarUnauthorizedException(message: String = "Google Calendar access token expired") :
    IOException(message)

class HttpUrlConnectionGoogleCalendarTransport : GoogleCalendarTransport {
    override fun fetchUpcomingEvents(
        accessToken: String,
        maxResults: Int,
        timeMinIso: String
    ): List<GoogleCalendarEvent> {
        val encodedTimeMin = URLEncoder.encode(timeMinIso, Charsets.UTF_8.name())
        val url = URL(
            "https://www.googleapis.com/calendar/v3/calendars/primary/events" +
                "?maxResults=$maxResults" +
                "&singleEvents=true" +
                "&orderBy=startTime" +
                "&timeMin=$encodedTimeMin"
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw GoogleCalendarUnauthorizedException()
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException("Calendar API failed with HTTP $responseCode${errorText?.let { ": $it" } ?: ""}")
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            return parseEvents(JSONObject(json).optJSONArray("items") ?: JSONArray())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEvents(items: JSONArray): List<GoogleCalendarEvent> {
        val events = mutableListOf<GoogleCalendarEvent>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val start = item.optJSONObject("start")
            val startIso = start?.optString("dateTime")?.takeIf { it.isNotBlank() }
                ?: start?.optString("date").orEmpty()

            events += GoogleCalendarEvent(
                id = item.optString("id"),
                summary = item.optString("summary", "(No title)"),
                startIso = startIso,
                htmlLink = item.optString("htmlLink")
            )
        }
        return events
    }
}

class GoogleCalendarClient(
    private val tokenProvider: GoogleCalendarTokenProvider,
    private val transport: GoogleCalendarTransport = HttpUrlConnectionGoogleCalendarTransport(),
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val resultPoster: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    }
) {
    companion object {
        private const val DEFAULT_UPCOMING_EVENT_LIMIT = 20
    }

    fun fetchUpcomingEvents(
        activity: Activity?,
        maxResults: Int = DEFAULT_UPCOMING_EVENT_LIMIT,
        callback: (Result<List<GoogleCalendarEvent>>) -> Unit
    ) {
        tokenProvider.requestCalendarAccessToken(activity, allowUi = false) { tokenResult ->
            tokenResult.onSuccess { accessToken ->
                fetchWithToken(
                    activity = activity,
                    accessToken = accessToken,
                    maxResults = maxResults.coerceAtLeast(1),
                    didRetry = false,
                    callback = callback
                )
            }.onFailure { throwable ->
                postResult(callback, Result.failure(throwable))
            }
        }
    }

    private fun fetchWithToken(
        activity: Activity?,
        accessToken: String,
        maxResults: Int,
        didRetry: Boolean,
        callback: (Result<List<GoogleCalendarEvent>>) -> Unit
    ) {
        executor.execute {
            try {
                val events = transport.fetchUpcomingEvents(accessToken, maxResults, currentUtcIso())
                postResult(callback, Result.success(events))
            } catch (unauthorized: GoogleCalendarUnauthorizedException) {
                if (didRetry) {
                    postResult(callback, Result.failure(unauthorized))
                    return@execute
                }

                tokenProvider.clearAccessToken(accessToken) {
                    tokenProvider.requestCalendarAccessToken(activity, allowUi = false) { retryResult ->
                        retryResult.onSuccess { refreshedToken ->
                            fetchWithToken(
                                activity = activity,
                                accessToken = refreshedToken,
                                maxResults = maxResults,
                                didRetry = true,
                                callback = callback
                            )
                        }.onFailure { throwable ->
                            postResult(callback, Result.failure(throwable))
                        }
                    }
                }
            } catch (throwable: Throwable) {
                postResult(callback, Result.failure(throwable))
            }
        }
    }

    private fun currentUtcIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun <T> postResult(callback: (Result<T>) -> Unit, result: Result<T>) {
        resultPoster(Runnable { callback(result) })
    }
}
