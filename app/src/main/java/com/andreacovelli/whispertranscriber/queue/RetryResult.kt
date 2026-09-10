package com.andreacovelli.whispertranscriber.queue

enum class RetryResult {
    ACCEPTED,
    QUEUE_FULL,
    UNAVAILABLE_AUDIO,
    INELIGIBLE_STATUS,
}
