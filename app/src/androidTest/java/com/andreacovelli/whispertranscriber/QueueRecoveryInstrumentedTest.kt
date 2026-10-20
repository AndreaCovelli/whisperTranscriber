package com.andreacovelli.whispertranscriber

import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueRecoveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun survivingSchedulerEntry_doesNotPreserveDeadExecutionClaim() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            val id = fixture.db.transcriptionJobDao().insert(
                job().copy(isRunning = true, executionOwnerId = "dead-process:worker"),
            ).toInt()
            fixture.scheduler.active += id
            fixture.queue.recoverAndMaintainQueue()
            assertFalse(checkNotNull(fixture.repository.getJob(id)).isRunning)
            assertTrue(fixture.scheduler.scheduled.isEmpty())
            val claimed = fixture.repository.claimJobForExecution(
                id,
                fixture.coordinator.ownerId(),
                UserMessage(R.string.transcription_status_starting),
            )
            assertTrue(claimed != null)
        }
    }

    @Test
    fun foregroundDeniedOnBothBackends_failsOnceAndAdvancesTheQueue() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            val first = fixture.db.transcriptionJobDao().insert(job()).toInt()
            val next = fixture.db.transcriptionJobDao().insert(job()).toInt()
            fixture.scheduler.supportsUserInitiatedJobs = true
            assertFalse(fixture.queue.onForegroundPromotionDenied(first))
            assertEquals(JOB_STATUS_ERROR, fixture.repository.getJob(first)?.status)
            assertEquals(0, fixture.repository.getJob(first)?.retryCount)
            assertEquals(
                context.getString(R.string.transcription_foreground_denied_reopen),
                fixture.repository.getJob(first)?.error,
            )
            assertEquals(listOf(next), fixture.scheduler.scheduled)
            assertFalse(fixture.queue.onForegroundPromotionDenied(first))
            assertEquals(listOf(next), fixture.scheduler.scheduled)
        }
    }

    @Test
    fun workerAndJobService_areSerializedAndCancelledWaitersNeverExecute() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var secondExecuted = false
            val first = launch {
                fixture.coordinator.runSerialized(1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.runSerialized(2) { secondExecuted = true }
            }
            assertFalse(secondExecuted)
            second.cancelAndJoin()
            release.complete(Unit)
            first.join()
            assertFalse(secondExecuted)
            fixture.coordinator.runSerialized(3) { secondExecuted = true }
            assertTrue(secondExecuted)
        }
    }

    @Test
    fun cancellationWhenClaimReturns_clearsTheCommittedOwner() = runBlocking {
        val cancelOnDispatch = AtomicBoolean()
        lateinit var execution: Job
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (cancelOnDispatch.compareAndSet(true, false)) execution.cancel()
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val callback = RoomDatabase.QueryCallback { sql, _ ->
            if (sql.contains("SET isRunning = 1") && sql.contains("executionOwnerId")) cancelOnDispatch.set(true)
        }
        RepairTestFixture(context, callback).use { fixture ->
            val id = fixture.db.transcriptionJobDao().insert(job()).toInt()
            fixture.coordinator.awaitRecovery()
            execution = async(dispatcher, start = CoroutineStart.LAZY) {
                fixture.runner.runJob(id, "worker", "Speaker") { _, _, _ -> }
            }
            execution.start()
            withTimeout(10_000) { execution.join() }
            assertTrue(execution.isCancelled)
            assertEquals(JOB_STATUS_QUEUED, fixture.repository.getJob(id)?.status)
            assertEquals("", fixture.repository.getJob(id)?.executionOwnerId)
            assertEquals(0, fixture.repository.countBlockingJobs())
        }
    }

    @Test
    fun processingNotification_includesOpenAndCancelActions() {
        RepairTestFixture(context).use { fixture ->
            val notification = fixture.notifier.buildProcessingNotification(
                7,
                "audio.wav",
                UserMessage(R.string.transcription_status_starting),
            )
            assertTrue(notification.contentIntent != null)
            assertTrue(notification.actions.any { it.title == context.getString(R.string.stop_transcription) })
        }
    }

    private fun job() =
        TranscriptionJob(uri = "content://test/audio.wav", displayName = "audio.wav", status = JOB_STATUS_QUEUED)
}
