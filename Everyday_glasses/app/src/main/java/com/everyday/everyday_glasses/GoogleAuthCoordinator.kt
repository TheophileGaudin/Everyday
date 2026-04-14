package com.everyday.everyday_glasses

import android.app.Activity
import android.util.Log
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface GoogleCalendarTokenProvider {
    fun requestCalendarAccessToken(
        activity: Activity?,
        allowUi: Boolean,
        callback: (Result<String>) -> Unit
    )

    fun clearAccessToken(
        token: String,
        callback: (Result<Unit>) -> Unit
    )
}

interface GooglePhoneFallbackBridge {
    fun requestPhoneAuthorization()
    fun requestCalendarSnapshot()
    fun disconnectPhoneAuthorization()
}

enum class GooglePhoneAuthStatus {
    SIGNED_OUT,
    AUTHORIZING,
    AUTHORIZED,
    ERROR
}

private data class GoogleIdTokenProfile(
    val email: String?,
    val displayName: String?
)

private data class InMemoryGoogleAccessToken(
    val token: String,
    val expiresAtMs: Long
) {
    fun isValid(nowMs: Long = System.currentTimeMillis()): Boolean = token.isNotBlank() && nowMs < expiresAtMs
}

private data class ActiveGoogleDeviceFlow(
    val deviceCode: String,
    val expiresAtMs: Long,
    val pollIntervalMs: Long,
    val fallbackState: GoogleAuthState,
    val canceled: AtomicBoolean = AtomicBoolean(false)
)

