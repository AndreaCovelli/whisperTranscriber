package com.andreacovelli.whispertranscriber

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.andreacovelli.whispertranscriber.security.KeystoreCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import java.nio.charset.StandardCharsets

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class TranscriptionProvider(val modelName: String? = null) {
    ELEVEN_LABS,
    GEMINI_FLASH_LITE("gemini-flash-lite-latest"),
    GEMINI_FLASH("gemini-flash-latest"),
    GEMINI_PRO("gemini-pro-latest"),
    GEMINI_CUSTOM; // model name stored separately in DataStore

    fun isGemini(): Boolean = this != ELEVEN_LABS
}

sealed class ApiKeyState {
    object Missing : ApiKeyState()
    object Present : ApiKeyState()
    data class Error(val message: String) : ApiKeyState()
}

class ApiKeyManager(private val context: Context) {
    companion object {
        private val ELEVEN_LABS_API_KEY_ENC = stringPreferencesKey("eleven_labs_api_key_enc")
        private val GEMINI_API_KEY_ENC = stringPreferencesKey("gemini_api_key_enc")
        private val GEMINI_CUSTOM_MODEL_NAME = stringPreferencesKey("gemini_custom_model_name")

        private val SELECTED_PROVIDER = stringPreferencesKey("transcription_provider")
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

    suspend fun saveCustomGeminiModelName(name: String) {
        context.dataStore.edit { prefs ->
            if (name.isBlank()) prefs.remove(GEMINI_CUSTOM_MODEL_NAME)
            else prefs[GEMINI_CUSTOM_MODEL_NAME] = name.trim()
        }
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
        if (enc == null) ApiKeyState.Missing
        else {
            try {
                KeystoreCrypto.decryptFromBase64(enc)?.fill(0)
                ApiKeyState.Present
            } catch (e: Exception) {
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
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getElevenLabsApiKey(): CharArray? = getApiKey(ELEVEN_LABS_API_KEY_ENC)
    suspend fun getGeminiApiKey(): CharArray? = getApiKey(GEMINI_API_KEY_ENC)

    val customGeminiModelNameFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[GEMINI_CUSTOM_MODEL_NAME]?.takeIf { it.isNotBlank() }
    }

    val providerFlow: Flow<TranscriptionProvider> = context.dataStore.data.map { p ->
        val providerName = p[SELECTED_PROVIDER]
        try {
            if (providerName != null) {
                TranscriptionProvider.valueOf(providerName)
            } else {
                TranscriptionProvider.ELEVEN_LABS
            }
        } catch (_: IllegalArgumentException) {
            TranscriptionProvider.ELEVEN_LABS
        }
    }
}
