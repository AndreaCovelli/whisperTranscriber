package com.andreacovelli.whispertranscriber

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoSummaryClaimInstrumentedTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun claimAutoSummary_onlySucceedsOnceForEligibleJob() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val jobId = jobDao.insert(testJob()).toInt()

        val firstClaim = jobDao.claimAutoSummary(jobId)
        val secondClaim = jobDao.claimAutoSummary(jobId)
        val storedJob = jobDao.getJobById(jobId)

        assertEquals(1, firstClaim)
        assertEquals(0, secondClaim)
        assertTrue(storedJob?.isSummarizing == true)
        assertEquals(null, storedJob?.summaryError)
    }

    @Test
    fun claimManualSummary_allowsResummarizeButStillDeduplicates() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val jobId = jobDao.insert(testJob(summary = "Existing summary")).toInt()

        val firstClaim = jobDao.claimManualSummary(jobId)
        val secondClaim = jobDao.claimManualSummary(jobId)
        val storedJob = jobDao.getJobById(jobId)

        assertEquals(1, firstClaim)
        assertEquals(0, secondClaim)
        assertTrue(storedJob?.isSummarizing == true)
    }

    @Test
    fun resetStaleSummaries_clearsLingeringInProgressFlags() = runBlocking {
        val jobDao = db.transcriptionJobDao()
        val firstJobId = jobDao.insert(testJob(isSummarizing = true)).toInt()
        val secondJobId = jobDao.insert(testJob(displayName = "second.wav", isSummarizing = true)).toInt()

        val cleared = jobDao.resetStaleSummaries()

        assertEquals(2, cleared)
        assertFalse(jobDao.getJobById(firstJobId)?.isSummarizing ?: true)
        assertFalse(jobDao.getJobById(secondJobId)?.isSummarizing ?: true)
    }

    private fun testJob(
        displayName: String = "sample.wav",
        summary: String = "",
        isSummarizing: Boolean = false
    ): TranscriptionJob {
        return TranscriptionJob(
            uri = "content://test/$displayName",
            displayName = displayName,
            transcript = "Transcript text",
            summary = summary,
            isRunning = false,
            isSummarizing = isSummarizing
        )
    }
}
