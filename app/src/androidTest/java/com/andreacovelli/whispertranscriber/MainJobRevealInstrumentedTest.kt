package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreacovelli.whispertranscriber.transcription.DISPLAY_JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.ui.main.JobRevealRequest
import com.andreacovelli.whispertranscriber.ui.main.TranscriptionJobState
import com.andreacovelli.whispertranscriber.ui.main.UiState
import com.andreacovelli.whispertranscriber.ui.screens.MainJobList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainJobRevealInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun readyTargetNearListEnd_scrollsIntoViewAndAcknowledgesOnce() {
        val jobs = testJobs()
        val uiState = mutableStateOf(
            UiState(
                jobs = jobs,
                jobRevealRequest = JobRevealRequest.Ready(token = 1L, jobId = jobs.last().id),
            ),
        )
        var acknowledgementCount = 0

        setMainJobList(uiState) {
            acknowledgementCount += 1
            uiState.value = uiState.value.copy(jobRevealRequest = null)
        }

        composeRule.mainClock.advanceTimeUntil { acknowledgementCount == 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(jobs.last().displayName).assertIsDisplayed()
        composeRule.runOnIdle {
            uiState.value = uiState.value.copy(status = "Recomposed")
        }
        composeRule.waitForIdle()
        assertEquals(1, acknowledgementCount)
    }

    @Test
    fun targetPublishedAfterInitialComposition_scrollsIntoView() {
        val jobs = testJobs()
        val uiState = mutableStateOf(UiState(jobs = jobs))
        var acknowledgedToken: Long? = null

        setMainJobList(uiState) { token ->
            acknowledgedToken = token
            uiState.value = uiState.value.copy(jobRevealRequest = null)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            uiState.value = uiState.value.copy(
                jobRevealRequest = JobRevealRequest.Ready(token = 7L, jobId = jobs.last().id),
            )
        }

        composeRule.mainClock.advanceTimeUntil { acknowledgedToken == 7L }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(jobs.last().displayName).assertIsDisplayed()
    }

    @Test
    fun targetPublishedWhileMainIsAbsent_waitsForNextMainVisit() {
        val jobs = testJobs()
        val uiState = mutableStateOf(UiState(jobs = jobs))
        val showMain = mutableStateOf(true)
        var acknowledgementCount = 0

        composeRule.setContent {
            MaterialTheme {
                if (showMain.value) {
                    MainJobList(
                        uiState = uiState.value,
                        listState = rememberLazyListState(),
                        onNavigateToSettings = {},
                        onClearCompleted = {},
                        onJobRevealHandled = {
                            acknowledgementCount += 1
                            uiState.value = uiState.value.copy(jobRevealRequest = null)
                        },
                    ) { job ->
                        TestJobRow(job)
                    }
                } else {
                    Text("Away from Main")
                }
            }
        }
        composeRule.runOnIdle {
            showMain.value = false
            uiState.value = uiState.value.copy(
                jobRevealRequest = JobRevealRequest.Ready(token = 9L, jobId = jobs.last().id),
            )
        }
        composeRule.onNodeWithText("Away from Main").assertIsDisplayed()
        assertEquals(0, acknowledgementCount)

        composeRule.runOnIdle {
            showMain.value = true
        }

        composeRule.mainClock.advanceTimeUntil { acknowledgementCount == 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(jobs.last().displayName).assertIsDisplayed()
        composeRule.runOnIdle {
            uiState.value = uiState.value.copy(status = "Recomposed")
        }
        composeRule.waitForIdle()
        assertEquals(1, acknowledgementCount)
    }

    private fun setMainJobList(uiState: MutableState<UiState>, onJobRevealHandled: (Long) -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                MainJobList(
                    uiState = uiState.value,
                    listState = rememberLazyListState(),
                    onNavigateToSettings = {},
                    onClearCompleted = {},
                    onJobRevealHandled = onJobRevealHandled,
                ) { job ->
                    TestJobRow(job)
                }
            }
        }
    }

    private fun testJobs(): List<TranscriptionJobState> = (1..30).map { id ->
        TranscriptionJobState(
            id = id,
            displayName = "audio-$id.wav",
            status = DISPLAY_JOB_STATUS_QUEUED,
        )
    }
}

@Composable
private fun TestJobRow(job: TranscriptionJobState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Text(job.displayName)
    }
}
