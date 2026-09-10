package com.andreacovelli.whispertranscriber.background

import androidx.room.withTransaction
import com.andreacovelli.whispertranscriber.data.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StoredResultPublisher @Inject constructor(
    private val db: AppDatabase,
    private val notifier: BackgroundShareNotifier,
) {
    // Reading and publishing share the database transaction with content mutations.
    // Clearing disables result publication before removing the displayed notification.
    internal suspend fun publish(jobId: Int, kind: BackgroundShareResultKind, pageIndex: Int = 0) {
        db.withTransaction {
            val job = db.transcriptionJobDao().getJobById(jobId)
            val history = db.transcriptionHistoryDao().getByJobId(jobId)
            val text = when (kind) {
                BackgroundShareResultKind.TRANSCRIPTION -> history?.text.orEmpty()
                BackgroundShareResultKind.SUMMARY -> history?.summary.orEmpty()
            }
            if (job?.notifyResult != true || text.isBlank()) {
                notifier.cancelResult(jobId)
            } else {
                notifier.showResultPage(jobId, job.displayName, kind, text, pageIndex)
            }
        }
    }
}
