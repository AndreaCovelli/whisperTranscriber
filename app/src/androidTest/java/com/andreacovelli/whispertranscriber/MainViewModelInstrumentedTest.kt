package com.andreacovelli.whispertranscriber

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.background.SummaryScheduler
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.MAX_NONTERMINAL_JOBS
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_CANCELLED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RUNNING
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import com.andreacovelli.whispertranscriber.ui.main.JobRevealRequest
import com.andreacovelli.whispertranscriber.ui.main.MainMsg
import com.andreacovelli.whispertranscriber.ui.main.MainUiState
import com.andreacovelli.whispertranscriber.ui.main.MainViewModel
import com.andreacovelli.whispertranscriber.ui.main.ProviderSummary
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drives [MainViewModel] against real Room and queue code; only WorkManager summary scheduling is faked. */
@RunWith(AndroidJUnit4::class)
class MainViewModelInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stagedDirectory = File(context.noBackupFilesDir, "queued_audio")
    private val testFiles = mutableListOf<File>()
    private val summaries = FakeSummaryScheduler()
    private lateinit var settings: IsolatedSettings
    private lateinit var fixture: RepairTestFixture
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() = runBlocking {
        settings = IsolatedSettings(context)
        fixture = RepairTestFixture(context, settings = settings.manager)
        // Recover once up front so jobs inserted by a test are not treated as left over from a dead session.
        fixture.coordinator.awaitRecovery()
        viewModel = MainViewModel(
            settings.manager,
            fixture.repository,
            fixture.queue,
            TextTransfer(context, Dispatchers.IO),
            summaries,
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { viewModel.viewModelScope.cancel() }
        fixture.db.transcriptionJobDao().getAllJobs().first().forEach { job ->
            job.stagedFilePath?.let { File(it).delete() }
        }
        fixture.close()
        settings.close()
        testFiles.forEach(File::delete)
    }

    @Test
    fun sharedAudioReceived_queuesFilesAndRevealsTheFirstCreatedJob() = runBlocking {
        val first = sourceAudio()
        val second = sourceAudio()

        dispatch(MainMsg.SharedAudioReceived(listOf(first.toUri(), second.toUri())))

        val state = awaitState { it.jobRevealRequest is JobRevealRequest.Ready }
        val reveal = state.jobRevealRequest as JobRevealRequest.Ready
        assertEquals(UserMessage.verbatim(QuantityText(R.plurals.main_queued_files, 2)), state.status)
        assertEquals(reveal.token, state.mainNavigationRequest?.token)
        assertEquals(state.jobs.single { it.uri == first.toUri() }.id, reveal.jobId)
        assertEquals(setOf(first.toUri(), second.toUri()), state.jobs.map { it.uri }.toSet())
        state.jobs.forEach { job ->
            val staged = File(checkNotNull(fixture.repository.getJob(job.id)?.stagedFilePath))
            assertEquals(stagedDirectory.absolutePath, staged.parentFile?.absolutePath)
            assertTrue(staged.isFile)
        }
    }

    @Test
    fun sharedAudioThatCannotBeRead_reportsFailureAndDropsTheReveal() = runBlocking {
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.wav")

        dispatch(MainMsg.SharedAudioReceived(listOf(missing.toUri())))

        val state = awaitState {
            it.status == UserMessage(R.string.background_share_queue_failed_body) && it.jobRevealRequest == null
        }
        assertTrue(state.jobs.isEmpty())
        assertTrue(fixture.repository.jobsState.first().isEmpty())
    }

    @Test
    fun retryJob_requeuesSilentlyWhenAcceptedAndExplainsAnIneligibleJob() = runBlocking {
        val failed = insertJob(JOB_STATUS_ERROR, stagedFile = stagedAudio())
        val completed = insertJob(JOB_STATUS_COMPLETED, stagedFile = stagedAudio())
        awaitState { state -> state.jobs.any { it.id == failed && it.canRetry } }

        dispatch(MainMsg.RetryJob(failed))
        awaitState { state -> state.jobs.any { it.id == failed && it.status == JOB_STATUS_QUEUED } }
        dispatch(MainMsg.RetryJob(completed))

        // Accepted retries send no event, so the first one belongs to the ineligible job.
        assertEquals(R.string.retry_ineligible, awaitEvent())
    }

    @Test
    fun retryJob_withMissingStagedAudio_reportsUnavailableAudio() = runBlocking {
        val missingAudio = File(stagedDirectory, "missing-${System.nanoTime()}.wav")
        val failed = insertJob(JOB_STATUS_ERROR, stagedFile = missingAudio)

        dispatch(MainMsg.RetryJob(failed))

        assertEquals(R.string.queued_audio_unavailable, awaitEvent())
        assertEquals(JOB_STATUS_ERROR, fixture.repository.getJob(failed)?.status)
    }

    @Test
    fun retryJob_withFullQueue_reportsQueueFull() = runBlocking {
        repeat(MAX_NONTERMINAL_JOBS) { insertJob(JOB_STATUS_QUEUED) }
        val failed = insertJob(JOB_STATUS_ERROR, stagedFile = stagedAudio())

        dispatch(MainMsg.RetryJob(failed))

        assertEquals(R.string.retry_queue_full, awaitEvent())
        assertEquals(JOB_STATUS_ERROR, fixture.repository.getJob(failed)?.status)
    }

    @Test
    fun cancelJob_marksTheJobCancelledAndKeepsItsAudioForRetry() = runBlocking {
        val audio = stagedAudio()
        val queued = insertJob(JOB_STATUS_QUEUED, stagedFile = audio)
        fixture.scheduler.active += queued

        dispatch(MainMsg.CancelJob(queued))

        val job = awaitState { state -> state.jobs.any { it.id == queued && it.status == JOB_STATUS_CANCELLED } }
            .jobs.single { it.id == queued }
        assertEquals(UserMessage(R.string.job_cancelled_error), job.error)
        assertTrue(job.canRetry)
        assertTrue(audio.isFile)
        assertFalse(queued in fixture.scheduler.active)
    }

    @Test
    fun deleteJob_removesTheJobAndItsStagedAudio() = runBlocking {
        val audio = stagedAudio()
        val failed = insertJob(JOB_STATUS_ERROR, stagedFile = audio)
        awaitState { state -> state.jobs.any { it.id == failed } }

        dispatch(MainMsg.DeleteJob(failed))

        awaitState { state -> state.jobs.none { it.id == failed } }
        assertNull(fixture.repository.getJob(failed))
        awaitDeleted(audio)
    }

    @Test
    fun clearCompletedJobs_removesFinishedJobsAndAudioButKeepsQueuedWork() = runBlocking {
        val completedAudio = stagedAudio()
        val failedAudio = stagedAudio()
        val queuedAudio = stagedAudio()
        val completed = insertJob(JOB_STATUS_COMPLETED, stagedFile = completedAudio)
        val failed = insertJob(JOB_STATUS_ERROR, stagedFile = failedAudio)
        val queued = insertJob(JOB_STATUS_QUEUED, stagedFile = queuedAudio)
        awaitState { it.jobs.size == 3 }

        dispatch(MainMsg.ClearCompletedJobs)

        val state = awaitState { it.jobs.size == 1 }
        assertEquals(queued, state.jobs.single().id)
        assertNull(fixture.repository.getJob(completed))
        assertNull(fixture.repository.getJob(failed))
        awaitDeleted(completedAudio, failedAudio)
        assertTrue(queuedAudio.isFile)
    }

    @Test
    fun summarizeJob_enqueuesWorkWithCurrentSettingsAndShowsItsResult() = runBlocking<Unit> {
        settings.manager.saveSummarizerProvider(TranscriptionProvider.GEMINI_CUSTOM)
        settings.manager.saveSummarizerCustomGeminiModelName("gemini-test-model")
        val jobId = insertJob(JOB_STATUS_COMPLETED, displayName = "meeting.wav", transcript = "Transcript")
        awaitState { state -> state.jobs.any { it.id == jobId } }

        dispatch(MainMsg.SummarizeJob(jobId))

        val request = withTimeout(TIMEOUT_MILLIS) { summaries.enqueued.first { it.isNotEmpty() } }.single()
        val expectedSettings = SummarizationSettings(TranscriptionProvider.GEMINI_CUSTOM, "gemini-test-model")
        assertEquals(SummaryRequest(jobId, "meeting.wav", expectedSettings), request)
        summaries.result.complete(UserMessage(R.string.summarization_status_generated))
        awaitState { it.status == UserMessage(R.string.summarization_status_generated) }
    }

    @Test
    fun cancelSummary_cancelsScheduledWorkAndShowsItsMessage() = runBlocking {
        val jobId = insertJob(JOB_STATUS_COMPLETED, transcript = "Transcript")

        dispatch(MainMsg.CancelSummary(jobId))

        awaitState { it.status == UserMessage(R.string.summarization_status_nothing_to_stop) }
        assertEquals(listOf(jobId), summaries.cancelled)
    }

    @Test
    fun cancelSummary_forBackgroundSummary_cancelsTheRunningJobInstead() = runBlocking {
        val jobId = fixture.db.transcriptionJobDao().insert(
            job(JOB_STATUS_RUNNING).copy(
                transcript = "Transcript",
                isRunning = true,
                isSummarizing = true,
                executionOwnerId = fixture.coordinator.ownerId(),
            ),
        ).toInt()

        dispatch(MainMsg.CancelSummary(jobId))

        awaitState { it.status == UserMessage(R.string.summarization_status_cancelled) }
        assertEquals(JOB_STATUS_CANCELLED, fixture.repository.getJob(jobId)?.status)
        assertTrue(summaries.cancelled.isEmpty())
    }

    @Test
    fun saveJobContent_writesTheTranscriptAndRejectsBlankContent() = runBlocking {
        val jobId = insertJob(JOB_STATUS_COMPLETED, transcript = "Saved transcript é")
        val destination = File.createTempFile("main-vm-save-", ".txt", context.cacheDir).also(testFiles::add)

        dispatch(MainMsg.SaveJobContent(jobId, HistoryContentField.TRANSCRIPT, destination.toUri()))

        assertEquals(R.string.text_save_success, awaitEvent())
        assertEquals("Saved transcript é", destination.readText(Charsets.UTF_8))

        dispatch(MainMsg.SaveJobContent(jobId, HistoryContentField.SUMMARY, destination.toUri()))

        assertEquals(R.string.text_save_failed, awaitEvent())
    }

    @Test
    fun providerSummary_followsSettingsAndGeminiKeyState() = runBlocking<Unit> {
        settings.manager.saveProvider(TranscriptionProvider.GEMINI_CUSTOM)
        settings.manager.saveTranscriptionCustomGeminiModelName("gemini-transcriber")
        settings.manager.saveSummarizerProvider(TranscriptionProvider.GEMINI_PRO)
        val expected = ProviderSummary(
            transcriptionProvider = TranscriptionProvider.GEMINI_CUSTOM,
            transcriptionCustomModelName = "gemini-transcriber",
            summarizerProvider = TranscriptionProvider.GEMINI_PRO,
            canSummarize = false,
        )
        awaitState { it.providerSummary == expected }

        settings.manager.saveGeminiApiKey("test-gemini-key")
        awaitState { it.providerSummary == expected.copy(canSummarize = true) }

        settings.manager.forgetGeminiApiKey()
        awaitState { it.providerSummary == expected }
    }

    private suspend fun dispatch(msg: MainMsg) = withContext(Dispatchers.Main) { viewModel.dispatch(msg) }

    private suspend fun awaitState(predicate: (MainUiState) -> Boolean): MainUiState =
        withTimeout(TIMEOUT_MILLIS) { viewModel.uiState.first(predicate) }

    private suspend fun awaitEvent(): Int = withTimeout(TIMEOUT_MILLIS) { viewModel.events.first() }

    private suspend fun insertJob(
        status: String,
        stagedFile: File? = null,
        displayName: String = "audio.wav",
        transcript: String = "",
    ): Int = fixture.db.transcriptionJobDao().insert(
        job(status).copy(
            displayName = displayName,
            stagedFilePath = stagedFile?.absolutePath,
            transcript = transcript,
            error = if (status == JOB_STATUS_ERROR) StoredMessages.encode(UserMessage(R.string.retry)) else null,
            finishedAt = if (status == JOB_STATUS_QUEUED) 0 else System.currentTimeMillis(),
        ),
    ).toInt()

    private fun job(status: String) = TranscriptionJob(
        uri = "content://test/audio.wav",
        displayName = "audio.wav",
        status = status,
    )

    private fun sourceAudio(): File = File.createTempFile("main-vm-source-", ".wav", context.cacheDir).apply {
        writeBytes(ByteArray(1_024) { 7 })
        testFiles += this
    }

    private fun stagedAudio(): File = File.createTempFile("main-vm-staged-", ".wav", stagedDirectory.apply { mkdirs() })
        .apply {
            writeBytes(ByteArray(1_024) { 7 })
            testFiles += this
        }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}

private data class SummaryRequest(val jobId: Int, val displayName: String, val settings: SummarizationSettings)

/** Records summary requests; a test completes [result] to finish the enqueued work. */
private class FakeSummaryScheduler : SummaryScheduler {
    val enqueued = MutableStateFlow<List<SummaryRequest>>(emptyList())
    val cancelled: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val result = CompletableDeferred<UserMessage?>()

    override suspend fun enqueue(jobId: Int, displayName: String, settings: SummarizationSettings): UUID {
        enqueued.value += SummaryRequest(jobId, displayName, settings)
        return UUID.randomUUID()
    }

    override fun statusMessages(workId: UUID): Flow<UserMessage?> = flow { emit(result.await()) }

    override suspend fun cancel(jobId: Int): UserMessage {
        cancelled += jobId
        return UserMessage(R.string.summarization_status_nothing_to_stop)
    }
}
