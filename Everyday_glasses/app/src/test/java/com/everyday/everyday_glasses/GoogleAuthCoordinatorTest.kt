package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor

class GoogleAuthCoordinatorTest {
    @Test
    fun `successful device connect ends in calendar authorized state`() {
        val store = InMemoryGoogleAuthStateStore()
        val deviceService = FakeGoogleOAuthDeviceService(
            deviceCodeResponse = GoogleOAuthDeviceCodeResponse(
                deviceCode = "device-code",
                userCode = "ABCD-EFGH",
                verificationUri = "https://google.com/device",
                expiresInSeconds = 1800,
                intervalSeconds = 5
            ),
            pollResponses = ArrayDeque(
                listOf(
                    FakePollResponse.Success(
                        GoogleOAuthTokenResponse(
                            accessToken = "fresh-access-token",
                            expiresInSeconds = 3600,
                            refreshToken = "refresh-token",
                            idToken = buildIdToken("user@example.com", "Everyday User")
                        )
                    )
                )
            )
        )
        val coordinator = GoogleAuthCoordinator(
            clientId = "device-client-id.apps.googleusercontent.com",
            store = store,
            deviceService = deviceService,
            tokenService = FakeGoogleOAuthTokenService(),
            networkStatusProvider = FakeNetworkStatusProvider(true),
            phoneFallbackBridge = RecordingPhoneFallbackBridge(),
            executor = Executor { runnable -> runnable.run() }
        )

        coordinator.beginConnect(null)

        assertEquals("device-client-id.apps.googleusercontent.com", deviceService.lastDeviceCodeClientId)
        assertEquals(
            listOf("openid", "email", "profile", GoogleAuthCoordinator.CALENDAR_READONLY_SCOPE),
            deviceService.lastRequestedScopes
        )
        assertEquals(GoogleAuthState.Status.CALENDAR_AUTHORIZED, coordinator.getCurrentState().status)
        assertEquals("user@example.com", coordinator.getCurrentState().account?.email)
        assertEquals("refresh-token", store.refreshToken)
        assertEquals(GoogleAuthState.Status.CALENDAR_AUTHORIZED, store.state.status)
        assertEquals("device-code", deviceService.lastPolledDeviceCode)
    }

    @Test
    fun `unsupported device scope switches to phone fallback and requests phone authorization`() {
        val phoneBridge = RecordingPhoneFallbackBridge()
        val coordinator = GoogleAuthCoordinator(
            clientId = "device-client-id.apps.googleusercontent.com",
            store = InMemoryGoogleAuthStateStore(),
            deviceService = FakeGoogleOAuthDeviceService(
                requestDeviceCodeFailure = GoogleOAuthScopeUnsupportedException("Calendar scope is not supported")
            ),
            tokenService = FakeGoogleOAuthTokenService(),
            networkStatusProvider = FakeNetworkStatusProvider(true),
            phoneFallbackBridge = phoneBridge,
            executor = Executor { runnable -> runnable.run() }
        )

        coordinator.beginConnect(null)

        assertEquals(GoogleAuthState.Status.PHONE_FALLBACK_REQUIRED, coordinator.getCurrentState().status)
        assertEquals(GoogleAuthState.AuthMode.PHONE_FALLBACK, coordinator.getCurrentState().authMode)
        assertEquals(1, phoneBridge.authorizationRequests)
    }

    @Test
    fun `refresh token is used for silent calendar access`() {
        val account = GoogleAccountSummary("user@example.com", "Everyday User")
        val store = InMemoryGoogleAuthStateStore(
            initialState = GoogleAuthState.signedIn(account),
            initialRefreshToken = "refresh-token"
        )
        val tokenService = FakeGoogleOAuthTokenService(
            refreshResponse = GoogleOAuthTokenResponse(
                accessToken = "refreshed-access-token",
                expiresInSeconds = 3600,
                refreshToken = "refresh-token",
                idToken = buildIdToken("user@example.com", "Everyday User")
            )
        )
        val coordinator = GoogleAuthCoordinator(
            clientId = "device-client-id.apps.googleusercontent.com",
            store = store,
            tokenService = tokenService,
            networkStatusProvider = FakeNetworkStatusProvider(true),
            phoneFallbackBridge = RecordingPhoneFallbackBridge(),
            executor = Executor { runnable -> runnable.run() }
        )

        var tokenResult: Result<String>? = null
        coordinator.requestCalendarAccessToken(activity = null, allowUi = false) { tokenResult = it }

        assertEquals("refresh-token", tokenService.lastRefreshToken)
        assertEquals("refreshed-access-token", tokenResult!!.getOrThrow())
        assertEquals(GoogleAuthState.Status.CALENDAR_AUTHORIZED, coordinator.getCurrentState().status)
        assertEquals("user@example.com", coordinator.getCurrentState().account?.email)
    }

    @Test
    fun `phone auth updates state and requests snapshot`() {
        val phoneBridge = RecordingPhoneFallbackBridge()
        val coordinator = GoogleAuthCoordinator(
            clientId = "device-client-id.apps.googleusercontent.com",
            store = InMemoryGoogleAuthStateStore(
                GoogleAuthState.phoneFallbackRequired(detail = "Phone sign-in required")
            ),
            tokenService = FakeGoogleOAuthTokenService(),
            networkStatusProvider = FakeNetworkStatusProvider(true),
            phoneFallbackBridge = phoneBridge,
            executor = Executor { runnable -> runnable.run() }
        )

        coordinator.onPhoneAuthStateChanged(
            status = GooglePhoneAuthStatus.AUTHORIZED,
            account = GoogleAccountSummary("user@example.com", "Everyday User"),
            detail = "Authorized on phone"
        )

        assertEquals(GoogleAuthState.Status.CALENDAR_AUTHORIZED, coordinator.getCurrentState().status)
        assertEquals(GoogleAuthState.AuthMode.PHONE_FALLBACK, coordinator.getCurrentState().authMode)
        assertEquals(1, phoneBridge.snapshotRequests)
    }

