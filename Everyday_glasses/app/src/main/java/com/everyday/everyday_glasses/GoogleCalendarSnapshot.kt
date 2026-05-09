package com.everyday.everyday_glasses

data class GoogleCalendarSnapshot(
    val account: GoogleAccountSummary? = null,
    val events: List<GoogleCalendarEvent> = emptyList(),
    val fetchedAtMs: Long = 0L,
    val staleAfterMs: Long = 0L,
    val sourceMode: GoogleAuthState.AuthMode = GoogleAuthState.AuthMode.PHONE_FALLBACK
)
