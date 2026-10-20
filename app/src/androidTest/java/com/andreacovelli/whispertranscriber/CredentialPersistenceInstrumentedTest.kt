package com.andreacovelli.whispertranscriber

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.services.ValidationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.ui.settings.SettingsMsg
import com.andreacovelli.whispertranscriber.ui.settings.SettingsViewModel
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialPersistenceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun malformedCiphertext_isAnErrorAndCannotBeRevealed() = runBlocking {
        listOf("", "invalid", "bad:base64", "AAAA:AAAA").forEach { invalid ->
            val store = ControlledStore(preferencesOf(stringPreferencesKey("gemini_api_key_enc") to invalid))
            val manager = ApiKeyManager(context, Dispatchers.IO, store)
            assertTrue(manager.geminiApiKeyStateFlow.first() is ApiKeyState.Error)
            assertNull(manager.getGeminiApiKey())
        }
    }

    @Test
    fun forgetDuringValidation_preventsLateSavingAndConfirmsAfterDeletion() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            Response.Builder().request(
                chain.request(),
            ).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val store = ControlledStore()
        val manager = ApiKeyManager(context, Dispatchers.IO, store)
        val viewModelStore = ViewModelStore()
        val viewModel = settingsViewModel(viewModelStore, manager, client)
        try {
            withContext(Dispatchers.Main) { viewModel.dispatch(SettingsMsg.SaveGeminiKey("test-credential")) }
            withTimeout(5_000) { started.await() }
            withContext(Dispatchers.Main) { viewModel.dispatch(SettingsMsg.ForgetGeminiKey) }
            assertEquals(R.string.api_key_forgotten, withTimeout(5_000) { viewModel.events.first() })
            assertEquals(ApiKeyState.Missing, manager.geminiApiKeyStateFlow.first())
            release.countDown()
            assertNull(manager.getGeminiApiKey())
        } finally {
            release.countDown()
            withContext(Dispatchers.Main) { viewModelStore.clear() }
        }
    }

    @Test
    fun forgetDuringReveal_cancelsTheReadAndStorageFailureHasNoSuccessConfirmation() = runBlocking {
        val store = ControlledStore()
        val manager = ApiKeyManager(context, Dispatchers.IO, store)
        manager.saveGeminiApiKey("test-credential")
        manager.saveMicrosoftSpeechApiKey("test-credential")
        val viewModelStore = ViewModelStore()
        val viewModel = settingsViewModel(viewModelStore, manager, OkHttpClient())
        try {
            // Let the view model finish its initial settings reads before reads are gated.
            withTimeout(5_000) {
                viewModel.uiState.first {
                    it.geminiApiKeyState is ApiKeyState.Present && it.microsoftSpeechApiKeyState is ApiKeyState.Present
                }
            }
            store.readGate = CompletableDeferred()
            val reveal = async { viewModel.loadGeminiKeyForDisplay() }
            withTimeout(5_000) { store.readStarted.await() }
            withContext(Dispatchers.Main) { viewModel.dispatch(SettingsMsg.ForgetGeminiKey) }
            assertEquals(R.string.api_key_forgotten, withTimeout(5_000) { viewModel.events.first() })
            withTimeout(5_000) { reveal.join() }
            assertTrue(reveal.isCancelled)
            store.readGate = null
            assertEquals(ApiKeyState.Missing, manager.geminiApiKeyStateFlow.first())
            store.failWrites = true
            withContext(Dispatchers.Main) { viewModel.dispatch(SettingsMsg.ForgetGeminiKey) }
            assertEquals(R.string.settings_persistence_failed, withTimeout(5_000) { viewModel.events.first() })
            // A later successful forget is the next event, so the failure sent no success confirmation.
            store.failWrites = false
            withContext(Dispatchers.Main) { viewModel.dispatch(SettingsMsg.ForgetGeminiKey) }
            assertEquals(R.string.api_key_forgotten, withTimeout(5_000) { viewModel.events.first() })
        } finally {
            withContext(Dispatchers.Main) { viewModelStore.clear() }
        }
    }

    private suspend fun settingsViewModel(
        viewModelStore: ViewModelStore,
        manager: ApiKeyManager,
        client: OkHttpClient,
    ): SettingsViewModel = withContext(Dispatchers.Main) {
        val factory = viewModelFactory {
            initializer {
                SettingsViewModel(manager, ValidationService(manager, client, Dispatchers.IO), Dispatchers.IO)
            }
        }
        ViewModelProvider.create(viewModelStore, factory)[SettingsViewModel::class]
    }

    private class ControlledStore(var stored: Preferences = emptyPreferences()) : DataStore<Preferences> {
        var readGate: CompletableDeferred<Unit>? = null
        val readStarted = CompletableDeferred<Unit>()
        var failWrites = false
        override val data = flow {
            if (readGate != null) readStarted.complete(Unit)
            readGate?.await()
            emit(stored)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("storage unavailable")
            return transform(stored).also { stored = it }
        }
    }
}
