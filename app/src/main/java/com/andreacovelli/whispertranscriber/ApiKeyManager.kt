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

enum class TranscriptionProvider {
    ASSEMBLY_AI,
    GEMINI_25_FLASH
}

class ApiKeyManager(private val context: Context) {
    companion object {
        // Legacy plaintext keys (retain for migration/fallback)
        private val LEGACY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        private val ASSEMBLY_API_KEY = stringPreferencesKey("assembly_ai_api_key")
        private val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")

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
                prefs.remove(ASSEMBLY_API_KEY)
                prefs.remove(LEGACY_OPENAI_API_KEY)
            } else {
                val enc = KeystoreCrypto.encryptToBase64(trimmed.toByteArray(Charsets.UTF_8))
                prefs[ASSEMBLY_API_KEY_ENC] = enc
                prefs.remove(ASSEMBLY_API_KEY) // wipe plaintext remnants
                prefs.remove(LEGACY_OPENAI_API_KEY)
            }
        }
    }

    suspend fun saveGeminiApiKey(key: String) {
        val trimmed = key.trim()
        context.dataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(GEMINI_API_KEY_ENC)
                prefs.remove(GEMINI_API_KEY)
            } else {
                val enc = KeystoreCrypto.encryptToBase64(trimmed.toByteArray(Charsets.UTF_8))
                prefs[GEMINI_API_KEY_ENC] = enc
                prefs.remove(GEMINI_API_KEY) // wipe plaintext remnants
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
            it.remove(ASSEMBLY_API_KEY)
            it.remove(LEGACY_OPENAI_API_KEY)
        }
    }

    suspend fun forgetGeminiApiKey() {
        context.dataStore.edit {
            it.remove(GEMINI_API_KEY_ENC)
            it.remove(GEMINI_API_KEY)
        }
    }

    // One-time migration to encrypted storage if plaintext exists
    suspend fun migrateToEncryptedIfNeeded() {
        context.dataStore.edit { prefs ->
            // AssemblyAI
            val hasEncAssembly = !prefs[ASSEMBLY_API_KEY_ENC].isNullOrBlank()
            val plainAssembly = prefs[ASSEMBLY_API_KEY] ?: prefs[LEGACY_OPENAI_API_KEY]
            if (!hasEncAssembly && !plainAssembly.isNullOrBlank()) {
                val enc = KeystoreCrypto.encryptToBase64(plainAssembly.toByteArray(Charsets.UTF_8))
                prefs[ASSEMBLY_API_KEY_ENC] = enc
                prefs.remove(ASSEMBLY_API_KEY)
                prefs.remove(LEGACY_OPENAI_API_KEY)
            }

            // Gemini
            val hasEncGemini = !prefs[GEMINI_API_KEY_ENC].isNullOrBlank()
            val plainGemini = prefs[GEMINI_API_KEY]
            if (!hasEncGemini && !plainGemini.isNullOrBlank()) {
                val enc = KeystoreCrypto.encryptToBase64(plainGemini.toByteArray(Charsets.UTF_8))
                prefs[GEMINI_API_KEY_ENC] = enc
                prefs.remove(GEMINI_API_KEY)
            }
        }
    }

    // Read (decrypt only when needed, fallback to legacy if present)
    val assemblyApiKeyFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[ASSEMBLY_API_KEY_ENC]?.let { enc ->
            KeystoreCrypto.decryptFromBase64(enc)?.toString(Charsets.UTF_8)
        } ?: (p[ASSEMBLY_API_KEY] ?: p[LEGACY_OPENAI_API_KEY])
    }

    val geminiApiKeyFlow: Flow<String?> = context.dataStore.data.map { p ->
        p[GEMINI_API_KEY_ENC]?.let { enc ->
            KeystoreCrypto.decryptFromBase64(enc)?.toString(Charsets.UTF_8)
        } ?: p[GEMINI_API_KEY]
    }

    val providerFlow: Flow<TranscriptionProvider> = context.dataStore.data.map { p ->
        when (p[SELECTED_PROVIDER]) {
            TranscriptionProvider.GEMINI_25_FLASH.name -> TranscriptionProvider.GEMINI_25_FLASH
            else -> TranscriptionProvider.ASSEMBLY_AI
        }
    }
}