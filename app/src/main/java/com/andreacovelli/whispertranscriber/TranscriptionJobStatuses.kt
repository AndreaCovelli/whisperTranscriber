package com.andreacovelli.whispertranscriber

internal const val JOB_STATUS_QUEUED = "QUEUED"
internal const val JOB_STATUS_RETRY_SCHEDULED = "RETRY_SCHEDULED"
internal const val JOB_STATUS_COMPLETED = "COMPLETED"
internal const val JOB_STATUS_CLEARED = "CLEARED"

internal const val DISPLAY_JOB_STATUS_QUEUED = "Queued"
internal const val DISPLAY_JOB_STATUS_RETRY_SCHEDULED = "Retry scheduled"
internal const val DISPLAY_JOB_STATUS_COMPLETED = "Completed"
internal const val DISPLAY_JOB_STATUS_CLEARED = "Cleared"

internal const val SUMMARY_STATUS_SUMMARIZING = "SUMMARIZING"
internal const val SUMMARY_STATUS_GENERATED = "GENERATED"
internal const val SUMMARY_STATUS_ERROR = "ERROR"
internal const val SUMMARY_STATUS_CANCELLED = "CANCELLED"

internal const val DISPLAY_SUMMARY_STATUS_SUMMARIZING = "Summarizing..."
internal const val DISPLAY_SUMMARY_STATUS_GENERATED = "Summary generated"
internal const val DISPLAY_SUMMARY_STATUS_ERROR = "Error"
internal const val DISPLAY_SUMMARY_STATUS_CANCELLED = "Cancelled"

internal fun displayJobStatus(status: String): String = when (status) {
    JOB_STATUS_QUEUED -> DISPLAY_JOB_STATUS_QUEUED
    JOB_STATUS_RETRY_SCHEDULED -> DISPLAY_JOB_STATUS_RETRY_SCHEDULED
    JOB_STATUS_COMPLETED -> DISPLAY_JOB_STATUS_COMPLETED
    JOB_STATUS_CLEARED -> DISPLAY_JOB_STATUS_CLEARED
    else -> status
}

internal fun displaySummaryStatus(status: String): String = when (status) {
    SUMMARY_STATUS_SUMMARIZING -> DISPLAY_SUMMARY_STATUS_SUMMARIZING
    SUMMARY_STATUS_GENERATED -> DISPLAY_SUMMARY_STATUS_GENERATED
    SUMMARY_STATUS_ERROR -> DISPLAY_SUMMARY_STATUS_ERROR
    SUMMARY_STATUS_CANCELLED -> DISPLAY_SUMMARY_STATUS_CANCELLED
    else -> status
}
