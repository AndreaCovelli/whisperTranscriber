package com.andreacovelli.whispertranscriber.queue

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cancels local I/O after persisted owners have been invalidated. */
@Singleton
class JobOperationRegistry @Inject constructor() {
    private val lock = Any()
    private val operations = mutableMapOf<Int, MutableSet<Job>>()
    private val claimMutex = Mutex()

    // Clear operations cancel waiting attempts before allowing another claim to start.
    suspend fun <T> withClaimLock(block: suspend () -> T): T = claimMutex.withLock {
        currentCoroutineContext().ensureActive()
        block()
    }

    suspend fun <T> track(jobId: Int, block: suspend () -> T): T = coroutineScope {
        val operation = coroutineContext.job
        synchronized(lock) { operations.getOrPut(jobId) { mutableSetOf() }.add(operation) }
        try {
            block()
        } finally {
            synchronized(lock) {
                operations[jobId]?.let { jobs ->
                    jobs.remove(operation)
                    if (jobs.isEmpty()) operations.remove(jobId)
                }
            }
        }
    }

    fun cancel(jobId: Int) {
        val jobs = synchronized(lock) { operations.remove(jobId)?.toList().orEmpty() }
        jobs.forEach { it.cancel() }
    }
}
