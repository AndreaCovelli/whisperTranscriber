package com.andreacovelli.whispertranscriber.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.history.buildHistorySearchQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun getPagedHistory(rawQuery: String): Flow<PagingData<TranscriptionHistory>>

    suspend fun hasHistory(): Boolean

    suspend fun getExportPage(limit: Int, offset: Int): List<TranscriptionHistory>

    suspend fun insert(historyItem: TranscriptionHistory): Int

    suspend fun updateSummary(id: Int, summary: String, summaryProviderName: String, summaryModel: String): Int

    suspend fun updateSummaryByJobId(jobId: Int, summary: String, summaryProviderName: String, summaryModel: String)

    suspend fun updateText(id: Int, text: String)

    suspend fun deleteById(id: Int): List<Int>

    suspend fun deleteAll(): List<Int>
}

private fun Long.toHistoryId(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "History id out of Int range: $this" }
    return toInt()
}

@Singleton
class RoomHistoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val historyDao: TranscriptionHistoryDao,
    private val jobDao: TranscriptionJobDao,
) : HistoryRepository {
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

    override suspend fun insert(historyItem: TranscriptionHistory): Int = historyDao.insert(historyItem).toHistoryId()

    override suspend fun updateSummary(
        id: Int,
        summary: String,
        summaryProviderName: String,
        summaryModel: String,
    ): Int = historyDao.updateSummary(id, summary, summaryProviderName, summaryModel)

    override suspend fun updateSummaryByJobId(
        jobId: Int,
        summary: String,
        summaryProviderName: String,
        summaryModel: String,
    ) {
        historyDao.updateSummaryByJobId(jobId, summary, summaryProviderName, summaryModel)
    }

    override suspend fun updateText(id: Int, text: String) {
        historyDao.updateText(id, text)
    }

    override suspend fun deleteById(id: Int): List<Int> = db.withTransaction {
        val linkedJobIds = jobDao.getJobIdsByHistoryId(id)
        jobDao.invalidateOwnersByHistoryId(id)
        jobDao.deleteTerminalByHistoryId(id)
        historyDao.deleteById(id)
        linkedJobIds
    }

    override suspend fun deleteAll(): List<Int> = db.withTransaction {
        val linkedJobIds = jobDao.getAllHistoryLinkedJobIds()
        jobDao.invalidateAllHistoryLinkedOwners()
        jobDao.deleteAllTerminalWithHistory()
        historyDao.deleteAllHistory()
        linkedJobIds
    }
}
