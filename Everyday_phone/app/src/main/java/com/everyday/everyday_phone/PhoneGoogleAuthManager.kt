package com.everyday.everyday_phone

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
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
import java.util.concurrent.atomic.AtomicBoolean

data class PhoneGoogleAccountSummary(
    val email: String,
    val displayName: String? = null
)

data class PhoneCalendarEvent(
    val id: String,
    val summary: String,
    val startIso: String,
    val htmlLink: String? = null
)

data class PhoneGoogleCalendarSnapshot(
    val account: PhoneGoogleAccountSummary,
    val events: List<PhoneCalendarEvent>,
    val fetchedAtMs: Long,
    val staleAfterMs: Long
)

data class PhoneGoogleAuthState(
    val status: Status = Status.SIGNED_OUT,
    val account: PhoneGoogleAccountSummary? = null,
    val detail: String? = null
) {
    enum class Status {
        SIGNED_OUT,
        AUTHORIZING,
        AUTHORIZED,
        ERROR
    }
}

class PhoneGoogleAuthManager(context: Context) {
    companion object {
        private const val TAG = "PhoneGoogleAuth"
        private const val PREFS_NAME = "phone_google_auth"
        private const val KEY_EMAIL = "email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_LAST_SNAPSHOT = "last_snapshot"
        private const val CALENDAR_SNAPSHOT_MAX_RESULTS = 20
        private const val SNAPSHOT_STALE_AFTER_MS = 15 * 60 * 1000L
        private const val AUTHORIZATION_MAX_ATTEMPTS = 2
        private const val HTTP_REQUEST_MAX_ATTEMPTS = 3
        private const val TRANSIENT_RETRY_DELAY_MS = 1_200L
        private const val SNAPSHOT_BOOTSTRAP_MAX_ATTEMPTS = 6
        private const val AUTHORIZED_SYNCING_DETAIL = "Phone companion connected. Syncing Calendar data"
        private const val AUTHORIZED_READY_DETAIL = "Phone companion is providing Calendar data"
        private const val AUTHORIZED_RETRYING_DETAIL = "Phone companion connected. Calendar sync will retry shortly"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val authorizationClient = Identity.getAuthorizationClient(appContext)
    private val networkExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotRefreshInFlight = AtomicBoolean(false)
    private val requestedScopes = listOf(
        Scope("openid"),
        Scope("email"),
        Scope("profile"),
        Scope("https://www.googleapis.com/auth/calendar.readonly")
    )

    private var pendingOnStateChanged: ((PhoneGoogleAuthState) -> Unit)? = null
    private var pendingOnSnapshotChanged: ((PhoneGoogleCalendarSnapshot?) -> Unit)? = null

    fun loadState(): PhoneGoogleAuthState {
        val email = prefs.getString(KEY_EMAIL, null)
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
        val account = email?.let { PhoneGoogleAccountSummary(it, displayName) }
        val snapshot = loadCachedSnapshot()
        return if (account != null) {
            PhoneGoogleAuthState(
                status = PhoneGoogleAuthState.Status.AUTHORIZED,
                account = account,
                detail = when {
                    snapshot == null -> AUTHORIZED_SYNCING_DETAIL
                    isSnapshotStale(snapshot) -> "Phone companion is providing cached Calendar data"
                    else -> AUTHORIZED_READY_DETAIL
                }
            )
        } else {
            PhoneGoogleAuthState()
        }
    }

    fun loadCachedSnapshot(): PhoneGoogleCalendarSnapshot? {
        val raw = prefs.getString(KEY_LAST_SNAPSHOT, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val accountJson = root.getJSONObject("account")
            val account = PhoneGoogleAccountSummary(
                email = accountJson.optString("email"),
                displayName = accountJson.optString("displayName").takeIf { it.isNotBlank() }
            )
            val events = mutableListOf<PhoneCalendarEvent>()
            val items = root.optJSONArray("events") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                events += PhoneCalendarEvent(
                    id = item.optString("id"),
                    summary = item.optString("summary"),
                    startIso = item.optString("startIso"),
                    htmlLink = item.optString("htmlLink").takeIf { it.isNotBlank() }
                )
            }
            PhoneGoogleCalendarSnapshot(
                account = account,
                events = events,
                fetchedAtMs = root.optLong("fetchedAtMs", 0L),
                staleAfterMs = root.optLong("staleAfterMs", SNAPSHOT_STALE_AFTER_MS)
            )
        }.getOrNull()
    }

