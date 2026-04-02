package com.andreacovelli.whispertranscriber

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionHistoryDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private val HISTORY_SEARCH_TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")

internal fun extractHistorySearchTokens(rawQuery: String): List<String> = HISTORY_SEARCH_TOKEN_REGEX
    .findAll(rawQuery)
    .map { it.value }
    .distinct()
    .toList()

internal fun buildHistorySearchQuery(rawQuery: String): String = extractHistorySearchTokens(rawQuery)
    .map { "$it*" }
    .joinToString(" ")

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val historyDao: TranscriptionHistoryDao

    init {
        val database = AppDatabase.getDatabase(application)
        historyDao = database.transcriptionHistoryDao()
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _shareEvent = Channel<String>()
    val shareEvent = _shareEvent.receiveAsFlow()

    fun prepareShareContent(item: TranscriptionHistory) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val content = buildString {
                append(item.text)
                if (item.summary.isNotBlank()) {
                    append(app.getString(R.string.summary_header))
                    append(item.summary)
                }
            }
            _shareEvent.send(content)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val pagedHistoryItems: Flow<PagingData<TranscriptionHistory>> = _searchQuery
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = true),
                pagingSourceFactory = {
                    val ftsQuery = buildHistorySearchQuery(query)
                    if (ftsQuery.isBlank()) {
                        historyDao.getAllHistoryPaged()
                    } else {
                        historyDao.searchHistoryPaged(ftsQuery)
                    }
                },
            ).flow
        }
        .cachedIn(viewModelScope)

    fun clearSummary(item: TranscriptionHistory) {
        viewModelScope.launch {
            historyDao.updateSummary(item.id, "")
        }
    }

    fun deleteHistoryItem(item: TranscriptionHistory) {
        viewModelScope.launch {
            historyDao.deleteById(item.id)
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            historyDao.deleteAllHistory()
        }
    }
}
