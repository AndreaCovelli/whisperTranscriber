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
    ASSEMBLY_AI,
    GEMINI_FLASH_LITE("gemini-flash-lite-latest"),
    GEMINI_FLASH("gemini-flash-latest"),
    GEMINI_PRO("gemini-pro-latest"),
    GEMINI_CUSTOM; // model name stored separately in DataStore

    fun isGemini(): Boolean = this != ASSEMBLY_AI
}

sealed class ApiKeyState {
    object Missing : ApiKeyState()
    object Present : ApiKeyState()
    data class Error(val message: String) : ApiKeyState()
}

class ApiKeyManager(private val context: Context) {
    companion object {
        private val ASSEMBLY_API_KEY_ENC = stringPreferencesKey("assembly_ai_api_key_enc")
        private val GEMINI_API_KEY_ENC = stringPreferencesKey("gemini_api_key_enc")
        private val GEMINI_CUSTOM_MODEL_NAME = stringPreferencesKey("gemini_custom_model_name")

        private val SELECTED_PROVIDER = stringPreferencesKey("transcription_provider")
    }

    // Save (encrypt before persisting)
    suspend fun saveAssemblyApiKey(key: String) {
        val trimmed = key.trim()
        context.dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(ASSEMBLY_API_KEY_ENC)
            } else {
                val enc = KeystoreCrypto.encryptToBase64(trimmed.toByteArray(Charsets.UTF_8))
                prefs[ASSEMBLY_API_KEY_ENC] = enc
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
    suspend fun forgetAssemblyApiKey() {
        context.dataStore.edit {
            it.remove(ASSEMBLY_API_KEY_ENC)
        }
    }

    suspend fun forgetGeminiApiKey() {
        context.dataStore.edit {
            it.remove(GEMINI_API_KEY_ENC)
        }
    }

    // Read flows for UI state (does not expose keys)
    val assemblyApiKeyStateFlow: Flow<ApiKeyState> = context.dataStore.data.map { p ->
        val enc = p[ASSEMBLY_API_KEY_ENC]
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

    val geminiApiKeyStateFlow: Flow<ApiKeyState> = context.dataStore.data.map { p ->
        val enc = p[GEMINI_API_KEY_ENC]
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

    // Suspend functions to securely read keys on-demand
    suspend fun getAssemblyApiKey(): CharArray? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        val enc = prefs[ASSEMBLY_API_KEY_ENC] ?: return null
        return try {
            val bytes = KeystoreCrypto.decryptFromBase64(enc) ?: return null
            val chars = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes)).array()
            bytes.fill(0)
            chars
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getGeminiApiKey(): CharArray? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        val enc = prefs[GEMINI_API_KEY_ENC] ?: return null
        return try {
            val bytes = KeystoreCrypto.decryptFromBase64(enc) ?: return null
            val chars = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes)).array()
            bytes.fill(0)
            chars
        } catch (e: Exception) {
            null
        }
    }

    val customGeminiModelNameFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[GEMINI_CUSTOM_MODEL_NAME]?.takeIf { it.isNotBlank() }
    }

    val providerFlow: Flow<TranscriptionProvider> = context.dataStore.data.map { p ->
        val providerName = p[SELECTED_PROVIDER]
        try {
            if (providerName != null) {
                TranscriptionProvider.valueOf(providerName)
            } else {
                TranscriptionProvider.ASSEMBLY_AI
            }
        } catch (_: IllegalArgumentException) {
            if (providerName == "GEMINI_25_FLASH") {
                TranscriptionProvider.GEMINI_FLASH
            } else {
                TranscriptionProvider.ASSEMBLY_AI
            }
        }
    }
}