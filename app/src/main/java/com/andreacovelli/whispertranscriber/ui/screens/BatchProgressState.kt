package com.andreacovelli.whispertranscriber.ui.screens

import com.andreacovelli.whispertranscriber.TranscriptionJobState

data class BatchProgressState(
    val currentFileNumber: Int,
    val totalFiles: Int,
    val completedFiles: Int,
)

internal fun buildBatchProgressState(jobs: List<TranscriptionJobState>): BatchProgressState? {
    val latestActiveBatch = jobs
        .asSequence()
        .filter { it.batchId.isNotBlank() }
        .groupBy { it.batchId }
        .values
        .filter { group -> group.size > 1 && group.any { it.isRunning || it.status == "Queued" } }
        .maxByOrNull { group -> group.maxOf(TranscriptionJobState::id) }
        ?: return null

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
