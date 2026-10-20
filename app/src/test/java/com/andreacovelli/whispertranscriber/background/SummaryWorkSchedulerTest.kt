package com.andreacovelli.whispertranscriber.background

import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryWorkSchedulerTest {
    private val requestId = UUID.randomUUID()
    private val otherId = UUID.randomUUID()

    @Test
    fun insertedRequest_isReported() {
        val uniqueWork = listOf(WorkInfo(requestId, WorkInfo.State.ENQUEUED, emptySet()))

        assertEquals(requestId, retainedSummaryWorkId(requestId, uniqueWork))
    }

    @Test
    fun requestDroppedByKeep_reportsThePendingWorkThatWasKept() {
        val uniqueWork = listOf(WorkInfo(otherId, WorkInfo.State.RUNNING, emptySet()))

        assertEquals(otherId, retainedSummaryWorkId(requestId, uniqueWork))
    }

    @Test
    fun keptWorkThatFinishedBeforeTheLookup_isStillReported() {
        val uniqueWork = listOf(WorkInfo(otherId, WorkInfo.State.SUCCEEDED, emptySet()))

        assertEquals(otherId, retainedSummaryWorkId(requestId, uniqueWork))
    }

    @Test
    fun noUniqueWork_fallsBackToTheRequest() {
        assertEquals(requestId, retainedSummaryWorkId(requestId, emptyList()))
    }
}
