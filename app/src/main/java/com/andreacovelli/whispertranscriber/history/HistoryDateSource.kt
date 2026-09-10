package com.andreacovelli.whispertranscriber.history

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

data class HistoryGroupingContext(val currentDate: LocalDate, val zoneId: ZoneId)

interface HistoryDateSource {
    val changes: Flow<HistoryGroupingContext>
}

class SystemHistoryDateSource @Inject constructor() : HistoryDateSource {
    override val changes: Flow<HistoryGroupingContext> = flow {
        while (true) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            emit(HistoryGroupingContext(now.toLocalDate(), zone))
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            // Recheck wall-clock and time-zone changes as well as local midnight.
            delay((Duration.between(now, midnight).toMillis() + 500).coerceIn(1, 60_000))
        }
    }.distinctUntilChanged()
}
