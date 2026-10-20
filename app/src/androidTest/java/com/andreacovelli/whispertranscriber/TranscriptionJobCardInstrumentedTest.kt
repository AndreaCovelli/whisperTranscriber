package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_ERROR
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_RUNNING
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_GENERATED
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCard
import com.andreacovelli.whispertranscriber.ui.components.TranscriptionJobCardActions
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionJobCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = mutableListOf<String>()
    private var canSummarize by mutableStateOf(true)

    @Test
    fun runningConversion_showsProgressAndOnlyTheStopAction() {
        val progress = UserMessage(R.string.transcription_status_converting_progress, listOf(42.0f))
        setCard(
            TranscriptionJobState(
                id = 1,
                displayName = "meeting.wav",
                status = JOB_STATUS_RUNNING,
                statusMessage = progress,
                progress = 0.42f,
                isRunning = true,
            ),
        )

        composeRule.onNodeWithText(progress.resolve(context)).assertIsDisplayed()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f))).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.delete_job_description))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.summarize)).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.stop_transcription)).performClick()

        composeRule.runOnIdle { assertEquals(listOf("cancelTranscription"), calls) }
    }

    @Test
    fun failedJob_showsTheErrorAndRetriesOnlyWhenAllowed() {
        val failed = TranscriptionJobState(
            id = 2,
            displayName = "meeting.wav",
            status = JOB_STATUS_ERROR,
            error = UserMessage.verbatim("Provider request failed"),
            canRetry = true,
        )
        setCard(failed)
        val retry = context.getString(R.string.retry)

        composeRule.onNodeWithText("Provider request failed").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.stop_transcription)).assertDoesNotExist()
        composeRule.onNodeWithText(retry).assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.delete_job_description)).performClick()

        composeRule.runOnIdle { assertEquals(listOf("retryTranscription", "delete"), calls) }
    }

    @Test
    fun failedJobWithoutAudio_disablesRetry() {
        setCard(
            TranscriptionJobState(
                id = 3,
                status = JOB_STATUS_ERROR,
                error = UserMessage.verbatim("Audio missing"),
                canRetry = false,
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.retry)).assertIsNotEnabled()
    }

    @Test
    fun completedTranscript_summarizesOnlyWithAvailableSummarizer() {
        val completed = TranscriptionJobState(
            id = 4,
            displayName = "meeting.wav",
            status = JOB_STATUS_COMPLETED,
            transcript = "Transcript text",
        )
        canSummarize = false
        setCard(completed)
        val summarize = context.getString(R.string.summarize)
        composeRule.onNodeWithText(summarize).assertIsNotEnabled()

        composeRule.runOnIdle { canSummarize = true }
        composeRule.onNodeWithText(summarize).assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.clear_transcription)).performClick()

        composeRule.runOnIdle { assertEquals(listOf("summarize", "clearTranscript"), calls) }
    }

    @Test
    fun summarizingJob_showsProgressAndCancelsTheSummary() {
        setCard(
            TranscriptionJobState(
                id = 5,
                status = JOB_STATUS_COMPLETED,
                transcript = "Transcript text",
                summaryStatus = SUMMARY_STATUS_SUMMARIZING,
                isSummarizing = true,
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.summary_status_summarizing)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.summarize)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.resummarize)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()

        composeRule.runOnIdle { assertEquals(listOf("cancelSummary"), calls) }
    }

    @Test
    fun summaryError_isShownAndTheSummaryCanBeRetriedOrCleared() {
        setCard(
            TranscriptionJobState(
                id = 6,
                status = JOB_STATUS_COMPLETED,
                transcript = "Transcript text",
                summary = "Earlier summary",
                summaryStatus = SUMMARY_STATUS_GENERATED,
                summaryError = UserMessage.verbatim("Quota exceeded"),
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.job_summarization_error, "Quota exceeded"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Earlier summary").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.resummarize)).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.clear_summary)).performClick()

        composeRule.runOnIdle { assertEquals(listOf("retrySummary", "clearSummary"), calls) }
    }

    private fun setCard(job: TranscriptionJobState) {
        val actions = TranscriptionJobCardActions(
            summarize = { calls += "summarize" },
            clearSummary = { calls += "clearSummary" },
            clearTranscript = { calls += "clearTranscript" },
            copyTranscript = { calls += "copyTranscript" },
            copySummary = { calls += "copySummary" },
            shareTranscript = { calls += "shareTranscript" },
            shareSummary = { calls += "shareSummary" },
            retryTranscription = { calls += "retryTranscription" },
            retrySummary = { calls += "retrySummary" },
            cancel = { calls += "cancel" },
            cancelTranscription = { calls += "cancelTranscription" },
            cancelSummary = { calls += "cancelSummary" },
            delete = { calls += "delete" },
        )
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TranscriptionJobCard(job = job, canSummarize = canSummarize, actions = actions)
                }
            }
        }
    }
}
