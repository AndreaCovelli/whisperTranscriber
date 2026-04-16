package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal sealed interface HistoryDateLabel {
    data object Today : HistoryDateLabel

    data object Yesterday : HistoryDateLabel

    data class Month(val yearMonth: YearMonth) : HistoryDateLabel
}

internal data class HistoryDateBucket(val key: String, val label: HistoryDateLabel, val zoneId: ZoneId)

internal sealed interface HistoryListRow {
    val stableKey: String

    data class Header(val bucket: HistoryDateBucket) : HistoryListRow {
        override val stableKey: String = "header:${bucket.key}"
    }

    data class Item(val history: TranscriptionHistory, val bucket: HistoryDateBucket) : HistoryListRow {
        override val stableKey: String = "item:${history.id}"
    }
}

internal fun historyDateBucket(timestamp: Long, currentDate: LocalDate, zoneId: ZoneId): HistoryDateBucket =
    when (val itemDate = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()) {
        currentDate -> HistoryDateBucket(
            key = "day:$itemDate",
            label = HistoryDateLabel.Today,
            zoneId = zoneId,
        )

        currentDate.minusDays(1) -> HistoryDateBucket(
            key = "day:$itemDate",
            label = HistoryDateLabel.Yesterday,
            zoneId = zoneId,
        )

        else -> {
            val yearMonth = YearMonth.from(itemDate)
            HistoryDateBucket(
                key = "month:$yearMonth",
                label = HistoryDateLabel.Month(yearMonth),
                zoneId = zoneId,
            )
        }
    }

internal fun historyDateHeader(
    previousBucket: HistoryDateBucket?,
    currentBucket: HistoryDateBucket,
): HistoryListRow.Header? = if (previousBucket?.key == currentBucket.key) null else HistoryListRow.Header(currentBucket)

internal fun historyListItem(
    history: TranscriptionHistory,
    currentDate: LocalDate,
    zoneId: ZoneId,
): HistoryListRow.Item = HistoryListRow.Item(
    history = history,
    bucket = historyDateBucket(history.timestamp, currentDate, zoneId),
)
