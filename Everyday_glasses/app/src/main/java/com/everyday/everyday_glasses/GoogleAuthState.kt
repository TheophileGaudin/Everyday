package com.everyday.everyday_glasses

data class GoogleAccountSummary(
    val email: String,
    val displayName: String? = null
) {
    val primaryLabel: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: email
}

data class GoogleAuthState(
    val status: Status = Status.SIGNED_OUT,
    val account: GoogleAccountSummary? = null,
    val detail: String? = null,
    val authMode: AuthMode = AuthMode.DIRECT_DEVICE,
    val verificationUri: String? = null,
    val userCode: String? = null
) {
    enum class Status {
        SIGNED_OUT,
        SIGNING_IN,
        AWAITING_DEVICE_VERIFICATION,
        SIGNED_IN,
        CALENDAR_AUTHORIZED,
        CALENDAR_DENIED,
        PHONE_FALLBACK_REQUIRED,
        GOOGLE_UNAVAILABLE,
        NETWORK_ERROR
    }

    enum class AuthMode {
        DIRECT_DEVICE,
        PHONE_FALLBACK
    }

    val hasAccount: Boolean
        get() = account != null

    val isCalendarAuthorized: Boolean
        get() = status == Status.CALENDAR_AUTHORIZED

    val isPhoneFallbackMode: Boolean
        get() = authMode == AuthMode.PHONE_FALLBACK

    companion object {
        fun signedOut(
            detail: String? = null,
            authMode: AuthMode = AuthMode.DIRECT_DEVICE
        ): GoogleAuthState =
            GoogleAuthState(status = Status.SIGNED_OUT, detail = detail, authMode = authMode)

        fun signingIn(
            account: GoogleAccountSummary? = null,
            detail: String? = null,
            authMode: AuthMode = AuthMode.DIRECT_DEVICE
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.SIGNING_IN,
                account = account,
                detail = detail,
                authMode = authMode
            )

        fun awaitingDeviceVerification(
            account: GoogleAccountSummary? = null,
            verificationUri: String,
            userCode: String,
            detail: String? = null
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.AWAITING_DEVICE_VERIFICATION,
                account = account,
                detail = detail,
                authMode = AuthMode.DIRECT_DEVICE,
                verificationUri = verificationUri,
                userCode = userCode
            )

        fun signedIn(
            account: GoogleAccountSummary,
            detail: String? = null,
            authMode: AuthMode = AuthMode.DIRECT_DEVICE
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.SIGNED_IN,
                account = account,
                detail = detail,
                authMode = authMode
            )

        fun calendarAuthorized(
            account: GoogleAccountSummary,
            detail: String? = null,
            authMode: AuthMode = AuthMode.DIRECT_DEVICE
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.CALENDAR_AUTHORIZED,
                account = account,
                detail = detail,
                authMode = authMode
            )

        fun calendarDenied(
            account: GoogleAccountSummary,
            detail: String? = null,
            authMode: AuthMode = AuthMode.DIRECT_DEVICE
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.CALENDAR_DENIED,
                account = account,
                detail = detail,
                authMode = authMode
            )

        fun phoneFallbackRequired(
            account: GoogleAccountSummary? = null,
            detail: String? = null
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.PHONE_FALLBACK_REQUIRED,
                account = account,
                detail = detail,
                authMode = AuthMode.PHONE_FALLBACK
            )

        fun googleUnavailable(
            account: GoogleAccountSummary? = null,
            detail: String? = null,
            authMode: AuthMode = AuthMode.DIRECT_DEVICE
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.GOOGLE_UNAVAILABLE,
                account = account,
                detail = detail,
                authMode = authMode
            )

        fun networkError(
            account: GoogleAccountSummary? = null,
            detail: String? = null,
            authMode: AuthMode = AuthMode.DIRECT_DEVICE
        ): GoogleAuthState =
            GoogleAuthState(
                status = Status.NETWORK_ERROR,
                account = account,
                detail = detail,
                authMode = authMode
            )
    }
}

data class PersistedGoogleAuthState(
    val accountEmail: String? = null,
    val accountDisplayName: String? = null,
    val authMode: String = GoogleAuthState.AuthMode.DIRECT_DEVICE.name,
    val isCalendarAuthorized: Boolean = false,
    val isCalendarDenied: Boolean = false,
    val requiresPhoneFallback: Boolean = false
) {
    fun toAuthState(): GoogleAuthState {
        val mode = runCatching { GoogleAuthState.AuthMode.valueOf(authMode) }
            .getOrDefault(GoogleAuthState.AuthMode.DIRECT_DEVICE)
        val account = accountEmail?.let { GoogleAccountSummary(email = it, displayName = accountDisplayName) }

        if (account == null) {
            return if (requiresPhoneFallback || mode == GoogleAuthState.AuthMode.PHONE_FALLBACK) {
                GoogleAuthState.phoneFallbackRequired(detail = "Phone sign-in required")
            } else {
                GoogleAuthState.signedOut(authMode = mode)
            }
        }

        return when {
            isCalendarAuthorized -> GoogleAuthState.calendarAuthorized(account, authMode = mode)
            isCalendarDenied -> GoogleAuthState.calendarDenied(account, authMode = mode)
            requiresPhoneFallback || mode == GoogleAuthState.AuthMode.PHONE_FALLBACK ->
                GoogleAuthState.phoneFallbackRequired(account, detail = "Phone sign-in required")
            else -> GoogleAuthState.signedIn(account, authMode = mode)
        }
    }

    companion object {
        fun fromAuthState(state: GoogleAuthState): PersistedGoogleAuthState {
            val account = state.account
            return when (state.status) {
                GoogleAuthState.Status.CALENDAR_AUTHORIZED -> PersistedGoogleAuthState(
                    accountEmail = account?.email,
                    accountDisplayName = account?.displayName,
                    authMode = state.authMode.name,
                    isCalendarAuthorized = true
                )

                GoogleAuthState.Status.CALENDAR_DENIED -> PersistedGoogleAuthState(
                    accountEmail = account?.email,
                    accountDisplayName = account?.displayName,
                    authMode = state.authMode.name,
                    isCalendarDenied = true
                )

                GoogleAuthState.Status.PHONE_FALLBACK_REQUIRED -> PersistedGoogleAuthState(
                    accountEmail = account?.email,
                    accountDisplayName = account?.displayName,
                    authMode = GoogleAuthState.AuthMode.PHONE_FALLBACK.name,
                    requiresPhoneFallback = true
                )

                GoogleAuthState.Status.SIGNED_IN,
                GoogleAuthState.Status.SIGNING_IN,
                GoogleAuthState.Status.AWAITING_DEVICE_VERIFICATION,
                GoogleAuthState.Status.GOOGLE_UNAVAILABLE,
                GoogleAuthState.Status.NETWORK_ERROR -> PersistedGoogleAuthState(
                    accountEmail = account?.email,
                    accountDisplayName = account?.displayName,
                    authMode = state.authMode.name
                )

                GoogleAuthState.Status.SIGNED_OUT -> PersistedGoogleAuthState(
                    authMode = state.authMode.name,
                    requiresPhoneFallback = state.authMode == GoogleAuthState.AuthMode.PHONE_FALLBACK
                )
            }
        }
    }
}
