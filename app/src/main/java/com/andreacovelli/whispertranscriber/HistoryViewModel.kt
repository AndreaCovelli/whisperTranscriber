package com.andreacovelli.whispertranscriber

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
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

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _shareEvent = Channel<String>()
    val shareEvent = _shareEvent.receiveAsFlow()

    fun prepareShareContent(item: TranscriptionHistory) {
        viewModelScope.launch {
            val content = buildString {
                append(item.text)
                if (item.summary.isNotBlank()) {
                    append(appContext.getString(R.string.summary_header))
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
        .flatMapLatest(historyRepository::getPagedHistory)
        .cachedIn(viewModelScope)

    fun clearSummary(item: TranscriptionHistory) {
        viewModelScope.launch {
            historyRepository.updateSummary(item.id, "")
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
}
