package com.andreacovelli.whispertranscriber

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCard
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCardActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryItemCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun truncatedHistoryItem_usesExplicitExpandControl() {
        val showMoreLabel = context.getString(R.string.history_item_show_more)
        val showLessLabel = context.getString(R.string.history_item_show_less)
        val copyTranscriptLabel = context.getString(
            R.string.copy_type,
            context.getString(R.string.job_transcription_title),
        )
        val copySummaryLabel = context.getString(
            R.string.copy_type,
            context.getString(R.string.job_summary_title),
        )
        val shareTranscriptLabel = context.getString(R.string.share_transcription_title)
        val shareSummaryLabel = context.getString(R.string.share_summary_title)
        val clearTranscriptLabel = context.getString(R.string.clear_transcription)
        val clearSummaryLabel = context.getString(R.string.clear_summary)
        val item = TranscriptionHistory(
            id = 1,
            text = List(20) { "Transcript chunk $it" }.joinToString(" "),
            language = "en",
            summary = List(12) { "Summary point $it" }.joinToString(" "),
            summaryModel = "gemini-2.5-flash",
            displayName = "meeting.wav",
        )

        composeRule.setContent {
            MaterialTheme {
                var expanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.width(220.dp)) {
                    HistoryItemCard(
                        item = item,
                        expanded = expanded,
                        onToggleExpand = { expanded = !expanded },
                        actions = HistoryItemCardActions(
                            copyTranscript = {},
                            copySummary = {},
                            clearTranscript = {},
                            clearSummary = {},
                            delete = {},
                            shareTranscript = {},
                            shareSummary = {},
                            saveTranscript = {},
                            saveSummary = {},
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(showMoreLabel).assertExists()
        composeRule.onAllNodesWithContentDescription(copyTranscriptLabel).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(copySummaryLabel).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(shareTranscriptLabel).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(shareSummaryLabel).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(clearTranscriptLabel).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(clearSummaryLabel).assertCountEquals(1)
        composeRule.onNodeWithText(showMoreLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(showLessLabel).assertExists()
    }

    @Test
    fun summaryOnlyHistoryItem_rendersSummaryWithoutTranscriptActions() {
        val summaryTitle = context.getString(R.string.job_summary_title)
        val copySummaryLabel = context.getString(R.string.copy_type, summaryTitle)
        val copyTranscriptLabel = context.getString(
            R.string.copy_type,
            context.getString(R.string.job_transcription_title),
        )
        val item = TranscriptionHistory(
            id = 2,
            text = "",
            language = "en",
            summary = "Summary only content that should stay visible and selectable.",
            summaryModel = "gemini-2.5-flash",
            displayName = "summary-only.wav",
        )

        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(260.dp)) {
                    HistoryItemCard(
                        item = item,
                        expanded = false,
                        onToggleExpand = {},
                        actions = HistoryItemCardActions(
                            copyTranscript = {},
                            copySummary = {},
                            clearTranscript = {},
                            clearSummary = {},
                            delete = {},
                            shareTranscript = {},
                            shareSummary = {},
                            saveTranscript = {},
                            saveSummary = {},
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(summaryTitle).assertExists()
        composeRule.onAllNodesWithContentDescription(copySummaryLabel).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(copyTranscriptLabel).assertCountEquals(0)
        composeRule.onNodeWithText("Summary only content", substring = true).assertExists()
    }

    @Test
    fun historyItemWithRecordingDetails_showsDurationSizeAndSpeakerLabels() {
        val item = TranscriptionHistory(
            id = 3,
            text = "Speaker A: Hello",
            language = "en",
            model = "MAI-Transcribe-2",
            displayName = "details.wav",
            durationMs = 125_000L,
            sourceSizeBytes = 3_000_000L,
            speakerLabels = true,
        )

        composeRule.setContent {
            MaterialTheme {
                HistoryItemCard(item = item, expanded = false, onToggleExpand = {}, actions = noOpActions())
            }
        }
        composeRule.waitForIdle()

        val size = Formatter.formatShortFileSize(context, item.sourceSizeBytes)
        val speakerLabels = context.getString(R.string.history_item_speaker_labels)
        composeRule.onNodeWithText("2:05 · $size · $speakerLabels").assertExists()
    }

    @Test
    fun historyItemWithTranscript_offersEditOnlyWhenTheTranscriptExists() {
        var edits = 0
        val withTranscript = TranscriptionHistory(id = 4, text = "Short", language = "en", displayName = "a.wav")
        val summaryOnly = withTranscript.copy(id = 5, text = "", summary = "Summary", displayName = "b.wav")
        val editLabel = context.getString(R.string.history_edit_transcript)

        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    listOf(withTranscript, summaryOnly).forEach { item ->
                        HistoryItemCard(
                            item = item,
                            expanded = false,
                            onToggleExpand = {},
                            actions = noOpActions().copy(editTranscript = { edits++ }),
                        )
                    }
                }
            }
        }

        composeRule.onAllNodesWithText(editLabel).assertCountEquals(1)
        composeRule.onNodeWithText(editLabel).performClick()
        composeRule.runOnIdle { assertEquals(1, edits) }
    }

    @Test
    fun recreatedExpandedCard_keepsItsCollapseControl() {
        var generation by mutableStateOf(0)
        var expanded by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                // Keep the collapse control below the viewport regardless of the device's screen size.
                Box(modifier = Modifier.width(220.dp).height(320.dp).verticalScroll(rememberScrollState())) {
                    key(generation) {
                        HistoryItemCard(
                            item = TranscriptionHistory(
                                id = 9,
                                text = "Long transcript ".repeat(100),
                                language = "en",
                                displayName = "audio.wav",
                            ),
                            expanded = expanded,
                            onToggleExpand = { expanded = !expanded },
                            actions = HistoryItemCardActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle { generation += 1 }
        composeRule.onNodeWithText(context.getString(R.string.history_item_show_less))
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { org.junit.Assert.assertFalse(expanded) }
        composeRule.onNodeWithText(context.getString(R.string.history_item_show_more)).assertExists()
    }

    @Test
    fun historyLoadFailure_exposesARetryAction() {
        var retries = 0
        composeRule.setContent {
            MaterialTheme { com.andreacovelli.whispertranscriber.ui.screens.HistoryLoadError { retries += 1 } }
        }
        composeRule.onNodeWithText(context.getString(R.string.history_load_failed)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, retries) }
    }

    private fun noOpActions() = HistoryItemCardActions(
        copyTranscript = {},
        copySummary = {},
        clearTranscript = {},
        clearSummary = {},
        delete = {},
        shareTranscript = {},
        shareSummary = {},
        saveTranscript = {},
        saveSummary = {},
    )
}
