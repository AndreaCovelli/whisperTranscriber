package com.andreacovelli.whispertranscriber.ui.screens

import com.andreacovelli.whispertranscriber.TranscriptionJobState

data class BatchProgressState(val currentFileNumber: Int, val totalFiles: Int, val completedFiles: Int)

internal fun buildBatchProgressState(jobs: List<TranscriptionJobState>): BatchProgressState? {
    val targetJob = jobs
        .asSequence()
        .filter { it.batchId.isNotBlank() }
        .filter { it.isRunning || it.isQueuedLike() }
        .minWithOrNull(compareBy({ !it.isRunning }, { it.timestamp }, { it.id }))
        ?: return null

    var totalFiles = 0
    var completedFiles = 0
    jobs.forEach { job ->
        if (job.batchId != targetJob.batchId) return@forEach
        totalFiles += 1
        if (!job.isRunning && !job.isQueuedLike()) {
            completedFiles += 1
        }
    }

    if (totalFiles <= 1) return null
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

private fun TranscriptionJobState.isQueuedLike(): Boolean = status == "Queued" || status == "Retry scheduled"