class GoogleAuthCoordinator(
    private val clientId: String,
    private val store: GoogleAuthStateStore,
    private val deviceService: GoogleOAuthDeviceService = HttpUrlConnectionGoogleOAuthDeviceService(),
    private val tokenService: GoogleOAuthTokenService = HttpUrlConnectionGoogleOAuthTokenService(),
    private val networkStatusProvider: NetworkStatusProvider,
    private val phoneFallbackBridge: GooglePhoneFallbackBridge? = null,
    private val executor: Executor = Executors.newSingleThreadExecutor()
) : GoogleCalendarTokenProvider {

    companion object {
        private const val TAG = "GoogleAuthCoordinator"
        const val CALENDAR_READONLY_SCOPE = "https://www.googleapis.com/auth/calendar.readonly"
        const val CLIENT_ID_PLACEHOLDER = "YOUR_GOOGLE_DEVICE_CLIENT_ID.apps.googleusercontent.com"

        private const val DIRECT_FLOW_DETAIL = "Use your phone browser to approve Google Calendar, then enter the code shown here."
        private const val PHONE_FALLBACK_DETAIL =
            "Calendar access needs the phone companion on this device. Open the phone app to continue."
        private const val RECONNECT_DETAIL = "Reconnect Google to continue"
        private const val CONFIG_DETAIL = "Set a Google device OAuth client ID in strings.xml"
    }

    var onStateChanged: ((GoogleAuthState) -> Unit)? = null

    private var currentState: GoogleAuthState = store.loadState()
    private var pendingTokenCallback: ((Result<String>) -> Unit)? = null
    private var activeFlow: ActiveGoogleDeviceFlow? = null
    private var inMemoryAccessToken: InMemoryGoogleAccessToken? = null

    fun getCurrentState(): GoogleAuthState = currentState

    fun publishCurrentState() {
        onStateChanged?.invoke(currentState)
    }

    fun beginConnect(activity: Activity?) {
        if (currentState.isPhoneFallbackMode) {
            requestPhoneFallbackAuthorization()
            return
        }

        if (!ensureConfigured()) return
        if (!ensureNetwork()) return
        startDeviceAuthorizationFlow()
    }

    fun requestCalendarGrant(activity: Activity?) {
        requestCalendarAccessToken(activity, allowUi = true) { result ->
            result.exceptionOrNull()?.let { throwable ->
                if (throwable !is GoogleUserCanceledException) {
                    Log.w(TAG, "Calendar grant request failed", throwable)
                }
            }
        }
    }

    fun retry(activity: Activity?) {
        if (activeFlow != null) return

        if (currentState.isPhoneFallbackMode || currentState.status == GoogleAuthState.Status.PHONE_FALLBACK_REQUIRED) {
            requestPhoneFallbackAuthorization()
            return
        }

        if (store.loadRefreshToken().isNullOrBlank() && !hasValidInMemoryToken()) {
            beginConnect(activity)
            return
        }

        requestCalendarGrant(activity)
    }

    fun refreshStateSilently(activity: Activity?) {
        if (currentState.isPhoneFallbackMode) {
            phoneFallbackBridge?.requestCalendarSnapshot()
            publishCurrentState()
            return
        }

        val account = currentState.account ?: run {
            publishCurrentState()
            return
        }

        if (!isConfigured()) {
            publish(GoogleAuthState.googleUnavailable(account, CONFIG_DETAIL))
            return
        }

        if (hasValidInMemoryToken()) {
            saveStableState(GoogleAuthState.calendarAuthorized(account, "Calendar access granted"))
            return
        }

        val refreshToken = store.loadRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            saveStableState(GoogleAuthState.signedIn(account, RECONNECT_DETAIL))
            return
        }

        requestCalendarAccessToken(activity, allowUi = false) { result ->
            result.exceptionOrNull()?.let { throwable ->
                when (throwable) {
                    is GoogleUserActionRequiredException -> saveStableState(
                        GoogleAuthState.signedIn(account, RECONNECT_DETAIL)
                    )

                    is GoogleUserCanceledException -> saveStableState(
                        GoogleAuthState.calendarDenied(account, "Calendar access denied")
                    )

                    else -> publish(classifyError(account, throwable))
                }
            }
        }
    }

    fun cancelActiveFlow() {
        val flow = activeFlow ?: return
        flow.canceled.set(true)
        activeFlow = null

        pendingTokenCallback?.invoke(Result.failure(GoogleUserCanceledException()))
        pendingTokenCallback = null

        saveStableState(flow.fallbackState)
    }

    fun disconnect() {
        activeFlow?.canceled?.set(true)
        activeFlow = null

        val refreshToken = store.loadRefreshToken()
        if (!refreshToken.isNullOrBlank()) {
            executor.execute {
                runCatching { tokenService.revokeToken(refreshToken) }
                    .exceptionOrNull()
                    ?.let { Log.w(TAG, "Failed to revoke Google refresh token", it) }
            }
        }

        if (currentState.isPhoneFallbackMode) {
            phoneFallbackBridge?.disconnectPhoneAuthorization()
        }

        inMemoryAccessToken = null
        store.clear()
        publish(GoogleAuthState.signedOut("Signed out"))
    }

    fun onPhoneAuthStateChanged(
        status: GooglePhoneAuthStatus,
        account: GoogleAccountSummary? = null,
        detail: String? = null
    ) {
        when (status) {
            GooglePhoneAuthStatus.SIGNED_OUT -> saveStableState(
                GoogleAuthState.phoneFallbackRequired(
                    account = account ?: currentState.account,
                    detail = detail ?: PHONE_FALLBACK_DETAIL
                )
            )

            GooglePhoneAuthStatus.AUTHORIZING -> publish(
                GoogleAuthState.signingIn(
                    account = account ?: currentState.account,
                    detail = detail ?: "Complete Google authorization on your phone",
                    authMode = GoogleAuthState.AuthMode.PHONE_FALLBACK
                )
            )

            GooglePhoneAuthStatus.AUTHORIZED -> {
                val resolvedAccount = account ?: currentState.account
                if (resolvedAccount != null) {
                    saveStableState(
                        GoogleAuthState.calendarAuthorized(
                            resolvedAccount,
                            detail ?: "Calendar data is being synced from the phone",
                            authMode = GoogleAuthState.AuthMode.PHONE_FALLBACK
                        )
                    )
                } else {
                    saveStableState(GoogleAuthState.phoneFallbackRequired(detail = detail ?: PHONE_FALLBACK_DETAIL))
                }
                phoneFallbackBridge?.requestCalendarSnapshot()
            }

            GooglePhoneAuthStatus.ERROR -> publish(
                GoogleAuthState.phoneFallbackRequired(
                    account = account ?: currentState.account,
                    detail = detail ?: PHONE_FALLBACK_DETAIL
                )
            )
        }
    }

    fun onPhoneCalendarSnapshotReceived(snapshot: GoogleCalendarSnapshot) {
        val account = snapshot.account ?: currentState.account ?: return
        saveStableState(
            GoogleAuthState.calendarAuthorized(
                account,
                detail = "Calendar data synced from phone",
                authMode = GoogleAuthState.AuthMode.PHONE_FALLBACK
            )
        )
    }

    override fun requestCalendarAccessToken(
        activity: Activity?,
        allowUi: Boolean,
        callback: (Result<String>) -> Unit
    ) {
        if (currentState.isPhoneFallbackMode) {
            if (allowUi) {
                requestPhoneFallbackAuthorization()
            } else {
                phoneFallbackBridge?.requestCalendarSnapshot()
            }
            callback(Result.failure(GoogleUserActionRequiredException(PHONE_FALLBACK_DETAIL)))
            return
        }

        if (!ensureConfigured()) {
            callback(Result.failure(IllegalStateException("Google OAuth client ID is not configured")))
            return
        }

        currentValidAccessToken()?.let { accessToken ->
            currentState.account?.let { account ->
                if (!currentState.isCalendarAuthorized) {
                    saveStableState(GoogleAuthState.calendarAuthorized(account, "Calendar access granted"))
                }
            }
            callback(Result.success(accessToken))
            return
        }

        val refreshToken = store.loadRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            if (allowUi) {
                pendingTokenCallback = callback
                startDeviceAuthorizationFlow()
            } else {
                currentState.account?.let { account ->
                    saveStableState(GoogleAuthState.signedIn(account, RECONNECT_DETAIL))
                }
                callback(Result.failure(GoogleUserActionRequiredException(RECONNECT_DETAIL)))
            }
            return
        }

        if (!networkStatusProvider.isConnected()) {
            val failure = java.io.IOException("Network error")
            publish(GoogleAuthState.networkError(currentState.account, "Network error"))
            callback(Result.failure(failure))
            return
        }

        exchangeRefreshToken(refreshToken, allowUi, callback)
    }

    override fun clearAccessToken(token: String, callback: (Result<Unit>) -> Unit) {
        if (inMemoryAccessToken?.token == token) {
            inMemoryAccessToken = null
        }
        callback(Result.success(Unit))
    }

    private fun requestPhoneFallbackAuthorization() {
        saveStableState(
            GoogleAuthState.phoneFallbackRequired(
                account = currentState.account,
                detail = PHONE_FALLBACK_DETAIL
            )
        )
        phoneFallbackBridge?.requestPhoneAuthorization()
    }

    private fun startDeviceAuthorizationFlow() {
        if (activeFlow != null) return

        val fallbackState = currentState.account?.let {
            if (currentState.isCalendarAuthorized) {
                GoogleAuthState.calendarAuthorized(it, currentState.detail, authMode = currentState.authMode)
            } else {
                GoogleAuthState.signedIn(it, currentState.detail, authMode = currentState.authMode)
            }
        } ?: GoogleAuthState.signedOut(authMode = GoogleAuthState.AuthMode.DIRECT_DEVICE)

        publish(GoogleAuthState.signingIn(account = fallbackState.account, detail = "Preparing Google verification..."))

        executor.execute {
            try {
                val response = deviceService.requestDeviceCode(
                    clientId = clientId,
                    scopes = listOf("openid", "email", "profile", CALENDAR_READONLY_SCOPE)
                )

                activeFlow = ActiveGoogleDeviceFlow(
                    deviceCode = response.deviceCode,
                    expiresAtMs = System.currentTimeMillis() + (response.expiresInSeconds * 1000L),
                    pollIntervalMs = response.intervalSeconds.coerceAtLeast(5L) * 1000L,
                    fallbackState = fallbackState
                )

                publish(
                    GoogleAuthState.awaitingDeviceVerification(
                        account = fallbackState.account,
                        verificationUri = response.verificationUri,
                        userCode = response.userCode,
                        detail = DIRECT_FLOW_DETAIL
                    )
                )

                pollForDeviceAuthorization()
            } catch (unsupported: GoogleOAuthScopeUnsupportedException) {
                switchToPhoneFallback(unsupported.message ?: PHONE_FALLBACK_DETAIL)
            } catch (throwable: Throwable) {
                finishFlowWithError(fallbackState.account, throwable)
            }
        }
    }

    private fun pollForDeviceAuthorization() {
        val flow = activeFlow ?: return
        var intervalMs = flow.pollIntervalMs

        while (activeFlow === flow && !flow.canceled.get()) {
            if (System.currentTimeMillis() >= flow.expiresAtMs) {
                activeFlow = null
                pendingTokenCallback?.invoke(Result.failure(GoogleUserActionRequiredException("Verification code expired. Retry.")))
                pendingTokenCallback = null
                saveStableState(flow.fallbackState)
                return
            }

            try {
                val tokenResponse = deviceService.pollForTokens(clientId, flow.deviceCode)
                activeFlow = null
                handleTokenExchangeSuccess(tokenResponse)
                return
            } catch (_: GoogleOAuthAuthorizationPendingException) {
                sleepQuietly(intervalMs)
            } catch (_: GoogleOAuthSlowDownException) {
                intervalMs += 5_000L
                sleepQuietly(intervalMs)
            } catch (_: GoogleOAuthExpiredTokenException) {
                activeFlow = null
                pendingTokenCallback?.invoke(Result.failure(GoogleUserActionRequiredException("Verification code expired. Retry.")))
                pendingTokenCallback = null
                saveStableState(flow.fallbackState)
                return
            } catch (denied: GoogleOAuthAccessDeniedException) {
                activeFlow = null
                pendingTokenCallback?.invoke(Result.failure(GoogleUserCanceledException(denied.message ?: "Google flow canceled")))
                pendingTokenCallback = null
                flow.fallbackState.account?.let {
                    saveStableState(GoogleAuthState.calendarDenied(it, "Calendar access denied"))
                } ?: saveStableState(GoogleAuthState.signedOut("Google sign-in canceled"))
                return
            } catch (unsupported: GoogleOAuthScopeUnsupportedException) {
                activeFlow = null
                switchToPhoneFallback(unsupported.message ?: PHONE_FALLBACK_DETAIL)
                return
            } catch (throwable: Throwable) {
                activeFlow = null
                finishFlowWithError(flow.fallbackState.account, throwable)
                return
            }
        }
    }

    private fun switchToPhoneFallback(detail: String) {
        pendingTokenCallback?.invoke(Result.failure(GoogleUserActionRequiredException(detail)))
        pendingTokenCallback = null
        saveStableState(GoogleAuthState.phoneFallbackRequired(account = currentState.account, detail = detail))
        phoneFallbackBridge?.requestPhoneAuthorization()
    }

    private fun handleTokenExchangeSuccess(tokenResponse: GoogleOAuthTokenResponse) {
        val profile = tokenResponse.idToken?.let(::parseGoogleIdToken)
        val account = when {
            !profile?.email.isNullOrBlank() -> GoogleAccountSummary(
                email = profile?.email.orEmpty(),
                displayName = profile?.displayName
            )

            currentState.account != null -> currentState.account!!
            else -> throw IllegalStateException("Google sign-in finished without account details")
        }

        val expiresAtMs = System.currentTimeMillis() + (tokenResponse.expiresInSeconds * 1000L) - 30_000L
        inMemoryAccessToken = InMemoryGoogleAccessToken(
            token = tokenResponse.accessToken,
            expiresAtMs = expiresAtMs.coerceAtLeast(System.currentTimeMillis() + 30_000L)
        )

        tokenResponse.refreshToken?.let { store.saveRefreshToken(it) }
        saveStableState(GoogleAuthState.calendarAuthorized(account, "Calendar access granted"))

        pendingTokenCallback?.invoke(Result.success(tokenResponse.accessToken))
        pendingTokenCallback = null
    }

    private fun exchangeRefreshToken(
        refreshToken: String,
        allowUi: Boolean,
        callback: (Result<String>) -> Unit
    ) {
        executor.execute {
            try {
                val tokenResponse = tokenService.refreshAccessToken(clientId, refreshToken)
                tokenResponse.refreshToken?.let { store.saveRefreshToken(it) }

                val expiresAtMs = System.currentTimeMillis() + (tokenResponse.expiresInSeconds * 1000L) - 30_000L
                inMemoryAccessToken = InMemoryGoogleAccessToken(
                    token = tokenResponse.accessToken,
                    expiresAtMs = expiresAtMs.coerceAtLeast(System.currentTimeMillis() + 30_000L)
                )

                val account = resolveAccountForTokenResponse(tokenResponse)
                saveStableState(GoogleAuthState.calendarAuthorized(account, "Calendar access granted"))
                callback(Result.success(tokenResponse.accessToken))
            } catch (invalidGrant: GoogleOAuthInvalidGrantException) {
                handleRefreshTokenExpired(allowUi, callback)
            } catch (unsupported: GoogleOAuthScopeUnsupportedException) {
                switchToPhoneFallback(unsupported.message ?: PHONE_FALLBACK_DETAIL)
                callback(Result.failure(unsupported))
            } catch (throwable: Throwable) {
                publish(classifyError(currentState.account, throwable))
                callback(Result.failure(throwable))
            }
        }
    }

    private fun handleRefreshTokenExpired(
        allowUi: Boolean,
        callback: (Result<String>) -> Unit
    ) {
        store.saveRefreshToken(null)
        inMemoryAccessToken = null

        val account = currentState.account
        if (allowUi) {
            pendingTokenCallback = callback
            if (account != null) {
                saveStableState(GoogleAuthState.signedIn(account, RECONNECT_DETAIL))
            }
            startDeviceAuthorizationFlow()
            return
        }

        if (account != null) {
            saveStableState(GoogleAuthState.signedIn(account, RECONNECT_DETAIL))
        }
        callback(Result.failure(GoogleUserActionRequiredException(RECONNECT_DETAIL)))
    }

    private fun finishFlowWithError(
        account: GoogleAccountSummary?,
        throwable: Throwable
    ) {
        publish(classifyError(account, throwable))
        pendingTokenCallback?.invoke(Result.failure(throwable))
        pendingTokenCallback = null
    }

    private fun resolveAccountForTokenResponse(tokenResponse: GoogleOAuthTokenResponse): GoogleAccountSummary {
        val profile = tokenResponse.idToken?.let(::parseGoogleIdToken)
        if (!profile?.email.isNullOrBlank()) {
            return GoogleAccountSummary(
                email = profile?.email.orEmpty(),
                displayName = profile?.displayName ?: currentState.account?.displayName
            )
        }

        return currentState.account
            ?: throw IllegalStateException("Google token response did not include account details")
    }

    private fun classifyError(account: GoogleAccountSummary?, throwable: Throwable): GoogleAuthState {
        if (!networkStatusProvider.isConnected()) {
            return GoogleAuthState.networkError(account, "Network error", authMode = currentState.authMode)
        }

        return when (throwable) {
            is GoogleOAuthScopeUnsupportedException -> GoogleAuthState.phoneFallbackRequired(account, throwable.message)
            is GoogleOAuthClientConfigurationException -> GoogleAuthState.googleUnavailable(account, throwable.message)
            is GoogleOAuthInvalidGrantException -> GoogleAuthState.signedIn(
                account ?: return GoogleAuthState.signedOut(RECONNECT_DETAIL),
                RECONNECT_DETAIL
            )

            else -> GoogleAuthState.googleUnavailable(
                account,
                throwable.message ?: "Google auth failed",
                authMode = currentState.authMode
            )
        }
    }

    private fun parseGoogleIdToken(idToken: String): GoogleIdTokenProfile {
        val parts = idToken.split('.')
        if (parts.size < 2) {
            return GoogleIdTokenProfile(email = null, displayName = null)
        }

        val payload = String(
            Base64.getUrlDecoder().decode(parts[1]),
            Charsets.UTF_8
        )
        return GoogleIdTokenProfile(
            email = extractJsonField(payload, "email"),
            displayName = extractJsonField(payload, "name")
        )
    }

    private fun extractJsonField(json: String, fieldName: String): String? {
        val match = Regex("\"$fieldName\"\\s*:\\s*\"([^\"]*)\"").find(json) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    private fun currentValidAccessToken(): String? {
        val token = inMemoryAccessToken
        return if (token?.isValid() == true) token.token else null
    }

    private fun hasValidInMemoryToken(): Boolean = currentValidAccessToken() != null

    private fun ensureConfigured(): Boolean {
        if (!isConfigured()) {
            publish(GoogleAuthState.googleUnavailable(currentState.account, CONFIG_DETAIL))
            return false
        }
        return true
    }

    private fun ensureNetwork(): Boolean {
        if (!networkStatusProvider.isConnected()) {
            publish(GoogleAuthState.networkError(currentState.account, "Network error", authMode = currentState.authMode))
            return false
        }
        return true
    }

    private fun isConfigured(): Boolean {
        return clientId.isNotBlank() && clientId != CLIENT_ID_PLACEHOLDER
    }

    private fun saveStableState(state: GoogleAuthState) {
        currentState = state
        store.saveState(state)
        onStateChanged?.invoke(state)
    }

    private fun publish(state: GoogleAuthState) {
        currentState = state
        onStateChanged?.invoke(state)
    }

    private fun sleepQuietly(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
