package com.andreacovelli.whispertranscriber

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.andreacovelli.whispertranscriber.security.KeystoreCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class TranscriptionProvider(val modelName: String? = null) {
    ELEVEN_LABS,
    GEMINI_FLASH_LITE("gemini-flash-lite-latest"),
    GEMINI_FLASH("gemini-flash-latest"),
    GEMINI_PRO("gemini-pro-latest"),
    GEMINI_CUSTOM, // model name stored separately in DataStore
    ;

    fun isGemini(): Boolean = this != ELEVEN_LABS
}

sealed class ApiKeyState {
    object Missing : ApiKeyState()
    object Present : ApiKeyState()
    data class Error(val message: String) : ApiKeyState()
}

data class TranscriptionQueueSettings(
    val provider: TranscriptionProvider,
    val customModelName: String,
    val speakerDiarizationEnabled: Boolean,
)

internal fun decodeStoredTranscriptionProvider(name: String?): TranscriptionProvider = when (name) {
    null -> TranscriptionProvider.ELEVEN_LABS
    "GEMINI_25_FLASH" -> TranscriptionProvider.GEMINI_FLASH
    else -> runCatching { TranscriptionProvider.valueOf(name) }
        .getOrDefault(TranscriptionProvider.ELEVEN_LABS)
}

@Singleton
class ApiKeyManager @Inject constructor(@param:ApplicationContext private val context: Context) {
    companion object {
        private val ELEVEN_LABS_API_KEY_ENC = stringPreferencesKey("eleven_labs_api_key_enc")
        private val GEMINI_API_KEY_ENC = stringPreferencesKey("gemini_api_key_enc")
        private val GEMINI_TRANSCRIPTION_CUSTOM_MODEL_NAME = stringPreferencesKey("gemini_custom_model_name")
        private val GEMINI_SUMMARIZER_CUSTOM_MODEL_NAME = stringPreferencesKey("gemini_summarizer_custom_model_name")

        private val SELECTED_PROVIDER = stringPreferencesKey("transcription_provider")
        private val SELECTED_SUMMARIZER_PROVIDER = stringPreferencesKey("summarizer_provider")
        private val AUTOMATIC_SUMMARIZATION_ENABLED = booleanPreferencesKey("automatic_summarization_enabled")
        private val SPEAKER_DIARIZATION_ENABLED = booleanPreferencesKey("speaker_diarization_enabled")
    }

    val automaticSummarizationFlow: Flow<Boolean> = context.dataStore.data.map { p ->
        p[AUTOMATIC_SUMMARIZATION_ENABLED] ?: false
    }

    suspend fun saveAutomaticSummarization(enabled: Boolean) {
        context.dataStore.edit { it[AUTOMATIC_SUMMARIZATION_ENABLED] = enabled }
    }

    suspend fun saveSpeakerDiarization(enabled: Boolean) {
        context.dataStore.edit { it[SPEAKER_DIARIZATION_ENABLED] = enabled }
    }

