package com.andreacovelli.whispertranscriber.ui.screens

import com.andreacovelli.whispertranscriber.TranscriptionJobState

data class BatchProgressState(val currentFileNumber: Int, val totalFiles: Int, val completedFiles: Int)

internal fun buildBatchProgressState(jobs: List<TranscriptionJobState>): BatchProgressState? {
    val latestActiveJob = jobs
        .asSequence()
        .filter { it.batchId.isNotBlank() }
        .filter { it.isRunning || it.status == "Queued" }
        .maxByOrNull(TranscriptionJobState::id)
        ?: return null

    val latestActiveBatch = jobs.filter { it.batchId == latestActiveJob.batchId }
    if (latestActiveBatch.size <= 1) return null

    val totalFiles = latestActiveBatch.size
    val completedFiles = latestActiveBatch.count { !it.isRunning && it.status != "Queued" }
    val currentFileNumber = if (completedFiles >= totalFiles) {
        totalFiles
    } else {
        completedFiles + 1
    }

    return BatchProgressState(
        currentFileNumber = currentFileNumber,
        totalFiles = totalFiles,
        completedFiles = completedFiles,
    )
}
