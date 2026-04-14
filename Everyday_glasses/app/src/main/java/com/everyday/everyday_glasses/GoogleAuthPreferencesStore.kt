package com.everyday.everyday_glasses

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

interface GoogleAuthStateStore {
    fun loadState(): GoogleAuthState
    fun saveState(state: GoogleAuthState)
    fun loadRefreshToken(): String?
    fun saveRefreshToken(token: String?)
    fun clear()
}

class GoogleAuthPreferencesStore(context: Context) : GoogleAuthStateStore {
    companion object {
        private const val PREFS_NAME = "google_auth_state"
        private const val SECURE_PREFS_NAME = "google_auth_state_secure"
        private const val KEY_EMAIL = "email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_CALENDAR_AUTHORIZED = "calendar_authorized"
        private const val KEY_CALENDAR_DENIED = "calendar_denied"
        private const val KEY_PHONE_FALLBACK_REQUIRED = "phone_fallback_required"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = createSecurePrefs()

    override fun loadState(): GoogleAuthState {
        val persisted = PersistedGoogleAuthState(
            accountEmail = prefs.getString(KEY_EMAIL, null),
            accountDisplayName = prefs.getString(KEY_DISPLAY_NAME, null),
            authMode = prefs.getString(KEY_AUTH_MODE, GoogleAuthState.AuthMode.DIRECT_DEVICE.name)
                ?: GoogleAuthState.AuthMode.DIRECT_DEVICE.name,
            isCalendarAuthorized = prefs.getBoolean(KEY_CALENDAR_AUTHORIZED, false),
            isCalendarDenied = prefs.getBoolean(KEY_CALENDAR_DENIED, false),
            requiresPhoneFallback = prefs.getBoolean(KEY_PHONE_FALLBACK_REQUIRED, false)
        )
        return persisted.toAuthState()
    }

    override fun saveState(state: GoogleAuthState) {
        val persisted = PersistedGoogleAuthState.fromAuthState(state)
        prefs.edit()
            .putString(KEY_EMAIL, persisted.accountEmail)
            .putString(KEY_DISPLAY_NAME, persisted.accountDisplayName)
            .putString(KEY_AUTH_MODE, persisted.authMode)
            .putBoolean(KEY_CALENDAR_AUTHORIZED, persisted.isCalendarAuthorized)
            .putBoolean(KEY_CALENDAR_DENIED, persisted.isCalendarDenied)
            .putBoolean(KEY_PHONE_FALLBACK_REQUIRED, persisted.requiresPhoneFallback)
            .apply()
    }

    override fun loadRefreshToken(): String? = securePrefs.getString(KEY_REFRESH_TOKEN, null)

    override fun saveRefreshToken(token: String?) {
        securePrefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
        securePrefs.edit().clear().apply()
    }

    private fun createSecurePrefs(): SharedPreferences {
        return runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

            EncryptedSharedPreferences.create(
                SECURE_PREFS_NAME,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // Fall back rather than bricking auth entirely on vendor-specific keystore issues.
            appContext.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