    @Test
    fun `disconnect clears local state and revokes refresh token`() {
        val store = InMemoryGoogleAuthStateStore(
            initialState = GoogleAuthState.calendarAuthorized(
                GoogleAccountSummary("user@example.com", "Everyday User")
            ),
            initialRefreshToken = "refresh-token"
        )
        val tokenService = FakeGoogleOAuthTokenService()
        val coordinator = GoogleAuthCoordinator(
            clientId = "device-client-id.apps.googleusercontent.com",
            store = store,
            tokenService = tokenService,
            networkStatusProvider = FakeNetworkStatusProvider(true),
            phoneFallbackBridge = RecordingPhoneFallbackBridge(),
            executor = Executor { runnable -> runnable.run() }
        )

        coordinator.disconnect()

        assertEquals(GoogleAuthState.Status.SIGNED_OUT, coordinator.getCurrentState().status)
        assertEquals(GoogleAuthState.Status.SIGNED_OUT, store.state.status)
        assertNull(store.refreshToken)
        assertEquals("refresh-token", tokenService.revokedToken)
    }
}

private class InMemoryGoogleAuthStateStore(
    initialState: GoogleAuthState = GoogleAuthState.signedOut(),
    initialRefreshToken: String? = null
) : GoogleAuthStateStore {
    var state: GoogleAuthState = initialState
        private set
    var refreshToken: String? = initialRefreshToken
        private set

    override fun loadState(): GoogleAuthState = state

    override fun saveState(state: GoogleAuthState) {
        this.state = state
    }

    override fun loadRefreshToken(): String? = refreshToken

    override fun saveRefreshToken(token: String?) {
        refreshToken = token
    }

    override fun clear() {
        state = GoogleAuthState.signedOut()
        refreshToken = null
    }
}

private sealed class FakePollResponse {
    data class Success(val response: GoogleOAuthTokenResponse) : FakePollResponse()
    data class Failure(val throwable: IOException) : FakePollResponse()
}

private class FakeGoogleOAuthDeviceService(
    private val deviceCodeResponse: GoogleOAuthDeviceCodeResponse = GoogleOAuthDeviceCodeResponse(
        deviceCode = "device-code",
        userCode = "ABCD-EFGH",
        verificationUri = "https://google.com/device",
        expiresInSeconds = 1800,
        intervalSeconds = 5
    ),
    private val pollResponses: ArrayDeque<FakePollResponse> = ArrayDeque(),
    private val requestDeviceCodeFailure: IOException? = null
) : GoogleOAuthDeviceService {
    var lastDeviceCodeClientId: String? = null
        private set
    var lastRequestedScopes: List<String>? = null
        private set
    var lastPolledDeviceCode: String? = null
        private set

    override fun requestDeviceCode(
        clientId: String,
        scopes: List<String>
    ): GoogleOAuthDeviceCodeResponse {
        requestDeviceCodeFailure?.let { throw it }
        lastDeviceCodeClientId = clientId
        lastRequestedScopes = scopes
        return deviceCodeResponse
    }

    override fun pollForTokens(
        clientId: String,
        deviceCode: String
    ): GoogleOAuthTokenResponse {
        lastPolledDeviceCode = deviceCode
        val response = pollResponses.pollFirst()
            ?: error("No fake device poll response queued")
        return when (response) {
            is FakePollResponse.Success -> response.response
            is FakePollResponse.Failure -> throw response.throwable
            else -> error("Unexpected fake device poll response")
        }
    }
}

private class FakeGoogleOAuthTokenService(
    private val refreshResponse: GoogleOAuthTokenResponse = GoogleOAuthTokenResponse(
        accessToken = "refreshed-access-token",
        expiresInSeconds = 3600,
        refreshToken = "refresh-token",
        idToken = buildIdToken("user@example.com", "Everyday User")
    )
) : GoogleOAuthTokenService {
    var lastRefreshToken: String? = null
        private set
    var revokedToken: String? = null
        private set

    override fun refreshAccessToken(clientId: String, refreshToken: String): GoogleOAuthTokenResponse {
        lastRefreshToken = refreshToken
        return refreshResponse
    }

    override fun revokeToken(token: String) {
        revokedToken = token
    }
}

private class FakeNetworkStatusProvider(
    private val connected: Boolean
) : NetworkStatusProvider {
    override fun isConnected(): Boolean = connected
}

private class RecordingPhoneFallbackBridge : GooglePhoneFallbackBridge {
    var authorizationRequests = 0
        private set
    var snapshotRequests = 0
        private set
    var disconnectRequests = 0
        private set

    override fun requestPhoneAuthorization() {
        authorizationRequests += 1
    }

    override fun requestCalendarSnapshot() {
        snapshotRequests += 1
    }

    override fun disconnectPhoneAuthorization() {
        disconnectRequests += 1
    }
}

private fun buildIdToken(email: String, displayName: String): String {
    val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"email":"$email","name":"$displayName"}""".toByteArray()
    )
    return "$header.$payload.signature"
}
