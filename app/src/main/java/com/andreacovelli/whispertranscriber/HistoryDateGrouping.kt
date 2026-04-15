package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    data class Item(val history: TranscriptionHistory) : HistoryListRow {
        override val stableKey: String = "item:${history.id}"
    }
}

internal fun historyDateBucket(
    timestamp: Long,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = clock.zone,
): HistoryDateBucket {
    val currentDate = LocalDate.now(clock.withZone(zoneId))

    return when (val itemDate = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()) {
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
}

internal fun historyDateHeader(
    before: TranscriptionHistory?,
    after: TranscriptionHistory,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = clock.zone,
): HistoryListRow.Header? {
    val afterBucket = historyDateBucket(after.timestamp, clock, zoneId)
    val beforeBucketKey = before?.let { historyDateBucket(it.timestamp, clock, zoneId).key }
    return if (beforeBucketKey == afterBucket.key) null else HistoryListRow.Header(afterBucket)
}

internal fun buildHistoryListRows(
    items: List<TranscriptionHistory>,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = clock.zone,
): List<HistoryListRow> = buildList {
    var previousItem: TranscriptionHistory? = null
    items.forEach { item ->
        historyDateHeader(previousItem, item, clock, zoneId)?.let(::add)
        add(HistoryListRow.Item(item))
        previousItem = item
    }
}

internal fun formatHistoryMonthLabel(yearMonth: YearMonth, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(yearMonth.atDay(1))
