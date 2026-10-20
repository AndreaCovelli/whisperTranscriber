package com.andreacovelli.whispertranscriber.ui.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
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
import kotlinx.coroutines.launch

sealed interface HistoryUiEvent {
    data class LaunchExport(val format: HistoryExportFormat) : HistoryUiEvent

    data class Share(val content: String, val title: String) : HistoryUiEvent

    data class Toast(val message: String) : HistoryUiEvent
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val historyRepository: HistoryRepository,
    private val queueService: TranscriptionQueueService,
    private val textTransfer: TextTransfer,
    private val dateSource: HistoryDateSource,
) : ViewModel() {
    suspend fun shareText(text: String, title: String, launch: (Intent) -> Unit): Boolean =
        textTransfer.share(text, title, launch)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiEvents = Channel<HistoryUiEvent>()
    val uiEvents = _uiEvents.receiveAsFlow()
    fun prepareShareTranscript(item: TranscriptionHistory) {
        viewModelScope.launch {
            _uiEvents.send(
                HistoryUiEvent.Share(
                    content = item.text,
                    title = appContext.getString(R.string.share_transcription_title),
                ),
            )
        }
    }

    fun prepareShareSummary(item: TranscriptionHistory) {
        viewModelScope.launch {
            _uiEvents.send(
                HistoryUiEvent.Share(
                    content = item.summary,
                    title = appContext.getString(R.string.share_summary_title),
                ),
            )
        }
    }

    fun prepareExport(format: HistoryExportFormat) {
        viewModelScope.launch {
            if (!historyRepository.hasHistory()) {
                _uiEvents.send(HistoryUiEvent.Toast(appContext.getString(R.string.history_export_empty)))
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
                _uiEvents.send(HistoryUiEvent.Toast(appContext.getString(R.string.history_export_success)))
            }.onFailure {
                if (it is CancellationException) throw it
                _uiEvents.send(HistoryUiEvent.Toast(appContext.getString(R.string.history_export_failed)))
            }
        }
    }

    fun setSearchQuery(query: String) {
        dispatch(HistoryMsg.SetSearchQuery(query))
    }

    fun dispatch(msg: HistoryMsg) {
        when (msg) {
            is HistoryMsg.SetSearchQuery -> _searchQuery.value = msg.query
            is HistoryMsg.PrepareShareTranscript -> prepareShareTranscript(msg.item)
            is HistoryMsg.PrepareShareSummary -> prepareShareSummary(msg.item)
            is HistoryMsg.PrepareExport -> prepareExport(msg.format)
            is HistoryMsg.ExportHistory -> exportHistory(msg.uri, msg.format)
            is HistoryMsg.ClearSummary -> clearSummary(msg.item)
            is HistoryMsg.ClearTranscript -> clearTranscript(msg.item)
            is HistoryMsg.DeleteItem -> deleteHistoryItem(msg.item)
            HistoryMsg.DeleteAll -> deleteAllHistory()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    internal val pagedHistoryItems: Flow<PagingData<HistoryListRow>> = _searchQuery
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest(historyRepository::getPagedHistory)
        .cachedIn(viewModelScope)
        .combine(dateSource.changes) { pagingData, groupingContext ->
            pagingData
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

    fun deleteHistoryItem(item: TranscriptionHistory) {
        viewModelScope.launch {
            queueService.cancelRemovedHistoryJobs(historyRepository.deleteById(item.id))
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            queueService.cancelRemovedHistoryJobs(historyRepository.deleteAll())
        }
    }
}
