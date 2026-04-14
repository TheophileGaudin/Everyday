package com.everyday.everyday_glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface NetworkStatusProvider {
    fun isConnected(): Boolean
}

data class GoogleOAuthDeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long
)

data class GoogleOAuthTokenResponse(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String? = null,
    val idToken: String? = null
)

interface GoogleOAuthDeviceService {
    @Throws(IOException::class)
    fun requestDeviceCode(
        clientId: String,
        scopes: List<String>
    ): GoogleOAuthDeviceCodeResponse

    @Throws(IOException::class)
    fun pollForTokens(
        clientId: String,
        deviceCode: String
    ): GoogleOAuthTokenResponse
}

interface GoogleOAuthTokenService {
    @Throws(IOException::class)
    fun refreshAccessToken(
        clientId: String,
        refreshToken: String
    ): GoogleOAuthTokenResponse

    @Throws(IOException::class)
    fun revokeToken(token: String)
}

class GoogleUserActionRequiredException(message: String = "Google Calendar access needs user action") :
    IllegalStateException(message)

class GoogleUserCanceledException(message: String = "Google flow canceled") :
    IllegalStateException(message)

class GoogleOAuthInvalidGrantException(message: String = "Google authorization expired") :
    IOException(message)

class GoogleOAuthClientConfigurationException(message: String) :
    IOException(message)

class GoogleOAuthAuthorizationPendingException :
    IOException("Google authorization is still pending")

class GoogleOAuthSlowDownException :
    IOException("Google requested slower device-code polling")

class GoogleOAuthExpiredTokenException :
    IOException("Google device code expired")

class GoogleOAuthAccessDeniedException(message: String = "Google authorization denied") :
    IOException(message)

class GoogleOAuthScopeUnsupportedException(message: String) :
    IOException(message)

class HttpUrlConnectionGoogleOAuthDeviceService : GoogleOAuthDeviceService {
    companion object {
        private const val DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    }

    override fun requestDeviceCode(
        clientId: String,
        scopes: List<String>
    ): GoogleOAuthDeviceCodeResponse {
        val body = formPost(
            DEVICE_CODE_ENDPOINT,
            linkedMapOf(
                "client_id" to clientId,
                "scope" to scopes.joinToString(" ")
            )
        )
        val json = JSONObject(body)
        return GoogleOAuthDeviceCodeResponse(
            deviceCode = json.optString("device_code"),
            userCode = json.optString("user_code"),
            verificationUri = json.optString("verification_uri")
                .ifBlank { json.optString("verification_url") }
                .ifBlank { "https://www.google.com/device" },
            expiresInSeconds = json.optLong("expires_in", 1800L),
            intervalSeconds = json.optLong("interval", 5L)
        )
    }

    override fun pollForTokens(
        clientId: String,
        deviceCode: String
    ): GoogleOAuthTokenResponse {
        val body = formPost(
            TOKEN_ENDPOINT,
            linkedMapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
            )
        )
        val json = JSONObject(body)
        return GoogleOAuthTokenResponse(
            accessToken = json.optString("access_token"),
            expiresInSeconds = json.optLong("expires_in", 3600L),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            idToken = json.optString("id_token").takeIf { it.isNotBlank() }
        )
    }

    private fun formPost(
        endpoint: String,
        fields: Map<String, String>
    ): String {
        val payload = fields.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }.toByteArray(Charsets.UTF_8)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload) }
            val responseCode = connection.responseCode
            val body = (connection.inputStream ?: connection.errorStream)
                .bufferedReader()
                .use { it.readText() }

            if (responseCode !in 200..299) {
                throw mapGoogleOAuthError(body)
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun mapGoogleOAuthError(body: String): IOException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val error = json?.optString("error").orEmpty()
        val description = json?.optString("error_description").orEmpty()
        val message = description.ifBlank { body.ifBlank { "Google OAuth failed" } }
        return when (error) {
            "authorization_pending" -> GoogleOAuthAuthorizationPendingException()
            "slow_down" -> GoogleOAuthSlowDownException()
            "expired_token" -> GoogleOAuthExpiredTokenException()
            "access_denied" -> GoogleOAuthAccessDeniedException(message)
            "invalid_scope" -> GoogleOAuthScopeUnsupportedException(message)
            "invalid_grant" -> GoogleOAuthInvalidGrantException(message)
            "unauthorized_client",
            "invalid_client" -> GoogleOAuthClientConfigurationException(message)
            else -> IOException(message)
        }
    }
}

class HttpUrlConnectionGoogleOAuthTokenService : GoogleOAuthTokenService {
    companion object {
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
    }

    override fun refreshAccessToken(
        clientId: String,
        refreshToken: String
    ): GoogleOAuthTokenResponse {
        val body = formPost(
            TOKEN_ENDPOINT,
            linkedMapOf(
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            )
        )
        val json = JSONObject(body)
        return GoogleOAuthTokenResponse(
            accessToken = json.optString("access_token"),
            expiresInSeconds = json.optLong("expires_in", 3600L),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
            idToken = json.optString("id_token").takeIf { it.isNotBlank() }
        )
    }

    override fun revokeToken(token: String) {
        formPost(
            REVOKE_ENDPOINT,
            linkedMapOf("token" to token)
        )
    }

    private fun formPost(
        endpoint: String,
        fields: Map<String, String>
    ): String {
        val payload = fields.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }.toByteArray(Charsets.UTF_8)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload) }
            val responseCode = connection.responseCode
            val body = (connection.inputStream ?: connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (responseCode !in 200..299) {
                throw mapGoogleOAuthError(body)
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun mapGoogleOAuthError(body: String): IOException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val error = json?.optString("error").orEmpty()
        val description = json?.optString("error_description").orEmpty()
        val message = description.ifBlank { body.ifBlank { "Google OAuth failed" } }
        return when (error) {
            "invalid_grant" -> GoogleOAuthInvalidGrantException(message)
            "invalid_scope" -> GoogleOAuthScopeUnsupportedException(message)
            "unauthorized_client",
            "invalid_client" -> GoogleOAuthClientConfigurationException(message)
            else -> IOException(message)
        }
    }
}

class DefaultNetworkStatusProvider(
    context: Context
) : NetworkStatusProvider {
    private val appContext = context.applicationContext

    override fun isConnected(): Boolean {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
