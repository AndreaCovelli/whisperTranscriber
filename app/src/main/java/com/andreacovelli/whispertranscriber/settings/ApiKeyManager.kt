package com.andreacovelli.whispertranscriber.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.security.KeystoreCrypto
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_MAI_TRANSCRIBE_MODEL
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechEndpoint
import com.andreacovelli.whispertranscriber.transcription.TranscriptionHints
import com.andreacovelli.whispertranscriber.transcription.normalizeTranscriptionLanguage
import com.andreacovelli.whispertranscriber.transcription.parseVocabulary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class TranscriptionProvider(val modelName: String? = null) {
    MICROSOFT_MAI_TRANSCRIBE(MICROSOFT_MAI_TRANSCRIBE_MODEL),
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
    data class Error(val message: UserMessage) : ApiKeyState()
}

data class TranscriptionQueueSettings(
    val provider: TranscriptionProvider,
    val customModelName: String,
    val speakerDiarizationEnabled: Boolean,
    /** A supported language code, or "" for automatic detection. */
    val languageHint: String = "",
    /** Names and terms as entered by the user; see [parseVocabulary]. */
    val vocabulary: String = "",
) {
    fun hints(): TranscriptionHints = TranscriptionHints(
        languageCode = normalizeTranscriptionLanguage(languageHint),
        vocabulary = parseVocabulary(vocabulary),
    )
}

data class SummarizationSettings(val provider: TranscriptionProvider, val customModelName: String)