    fun beginAuthorization(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onStateChanged: (PhoneGoogleAuthState) -> Unit,
        onSnapshotChanged: (PhoneGoogleCalendarSnapshot?) -> Unit
    ) {
        pendingOnStateChanged = onStateChanged
        pendingOnSnapshotChanged = onSnapshotChanged
        fileLog("Google auth: beginAuthorization")
        onStateChanged(PhoneGoogleAuthState(status = PhoneGoogleAuthState.Status.AUTHORIZING, detail = "Launching Google authorization"))
        launchAuthorizationRequest(
            activity = activity,
            launcher = launcher,
            onStateChanged = onStateChanged,
            attempt = 0
        )
    }

    private fun launchAuthorizationRequest(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onStateChanged: (PhoneGoogleAuthState) -> Unit,
        attempt: Int
    ) {
        fileLog("Google auth: authorize() attempt ${attempt + 1}/$AUTHORIZATION_MAX_ATTEMPTS")

        val requestBuilder = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)

        loadState().account?.email?.takeIf { it.isNotBlank() }?.let { email ->
            requestBuilder.setAccount(Account(email, "com.google"))
        }

        authorizationClient.authorize(requestBuilder.build())
            .addOnSuccessListener { result ->
                fileLog("Google auth: authorize() success, hasResolution=${result.hasResolution()}, hasAccessToken=${!result.accessToken.isNullOrBlank()}")
                handleAuthorizationResult(result, launcher)
            }
            .addOnFailureListener { error ->
                if (shouldRetryTransientFailure(error) && attempt + 1 < AUTHORIZATION_MAX_ATTEMPTS) {
                    val nextAttempt = attempt + 2
                    fileLog(
                        "Google auth: transient authorize() failure on attempt ${attempt + 1}: " +
                            "${error::class.java.simpleName}: ${error.message}. Retrying (attempt $nextAttempt/$AUTHORIZATION_MAX_ATTEMPTS)"
                    )
                    onStateChanged(
                        PhoneGoogleAuthState(
                            status = PhoneGoogleAuthState.Status.AUTHORIZING,
                            detail = "Retrying Google authorization"
                        )
                    )
                    mainHandler.postDelayed(
                        {
                            launchAuthorizationRequest(
                                activity = activity,
                                launcher = launcher,
                                onStateChanged = onStateChanged,
                                attempt = attempt + 1
                            )
                        },
                        retryDelayMs(attempt)
                    )
                    return@addOnFailureListener
                }
                Log.e(TAG, "Authorization failed", error)
                fileLog("Google auth: authorize() failure: ${error::class.java.simpleName}: ${error.message}")
                onStateChanged(PhoneGoogleAuthState(status = PhoneGoogleAuthState.Status.ERROR, detail = error.message ?: "Authorization failed"))
            }
    }

    fun handleAuthorizationIntentResult(
        resultCode: Int,
        data: Intent?,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        val onStateChanged = pendingOnStateChanged ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            fileLog("Google auth: authorization intent canceled, resultCode=$resultCode, hasData=${data != null}")
            onStateChanged(PhoneGoogleAuthState(status = PhoneGoogleAuthState.Status.ERROR, detail = "Authorization canceled"))
            return
        }

        runCatching { authorizationClient.getAuthorizationResultFromIntent(data) }
            .onSuccess { result ->
                fileLog("Google auth: parsed authorization result, hasResolution=${result.hasResolution()}, hasAccessToken=${!result.accessToken.isNullOrBlank()}")
                handleAuthorizationResult(result, launcher)
            }
            .onFailure { error ->
                Log.e(TAG, "Failed to parse authorization result", error)
                fileLog("Google auth: failed to parse authorization result: ${error::class.java.simpleName}: ${error.message}")
                onStateChanged(PhoneGoogleAuthState(status = PhoneGoogleAuthState.Status.ERROR, detail = error.message ?: "Authorization failed"))
            }
    }

    fun revokeAccess(
        activity: Activity,
        onStateChanged: (PhoneGoogleAuthState) -> Unit,
        onComplete: () -> Unit
    ) {
        val accountEmail = loadState().account?.email
        if (accountEmail.isNullOrBlank()) {
            clearLocalState()
            onStateChanged(PhoneGoogleAuthState())
            onComplete()
            return
        }

        val request = RevokeAccessRequest.builder()
            .setAccount(Account(accountEmail, "com.google"))
            .setScopes(requestedScopes)
            .build()

        Identity.getAuthorizationClient(activity)
            .revokeAccess(request)
            .addOnSuccessListener {
                clearLocalState()
                onStateChanged(PhoneGoogleAuthState())
                onComplete()
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Revoke access failed", error)
                clearLocalState()
                onStateChanged(PhoneGoogleAuthState())
                onComplete()
            }
    }

    fun refreshSnapshotSilently(
        callback: (Result<PhoneGoogleCalendarSnapshot>) -> Unit
    ) {
        val account = loadState().account
        if (account == null) {
            postSnapshotRefreshResult(
                callback,
                Result.failure(IllegalStateException("Google Calendar is not connected"))
            )
            return
        }

        if (!snapshotRefreshInFlight.compareAndSet(false, true)) {
            postSnapshotRefreshResult(
                callback,
                Result.failure(IllegalStateException("Google Calendar refresh already in progress"))
            )
            return
        }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .setAccount(Account(account.email, "com.google"))
            .build()

        authorizationClient.authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    fileLog("Google auth: silent refresh requires user resolution")
                    finishSnapshotRefresh(
                        callback,
                        Result.failure(IllegalStateException("Google authorization requires user action"))
                    )
                    return@addOnSuccessListener
                }

                val accessToken = result.accessToken
                if (accessToken.isNullOrBlank()) {
                    fileLog("Google auth: silent refresh returned no access token")
                    finishSnapshotRefresh(
                        callback,
                        Result.failure(IllegalStateException("Google did not return an access token"))
                    )
                    return@addOnSuccessListener
                }

                networkExecutor.execute {
                    try {
                        val snapshot = fetchCalendarSnapshotWithRetry(accessToken, account)
                        saveSnapshot(snapshot)
                        finishSnapshotRefresh(callback, Result.success(snapshot))
                    } catch (error: Throwable) {
                        Log.w(TAG, "Silent snapshot refresh failed", error)
                        fileLog("Google auth: silent refresh failed: ${error::class.java.simpleName}: ${error.message}")
                        finishSnapshotRefresh(callback, Result.failure(error))
                    }
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Silent authorization failed", error)
                fileLog("Google auth: silent authorize() failure: ${error::class.java.simpleName}: ${error.message}")
                finishSnapshotRefresh(callback, Result.failure(error))
            }
    }

    private fun handleAuthorizationResult(
        result: AuthorizationResult,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        if (result.hasResolution()) {
            fileLog("Google auth: result requires user resolution")
            val pendingIntent = result.pendingIntent
            if (pendingIntent == null) {
                fileLog("Google auth: result.hasResolution() but pendingIntent is null")
                postStateChanged(
                    PhoneGoogleAuthState(status = PhoneGoogleAuthState.Status.ERROR, detail = "Google authorization needs user action")
                )
                return
            }
            val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            launcher.launch(request)
            return
        }

        val accessToken = result.accessToken
        if (accessToken.isNullOrBlank()) {
            fileLog("Google auth: missing access token after authorization")
            postStateChanged(
                PhoneGoogleAuthState(status = PhoneGoogleAuthState.Status.ERROR, detail = "Google did not return an access token")
            )
            return
        }

        networkExecutor.execute {
            finalizeAuthorization(accessToken)
        }
    }

    private fun finalizeAuthorization(accessToken: String) {
        val cachedAccount = loadState().account

        try {
            fileLog("Google auth: access token received, fetching user info")
            val account = runCatching { fetchUserInfoWithRetry(accessToken) }
                .recoverCatching { error ->
                    if (cachedAccount != null && shouldRetryTransientFailure(error)) {
                        fileLog(
                            "Google auth: reusing cached account ${cachedAccount.email} after transient userinfo failure: " +
                                "${error::class.java.simpleName}: ${error.message}"
                        )
                        cachedAccount
                    } else {
                        throw error
                    }
                }
                .getOrThrow()

            saveAccount(account)
            postStateChanged(
                PhoneGoogleAuthState(
                    status = PhoneGoogleAuthState.Status.AUTHORIZED,
                    account = account,
                    detail = AUTHORIZED_SYNCING_DETAIL
                )
            )

            fileLog("Google auth: user info ready for ${account.email}, fetching calendar snapshot")
            try {
                val snapshot = fetchCalendarSnapshotWithRetry(accessToken, account)
                saveSnapshot(snapshot)
                fileLog("Google auth: calendar snapshot fetched successfully, events=${snapshot.events.size}")
                postStateChanged(
                    PhoneGoogleAuthState(
                        status = PhoneGoogleAuthState.Status.AUTHORIZED,
                        account = account,
                        detail = AUTHORIZED_READY_DETAIL
                    )
                )
                postSnapshotChanged(snapshot)
            } catch (error: Throwable) {
                if (!shouldRetryTransientFailure(error)) {
                    throw error
                }

                Log.w(TAG, "Calendar snapshot fetch is temporarily unavailable", error)
                fileLog(
                    "Google auth: transient calendar snapshot failure after authorization: " +
                        "${error::class.java.simpleName}: ${error.message}"
                )
                postStateChanged(
                    PhoneGoogleAuthState(
                        status = PhoneGoogleAuthState.Status.AUTHORIZED,
                        account = account,
                        detail = AUTHORIZED_RETRYING_DETAIL
                    )
                )
                scheduleImmediateSnapshotRefresh(account)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to finalize Google authorization", error)
            val detail = error.message ?: "${error::class.java.simpleName} with no message"
            fileLog("Google auth: finalize failure: ${error::class.java.simpleName}: $detail")
            postStateChanged(
                PhoneGoogleAuthState(status = PhoneGoogleAuthState.Status.ERROR, detail = detail)
            )
        }
    }

    private fun postStateChanged(state: PhoneGoogleAuthState) {
        mainHandler.post {
            pendingOnStateChanged?.invoke(state)
        }
    }

    private fun postSnapshotChanged(snapshot: PhoneGoogleCalendarSnapshot?) {
        mainHandler.post {
            pendingOnSnapshotChanged?.invoke(snapshot)
        }
    }

    private fun scheduleImmediateSnapshotRefresh(
        account: PhoneGoogleAccountSummary,
        attempt: Int = 0
    ) {
        mainHandler.postDelayed(
            {
                refreshSnapshotSilently { result ->
                    result.onSuccess { snapshot ->
                        fileLog("Google auth: immediate snapshot retry succeeded, events=${snapshot.events.size}")
                        postStateChanged(
                            PhoneGoogleAuthState(
                                status = PhoneGoogleAuthState.Status.AUTHORIZED,
                                account = snapshot.account,
                                detail = AUTHORIZED_READY_DETAIL
                            )
                        )
                        postSnapshotChanged(snapshot)
                    }.onFailure { error ->
                        if (error.message != "Google Calendar refresh already in progress") {
                            fileLog(
                                "Google auth: immediate snapshot retry failed: " +
                                    "${error::class.java.simpleName}: ${error.message}"
                            )
                        }

                        if (shouldContinueSnapshotBootstrapRetry(account, error, attempt)) {
                            fileLog(
                                "Google auth: scheduling another bootstrap snapshot retry " +
                                    "(attempt ${attempt + 2}/$SNAPSHOT_BOOTSTRAP_MAX_ATTEMPTS)"
                            )
                            postStateChanged(
                                PhoneGoogleAuthState(
                                    status = PhoneGoogleAuthState.Status.AUTHORIZED,
                                    account = account,
                                    detail = AUTHORIZED_RETRYING_DETAIL
                                )
                            )
                            scheduleImmediateSnapshotRefresh(account, attempt + 1)
                        } else {
                            postStateChanged(
                                PhoneGoogleAuthState(
                                    status = PhoneGoogleAuthState.Status.AUTHORIZED,
                                    account = account,
                                    detail = AUTHORIZED_RETRYING_DETAIL
                                )
                            )
                        }
                    }
                }
            },
            retryDelayMs(attempt)
        )
    }

    private fun shouldContinueSnapshotBootstrapRetry(
        account: PhoneGoogleAccountSummary,
        error: Throwable,
        attempt: Int
    ): Boolean {
        if (attempt + 1 >= SNAPSHOT_BOOTSTRAP_MAX_ATTEMPTS) {
            return false
        }
        if (loadState().account?.email != account.email) {
            return false
        }
        val message = error.message
        if (message == "Google Calendar is not connected") {
            return false
        }
        return true
    }

    private fun fetchUserInfo(accessToken: String): PhoneGoogleAccountSummary {
        val connection = (URL("https://openidconnect.googleapis.com/v1/userinfo").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                fileLog("Google auth: userinfo HTTP $responseCode ${errorText ?: ""}".trim())
                throw IOException("User info request failed with HTTP $responseCode${errorText?.let { ": $it" } ?: ""}")
            }
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val email = root.optString("email")
            if (email.isBlank()) {
                fileLog("Google auth: userinfo response missing email")
                throw IOException("Google user info did not include an email")
            }
            return PhoneGoogleAccountSummary(
                email = email,
                displayName = root.optString("name").takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchUserInfoWithRetry(accessToken: String): PhoneGoogleAccountSummary {
        repeat(HTTP_REQUEST_MAX_ATTEMPTS) { attempt ->
            try {
                return fetchUserInfo(accessToken)
            } catch (error: IOException) {
                if (!shouldRetryTransientFailure(error) || attempt + 1 >= HTTP_REQUEST_MAX_ATTEMPTS) {
                    throw error
                }
                val delayMs = retryDelayMs(attempt)
                fileLog(
                    "Google auth: userinfo request hit a transient failure, retrying in ${delayMs}ms: " +
                        "${error::class.java.simpleName}: ${error.message}"
                )
                sleepQuietly(delayMs)
            }
        }

        throw IOException("User info retry exhausted")
    }

    private fun fetchCalendarSnapshot(
        accessToken: String,
        account: PhoneGoogleAccountSummary
    ): PhoneGoogleCalendarSnapshot {
        val encodedTimeMin = URLEncoder.encode(currentUtcIso(), Charsets.UTF_8.name())
        val url = URL(
            "https://www.googleapis.com/calendar/v3/calendars/primary/events" +
                "?maxResults=$CALENDAR_SNAPSHOT_MAX_RESULTS" +
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
            if (responseCode !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                fileLog("Google auth: calendar HTTP $responseCode ${errorText ?: ""}".trim())
                throw IOException("Calendar request failed with HTTP $responseCode${errorText?.let { ": $it" } ?: ""}")
            }

            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val items = root.optJSONArray("items") ?: JSONArray()
            val events = mutableListOf<PhoneCalendarEvent>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val start = item.optJSONObject("start")
                val startIso = start?.optString("dateTime")?.takeIf { it.isNotBlank() }
                    ?: start?.optString("date").orEmpty()
                events += PhoneCalendarEvent(
                    id = item.optString("id"),
                    summary = item.optString("summary", "(No title)"),
                    startIso = startIso,
                    htmlLink = item.optString("htmlLink").takeIf { it.isNotBlank() }
                )
            }

            return PhoneGoogleCalendarSnapshot(
                account = account,
                events = events,
                fetchedAtMs = System.currentTimeMillis(),
                staleAfterMs = SNAPSHOT_STALE_AFTER_MS
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchCalendarSnapshotWithRetry(
        accessToken: String,
        account: PhoneGoogleAccountSummary
    ): PhoneGoogleCalendarSnapshot {
        repeat(HTTP_REQUEST_MAX_ATTEMPTS) { attempt ->
            try {
                return fetchCalendarSnapshot(accessToken, account)
            } catch (error: IOException) {
                if (!shouldRetryTransientFailure(error) || attempt + 1 >= HTTP_REQUEST_MAX_ATTEMPTS) {
                    throw error
                }
                val delayMs = retryDelayMs(attempt)
                fileLog(
                    "Google auth: calendar fetch hit a transient failure, retrying in ${delayMs}ms: " +
                        "${error::class.java.simpleName}: ${error.message}"
                )
                sleepQuietly(delayMs)
            }
        }

        throw IOException("Calendar request retry exhausted")
    }

    private fun shouldRetryTransientFailure(error: Throwable): Boolean {
        val message = error.message?.lowercase(Locale.US).orEmpty()
        val httpCode = extractHttpStatusCode(error.message)

        return when {
            httpCode == 403 || httpCode == 429 -> true
            httpCode != null && httpCode in 500..599 -> true
            "service unavailable" in message -> true
            "temporarily unavailable" in message -> true
            "failed to fetch" in message -> true
            "timeout" in message -> true
            "timed out" in message -> true
            "failed to connect" in message -> true
            "connection reset" in message -> true
            "connection aborted" in message -> true
            "connection refused" in message -> true
            "network error" in message -> true
            else -> false
        }
    }

    private fun extractHttpStatusCode(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        val httpMatch = Regex("""http\s+(\d{3})""", RegexOption.IGNORE_CASE).find(message)
        if (httpMatch != null) {
            return httpMatch.groupValues.getOrNull(1)?.toIntOrNull()
        }
        val bareStatusMatch = Regex("""\b(\d{3})\b""").find(message) ?: return null
        return bareStatusMatch.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun retryDelayMs(attempt: Int): Long = TRANSIENT_RETRY_DELAY_MS * (attempt + 1L)

    private fun sleepQuietly(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun currentUtcIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun saveAccount(account: PhoneGoogleAccountSummary) {
        prefs.edit()
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_DISPLAY_NAME, account.displayName)
            .apply()
    }

    private fun saveSnapshot(snapshot: PhoneGoogleCalendarSnapshot) {
        val root = JSONObject().apply {
            put("fetchedAtMs", snapshot.fetchedAtMs)
            put("staleAfterMs", snapshot.staleAfterMs)
            put(
                "account",
                JSONObject().apply {
                    put("email", snapshot.account.email)
                    put("displayName", snapshot.account.displayName)
                }
            )
            put(
                "events",
                JSONArray().apply {
                    snapshot.events.forEach { event ->
                        put(
                            JSONObject().apply {
                                put("id", event.id)
                                put("summary", event.summary)
                                put("startIso", event.startIso)
                                put("htmlLink", event.htmlLink)
                            }
                        )
                    }
                }
            )
        }
        prefs.edit().putString(KEY_LAST_SNAPSHOT, root.toString()).apply()
    }

    private fun isSnapshotStale(
        snapshot: PhoneGoogleCalendarSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val staleAfterMs = snapshot.staleAfterMs.takeIf { it > 0L } ?: SNAPSHOT_STALE_AFTER_MS
        return nowMs - snapshot.fetchedAtMs >= staleAfterMs
    }

    private fun finishSnapshotRefresh(
        callback: (Result<PhoneGoogleCalendarSnapshot>) -> Unit,
        result: Result<PhoneGoogleCalendarSnapshot>
    ) {
        snapshotRefreshInFlight.set(false)
        postSnapshotRefreshResult(callback, result)
    }

    private fun postSnapshotRefreshResult(
        callback: (Result<PhoneGoogleCalendarSnapshot>) -> Unit,
        result: Result<PhoneGoogleCalendarSnapshot>
    ) {
        mainHandler.post {
            callback(result)
        }
    }

    private fun clearLocalState() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_LAST_SNAPSHOT)
            .apply()
    }
}
