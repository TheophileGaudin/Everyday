package com.everyday.everyday_glasses

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GoogleCalendarSnapshot(
    val account: GoogleAccountSummary? = null,
    val events: List<GoogleCalendarEvent> = emptyList(),
    val fetchedAtMs: Long = 0L,
    val staleAfterMs: Long = 0L,
    val sourceMode: GoogleAuthState.AuthMode = GoogleAuthState.AuthMode.PHONE_FALLBACK
)

class GoogleCalendarSnapshotStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "google_calendar_snapshot"
        private const val KEY_PAYLOAD = "payload"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(snapshot: GoogleCalendarSnapshot) {
        val root = JSONObject().apply {
            put("fetchedAtMs", snapshot.fetchedAtMs)
            put("staleAfterMs", snapshot.staleAfterMs)
            put("sourceMode", snapshot.sourceMode.name)
            snapshot.account?.let { account ->
                put(
                    "account",
                    JSONObject().apply {
                        put("email", account.email)
                        put("displayName", account.displayName)
                    }
                )
            }
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
        prefs.edit().putString(KEY_PAYLOAD, root.toString()).apply()
    }

    fun load(): GoogleCalendarSnapshot? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return runCatching {
            val root = JSONObject(payload)
            val account = root.optJSONObject("account")?.let {
                GoogleAccountSummary(
                    email = it.optString("email"),
                    displayName = it.optString("displayName").takeIf { value -> value.isNotBlank() }
                )
            }
            val events = buildList {
                val items = root.optJSONArray("events") ?: JSONArray()
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    add(
                        GoogleCalendarEvent(
                            id = item.optString("id"),
                            summary = item.optString("summary"),
                            startIso = item.optString("startIso"),
                            htmlLink = item.optString("htmlLink").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            GoogleCalendarSnapshot(
                account = account,
                events = events,
                fetchedAtMs = root.optLong("fetchedAtMs", 0L),
                staleAfterMs = root.optLong("staleAfterMs", 0L),
                sourceMode = runCatching {
                    GoogleAuthState.AuthMode.valueOf(
                        root.optString("sourceMode", GoogleAuthState.AuthMode.PHONE_FALLBACK.name)
                    )
                }.getOrDefault(GoogleAuthState.AuthMode.PHONE_FALLBACK)
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }
}
