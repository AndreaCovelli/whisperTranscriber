package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal sealed interface HistoryDateLabel {
    data object Today : HistoryDateLabel

    data object Yesterday : HistoryDateLabel

    data class Month(val yearMonth: YearMonth) : HistoryDateLabel
}

internal data class HistoryDateBucket(val key: String, val label: HistoryDateLabel)

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
        )

        currentDate.minusDays(1) -> HistoryDateBucket(
            key = "day:$itemDate",
            label = HistoryDateLabel.Yesterday,
        )

        else -> {
            val yearMonth = YearMonth.from(itemDate)
            HistoryDateBucket(
                key = "month:$yearMonth",
                label = HistoryDateLabel.Month(yearMonth),
            )
        }
    }

internal fun historyDateBucket(
    timestamp: Long,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = clock.zone,
): HistoryDateBucket = historyDateBucket(
    timestamp = timestamp,
    currentDate = LocalDate.now(clock.withZone(zoneId)),
    zoneId = zoneId,
)

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

internal fun historyListItem(
    history: TranscriptionHistory,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = clock.zone,
): HistoryListRow.Item = historyListItem(
    history = history,
    currentDate = LocalDate.now(clock.withZone(zoneId)),
    zoneId = zoneId,
)

internal fun buildHistoryListRows(
    items: List<TranscriptionHistory>,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = clock.zone,
): List<HistoryListRow> = buildList {
    val currentDate = LocalDate.now(clock.withZone(zoneId))
    var previousBucket: HistoryDateBucket? = null
    items.forEach { history ->
        val item = historyListItem(history, currentDate, zoneId)
        historyDateHeader(previousBucket, item.bucket)?.let(::add)
        add(item)
        previousBucket = item.bucket
    }
}
