package com.andreacovelli.whispertranscriber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.background.BackgroundShareResultKind
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentMutationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun getJobContent_readsLinkedHistoryAndFallsBackToJobColumns() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            val historyId = fixture.history.insert(
                TranscriptionHistory(text = "History transcript", language = "en", summary = "History summary"),
            )
            val linkedJobId = fixture.repository.addJob(
                TranscriptionJob(
                    uri = "content://test/linked.wav",
                    displayName = "linked.wav",
                    status = JOB_STATUS_COMPLETED,
                    transcript = "Stale transcript",
                    summary = "Stale summary",
                    historyId = historyId,
                ),
            ).toInt()
            val unlinkedJobId = fixture.repository.addJob(
                TranscriptionJob(
                    uri = "content://test/unlinked.wav",
                    displayName = "unlinked.wav",
                    status = JOB_STATUS_COMPLETED,
                    transcript = "Job transcript",
                    summary = "Job summary",
                ),
            ).toInt()
            val repository = fixture.repository

            assertEquals("History transcript", repository.getJobContent(linkedJobId, HistoryContentField.TRANSCRIPT))
            assertEquals("History summary", repository.getJobContent(linkedJobId, HistoryContentField.SUMMARY))
            assertEquals("Job transcript", repository.getJobContent(unlinkedJobId, HistoryContentField.TRANSCRIPT))
            assertEquals("Job summary", repository.getJobContent(unlinkedJobId, HistoryContentField.SUMMARY))
            assertEquals("", repository.getJobContent(-1, HistoryContentField.TRANSCRIPT))
        }
    }

    @Test
    fun clearSummary_cancelsAnAttemptWaitingToAcquireItsClaim() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            val (historyId, jobId) = linkedJob(fixture)
            val (clear, pending) = fixture.operations.withClaimLock {
                val clear = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.queue.clearHistoryContent(historyId, HistoryContentField.SUMMARY)
                }
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.summaryService.summarize(
                        jobId,
                        com.andreacovelli.whispertranscriber.settings.SummarizationSettings(
                            com.andreacovelli.whispertranscriber.settings.TranscriptionProvider.GEMINI_FLASH,
                            "",
                        ),
                    )
                }
                clear to pending
            }
            withTimeout(10_000) {
                clear.await()
                pending.join()
            }
            assertTrue(pending.isCancelled)
            assertEquals("", fixture.db.transcriptionHistoryDao().getById(historyId)?.summary)
            val job = checkNotNull(fixture.repository.getJob(jobId))
            assertTrue(job.autoSummaryAttempted)
            assertFalse(job.isSummarizing)
            assertEquals("", job.summaryAttemptId)
        }
    }

    @Test
    fun invalidSummaryResponse_preservesPreviousSummaryAndReportsSanitizedFailure() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            val (historyId, jobId) = linkedJob(fixture)
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(
                        okhttp3.ResponseBody.Companion.run {
                            """
                            {"candidates":[{"finishReason":"MAX_TOKENS",
                            "content":{"parts":[{"text":"private content"}]}}]}
                            """.trimIndent().toResponseBody()
                        },
                    ).build()
            }.build()
            val service = com.andreacovelli.whispertranscriber.services.SummarizationService(
                fixture.settings,
                fixture.repository,
                fixture.summaries,
                com.andreacovelli.whispertranscriber.security.DefaultGenAiClientFactory(http),
                fixture.coordinator,
                fixture.operations,
                kotlinx.coroutines.Dispatchers.IO,
            )
            fixture.settings.saveGeminiApiKey("test-credential")
            try {
                val result = service.summarize(
                    jobId,
                    com.andreacovelli.whispertranscriber.settings.SummarizationSettings(
                        com.andreacovelli.whispertranscriber.settings.TranscriptionProvider.GEMINI_FLASH,
                        "",
                    ),
                )
                assertEquals(UserMessage(R.string.provider_response_truncated), result.errorMessage)
                assertEquals("Existing summary", fixture.db.transcriptionHistoryDao().getById(historyId)?.summary)
                assertFalse(checkNotNull(fixture.repository.getJob(jobId)).isSummarizing)
            } finally {
                fixture.settings.forgetGeminiApiKey()
            }
        }
    }

    @Test
    fun bothScreens_clearTranscriptPreservingSummaryAndInvalidatingPendingWork() = runBlocking {
        listOf(false, true).forEach { useMain ->
            RepairTestFixture(context).use { fixture ->
                val (historyId, jobId) = linkedJob(fixture)
                fixture.summaries.claimManualSummary(jobId, "pending", "GEMINI_FLASH", "new-model")
                if (useMain) {
                    fixture.queue.clearJobTranscription(jobId)
                } else {
                    fixture.queue.clearHistoryContent(historyId, HistoryContentField.TRANSCRIPT)
                }
                assertFalse(
                    fixture.summaries.succeedSummarization(
                        jobId,
                        "pending",
                        "late content",
                        "GEMINI_FLASH",
                        "new-model",
                    ),
                )
                val history = checkNotNull(fixture.db.transcriptionHistoryDao().getById(historyId))
                assertEquals("", history.text)
                assertEquals("Existing summary", history.summary)
                assertEquals("summary-model", history.summaryModel)
                assertEquals("en", history.language)
                assertEquals("transcriber", history.model)
                fixture.publisher.publish(jobId, BackgroundShareResultKind.SUMMARY)
                assertFalse(fixture.notifier.showCachedResultPage(jobId, BackgroundShareResultKind.SUMMARY, 0))
            }
        }
    }

    @Test
    fun clearSummary_preventsLateResultAndOldNotificationPageFromRestoringContent() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            val (historyId, jobId) = linkedJob(fixture)
            fixture.publisher.publish(jobId, BackgroundShareResultKind.SUMMARY)
            fixture.summaries.claimManualSummary(jobId, "pending", "GEMINI_FLASH", "new-model")
            fixture.queue.clearHistoryContent(historyId, HistoryContentField.SUMMARY)
            assertFalse(
                fixture.summaries.succeedSummarization(jobId, "pending", "late content", "GEMINI_FLASH", "new-model"),
            )
            fixture.publisher.publish(jobId, BackgroundShareResultKind.SUMMARY, 1)
            val history = checkNotNull(fixture.db.transcriptionHistoryDao().getById(historyId))
            assertEquals("Transcript", history.text)
            assertEquals("", history.summary)
            assertFalse(fixture.notifier.showCachedResultPage(jobId, BackgroundShareResultKind.SUMMARY, 0))
        }
    }

    private suspend fun linkedJob(fixture: RepairTestFixture): Pair<Int, Int> {
        fixture.coordinator.awaitRecovery()
        val historyId = fixture.history.insert(
            TranscriptionHistory(
                text = "Transcript",
                summary = "Existing summary",
                language = "en",
                model = "transcriber",
                summaryModel = "summary-model",
                displayName = "audio.wav",
            ),
        )
        val jobId = fixture.db.transcriptionJobDao().insert(
            TranscriptionJob(
                uri = "content://test/audio.wav",
                displayName = "audio.wav",
                historyId = historyId,
                status = JOB_STATUS_COMPLETED,
                notifyResult = true,
            ),
        ).toInt()
        return historyId to jobId
    }
}
