package com.andreacovelli.whispertranscriber.security

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Last user intent wins, including when a storage write has already started. */
internal class CredentialOperations {
    data class Token(val isGemini: Boolean, val generation: Long)

    private class Slot {
        var generation = 0L
        val jobs = mutableSetOf<Job>()
        val persistence = Mutex()
    }

    private val lock = Any()
    private val slots = mapOf(false to Slot(), true to Slot())

    fun current(isGemini: Boolean): Token = synchronized(lock) {
        Token(isGemini, slots.getValue(isGemini).generation)
    }

    fun supersede(isGemini: Boolean): Token {
        val (token, jobs) = synchronized(lock) {
            val slot = slots.getValue(isGemini)
            slot.generation += 1
            Token(isGemini, slot.generation) to slot.jobs.toList().also { slot.jobs.clear() }
        }
        jobs.forEach { it.cancel() }
        return token
    }

    fun isCurrent(token: Token): Boolean = current(token.isGemini) == token

    suspend fun <T> run(token: Token, block: suspend () -> T): T? = coroutineScope {
        val job = coroutineContext.job
        val accepted = synchronized(lock) {
            if (isCurrent(token)) slots.getValue(token.isGemini).jobs.add(job) else false
        }
        if (!accepted) return@coroutineScope null
        try {
            block()
        } finally {
            synchronized(lock) { slots.getValue(token.isGemini).jobs.remove(job) }
        }
    }

    suspend fun persist(token: Token, block: suspend () -> Unit): Boolean =
        slots.getValue(token.isGemini).persistence.withLock {
            withContext(NonCancellable) {
                if (!isCurrent(token)) return@withContext false
                block()
                isCurrent(token)
            }
        }

    // Once requested, deletion must survive later edits while waiting for an older write.
    suspend fun delete(token: Token, block: suspend () -> Unit): Boolean = withContext(NonCancellable) {
        slots.getValue(token.isGemini).persistence.withLock {
            block()
            isCurrent(token)
        }
    }
}
