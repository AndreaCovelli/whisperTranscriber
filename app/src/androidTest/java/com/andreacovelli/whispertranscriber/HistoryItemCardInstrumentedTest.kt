package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCard
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
        val copyTextLabel = context.getString(R.string.copy_type, context.getString(R.string.copy_text_label))
        val copySummaryLabel = context.getString(R.string.copy_type, context.getString(R.string.copy_summary_label))
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
                        onCopy = {},
                        onClearSummary = {},
                        onDelete = {},
                        onShare = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(showMoreLabel).assertExists()
        composeRule.onNodeWithText(copyTextLabel).assertExists()
        composeRule.onNodeWithText(copySummaryLabel).assertExists()
        composeRule.onNodeWithText(showMoreLabel).performClick()
        composeRule.onNodeWithText(showLessLabel).assertExists()
    }

    @Test
    fun summaryOnlyHistoryItem_rendersSummaryWithoutTranscriptActions() {
        val summaryTitle = context.getString(R.string.job_summary_title)
        val copySummaryLabel = context.getString(R.string.copy_type, context.getString(R.string.copy_summary_label))
        val copyTextLabel = context.getString(R.string.copy_type, context.getString(R.string.copy_text_label))
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
                        onCopy = {},
                        onClearSummary = {},
                        onDelete = {},
                        onShare = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(summaryTitle).assertExists()
        composeRule.onNodeWithText(copySummaryLabel).assertExists()
        composeRule.onNodeWithText(copyTextLabel).assertDoesNotExist()
        composeRule.onNodeWithText("Summary only content", substring = true).assertExists()
    }
}
