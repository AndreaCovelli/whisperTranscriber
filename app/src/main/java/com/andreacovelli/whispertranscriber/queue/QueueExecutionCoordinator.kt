package com.andreacovelli.whispertranscriber.queue

import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A scheduler entry can survive a process; an execution claim cannot. */
@Singleton
class QueueExecutionCoordinator @Inject constructor(
    private val repository: TranscriptionRepository,
    private val operations: JobOperationRegistry = JobOperationRegistry(),
) {
    private val sessionId = UUID.randomUUID().toString()
    private val recoveryMutex = Mutex()
    private val executionMutex = Mutex()
    private var recovered = false

    fun ownerId(attempt: String = UUID.randomUUID().toString()): String = "$sessionId:$attempt"

    suspend fun awaitRecovery() {
        recoveryMutex.withLock {
            if (!recovered) {
                repository.recoverPreviousSession("$sessionId:")
                recovered = true
            }
        }
    }

    suspend fun <T> runSerialized(jobId: Int, block: suspend () -> T): T {
        awaitRecovery()
        return executionMutex.withLock { operations.track(jobId, block) }
    }
}