    // Save (encrypt before persisting)
    suspend fun saveElevenLabsApiKey(key: String) {
        val trimmed = key.trim()
        context.dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(ELEVEN_LABS_API_KEY_ENC)
            } else {
                val enc = KeystoreCrypto.encryptToBase64(trimmed.toByteArray(Charsets.UTF_8))
                prefs[ELEVEN_LABS_API_KEY_ENC] = enc
            }
        }
    }

    suspend fun saveGeminiApiKey(key: String) {
        val trimmed = key.trim()
        context.dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(GEMINI_API_KEY_ENC)
            } else {
                val enc = KeystoreCrypto.encryptToBase64(trimmed.toByteArray(Charsets.UTF_8))
                prefs[GEMINI_API_KEY_ENC] = enc
            }
        }
    }

    suspend fun saveProvider(provider: TranscriptionProvider) {
        context.dataStore.edit { it[SELECTED_PROVIDER] = provider.name }
    }

    suspend fun saveSummarizerProvider(provider: TranscriptionProvider) {
        context.dataStore.edit { it[SELECTED_SUMMARIZER_PROVIDER] = provider.name }
    }

    private suspend fun saveCustomModelName(name: String, key: Preferences.Key<String>) {
        context.dataStore.edit { prefs ->
            if (name.isBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = name.trim()
            }
        }
    }

    suspend fun saveTranscriptionCustomGeminiModelName(name: String) {
        saveCustomModelName(name, GEMINI_TRANSCRIPTION_CUSTOM_MODEL_NAME)
    }

    suspend fun saveSummarizerCustomGeminiModelName(name: String) {
        saveCustomModelName(name, GEMINI_SUMMARIZER_CUSTOM_MODEL_NAME)
    }

    // Quick wipe actions
    suspend fun forgetElevenLabsApiKey() {
        context.dataStore.edit {
            it.remove(ELEVEN_LABS_API_KEY_ENC)
        }
    }

    suspend fun forgetGeminiApiKey() {
        context.dataStore.edit {
            it.remove(GEMINI_API_KEY_ENC)
        }
    }

    // Read flows for UI state (does not expose keys)
    private fun getApiKeyStateFlow(key: Preferences.Key<String>): Flow<ApiKeyState> = context.dataStore.data.map { p ->
        val enc = p[key]
        if (enc == null) {
            ApiKeyState.Missing
        } else {
            try {
                KeystoreCrypto.decryptFromBase64(enc)?.fill(0)
                ApiKeyState.Present
            } catch (e: GeneralSecurityException) {
                ApiKeyState.Error(e.message ?: "Keystore decryption failed")
            } catch (e: IllegalArgumentException) {
                ApiKeyState.Error(e.message ?: "Keystore decryption failed")
            }
        }
    }

    val elevenLabsApiKeyStateFlow: Flow<ApiKeyState> = getApiKeyStateFlow(ELEVEN_LABS_API_KEY_ENC)
    val geminiApiKeyStateFlow: Flow<ApiKeyState> = getApiKeyStateFlow(GEMINI_API_KEY_ENC)

    // Suspend functions to securely read keys on-demand
    private suspend fun getApiKey(key: Preferences.Key<String>): CharArray? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        val enc = prefs[key] ?: return null
        return try {
            val bytes = KeystoreCrypto.decryptFromBase64(enc) ?: return null
            val cb = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
            val chars = CharArray(cb.remaining())
            cb.get(chars)
            bytes.fill(0)
            if (cb.hasArray()) cb.array().fill('\u0000')
            chars
        } catch (e: GeneralSecurityException) {
            Log.w("ApiKeyManager", "Failed to decrypt API key", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w("ApiKeyManager", "Failed to decode stored API key", e)
            null
        }
    }

    suspend fun getElevenLabsApiKey(): CharArray? = getApiKey(ELEVEN_LABS_API_KEY_ENC)
    suspend fun getGeminiApiKey(): CharArray? = getApiKey(GEMINI_API_KEY_ENC)

    val summarizerCustomGeminiModelNameFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[GEMINI_SUMMARIZER_CUSTOM_MODEL_NAME]?.takeIf { it.isNotBlank() }
    }

    val summarizerProviderFlow: Flow<TranscriptionProvider> = context.dataStore.data.map { p ->
        p[SELECTED_SUMMARIZER_PROVIDER]
            ?.let { runCatching { TranscriptionProvider.valueOf(it) }.getOrNull() }
            ?: TranscriptionProvider.GEMINI_FLASH
    }

    val transcriptionQueueSettingsFlow: Flow<TranscriptionQueueSettings> = context.dataStore.data.map { prefs ->
        TranscriptionQueueSettings(
            provider = decodeStoredTranscriptionProvider(prefs[SELECTED_PROVIDER]),
            customModelName = prefs[GEMINI_TRANSCRIPTION_CUSTOM_MODEL_NAME].orEmpty(),
            speakerDiarizationEnabled = prefs[SPEAKER_DIARIZATION_ENABLED] ?: false,
        )
    }

    suspend fun getTranscriptionQueueSettings(): TranscriptionQueueSettings {
        val prefs = context.dataStore.data.firstOrNull()
        return TranscriptionQueueSettings(
            provider = decodeStoredTranscriptionProvider(prefs?.get(SELECTED_PROVIDER)),
            customModelName = prefs?.get(GEMINI_TRANSCRIPTION_CUSTOM_MODEL_NAME).orEmpty(),
            speakerDiarizationEnabled = prefs?.get(SPEAKER_DIARIZATION_ENABLED) ?: false,
        )
    }
}
