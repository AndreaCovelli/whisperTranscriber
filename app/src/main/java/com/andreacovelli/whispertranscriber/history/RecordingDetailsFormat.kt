package com.andreacovelli.whispertranscriber.history

import java.util.Locale

private const val MILLISECONDS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Formats a recording length as "m:ss" or "h:mm:ss", rounding to the nearest second. */
fun formatRecordingDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0) + MILLISECONDS_PER_SECOND / 2) / MILLISECONDS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
