package com.andreacovelli.whispertranscriber

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class TranscriptionProvider {
    ASSEMBLY_AI,
    GEMINI_25_FLASH
}

class ApiKeyManager(private val context: Context) {
    companion object {
        // Back-compat: old key name retained as a fallback
        private val LEGACY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        private val ASSEMBLY_API_KEY = stringPreferencesKey("assembly_ai_api_key")
        private val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        private val SELECTED_PROVIDER = stringPreferencesKey("transcription_provider")
    }

    // Save
    suspend fun saveAssemblyApiKey(key: String) {
        context.dataStore.edit { it[ASSEMBLY_API_KEY] = key.trim() }
    }

    suspend fun saveGeminiApiKey(key: String) {
        context.dataStore.edit { it[GEMINI_API_KEY] = key.trim() }
    }

    suspend fun saveProvider(provider: TranscriptionProvider) {
        context.dataStore.edit { it[SELECTED_PROVIDER] = provider.name }
    }

    // Read
    val assemblyApiKeyFlow: Flow<String?> = context.dataStore.data
        .map { p -> p[ASSEMBLY_API_KEY] ?: p[LEGACY_OPENAI_API_KEY] }

    val geminiApiKeyFlow: Flow<String?> = context.dataStore.data
        .map { p -> p[GEMINI_API_KEY] }

    val providerFlow: Flow<TranscriptionProvider> = context.dataStore.data
        .map { p ->
            when (p[SELECTED_PROVIDER]) {
                TranscriptionProvider.GEMINI_25_FLASH.name -> TranscriptionProvider.GEMINI_25_FLASH
                else -> TranscriptionProvider.ASSEMBLY_AI
            }
        }
}