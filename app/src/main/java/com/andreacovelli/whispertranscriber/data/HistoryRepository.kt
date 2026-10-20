package com.andreacovelli.whispertranscriber.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat
import com.andreacovelli.whispertranscriber.history.HistoryExportWriter
import com.andreacovelli.whispertranscriber.history.buildHistorySearchQuery
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

interface HistoryRepository {
    fun getPagedHistory(rawQuery: String): Flow<PagingData<TranscriptionHistory>>

    suspend fun hasHistory(): Boolean

    suspend fun getById(id: Int): TranscriptionHistory?

    suspend fun exportHistory(
        format: HistoryExportFormat,
        temporaryDirectory: File,
        openDestination: () -> OutputStream,
    )

    suspend fun insert(historyItem: TranscriptionHistory): Int

    suspend fun updateSummary(id: Int, summary: String, summaryProviderName: String, summaryModel: String): Int

    suspend fun updateSummaryByJobId(jobId: Int, summary: String, summaryProviderName: String, summaryModel: String)

    suspend fun updateText(id: Int, text: String)

    suspend fun clearContent(id: Int, field: HistoryContentField): List<HistoryJobCleanup>

    suspend fun deleteById(id: Int): List<HistoryJobCleanup>

    suspend fun deleteAll(): List<HistoryJobCleanup>
}

enum class HistoryContentField { TRANSCRIPT, SUMMARY }

data class HistoryJobCleanup(val jobId: Int, val stagedFilePath: String?)

private fun Long.toHistoryId(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "History id out of Int range: $this" }
    return toInt()
}

@Singleton
class RoomHistoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val historyDao: TranscriptionHistoryDao,
    private val jobDao: TranscriptionJobDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    override suspend fun getById(id: Int): TranscriptionHistory? = historyDao.getById(id)

    override suspend fun exportHistory(
        format: HistoryExportFormat,
        temporaryDirectory: File,
        openDestination: () -> OutputStream,
    ) = withContext(ioDispatcher) {
        val temporaryFile = File.createTempFile("history-export-", ".tmp", temporaryDirectory)
        try {
            temporaryFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                // A deferred read-only transaction reads one consistent WAL snapshot without taking the
                // write lock, so queue and history writes are not blocked for the whole export. It is
                // bound to this thread, so nothing in this block may suspend.
                val database = db.openHelper.writableDatabase
                database.beginTransactionReadOnly()
                try {
                    val export = HistoryExportWriter(writer, format)
                    var previous: TranscriptionHistory? = null
                    while (true) {
                        ensureActive()
                        val page = historyDao.getHistoryExportPage(100, previous?.timestamp, previous?.id)
                        if (page.isEmpty()) break
                        page.forEach(export::append)
                        previous = page.last()
                    }
                    export.finish()
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
            currentCoroutineContext().ensureActive()
            openDestination().use { output ->
                temporaryFile.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            temporaryFile.delete()
        }
    }

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

    override suspend fun clearContent(id: Int, field: HistoryContentField): List<HistoryJobCleanup> =
        db.withTransaction {
            val linkedJobs = jobDao.getJobCleanupByHistoryId(id)
            linkedJobs.forEach { job ->
                when (field) {
                    HistoryContentField.TRANSCRIPT -> jobDao.clearTranscriptionResult(
                        job.jobId,
                        System.currentTimeMillis(),
                    )
                    HistoryContentField.SUMMARY -> jobDao.clearSummaryResult(job.jobId, System.currentTimeMillis())
                }
            }
            when (field) {
                HistoryContentField.TRANSCRIPT -> historyDao.updateText(id, "")
                HistoryContentField.SUMMARY -> historyDao.updateSummary(id, "", "", "")
            }
            linkedJobs
        }

    override suspend fun deleteById(id: Int): List<HistoryJobCleanup> = db.withTransaction {
        val linkedJobs = jobDao.getJobCleanupByHistoryId(id)
        jobDao.invalidateOwnersByHistoryId(id)
        jobDao.deleteByHistoryId(id)
        historyDao.deleteById(id)
        linkedJobs
    }

    override suspend fun deleteAll(): List<HistoryJobCleanup> = db.withTransaction {
        val linkedJobs = jobDao.getAllHistoryLinkedJobCleanup()
        jobDao.invalidateAllHistoryLinkedOwners()
        jobDao.deleteAllWithHistory()
        historyDao.deleteAllHistory()
        linkedJobs
    }
}
