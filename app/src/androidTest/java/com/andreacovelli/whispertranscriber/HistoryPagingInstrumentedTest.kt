package com.andreacovelli.whispertranscriber

import androidx.lifecycle.viewModelScope
import androidx.paging.AsyncPagingDataDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryDateLabel
import com.andreacovelli.whispertranscriber.history.HistoryDateSource
import com.andreacovelli.whispertranscriber.history.HistoryGroupingContext
import com.andreacovelli.whispertranscriber.history.HistoryListRow
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import com.andreacovelli.whispertranscriber.ui.history.HistoryViewModel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryPagingInstrumentedTest {
    @Test
    fun midnightAndClockRollback_reuseThePagingGenerationWithoutDuplicateHeaderKeys() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RepairTestFixture(context).use { fixture ->
            val zone = ZoneId.of("UTC")
            val today = LocalDate.of(2026, 4, 15)
            // Future, today, and older sections expose repeated month buckets after a clock rollback.
            listOf(today.plusDays(1), today, today.minusDays(3)).forEachIndexed { index, date ->
                fixture.history.insert(
                    TranscriptionHistory(
                        id = index + 1,
                        text = "Transcript",
                        language = "en",
                        displayName = "audio.wav",
                        timestamp = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    ),
                )
            }
            val dates = MutableStateFlow(HistoryGroupingContext(today, zone))
            val source = object : HistoryDateSource {
                override val changes = dates
            }
            val viewModel =
                HistoryViewModel(
                    context,
                    fixture.history,
                    fixture.queue,
                    TextTransfer(context, Dispatchers.IO),
                    source,
                    CoroutineScope(SupervisorJob()),
                )
            val rows = MutableStateFlow<List<HistoryListRow>>(emptyList())
            val differ = AsyncPagingDataDiffer(
                diffCallback = object : DiffUtil.ItemCallback<HistoryListRow>() {
                    override fun areItemsTheSame(oldItem: HistoryListRow, newItem: HistoryListRow): Boolean =
                        oldItem.stableKey == newItem.stableKey
                    override fun areContentsTheSame(oldItem: HistoryListRow, newItem: HistoryListRow): Boolean =
                        oldItem == newItem
                },
                updateCallback = object : ListUpdateCallback {
                    override fun onInserted(position: Int, count: Int) = Unit
                    override fun onRemoved(position: Int, count: Int) = Unit
                    override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                    override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
                },
            )
            differ.addOnPagesUpdatedListener { rows.value = differ.snapshot().items }
            val collection = launch(Dispatchers.Main) { viewModel.pagedHistoryItems.collectLatest(differ::submitData) }
            try {
                val initial = withTimeout(5_000) { rows.first { it.filterIsInstance<HistoryListRow.Item>().size == 3 } }
                assertEquals(initial.size, initial.map { it.stableKey }.toSet().size)
                dates.value = HistoryGroupingContext(today.plusDays(1), zone)
                withTimeout(5_000) {
                    rows.first { list ->
                        list.filterIsInstance<HistoryListRow.Header>().any {
                            it.firstItemId == 2 &&
                                it.bucket.label == HistoryDateLabel.Yesterday
                        }
                    }
                }
                dates.value = HistoryGroupingContext(today, zone)
                val restored = withTimeout(5_000) { rows.first { it == initial } }
                assertTrue(restored.any { it is HistoryListRow.Header })
            } finally {
                collection.cancelAndJoin()
                withContext(Dispatchers.Main) { viewModel.viewModelScope.cancel() }
            }
        }
    }
}
