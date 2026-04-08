package com.andreacovelli.whispertranscriber.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.andreacovelli.whispertranscriber.buildHistorySearchQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun getPagedHistory(rawQuery: String): Flow<PagingData<TranscriptionHistory>>

    suspend fun hasHistory(): Boolean


    suspend fun getExportPage(limit: Int, offset: Int): List<TranscriptionHistory>

    suspend fun insert(historyItem: TranscriptionHistory): Int

    suspend fun updateSummary(id: Int, summary: String, summaryModel: String)

    suspend fun updateSummaryByJobId(jobId: Int, summary: String, summaryModel: String)

    suspend fun updateText(id: Int, text: String)

    suspend fun getById(id: Int): TranscriptionHistory?

    suspend fun deleteById(id: Int)

    suspend fun deleteAll()
}

@Singleton
class RoomHistoryRepository @Inject constructor(private val historyDao: TranscriptionHistoryDao) : HistoryRepository {
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

    override suspend fun hasHistory(): Boolean = historyDao.hasHistory()

    override suspend fun getExportPage(limit: Int, offset: Int): List<TranscriptionHistory> =
        historyDao.getHistoryExportPage(limit, offset)

    override suspend fun insert(historyItem: TranscriptionHistory): Int = historyDao.insert(historyItem).toInt()

    override suspend fun updateSummary(id: Int, summary: String, summaryModel: String) {
        historyDao.updateSummary(id, summary, summaryModel)
    }

    override suspend fun updateSummaryByJobId(jobId: Int, summary: String, summaryModel: String) {
        historyDao.updateSummaryByJobId(jobId, summary, summaryModel)
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
