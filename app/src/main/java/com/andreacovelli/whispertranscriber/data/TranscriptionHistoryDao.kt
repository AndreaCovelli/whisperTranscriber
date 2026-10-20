package com.andreacovelli.whispertranscriber.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranscriptionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(historyItem: TranscriptionHistory): Long

    @Query("DELETE FROM transcription_history WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM transcription_history")
    suspend fun deleteAllHistory()

    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC, id DESC")
    fun getAllHistoryPaged(): PagingSource<Int, TranscriptionHistory>

    // Blocking on purpose: export pages must run on the thread that holds the read transaction.
    @Query(
        """
        SELECT * FROM transcription_history
        WHERE :beforeTimestamp IS NULL OR timestamp < :beforeTimestamp
           OR (timestamp = :beforeTimestamp AND id < :beforeId)
        ORDER BY timestamp DESC, id DESC LIMIT :limit
        """,
    )
    fun getHistoryExportPage(limit: Int, beforeTimestamp: Long?, beforeId: Int?): List<TranscriptionHistory>

    @Query("SELECT EXISTS(SELECT 1 FROM transcription_history)")
    suspend fun hasHistory(): Boolean

    @Query(
        """
        SELECT h.*
        FROM transcription_history AS h
        INNER JOIN transcription_history_fts AS fts ON h.id = fts.rowid
        WHERE transcription_history_fts MATCH :query
        ORDER BY h.timestamp DESC, h.id DESC
        """,
    )
    fun searchHistoryPaged(query: String): PagingSource<Int, TranscriptionHistory>

    @Query(
        """
        UPDATE transcription_history
        SET summary = :summary,
            summaryProviderName = :summaryProviderName,
            summaryModel = :summaryModel
        WHERE id = :id
        """,
    )
    suspend fun updateSummary(id: Int, summary: String, summaryProviderName: String, summaryModel: String): Int

    @Query(
        """
        UPDATE transcription_history
        SET summary = :summary,
            summaryProviderName = :summaryProviderName,
            summaryModel = :summaryModel
        WHERE id = (
            SELECT historyId
            FROM active_jobs
            WHERE id = :jobId
        )
        """,
    )
    suspend fun updateSummaryByJobId(jobId: Int, summary: String, summaryProviderName: String, summaryModel: String)

    @Query("UPDATE transcription_history SET text = :text WHERE id = :id")
    suspend fun updateText(id: Int, text: String)

    @Query("SELECT * FROM transcription_history WHERE id = :id")
    suspend fun getById(id: Int): TranscriptionHistory?

    @Query(
        """
        SELECT h.*
        FROM transcription_history AS h
        INNER JOIN active_jobs AS j ON j.historyId = h.id
        WHERE j.id = :jobId
        """,
    )
    suspend fun getByJobId(jobId: Int): TranscriptionHistory?
}
