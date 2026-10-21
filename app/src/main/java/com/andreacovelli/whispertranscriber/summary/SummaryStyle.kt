package com.andreacovelli.whispertranscriber.summary

/** How a summary is shaped. Names are stored in settings and on queued jobs, so never rename them. */
enum class SummaryStyle {
    AUTOMATIC,
    BULLET_POINTS,
    ACTION_ITEMS,
    MEETING_MINUTES,
    CUSTOM,
}

internal const val MAX_SUMMARY_CUSTOM_PROMPT_LENGTH = 2_000

/** Unknown or missing names fall back to [SummaryStyle.AUTOMATIC]. */
fun decodeSummaryStyle(name: String?): SummaryStyle =
    SummaryStyle.entries.firstOrNull { it.name == name } ?: SummaryStyle.AUTOMATIC

/** Trims a user-written prompt and caps its length so it fits in job rows and WorkManager input. */
fun normalizeSummaryCustomPrompt(prompt: String): String = prompt.trim().take(MAX_SUMMARY_CUSTOM_PROMPT_LENGTH).trim()
