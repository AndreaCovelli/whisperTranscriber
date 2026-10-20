package com.andreacovelli.whispertranscriber

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue

/**
 * Waits until none of [files] exist. Queue cleanup deletes staged audio after the database change
 * that tests observe, so the files can briefly outlive the visible state update.
 */
internal suspend fun awaitDeleted(vararg files: File, timeoutMillis: Long = 10_000L) {
    val deleted = withTimeoutOrNull(timeoutMillis) {
        while (files.any(File::exists)) delay(POLL_MILLIS)
        true
    } ?: false
    assertTrue("Still present: ${files.filter(File::exists).map(File::getName)}", deleted)
}

private const val POLL_MILLIS = 20L
