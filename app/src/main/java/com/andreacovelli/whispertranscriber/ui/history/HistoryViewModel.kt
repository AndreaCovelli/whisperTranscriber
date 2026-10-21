package com.andreacovelli.whispertranscriber.ui.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.di.ApplicationScope
import com.andreacovelli.whispertranscriber.history.HistoryDateSource
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat
import com.andreacovelli.whispertranscriber.history.HistoryListRow
import com.andreacovelli.whispertranscriber.history.historyDateHeader
import com.andreacovelli.whispertranscriber.history.historyListItem
import com.andreacovelli.whispertranscriber.services.TranscriptionQueueService
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HistoryUiEvent {
    data class LaunchExport(val format: HistoryExportFormat) : HistoryUiEvent

    data class Share(val content: String, @param:StringRes val titleId: Int) : HistoryUiEvent

    data class Toast(@param:StringRes val messageId: Int) : HistoryUiEvent

    /** Offers to undo the deletion of [historyId]; the screen answers with an undo or finish message. */
    data class ItemDeleted(val historyId: Int) : HistoryUiEvent
}

/**
 * A transcript being corrected. The draft lives in the view model rather than in saved instance state, so a
 * long transcript survives rotation without being written into a Bundle.
 */
class TranscriptEditor(val historyId: Int, originalText: String, val hasSummary: Boolean) {
    val text = TextFieldState(originalText)
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val historyRepository: HistoryRepository,
    private val queueService: TranscriptionQueueService,
    private val textTransfer: TextTransfer,
    private val dateSource: HistoryDateSource,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    suspend fun shareText(text: String, title: String, launch: (Intent) -> Unit): Boolean =
        textTransfer.share(text, title, launch)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Items the user deleted that stay in the database until the undo offer ends.
    private val pendingDeletions = MutableStateFlow<Set<Int>>(emptySet())

    private val _transcriptEditor = MutableStateFlow<TranscriptEditor?>(null)
    val transcriptEditor: StateFlow<TranscriptEditor?> = _transcriptEditor.asStateFlow()

    private val _uiEvents = Channel<HistoryUiEvent>()
    val uiEvents = _uiEvents.receiveAsFlow()
    fun prepareShareTranscript(item: TranscriptionHistory) {
        viewModelScope.launch {
            _uiEvents.send(
                HistoryUiEvent.Share(
                    content = item.text,
                    titleId = R.string.share_transcription_title,
                ),
            )
        }
    }

    fun prepareShareSummary(item: TranscriptionHistory) {
        viewModelScope.launch {
            _uiEvents.send(
                HistoryUiEvent.Share(
                    content = item.summary,
                    titleId = R.string.share_summary_title,
                ),
            )
        }
    }

    fun prepareExport(format: HistoryExportFormat) {
        viewModelScope.launch {
            if (!historyRepository.hasHistory()) {
                _uiEvents.send(HistoryUiEvent.Toast(R.string.history_export_empty))
                return@launch
            }
            _uiEvents.send(HistoryUiEvent.LaunchExport(format))
        }
    }

    fun exportHistory(uri: Uri, format: HistoryExportFormat) {
        viewModelScope.launch {
            val result = runCatching {
                historyRepository.exportHistory(format, appContext.cacheDir) {
                    appContext.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Failed to open export destination.")
                }
            }

            result.onSuccess {
                _uiEvents.send(HistoryUiEvent.Toast(R.string.history_export_success))
            }.onFailure {
                if (it is CancellationException) throw it
                _uiEvents.send(HistoryUiEvent.Toast(R.string.history_export_failed))
            }
        }
    }

    fun saveContent(historyId: Int, field: HistoryContentField, destination: Uri) {
        viewModelScope.launch {
            val item = historyRepository.getById(historyId)
            val text = when (field) {
                HistoryContentField.TRANSCRIPT -> item?.text
                HistoryContentField.SUMMARY -> item?.summary
            }.orEmpty()
            val saved = text.isNotBlank() && textTransfer.save(text, destination)
            _uiEvents.send(HistoryUiEvent.Toast(if (saved) R.string.text_save_success else R.string.text_save_failed))
        }
    }

