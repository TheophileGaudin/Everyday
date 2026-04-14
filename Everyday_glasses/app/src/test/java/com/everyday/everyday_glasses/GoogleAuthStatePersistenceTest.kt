package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthStatePersistenceTest {
    @Test
    fun `calendar authorized state survives persistence round trip`() {
        val original = GoogleAuthState.calendarAuthorized(
            GoogleAccountSummary(email = "user@example.com", displayName = "Everyday User")
        )

        val persisted = PersistedGoogleAuthState.fromAuthState(original)
        val restored = persisted.toAuthState()

        assertTrue(persisted.isCalendarAuthorized)
        assertEquals(GoogleAuthState.Status.CALENDAR_AUTHORIZED, restored.status)
        assertEquals("user@example.com", restored.account?.email)
        assertEquals("Everyday User", restored.account?.displayName)
    }

    @Test
    fun `transient network state persists as signed in account`() {
        val original = GoogleAuthState.networkError(
            GoogleAccountSummary(email = "user@example.com", displayName = "Everyday User"),
            detail = "Network error"
        )

        val persisted = PersistedGoogleAuthState.fromAuthState(original)
        val restored = persisted.toAuthState()

        assertFalse(persisted.isCalendarAuthorized)
        assertFalse(persisted.isCalendarDenied)
        assertEquals(GoogleAuthState.Status.SIGNED_IN, restored.status)
        assertEquals("user@example.com", restored.account?.email)
    }

    @Test
    fun `phone fallback state survives persistence round trip`() {
        val original = GoogleAuthState.phoneFallbackRequired(
            account = GoogleAccountSummary(email = "user@example.com", displayName = "Everyday User"),
            detail = "Phone sign-in required"
        )

        val persisted = PersistedGoogleAuthState.fromAuthState(original)
        val restored = persisted.toAuthState()

        assertTrue(persisted.requiresPhoneFallback)
        assertEquals(GoogleAuthState.AuthMode.PHONE_FALLBACK, restored.authMode)
        assertEquals(GoogleAuthState.Status.PHONE_FALLBACK_REQUIRED, restored.status)
        assertEquals("user@example.com", restored.account?.email)
    }
}
