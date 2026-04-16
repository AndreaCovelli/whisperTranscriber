package com.andreacovelli.whispertranscriber

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HistoryUiEvent {
    data class LaunchExport(val format: HistoryExportFormat) : HistoryUiEvent

    data class Share(val content: String, val title: String) : HistoryUiEvent

    data class Toast(val message: String) : HistoryUiEvent
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val historyRepository: HistoryRepository,
) : ViewModel() {
    private companion object {
        const val EXPORT_PAGE_SIZE = 100
    }

    private data class HistoryGroupingContext(val currentDate: LocalDate, val zoneId: ZoneId)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiEvents = Channel<HistoryUiEvent>()
    val uiEvents = _uiEvents.receiveAsFlow()
    private val historyGroupingContext: Flow<HistoryGroupingContext> = flow {
        while (true) {
            val zoneId = ZoneId.systemDefault()
            emit(HistoryGroupingContext(currentDate = LocalDate.now(zoneId), zoneId = zoneId))
            delay(millisUntilNextLocalDateChange(zoneId))
        }
    }.distinctUntilChanged()

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
                withContext(Dispatchers.IO) {
                    val outputStream = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Failed to open export destination.")
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writeHistoryExport(
                            writer = writer,
                            format = format,
                            pageSize = EXPORT_PAGE_SIZE,
                            loadPage = historyRepository::getExportPage,
                        )
                    }
                }
            }

            result.onSuccess {
                _uiEvents.send(HistoryUiEvent.Toast(appContext.getString(R.string.history_export_success)))
            }.onFailure {
                _uiEvents.send(HistoryUiEvent.Toast(appContext.getString(R.string.history_export_failed)))
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    internal val pagedHistoryItems: Flow<PagingData<HistoryListRow>> = _searchQuery
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            combine(historyRepository.getPagedHistory(query), historyGroupingContext) { pagingData, groupingContext ->
                pagingData
                    .map { history ->
                        historyListItem(
                            history = history,
                            currentDate = groupingContext.currentDate,
                            zoneId = groupingContext.zoneId,
                        )
                    }
                    .insertSeparators { before: HistoryListRow.Item?, after: HistoryListRow.Item? ->
                        val nextItem = after ?: return@insertSeparators null
                        historyDateHeader(
                            previousBucket = before?.bucket,
                            currentBucket = nextItem.bucket,
                        )
                    }
            }
        }
        .cachedIn(viewModelScope)

    fun clearSummary(item: TranscriptionHistory) {
        viewModelScope.launch {
            historyRepository.updateSummary(item.id, "", "", "")
        }
    }

    fun clearTranscript(item: TranscriptionHistory) {
        viewModelScope.launch {
            historyRepository.updateText(item.id, "")
        }
    }

    fun deleteHistoryItem(item: TranscriptionHistory) {
        viewModelScope.launch {
            historyRepository.deleteById(item.id)
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            historyRepository.deleteAll()
        }
    }

    private fun millisUntilNextLocalDateChange(zoneId: ZoneId): Long {
        val now = ZonedDateTime.now(zoneId)
        val nextLocalMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zoneId)
        return Duration.between(now, nextLocalMidnight).toMillis().coerceAtLeast(1L)
    }
}
