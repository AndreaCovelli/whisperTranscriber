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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class TranscriptionProvider(val modelName: String? = null) {
    ELEVEN_LABS,
    MICROSOFT_MAI_TRANSCRIBE("mai-transcribe-1"),
    GEMINI_FLASH_LITE("gemini-flash-lite-latest"),
    GEMINI_FLASH("gemini-flash-latest"),
    GEMINI_PRO("gemini-pro-latest"),
    GEMINI_CUSTOM, // model name stored separately in DataStore
    ;

    fun isGemini(): Boolean = this == GEMINI_FLASH_LITE ||
        this == GEMINI_FLASH ||
        this == GEMINI_PRO ||
        this == GEMINI_CUSTOM
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

data class SummarizationSettings(val provider: TranscriptionProvider, val customModelName: String)

internal fun decodeStoredTranscriptionProvider(name: String?): TranscriptionProvider = when (name) {
    null, "ELEVEN_LABS" -> TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE
    "GEMINI_25_FLASH" -> TranscriptionProvider.GEMINI_FLASH
    else -> runCatching { TranscriptionProvider.valueOf(name) }
        .getOrDefault(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE)
}

internal fun decodeHistoricalTranscriptionProvider(name: String?): TranscriptionProvider = when (name) {
    null -> TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE
    "ELEVEN_LABS" -> TranscriptionProvider.ELEVEN_LABS
    "GEMINI_25_FLASH" -> TranscriptionProvider.GEMINI_FLASH
    else -> runCatching { TranscriptionProvider.valueOf(name) }
        .getOrDefault(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE)
}

internal fun decodeHistoricalSummarizerProvider(name: String?): TranscriptionProvider? {
    if (name.isNullOrBlank()) return null

    val provider = when (name) {
        "GEMINI_25_FLASH" -> TranscriptionProvider.GEMINI_FLASH
        else -> runCatching { TranscriptionProvider.valueOf(name) }.getOrNull()
    }
    return provider?.takeIf(TranscriptionProvider::isGemini)
}

private fun normalizeSpeakerDiarization(provider: TranscriptionProvider, enabled: Boolean): Boolean =
    provider.isGemini() && enabled

@Singleton
class ApiKeyManager @Inject constructor(@param:ApplicationContext private val context: Context) {
    companion object {
        private val MICROSOFT_SPEECH_API_KEY_ENC = stringPreferencesKey("microsoft_speech_api_key_enc")
        private val MICROSOFT_SPEECH_REGION = stringPreferencesKey("microsoft_speech_region")
        private val GEMINI_API_KEY_ENC = stringPreferencesKey("gemini_api_key_enc")
        private val GEMINI_TRANSCRIPTION_CUSTOM_MODEL_NAME = stringPreferencesKey("gemini_custom_model_name")
        private val GEMINI_SUMMARIZER_CUSTOM_MODEL_NAME = stringPreferencesKey("gemini_summarizer_custom_model_name")

        private val SELECTED_PROVIDER = stringPreferencesKey("transcription_provider")
        private val SELECTED_SUMMARIZER_PROVIDER = stringPreferencesKey("summarizer_provider")
        private val AUTOMATIC_SUMMARIZATION_ENABLED = booleanPreferencesKey("automatic_summarization_enabled")
        private val BACKGROUND_SHARE_SUMMARY_NOTIFICATIONS_ENABLED =
            booleanPreferencesKey("background_share_summary_notifications_enabled")
        private val SPEAKER_DIARIZATION_ENABLED = booleanPreferencesKey("speaker_diarization_enabled")
    }

    val automaticSummarizationFlow: Flow<Boolean> = context.dataStore.data.map { p ->
        p[AUTOMATIC_SUMMARIZATION_ENABLED] ?: false
    }

    val backgroundShareSummaryNotificationsEnabledFlow: Flow<Boolean> = context.dataStore.data.map { p ->
        p[BACKGROUND_SHARE_SUMMARY_NOTIFICATIONS_ENABLED] ?: false
    }

    suspend fun saveAutomaticSummarization(enabled: Boolean) {
        context.dataStore.edit { it[AUTOMATIC_SUMMARIZATION_ENABLED] = enabled }
    }

    suspend fun saveBackgroundShareSummaryNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BACKGROUND_SHARE_SUMMARY_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun saveSpeakerDiarization(enabled: Boolean) {
        context.dataStore.edit { it[SPEAKER_DIARIZATION_ENABLED] = enabled }
    }

    // Save (encrypt before persisting)
    suspend fun saveMicrosoftSpeechApiKey(key: String) {
        val trimmed = key.trim()
        context.dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(MICROSOFT_SPEECH_API_KEY_ENC)
            } else {
                val enc = KeystoreCrypto.encryptToBase64(trimmed.toByteArray(Charsets.UTF_8))
                prefs[MICROSOFT_SPEECH_API_KEY_ENC] = enc
            }
        }
    }

    suspend fun saveMicrosoftSpeechRegion(region: String) {
        val normalized = region.trim().lowercase()
        context.dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(MICROSOFT_SPEECH_REGION)
            } else {
                prefs[MICROSOFT_SPEECH_REGION] = normalized
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
    suspend fun forgetMicrosoftSpeechApiKey() {
        context.dataStore.edit {
            it.remove(MICROSOFT_SPEECH_API_KEY_ENC)
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

    val microsoftSpeechApiKeyStateFlow: Flow<ApiKeyState> = getApiKeyStateFlow(MICROSOFT_SPEECH_API_KEY_ENC)
    val geminiApiKeyStateFlow: Flow<ApiKeyState> = getApiKeyStateFlow(GEMINI_API_KEY_ENC)
    val microsoftSpeechRegionFlow: Flow<String> = context.dataStore.data.map { p ->
        p[MICROSOFT_SPEECH_REGION].orEmpty()
    }.distinctUntilChanged()

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

    suspend fun getMicrosoftSpeechApiKey(): CharArray? = getApiKey(MICROSOFT_SPEECH_API_KEY_ENC)
    suspend fun getGeminiApiKey(): CharArray? = getApiKey(GEMINI_API_KEY_ENC)

    val summarizerCustomGeminiModelNameFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[GEMINI_SUMMARIZER_CUSTOM_MODEL_NAME]?.takeIf { it.isNotBlank() }
    }

    val summarizerProviderFlow: Flow<TranscriptionProvider> = context.dataStore.data.map { p ->
        p[SELECTED_SUMMARIZER_PROVIDER]
            ?.let { runCatching { TranscriptionProvider.valueOf(it) }.getOrNull() }
            ?: TranscriptionProvider.GEMINI_FLASH
    }

    val summarizationSettingsFlow: Flow<SummarizationSettings> = context.dataStore.data.map { prefs ->
        SummarizationSettings(
            provider = prefs[SELECTED_SUMMARIZER_PROVIDER]
                ?.let { runCatching { TranscriptionProvider.valueOf(it) }.getOrNull() }
                ?: TranscriptionProvider.GEMINI_FLASH,
            customModelName = prefs[GEMINI_SUMMARIZER_CUSTOM_MODEL_NAME].orEmpty(),
        )
    }.distinctUntilChanged()

    val transcriptionQueueSettingsFlow: Flow<TranscriptionQueueSettings> = context.dataStore.data.map { prefs ->
        val provider = decodeStoredTranscriptionProvider(prefs[SELECTED_PROVIDER])
        TranscriptionQueueSettings(
            provider = provider,
            customModelName = prefs[GEMINI_TRANSCRIPTION_CUSTOM_MODEL_NAME].orEmpty(),
            speakerDiarizationEnabled = normalizeSpeakerDiarization(
                provider = provider,
                enabled = prefs[SPEAKER_DIARIZATION_ENABLED] ?: false,
            ),
        )
    }.distinctUntilChanged()

    suspend fun getTranscriptionQueueSettings(): TranscriptionQueueSettings = transcriptionQueueSettingsFlow.first()

    suspend fun getSummarizationSettings(): SummarizationSettings = summarizationSettingsFlow.first()
}
