package com.andreacovelli.whispertranscriber

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Create the DataStore instance
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class ApiKeyManager(private val context: Context) {
    companion object {
        private val API_KEY = stringPreferencesKey("openai_api_key")
    }

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { settings ->
            settings[API_KEY] = key.trim()
        }
    }

    val apiKeyFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[API_KEY]
        }
}