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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// Updated enum to include specific models and their API names
enum class TranscriptionProvider(val modelName: String? = null) {
    ASSEMBLY_AI,
    GEMINI_FLASH_LITE("gemini-flash-lite-latest"),
    GEMINI_FLASH("gemini-flash-latest"),
    GEMINI_PRO("gemini-pro-latest");

    // Helper to check if the provider is a Gemini variant
    fun isGemini(): Boolean {
        return this != ASSEMBLY_AI
    }
}

class ApiKeyManager(private val context: Context) {
    companion object {
        // Encrypted storage (IV:ciphertext Base64)
        private val ASSEMBLY_API_KEY_ENC = stringPreferencesKey("assembly_ai_api_key_enc")
        private val GEMINI_API_KEY_ENC = stringPreferencesKey("gemini_api_key_enc")

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

    // migrateToEncryptedIfNeeded() removed as requested.

    // Read (decrypt only when needed)
    val assemblyApiKeyFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[ASSEMBLY_API_KEY_ENC]?.let { enc ->
            KeystoreCrypto.decryptFromBase64(enc)?.toString(Charsets.UTF_8)
        }
    }

    val geminiApiKeyFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[GEMINI_API_KEY_ENC]?.let { enc ->
            KeystoreCrypto.decryptFromBase64(enc)?.toString(Charsets.UTF_8)
        }
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
            // Handle case where stored value might be obsolete (e.g., if GEMINI_25_FLASH was stored before)
            if (providerName == "GEMINI_25_FLASH") {
                TranscriptionProvider.GEMINI_FLASH
            } else {
                TranscriptionProvider.ASSEMBLY_AI
            }
        }
    }
}