    fun dispatch(msg: HistoryMsg) {
        when (msg) {
            is HistoryMsg.SetSearchQuery -> _searchQuery.value = msg.query
            is HistoryMsg.PrepareShareTranscript -> prepareShareTranscript(msg.item)
            is HistoryMsg.PrepareShareSummary -> prepareShareSummary(msg.item)
            is HistoryMsg.PrepareExport -> prepareExport(msg.format)
            is HistoryMsg.ExportHistory -> exportHistory(msg.uri, msg.format)
            is HistoryMsg.SaveContent -> saveContent(msg.historyId, msg.field, msg.destination)
            is HistoryMsg.ClearSummary -> clearSummary(msg.item)
            is HistoryMsg.ClearTranscript -> clearTranscript(msg.item)
            is HistoryMsg.EditTranscript ->
                _transcriptEditor.value =
                    TranscriptEditor(msg.item.id, msg.item.text, hasSummary = msg.item.summary.isNotBlank())
            HistoryMsg.SaveTranscriptEdit -> saveTranscriptEdit()
            HistoryMsg.CancelTranscriptEdit -> _transcriptEditor.value = null
            is HistoryMsg.DeleteItem -> deleteHistoryItem(msg.item)
            is HistoryMsg.UndoDeleteItem -> pendingDeletions.update { it - msg.historyId }
            is HistoryMsg.FinishDeleteItem -> finishDeletion(msg.historyId)
            HistoryMsg.DeleteAll -> deleteAllHistory()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    internal val pagedHistoryItems: Flow<PagingData<HistoryListRow>> = _searchQuery
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest(historyRepository::getPagedHistory)
        .cachedIn(viewModelScope)
        .combine(combine(dateSource.changes, pendingDeletions, ::Pair)) { pagingData, (groupingContext, pendingIds) ->
            pagingData
                .filter { history -> history.id !in pendingIds }
                .map { history ->
                    historyListItem(
                        history = history,
                        currentDate = groupingContext.currentDate,
                        zoneId = groupingContext.zoneId,
                    ) as HistoryListRow
                }
                .insertSeparators { before: HistoryListRow?, after: HistoryListRow? ->
                    val previousItem = before as? HistoryListRow.Item
                    val nextItem = after as? HistoryListRow.Item ?: return@insertSeparators null
                    historyDateHeader(
                        previousBucket = previousItem?.bucket,
                        currentBucket = nextItem.bucket,
                        firstItemId = nextItem.history.id,
                    )
                }
        }
        .cachedIn(viewModelScope)

    fun clearSummary(item: TranscriptionHistory) {
        viewModelScope.launch {
            queueService.clearHistoryContent(item.id, HistoryContentField.SUMMARY)
        }
    }

    fun clearTranscript(item: TranscriptionHistory) {
        viewModelScope.launch {
            queueService.clearHistoryContent(item.id, HistoryContentField.TRANSCRIPT)
        }
    }

    // A blank draft is rejected rather than saved: clearing a transcript has its own action, which also
    // cancels pending summarization.
    private fun saveTranscriptEdit() {
        val editor = _transcriptEditor.value ?: return
        val text = editor.text.text.toString().trim()
        if (text.isEmpty()) return
        _transcriptEditor.value = null
        viewModelScope.launch {
            historyRepository.updateText(editor.historyId, text)
            _uiEvents.send(HistoryUiEvent.Toast(R.string.history_transcript_updated))
        }
    }

    fun deleteHistoryItem(item: TranscriptionHistory) {
        pendingDeletions.update { it + item.id }
        viewModelScope.launch { _uiEvents.send(HistoryUiEvent.ItemDeleted(item.id)) }
    }

    // The application scope lets a deletion finish after the user leaves the screen. The id stays hidden until
    // the row is gone, so the item does not flash back while it is being deleted.
    private fun finishDeletion(historyId: Int) {
        if (historyId !in pendingDeletions.value) return
        applicationScope.launch {
            try {
                queueService.cancelRemovedHistoryJobs(historyRepository.deleteById(historyId))
            } finally {
                pendingDeletions.update { it - historyId }
            }
        }
    }

    override fun onCleared() {
        pendingDeletions.value.forEach(::finishDeletion)
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            queueService.cancelRemovedHistoryJobs(historyRepository.deleteAll())
        }
    }
}
