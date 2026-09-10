package com.andreacovelli.whispertranscriber

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.services.ValidationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.ui.main.MainSettingsDelegate
import com.andreacovelli.whispertranscriber.ui.main.UiState
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val events = Channel<Int>(Channel.UNLIMITED)
        val delegate = delegate(manager, client, scope, events)
        try {
            withContext(Dispatchers.Main) { delegate.actions.saveGeminiKey("test-credential") }
            withTimeout(5_000) { started.await() }
            withContext(Dispatchers.Main) { delegate.actions.forgetGeminiKey() }
            assertEquals(R.string.api_key_forgotten, withTimeout(5_000) { events.receive() })
            assertEquals(ApiKeyState.Missing, manager.geminiApiKeyStateFlow.first())
            release.countDown()
            assertNull(manager.getGeminiApiKey())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun forgetDuringReveal_cancelsTheReadAndStorageFailureHasNoSuccessConfirmation() = runBlocking {
        val store = ControlledStore()
        val manager = ApiKeyManager(context, Dispatchers.IO, store)
        manager.saveGeminiApiKey("test-credential")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val events = Channel<Int>(Channel.UNLIMITED)
        val delegate = delegate(manager, OkHttpClient(), scope, events)
        try {
            store.readGate = CompletableDeferred()
            val reveal = async { delegate.actions.loadGeminiKey() }
            withTimeout(5_000) { store.readStarted.await() }
            withContext(Dispatchers.Main) { delegate.actions.forgetGeminiKey() }
            assertEquals(R.string.api_key_forgotten, withTimeout(5_000) { events.receive() })
            withTimeout(5_000) { reveal.join() }
            assertTrue(reveal.isCancelled)
            store.readGate = null
            assertEquals(ApiKeyState.Missing, manager.geminiApiKeyStateFlow.first())
            store.failWrites = true
            withContext(Dispatchers.Main) { delegate.actions.forgetGeminiKey() }
            assertEquals(R.string.settings_persistence_failed, withTimeout(5_000) { events.receive() })
            assertTrue(events.tryReceive().isFailure)
        } finally {
            scope.cancel()
        }
    }

    private fun delegate(manager: ApiKeyManager, client: OkHttpClient, scope: CoroutineScope, events: Channel<Int>) =
        MainSettingsDelegate({
            UiState()
        }, {
        }, manager, ValidationService(manager, client, Dispatchers.IO), scope, Dispatchers.IO) {
            events.trySend(it)
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
