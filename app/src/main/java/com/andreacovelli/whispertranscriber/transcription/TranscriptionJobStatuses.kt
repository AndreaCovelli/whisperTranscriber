package com.andreacovelli.whispertranscriber.transcription

import androidx.annotation.StringRes
import com.andreacovelli.whispertranscriber.R

internal const val JOB_STATUS_QUEUED = "QUEUED"
internal const val JOB_STATUS_RUNNING = "RUNNING"
internal const val JOB_STATUS_RETRY_SCHEDULED = "RETRY_SCHEDULED"
internal const val JOB_STATUS_COMPLETED = "COMPLETED"
internal const val JOB_STATUS_CLEARED = "CLEARED"
internal const val JOB_STATUS_ERROR = "ERROR"
internal const val JOB_STATUS_CANCELLED = "CANCELLED"

internal const val SUMMARY_STATUS_SUMMARIZING = "SUMMARIZING"
internal const val SUMMARY_STATUS_GENERATED = "GENERATED"
internal const val SUMMARY_STATUS_ERROR = "ERROR"
internal const val SUMMARY_STATUS_CANCELLED = "CANCELLED"

/** Label for a stored status code, or null for an unknown value. */
@StringRes
internal fun displayJobStatus(status: String): Int? = when (status) {
    JOB_STATUS_QUEUED -> R.string.job_status_queued
    JOB_STATUS_RUNNING -> R.string.job_status_running
    JOB_STATUS_RETRY_SCHEDULED -> R.string.job_status_retry_scheduled
    JOB_STATUS_COMPLETED -> R.string.job_status_completed
    JOB_STATUS_CLEARED -> R.string.job_status_cleared
    JOB_STATUS_ERROR -> R.string.job_status_error
    JOB_STATUS_CANCELLED -> R.string.job_status_cancelled
    else -> null
}

/** Label for a stored summary status code, or null when the stored status is free text. */
@StringRes
internal fun displaySummaryStatus(status: String): Int? = when (status) {
    SUMMARY_STATUS_SUMMARIZING -> R.string.summary_status_summarizing
    SUMMARY_STATUS_GENERATED -> R.string.summary_status_generated
    SUMMARY_STATUS_ERROR -> R.string.summary_status_error
    SUMMARY_STATUS_CANCELLED -> R.string.summary_status_cancelled
    else -> null
}
