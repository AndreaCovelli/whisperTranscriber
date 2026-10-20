package com.andreacovelli.whispertranscriber

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.paging.AsyncPagingDataDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.history.HistoryDateSource
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat
import com.andreacovelli.whispertranscriber.history.HistoryGroupingContext
import com.andreacovelli.whispertranscriber.history.HistoryListRow
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_COMPLETED
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import com.andreacovelli.whispertranscriber.ui.history.HistoryMsg
import com.andreacovelli.whispertranscriber.ui.history.HistoryUiEvent
import com.andreacovelli.whispertranscriber.ui.history.HistoryViewModel
import java.io.File
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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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

/** Drives [HistoryViewModel] against real Room, FTS, and queue cleanup code. */
@RunWith(AndroidJUnit4::class)
class HistoryViewModelInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testFiles = mutableListOf<File>()
    private val visibleItems = MutableStateFlow<List<TranscriptionHistory>?>(null)
    private lateinit var settings: IsolatedSettings
    private lateinit var fixture: RepairTestFixture
    private lateinit var viewModel: HistoryViewModel

    // Outside runBlocking, which would otherwise wait for the never-ending collection before setUp returns.
    private val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun setUp() {
        settings = IsolatedSettings(context)
        fixture = RepairTestFixture(context, settings = settings.manager)
        val zone = ZoneId.of("UTC")
        val dates = object : HistoryDateSource {
            override val changes = MutableStateFlow(HistoryGroupingContext(LocalDate.of(2026, 4, 15), zone))
        }
        viewModel = HistoryViewModel(
            context,
            fixture.history,
            fixture.queue,
            TextTransfer(context, Dispatchers.IO),
            dates,
        )
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
        differ.addOnPagesUpdatedListener {
            visibleItems.value = differ.snapshot().items
                .filterIsInstance<HistoryListRow.Item>()
                .map { it.history }
        }
        collectionScope.launch { viewModel.pagedHistoryItems.collectLatest(differ::submitData) }
    }

    @After
    fun tearDown() = runBlocking {
        collectionScope.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.Main) { viewModel.viewModelScope.cancel() }
        fixture.close()
        settings.close()
        testFiles.forEach(File::delete)
    }

    @Test
    fun deleteItem_removesOnlyThatItemAndCancelsItsLinkedJob() = runBlocking {
        val removed = insertHistory("First transcript")
        val kept = insertHistory("Second transcript")
        val removedAudio = stagedAudio()
        val keptAudio = stagedAudio()
        val removedJob = insertLinkedJob(removed, removedAudio)
        val keptJob = insertLinkedJob(kept, keptAudio)
        awaitVisible(kept, removed)

        dispatch(HistoryMsg.DeleteItem(checkNotNull(history(removed))))

        awaitVisible(kept)
        awaitDeleted(removedAudio)
        assertNull(fixture.repository.getJob(removedJob))
        assertFalse(removedJob in fixture.scheduler.active)
        assertEquals(kept, fixture.repository.getJob(keptJob)?.historyId)
        assertTrue(keptAudio.isFile)
        assertTrue(keptJob in fixture.scheduler.active)
    }

    @Test
    fun deleteAll_removesEveryItemAndCancelsEveryLinkedJob() = runBlocking {
        val first = insertHistory("First transcript")
        val second = insertHistory("Second transcript")
        val firstAudio = stagedAudio()
        val secondAudio = stagedAudio()
        val firstJob = insertLinkedJob(first, firstAudio)
        val secondJob = insertLinkedJob(second, secondAudio)
        awaitVisible(second, first)

        dispatch(HistoryMsg.DeleteAll)

        awaitVisible()
        awaitDeleted(firstAudio, secondAudio)
        assertNull(fixture.repository.getJob(firstJob))
        assertNull(fixture.repository.getJob(secondJob))
        assertTrue(fixture.scheduler.active.isEmpty())
    }

    @Test
    fun clearTranscriptAndSummary_clearOnlyTheSelectedField() = runBlocking {
        val transcriptCleared = insertHistory("Transcript one", summary = "Summary one")
        val summaryCleared = insertHistory("Transcript two", summary = "Summary two")

        dispatch(HistoryMsg.ClearTranscript(checkNotNull(history(transcriptCleared))))
        dispatch(HistoryMsg.ClearSummary(checkNotNull(history(summaryCleared))))

        val items = withTimeout(TIMEOUT_MILLIS) {
            visibleItems.first { items ->
                items?.find { it.id == transcriptCleared }?.text == "" &&
                    items.find { it.id == summaryCleared }?.summary == ""
            }
        }.orEmpty()
        assertEquals("Summary one", items.single { it.id == transcriptCleared }.summary)
        assertEquals("Transcript two", items.single { it.id == summaryCleared }.text)
    }

    @Test
    fun searchQuery_matchesCaseInsensitivelyAndByPrefix() = runBlocking {
        val alpha = insertHistory("Alpha planning meeting")
        val beta = insertHistory("Weekly review", summary = "Budget decisions")
        val gamma = insertHistory("Gamma retrospective", displayName = "standup.wav")
        awaitVisible(gamma, beta, alpha)

        dispatch(HistoryMsg.SetSearchQuery("ALPHA"))
        awaitVisible(alpha)

        dispatch(HistoryMsg.SetSearchQuery("budg"))
        awaitVisible(beta)

        dispatch(HistoryMsg.SetSearchQuery("Stand"))
        awaitVisible(gamma)

        dispatch(HistoryMsg.SetSearchQuery("nomatch"))
        awaitVisible()
    }

    @Test
    fun blankOrPunctuationOnlyQuery_showsTheFullHistory() = runBlocking {
        val first = insertHistory("First transcript")
        val second = insertHistory("Second transcript")
        awaitVisible(second, first)

        dispatch(HistoryMsg.SetSearchQuery("first"))
        awaitVisible(first)
        dispatch(HistoryMsg.SetSearchQuery("   "))
        awaitVisible(second, first)

        dispatch(HistoryMsg.SetSearchQuery("first"))
        awaitVisible(first)
        dispatch(HistoryMsg.SetSearchQuery("*\"()-:"))
        awaitVisible(second, first)
    }

    @Test
    fun prepareExport_withEmptyHistory_reportsNothingToExport() = runBlocking {
        dispatch(HistoryMsg.PrepareExport(HistoryExportFormat.TXT))

        assertEquals(HistoryUiEvent.Toast(R.string.history_export_empty), awaitEvent())
    }

    @Test
    fun prepareExport_withHistory_launchesTheChosenFormat() = runBlocking {
        insertHistory("Transcript")

        dispatch(HistoryMsg.PrepareExport(HistoryExportFormat.CSV))

        assertEquals(HistoryUiEvent.LaunchExport(HistoryExportFormat.CSV), awaitEvent())
    }

    @Test
    fun exportHistory_writesTheDestinationAndReportsSuccess() = runBlocking {
        insertHistory("Exported transcript")
        val destination = File.createTempFile("history-export-test-", ".txt", context.cacheDir).also(testFiles::add)

        dispatch(HistoryMsg.ExportHistory(destination.toUri(), HistoryExportFormat.TXT))

        assertEquals(HistoryUiEvent.Toast(R.string.history_export_success), awaitEvent())
        assertTrue(destination.readText().contains("Exported transcript"))
    }

    @Test
    fun exportHistory_toUnavailableDestination_reportsFailure() = runBlocking {
        insertHistory("Transcript")
        val destination = "content://${context.packageName}.missing-provider/export.txt".toUri()

        dispatch(HistoryMsg.ExportHistory(destination, HistoryExportFormat.TXT))

        assertEquals(HistoryUiEvent.Toast(R.string.history_export_failed), awaitEvent())
    }

    @Test
    fun saveContent_writesTheChosenFieldAndReportsUnavailableDestinations() = runBlocking {
        val id = insertHistory("Transcript", summary = "Summary to save")
        val destination = File.createTempFile("history-save-test-", ".txt", context.cacheDir).also(testFiles::add)

        dispatch(HistoryMsg.SaveContent(id, HistoryContentField.SUMMARY, destination.toUri()))

        assertEquals(HistoryUiEvent.Toast(R.string.text_save_success), awaitEvent())
        assertEquals("Summary to save", destination.readText())

        val unavailable = "content://${context.packageName}.missing-provider/save.txt".toUri()
        dispatch(HistoryMsg.SaveContent(id, HistoryContentField.TRANSCRIPT, unavailable))

        assertEquals(HistoryUiEvent.Toast(R.string.text_save_failed), awaitEvent())
    }

    private suspend fun dispatch(msg: HistoryMsg) = withContext(Dispatchers.Main) { viewModel.dispatch(msg) }

    private suspend fun awaitEvent(): HistoryUiEvent = withTimeout(TIMEOUT_MILLIS) { viewModel.uiEvents.first() }

    /** Waits until the list shows exactly [ids], newest first. */
    private suspend fun awaitVisible(vararg ids: Int) {
        withTimeout(TIMEOUT_MILLIS) { visibleItems.first { items -> items?.map { it.id } == ids.toList() } }
    }

    private suspend fun history(id: Int): TranscriptionHistory? = fixture.db.transcriptionHistoryDao().getById(id)

    private var nextTimestamp = 1_000L

    private suspend fun insertHistory(text: String, summary: String = "", displayName: String = "audio.wav"): Int =
        fixture.history.insert(
            TranscriptionHistory(
                text = text,
                language = "en",
                summary = summary,
                displayName = displayName,
                timestamp = nextTimestamp++,
            ),
        )

    private suspend fun insertLinkedJob(historyId: Int, stagedFile: File): Int {
        val id = fixture.db.transcriptionJobDao().insert(
            TranscriptionJob(
                uri = "content://test/audio.wav",
                displayName = "audio.wav",
                stagedFilePath = stagedFile.absolutePath,
                status = JOB_STATUS_COMPLETED,
                historyId = historyId,
            ),
        ).toInt()
        fixture.scheduler.active += id
        return id
    }

    private fun stagedAudio(): File {
        val directory = File(context.noBackupFilesDir, "queued_audio").apply { mkdirs() }
        return File.createTempFile("history-vm-staged-", ".wav", directory).apply {
            writeBytes(ByteArray(1_024) { 7 })
            testFiles += this
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
