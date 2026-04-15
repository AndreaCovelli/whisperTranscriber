package com.andreacovelli.whispertranscriber

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCardActions
import com.andreacovelli.whispertranscriber.ui.screens.HistoryRowList
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryGroupedListInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zoneId: ZoneId = ZoneId.of("Europe/Rome")

    @Test
    fun historyRowList_rendersDateHeadersAlongsideCards() {
        val rows = buildHistoryListRows(
            items = listOf(
                historyItem(id = 1, year = 2026, month = 4, day = 15, hour = 9, displayName = "today.wav"),
                historyItem(id = 2, year = 2026, month = 4, day = 14, hour = 19, displayName = "yesterday.wav"),
                historyItem(id = 3, year = 2026, month = 4, day = 10, hour = 11, displayName = "older.wav"),
            ),
            clock = java.time.Clock.fixed(
                LocalDateTime.of(2026, 4, 15, 10, 0).atZone(zoneId).toInstant(),
                zoneId,
            ),
            zoneId = zoneId,
        )

        composeRule.setContent {
            MaterialTheme {
                val pagedItems = flowOf(PagingData.from(rows)).collectAsLazyPagingItems()
                Column(modifier = Modifier.width(360.dp)) {
                    HistoryRowList(
                        pagedItems = pagedItems,
                        searchTokens = emptyList(),
                        expandedIds = emptySet(),
                        onExpandedIdsChange = {},
                        actionFactory = {
                            HistoryItemCardActions(
                                copyTranscript = {},
                                copySummary = {},
                                clearTranscript = {},
                                clearSummary = {},
                                delete = {},
                                shareTranscript = {},
                                shareSummary = {},
                            )
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("today.wav").assertExists()
        composeRule.onNodeWithText("yesterday.wav").assertExists()
        composeRule.onNodeWithText("older.wav").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_group_today)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_group_yesterday)).assertExists()
        composeRule.onNodeWithText(formatHistoryMonthLabel(YearMonth.of(2026, 4), Locale.getDefault())).assertExists()
    }

    private fun historyItem(id: Int, year: Int, month: Int, day: Int, hour: Int, displayName: String) =
        TranscriptionHistory(
            id = id,
            text = "Transcript $id",
            language = "en",
            displayName = displayName,
            timestamp = LocalDateTime.of(year, month, day, hour, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        )
}
