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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val historyDao: TranscriptionHistoryDao

    init {
        val database = AppDatabase.getDatabase(application)
        historyDao = database.transcriptionHistoryDao()
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedHistoryItems: Flow<PagingData<TranscriptionHistory>> = _searchQuery
        .flatMapLatest { query ->
            Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = true),
                pagingSourceFactory = {
                    if (query.isBlank()) {
                        historyDao.getAllHistoryPaged()
                    } else {
                        historyDao.searchHistoryPaged(query)
                    }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    fun deleteHistoryItem(item: TranscriptionHistory) {
        viewModelScope.launch {
            historyDao.delete(item)
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            historyDao.deleteAll()
        }
    }
}
