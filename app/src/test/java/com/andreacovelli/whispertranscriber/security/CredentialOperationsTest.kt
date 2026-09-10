package com.andreacovelli.whispertranscriber.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialOperationsTest {
    @Test
    fun forget_supersedesPendingValidationAndRevealForOnlyItsProvider() = runBlocking {
        val operations = CredentialOperations()
        val old = operations.supersede(true)
        val microsoft = operations.current(false)
        var saved = false
        val response = CompletableDeferred<Unit>()
        val validation = launch(start = CoroutineStart.UNDISPATCHED) {
            operations.run(old) {
                withContext(NonCancellable) { response.await() }
                operations.persist(old) { saved = true }
            }
        }
        val reveal = async(start = CoroutineStart.UNDISPATCHED) {
            operations.run(old) { CompletableDeferred<String>().await() }
        }
        val forget = operations.supersede(true)
        assertTrue(operations.persist(forget) { saved = false })
        response.complete(Unit)
        validation.join()
        reveal.join()
        assertFalse(saved)
        assertTrue(reveal.isCancelled)
        assertTrue(operations.isCurrent(microsoft))
    }

    @Test
    fun forget_waitsForAlreadyStartedWriteAndThenDeletesIt() = runBlocking {
        val operations = CredentialOperations()
        val validationToken = operations.supersede(false)
        val releaseWrite = CompletableDeferred<Unit>()
        var stored: String? = null
        val validation = launch(start = CoroutineStart.UNDISPATCHED) {
            operations.run(validationToken) {
                operations.persist(validationToken) {
                    releaseWrite.await()
                    stored = "key"
                }
            }
        }
        val forgetToken = operations.supersede(false)
        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            operations.run(forgetToken) { operations.delete(forgetToken) { stored = null } }
        }
        assertFalse(deletion.isCompleted)
        operations.supersede(false) // Editing another value must not cancel an already requested deletion.
        releaseWrite.complete(Unit)
        validation.join()
        deletion.join()
        assertEquals(null, stored)
    }

    @Test
    fun failedPersistence_doesNotReportSuccessAndReleasesTheLock() = runBlocking {
        val operations = CredentialOperations()
        val token = operations.current(true)
        var confirmed = false
        try {
            confirmed = operations.persist(token) { throw java.io.IOException("storage unavailable") }
        } catch (_: java.io.IOException) {
            // The UI reports this failure; no success confirmation is allowed.
        }
        assertFalse(confirmed)
        assertTrue(operations.persist(operations.supersede(true)) {})
    }
}
