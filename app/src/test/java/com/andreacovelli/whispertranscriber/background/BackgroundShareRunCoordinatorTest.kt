package com.andreacovelli.whispertranscriber.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundShareRunCoordinatorTest {
    @Test
    fun replacementWaitsForCancelledAttemptCleanupBeforeStarting() = runBlocking {
        val coordinator = BackgroundShareRunCoordinator()
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val firstAttemptCleanupStarted = CompletableDeferred<Unit>()
        val allowFirstAttemptCleanup = CompletableDeferred<Unit>()
        val replacementScheduled = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()

        val firstAttempt = launch {
            coordinator.runSerialized {
                firstAttemptStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        firstAttemptCleanupStarted.complete(Unit)
                        allowFirstAttemptCleanup.await()
                    }
                }
            }
        }
        firstAttemptStarted.await()

        val replacement = launch {
            replacementScheduled.complete(Unit)
            coordinator.runSerialized {
                replacementStarted.complete(Unit)
            }
        }
        replacementScheduled.await()
        firstAttempt.cancel()
        firstAttemptCleanupStarted.await()

        assertFalse(replacementStarted.isCompleted)

        allowFirstAttemptCleanup.complete(Unit)
        firstAttempt.join()
        replacement.join()

        assertTrue(replacementStarted.isCompleted)
    }

    @Test
    fun eachAttemptReceivesDistinctExecutionOwner() {
        val coordinator = BackgroundShareRunCoordinator()

        val firstOwner = coordinator.nextExecutionOwnerId(jobSchedulerId = 42)
        val replacementOwner = coordinator.nextExecutionOwnerId(jobSchedulerId = 42)

        assertNotEquals(firstOwner, replacementOwner)
    }
}
