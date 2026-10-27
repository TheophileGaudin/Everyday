package com.everyday.everyday_glasses.interaction.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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

/** A Google API key ("AIza" and 35 more characters). */
private val GOOGLE_API_KEY = Regex("AIza[0-9A-Za-z_-]{35}")

/**
 * The API key in pasted text: a Google API key found anywhere in it (the text copied may hold more
 * than the key), otherwise the text without its spaces and line breaks.
 */
fun extractApiKey(text: String): String = GOOGLE_API_KEY.find(text)?.value ?: text.filterNot(Char::isWhitespace)

/** Whether [text] holds a Google API key, for logs that must not show the key itself. */
fun containsGoogleApiKey(text: String): Boolean = GOOGLE_API_KEY.containsMatchIn(text)

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

    @SuppressLint("UseKtx") /** The KTX edit function does not return whether saving succeeded which is what we need here */
    fun saveModel(modelId: String, mode: AssistantModelSelectionMode): Boolean = prefs.edit()
        .putString(KEY_MODEL_ID, modelId)
        .putString(KEY_SELECTION_MODE, mode.name)
        .commit()

    fun clearApiKey() {
        securePrefs?.edit {remove(KEY_API_KEY)}
    }

    fun isCapabilityProbed(modelId: String): Boolean =
        prefs.getStringSet(KEY_PROBED_MODELS, emptySet())?.contains(modelId) == true

    fun markCapabilityProbed(modelId: String) {
        val models = prefs.getStringSet(KEY_PROBED_MODELS, emptySet()).orEmpty().toMutableSet()
        models += modelId
        prefs.edit {putStringSet(KEY_PROBED_MODELS, models)}
    }

    /** Forgets that [modelId] passed its check (Google retired it, say), so it is checked again. */
    fun forgetCapabilityProbe(modelId: String) {
        val models = prefs.getStringSet(KEY_PROBED_MODELS, emptySet()).orEmpty().toMutableSet()
        if (models.remove(modelId)) prefs.edit {putStringSet(KEY_PROBED_MODELS, models)}
    }
}

object AssistantModelPolicy {
    private val stableFlashLite = Regex("^gemini-(\\d+(?:\\.\\d+)*)-flash-lite$")

    /** A versioned model id: "gemini-3.5-flash" is version 3.5 of the "flash" family. */
    private val versioned = Regex("^gemini-(\\d+(?:\\.\\d+)*)-(.+)$")

    /** The family of [id] ("flash", "flash-lite", "pro"), or null when it carries no version. */
    fun family(id: String): String? = versioned.matchEntire(id.removePrefix("models/").lowercase())?.groupValues?.get(2)

    private fun version(id: String): List<Int> = versioned.matchEntire(id.removePrefix("models/").lowercase())
        ?.groupValues?.get(1)?.split('.')?.mapNotNull(String::toIntOrNull).orEmpty()

    /**
     * The models of [id]'s family, newest first ("gemini-2.5-flash" → "gemini-3.8-flash", "gemini-3.7-flash", …):
     * what replaces a model Google retired.
     */
    fun sameFamilyNewestFirst(models: List<AssistantModel>, id: String): List<AssistantModel> {
        val family = family(id) ?: return emptyList()
        return models
            .filter { family(it.id) == family }
            .sortedWith { left, right -> compareParts(version(right.id), version(left.id)).takeIf { it != 0 } ?: left.id.compareTo(right.id) }
    }

    private fun compareParts(a: List<Int>, b: List<Int>): Int {
        for (index in 0 until maxOf(a.size, b.size)) {
            val delta = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (delta != 0) return delta
        }
        return 0
    }
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