internal fun decodeTranscriptionProvider(name: String?): TranscriptionProvider = when (name) {
    null -> TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE
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

@Singleton
class ApiKeyManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val store: DataStore<Preferences> = context.settingsDataStore,
) {
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
        private val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")
        private val TRANSCRIPTION_VOCABULARY = stringPreferencesKey("transcription_vocabulary")
    }

    val automaticSummarizationFlow: Flow<Boolean> = store.data.map { p ->
        p[AUTOMATIC_SUMMARIZATION_ENABLED] ?: false
    }

    val backgroundShareSummaryNotificationsEnabledFlow: Flow<Boolean> = store.data.map { p ->
        p[BACKGROUND_SHARE_SUMMARY_NOTIFICATIONS_ENABLED] ?: false
    }

    suspend fun saveAutomaticSummarization(enabled: Boolean) {
        store.edit { it[AUTOMATIC_SUMMARIZATION_ENABLED] = enabled }
    }

    suspend fun saveBackgroundShareSummaryNotificationsEnabled(enabled: Boolean) {
        store.edit { it[BACKGROUND_SHARE_SUMMARY_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun saveSpeakerDiarization(enabled: Boolean) {
        store.edit { it[SPEAKER_DIARIZATION_ENABLED] = enabled }
    }

    suspend fun saveTranscriptionLanguage(code: String) {
        val normalized = normalizeTranscriptionLanguage(code)
        store.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(TRANSCRIPTION_LANGUAGE)
            } else {
                prefs[TRANSCRIPTION_LANGUAGE] =
                    normalized
            }
        }
    }

    suspend fun saveTranscriptionVocabulary(vocabulary: String) {
        val trimmed = vocabulary.trim()
        store.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(TRANSCRIPTION_VOCABULARY) else prefs[TRANSCRIPTION_VOCABULARY] = trimmed
        }
    }

    private suspend fun encryptKey(key: String): String = withContext(ioDispatcher) {
        val bytes = key.toByteArray(Charsets.UTF_8)
        try {
            KeystoreCrypto.encryptToBase64(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    // Save (encrypt before persisting)
    suspend fun saveMicrosoftSpeechApiKey(key: String) {
        val trimmed = key.trim()
        store.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(MICROSOFT_SPEECH_API_KEY_ENC)
            } else {
                val enc = encryptKey(trimmed)
                prefs[MICROSOFT_SPEECH_API_KEY_ENC] = enc
            }
        }
    }

    suspend fun saveMicrosoftSpeechRegion(region: String) {
        val trimmed = region.trim()
        val candidate = if (trimmed.contains("://")) trimmed else trimmed.lowercase(Locale.ROOT)
        val normalized = candidate.takeIf(String::isNotEmpty)
            ?.let { MicrosoftSpeechEndpoint.parse(it).value }
            .orEmpty()
        store.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(MICROSOFT_SPEECH_REGION)
            } else {
                prefs[MICROSOFT_SPEECH_REGION] = normalized
            }
        }
    }

    suspend fun saveGeminiApiKey(key: String) {
        val trimmed = key.trim()
        store.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(GEMINI_API_KEY_ENC)
            } else {
                val enc = encryptKey(trimmed)
                prefs[GEMINI_API_KEY_ENC] = enc
            }
        }
    }

    suspend fun saveProvider(provider: TranscriptionProvider) {
        store.edit { it[SELECTED_PROVIDER] = provider.name }
    }

    suspend fun saveSummarizerProvider(provider: TranscriptionProvider) {
        store.edit { it[SELECTED_SUMMARIZER_PROVIDER] = provider.name }
    }

    private suspend fun saveCustomModelName(name: String, key: Preferences.Key<String>) {
        store.edit { prefs ->
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
        store.edit {
            it.remove(MICROSOFT_SPEECH_API_KEY_ENC)
        }
    }

    suspend fun clearInvalidMicrosoftSpeechConfiguration() {
        store.edit {
            it.remove(MICROSOFT_SPEECH_REGION)
            it.remove(MICROSOFT_SPEECH_API_KEY_ENC)
        }
    }

    suspend fun forgetGeminiApiKey() {
        store.edit {
            it.remove(GEMINI_API_KEY_ENC)
        }
    }

    // Read flows for UI state (does not expose keys)
    private fun getApiKeyStateFlow(key: Preferences.Key<String>): Flow<ApiKeyState> = store.data.map { p ->
        val enc = p[key]
        if (enc == null) {
            ApiKeyState.Missing
        } else {
            try {
                KeystoreCrypto.decryptFromBase64(enc).fill(0)
                ApiKeyState.Present
            } catch (_: GeneralSecurityException) {
                ApiKeyState.Error(UserMessage(R.string.api_key_decryption_failed))
            } catch (_: IllegalArgumentException) {
                ApiKeyState.Error(UserMessage(R.string.api_key_decryption_failed))
            }
        }
    }.flowOn(ioDispatcher)

    val microsoftSpeechApiKeyStateFlow: Flow<ApiKeyState> = getApiKeyStateFlow(MICROSOFT_SPEECH_API_KEY_ENC)
    val geminiApiKeyStateFlow: Flow<ApiKeyState> = getApiKeyStateFlow(GEMINI_API_KEY_ENC)
    val microsoftSpeechRegionFlow: Flow<String> = store.data.map { p ->
        p[MICROSOFT_SPEECH_REGION].orEmpty()
    }.distinctUntilChanged()

    // Suspend functions to securely read keys on-demand
    private suspend fun getApiKey(key: Preferences.Key<String>): CharArray? {
        var result: CharArray? = null
        try {
            return withContext(ioDispatcher) {
                val prefs = store.data.firstOrNull() ?: return@withContext null
                val enc = prefs[key] ?: return@withContext null
                var bytes: ByteArray? = null
                var decodedChars: CharArray? = null
                var charBuffer: java.nio.CharBuffer? = null
                try {
                    bytes = KeystoreCrypto.decryptFromBase64(enc)
                    charBuffer = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
                    decodedChars = CharArray(charBuffer.remaining())
                    charBuffer.get(decodedChars)
                    decodedChars.copyOf().also { result = it }
                } catch (_: GeneralSecurityException) {
                    Log.w("ApiKeyManager", "Failed to decrypt API key")
                    null
                } catch (_: IllegalArgumentException) {
                    Log.w("ApiKeyManager", "Failed to decode stored API key")
                    null
                } finally {
                    bytes?.fill(0)
                    decodedChars?.fill('\u0000')
                    if (charBuffer?.hasArray() == true) {
                        charBuffer.array().fill('\u0000')
                    }
                }
            }
        } catch (error: CancellationException) {
            // withContext may discard a decrypted result when returning to a cancelled caller.
            result?.fill('\u0000')
            throw error
        }
    }

    suspend fun getMicrosoftSpeechApiKey(): CharArray? = getApiKey(MICROSOFT_SPEECH_API_KEY_ENC)
    suspend fun getGeminiApiKey(): CharArray? = getApiKey(GEMINI_API_KEY_ENC)

    val summarizationSettingsFlow: Flow<SummarizationSettings> = store.data.map { prefs ->
        SummarizationSettings(
            provider = prefs[SELECTED_SUMMARIZER_PROVIDER]
                ?.let { runCatching { TranscriptionProvider.valueOf(it) }.getOrNull() }
                ?: TranscriptionProvider.GEMINI_FLASH,
            customModelName = prefs[GEMINI_SUMMARIZER_CUSTOM_MODEL_NAME].orEmpty(),
        )
    }.distinctUntilChanged()

    val transcriptionQueueSettingsFlow: Flow<TranscriptionQueueSettings> = store.data.map { prefs ->
        val provider = decodeTranscriptionProvider(prefs[SELECTED_PROVIDER])
        TranscriptionQueueSettings(
            provider = provider,
            customModelName = prefs[GEMINI_TRANSCRIPTION_CUSTOM_MODEL_NAME].orEmpty(),
            speakerDiarizationEnabled = prefs[SPEAKER_DIARIZATION_ENABLED] ?: false,
            languageHint = normalizeTranscriptionLanguage(prefs[TRANSCRIPTION_LANGUAGE]),
            vocabulary = prefs[TRANSCRIPTION_VOCABULARY].orEmpty(),
        )
    }.distinctUntilChanged()

    suspend fun getTranscriptionQueueSettings(): TranscriptionQueueSettings = transcriptionQueueSettingsFlow.first()

    suspend fun getSummarizationSettings(): SummarizationSettings = summarizationSettingsFlow.first()
}
