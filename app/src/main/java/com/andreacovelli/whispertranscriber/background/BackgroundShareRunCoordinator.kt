package com.andreacovelli.whispertranscriber.background

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class BackgroundShareRunCoordinator(private val serviceInstanceId: String = UUID.randomUUID().toString()) {
    private val runMutex = Mutex()
    private val attemptGeneration = AtomicLong()

    fun nextExecutionOwnerId(jobSchedulerId: Int): String =
        "job-scheduler:$jobSchedulerId:$serviceInstanceId:${attemptGeneration.incrementAndGet()}"

    suspend fun <T> runSerialized(block: suspend () -> T): T = runMutex.withLock {
        block()
    }
}
