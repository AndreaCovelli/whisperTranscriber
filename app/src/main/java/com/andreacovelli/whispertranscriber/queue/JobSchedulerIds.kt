package com.andreacovelli.whispertranscriber.queue

const val WORK_MANAGER_JOB_ID_RANGE_MIN = 100_000
const val WORK_MANAGER_JOB_ID_RANGE_MAX = 199_999

private const val BACKGROUND_SHARE_JOB_ID_OFFSET = 200_000

fun backgroundShareSchedulerJobId(transcriptionJobId: Int): Int {
    require(transcriptionJobId >= 0) { "Transcription job id must be non-negative" }
    require(transcriptionJobId <= Int.MAX_VALUE - BACKGROUND_SHARE_JOB_ID_OFFSET) {
        "Transcription job id is too large to convert into a scheduler job id"
    }
    return BACKGROUND_SHARE_JOB_ID_OFFSET + transcriptionJobId
}
