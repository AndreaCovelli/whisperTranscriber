package com.andreacovelli.whispertranscriber

import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryDateBucket
import com.andreacovelli.whispertranscriber.history.HistoryListRow
import com.andreacovelli.whispertranscriber.history.historyDateHeader
import com.andreacovelli.whispertranscriber.history.historyListItem
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCardActions
import com.andreacovelli.whispertranscriber.ui.screens.HistoryRowList
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
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
        val currentDate = LocalDate.of(2026, 4, 15)
        val rows = buildRowsForTest(
            items = listOf(
                historyItem(id = 1, day = 15, hour = 9, displayName = "today.wav"),
                historyItem(id = 2, day = 14, hour = 19, displayName = "yesterday.wav"),
                historyItem(id = 3, day = 10, hour = 11, displayName = "older.wav"),
            ),
            currentDate = currentDate,
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
        composeRule.waitForIdle()

        composeRule.onNodeWithText("today.wav").assertExists()
        composeRule.onNodeWithText("yesterday.wav").assertExists()
        composeRule.onNodeWithText("older.wav").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_group_today)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_group_yesterday)).assertExists()
        composeRule.onNodeWithText(formatHistoryMonthLabel(YearMonth.of(2026, 4))).assertExists()
    }

    private fun historyItem(id: Int, day: Int, hour: Int, displayName: String) = TranscriptionHistory(
        id = id,
        text = "Transcript $id",
        language = "en",
        displayName = displayName,
        timestamp = LocalDateTime.of(2026, 4, day, hour, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli(),
    )

    private fun buildRowsForTest(
        items: List<TranscriptionHistory>,
        currentDate: LocalDate,
        zoneId: ZoneId,
    ): List<HistoryListRow> = buildList {
        var previousBucket: HistoryDateBucket? = null
        items.forEach { history ->
            val item = historyListItem(history, currentDate, zoneId)
            historyDateHeader(previousBucket, item.bucket)?.let(::add)
            add(item)
            previousBucket = item.bucket
        }
    }

    private fun formatHistoryMonthLabel(yearMonth: YearMonth): String = DateUtils.formatDateTime(
        context,
        yearMonth.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_NO_MONTH_DAY or
            DateUtils.FORMAT_SHOW_YEAR,
    )
}
