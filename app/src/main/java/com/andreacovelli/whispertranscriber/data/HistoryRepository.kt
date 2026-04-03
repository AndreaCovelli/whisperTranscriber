package com.andreacovelli.whispertranscriber.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.andreacovelli.whispertranscriber.buildHistorySearchQuery
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun getPagedHistory(rawQuery: String): Flow<PagingData<TranscriptionHistory>>

    suspend fun insert(historyItem: TranscriptionHistory): Int

    suspend fun updateSummary(id: Int, summary: String)

    suspend fun updateText(id: Int, text: String)

    suspend fun getById(id: Int): TranscriptionHistory?

    suspend fun deleteById(id: Int)

    suspend fun deleteAll()
}

class RoomHistoryRepository(private val historyDao: TranscriptionHistoryDao) : HistoryRepository {
    override fun getPagedHistory(rawQuery: String): Flow<PagingData<TranscriptionHistory>> {
        val ftsQuery = buildHistorySearchQuery(rawQuery)
        return Pager(
            config = PagingConfig(pageSize = 20, enablePlaceholders = true),
            pagingSourceFactory = {
                if (ftsQuery.isBlank()) {
                    historyDao.getAllHistoryPaged()
                } else {
                    historyDao.searchHistoryPaged(ftsQuery)
                }
            },
        ).flow
    }

    override suspend fun insert(historyItem: TranscriptionHistory): Int = historyDao.insert(historyItem).toInt()

    override suspend fun updateSummary(id: Int, summary: String) {
        historyDao.updateSummary(id, summary)
    }

    override suspend fun updateText(id: Int, text: String) {
        historyDao.updateText(id, text)
    }

    override suspend fun getById(id: Int): TranscriptionHistory? = historyDao.getById(id)

    override suspend fun deleteById(id: Int) {
        historyDao.deleteById(id)
    }

    override suspend fun deleteAll() {
        historyDao.deleteAllHistory()
    }
}
