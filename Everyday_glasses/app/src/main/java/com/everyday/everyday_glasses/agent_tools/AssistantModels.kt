package com.everyday.everyday_glasses.agent_tools

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

enum class AssistantModelSelectionMode { AUTO_RECOMMENDED, USER_SELECTED }

data class AssistantModel(
    val id: String,
    val displayName: String,
    val description: String = ""
)

data class AssistantConfiguration(
    val apiKey: String,
    val modelId: String,
    val selectionMode: AssistantModelSelectionMode
)

class AssistantPreferencesStore(context: Context) {
    companion object {
        const val INITIAL_RECOMMENDED_MODEL = "gemini-3.5-flash-lite"
        private const val PREFS_NAME = "assistant_preferences"
        private const val SECURE_PREFS_NAME = "assistant_preferences_secure"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_SELECTION_MODE = "selection_mode"
        private const val KEY_PROBED_MODELS = "probed_audio_tool_models"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences? = runCatching {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            SECURE_PREFS_NAME,
            alias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    val isSecureStorageAvailable: Boolean get() = securePrefs != null

    fun load(): AssistantConfiguration? {
        val apiKey = securePrefs?.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (apiKey.isBlank()) return null
        val modelId = prefs.getString(KEY_MODEL_ID, INITIAL_RECOMMENDED_MODEL)
            ?.takeIf { it.isNotBlank() } ?: INITIAL_RECOMMENDED_MODEL
        val mode = runCatching {
            AssistantModelSelectionMode.valueOf(
                prefs.getString(KEY_SELECTION_MODE, AssistantModelSelectionMode.AUTO_RECOMMENDED.name)
                    ?: AssistantModelSelectionMode.AUTO_RECOMMENDED.name
            )
        }.getOrDefault(AssistantModelSelectionMode.AUTO_RECOMMENDED)
        return AssistantConfiguration(apiKey, modelId, mode)
    }

    fun save(configuration: AssistantConfiguration): Boolean {
        val secure = securePrefs ?: return false
        val keySaved = secure.edit().putString(KEY_API_KEY, configuration.apiKey.trim()).commit()
        val modelSaved = prefs.edit()
            .putString(KEY_MODEL_ID, configuration.modelId)
            .putString(KEY_SELECTION_MODE, configuration.selectionMode.name)
            .commit()
        return keySaved && modelSaved
    }

    fun saveModel(modelId: String, mode: AssistantModelSelectionMode): Boolean = prefs.edit()
        .putString(KEY_MODEL_ID, modelId)
        .putString(KEY_SELECTION_MODE, mode.name)
        .commit()

    fun clearApiKey() {
        securePrefs?.edit()?.remove(KEY_API_KEY)?.commit()
    }

    fun isCapabilityProbed(modelId: String): Boolean =
        prefs.getStringSet(KEY_PROBED_MODELS, emptySet())?.contains(modelId) == true

    fun markCapabilityProbed(modelId: String) {
        val models = prefs.getStringSet(KEY_PROBED_MODELS, emptySet()).orEmpty().toMutableSet()
        models += modelId
        prefs.edit().putStringSet(KEY_PROBED_MODELS, models).apply()
    }
}

object AssistantModelPolicy {
    private val stableFlashLite = Regex("^gemini-(\\d+(?:\\.\\d+)*)-flash-lite$")
    private val excluded = listOf("preview", "experimental", "exp", "latest", "live", "tts", "image", "embedding")

    fun isGeneralGeminiModel(id: String): Boolean {
        val normalized = id.removePrefix("models/").lowercase()
        return normalized.startsWith("gemini-") && excluded.none { normalized.contains(it) }
    }

    fun recommendedCandidates(models: List<AssistantModel>): List<AssistantModel> = models
        .filter { stableFlashLite.matches(it.id.removePrefix("models/").lowercase()) }
        .sortedWith { left, right -> compareVersionsDescending(left.id, right.id) }

    fun chooseRecommended(models: List<AssistantModel>): AssistantModel? = recommendedCandidates(models).firstOrNull()

    private fun compareVersionsDescending(left: String, right: String): Int {
        fun parts(value: String): List<Int> = stableFlashLite.matchEntire(value.removePrefix("models/").lowercase())
            ?.groupValues?.get(1)?.split('.')?.mapNotNull(String::toIntOrNull).orEmpty()
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val delta = (b.getOrElse(index) { 0 }).compareTo(a.getOrElse(index) { 0 })
            if (delta != 0) return delta
        }
        return left.compareTo(right)
    }
}
