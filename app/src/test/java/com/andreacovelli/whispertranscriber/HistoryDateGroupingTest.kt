package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryDateGroupingTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Rome")

    @Test
    fun historyDateBucket_returnsToday_forItemsOnCurrentLocalDate() {
        val clock = fixedClock(month = 4, day = 15)

        val bucket = historyDateBucket(timestamp = millisAt(2026, 4, 15, 8, 30), clock = clock, zoneId = zoneId)

        assertEquals("day:2026-04-15", bucket.key)
        assertEquals(HistoryDateLabel.Today, bucket.label)
    }

    @Test
    fun historyDateBucket_returnsYesterday_forItemsOnPreviousLocalDate() {
        val clock = fixedClock(month = 4, day = 15)

        val bucket = historyDateBucket(timestamp = millisAt(2026, 4, 14, 23, 45), clock = clock, zoneId = zoneId)

        assertEquals("day:2026-04-14", bucket.key)
        assertEquals(HistoryDateLabel.Yesterday, bucket.label)
    }

    @Test
    fun historyDateBucket_returnsMonthBucket_forOlderItemsInSameMonth() {
        val clock = fixedClock(month = 4, day = 15)

        val bucket = historyDateBucket(timestamp = millisAt(2026, 4, 10, 9, 0), clock = clock, zoneId = zoneId)

        assertEquals("month:2026-04", bucket.key)
        assertEquals(HistoryDateLabel.Month(YearMonth.of(2026, 4)), bucket.label)
    }

    @Test
    fun historyDateHeader_returnsNull_whenAdjacentItemsShareTheSameBucket() {
        val clock = fixedClock(month = 4, day = 15)
        val before = historyItem(id = 1, year = 2026, month = 4, day = 15, hour = 9)
        val after = historyItem(id = 2, year = 2026, month = 4, day = 15, hour = 8)

        val header = historyDateHeader(before = before, after = after, clock = clock, zoneId = zoneId)

        assertNull(header)
    }

    @Test
    fun historyDateHeader_returnsMonthHeader_whenMonthChanges() {
        val clock = fixedClock(month = 5, day = 3)
        val before = historyItem(id = 1, year = 2026, month = 4, day = 30, hour = 18)
        val after = historyItem(id = 2, year = 2026, month = 3, day = 31, hour = 21)

        val header = historyDateHeader(before = before, after = after, clock = clock, zoneId = zoneId)

        assertEquals(
            HistoryListRow.Header(
                HistoryDateBucket(
                    key = "month:2026-03",
                    label = HistoryDateLabel.Month(YearMonth.of(2026, 3)),
                ),
            ),
            header,
        )
    }

    @Test
    fun buildHistoryListRows_addsOneHeaderPerBucket_forDescendingResults() {
        val clock = fixedClock(month = 4, day = 15)
        val rows = buildHistoryListRows(
            items = listOf(
                historyItem(id = 1, year = 2026, month = 4, day = 15, hour = 9),
                historyItem(id = 2, year = 2026, month = 4, day = 15, hour = 7),
                historyItem(id = 3, year = 2026, month = 4, day = 14, hour = 20),
                historyItem(id = 4, year = 2026, month = 4, day = 10, hour = 12),
                historyItem(id = 5, year = 2026, month = 4, day = 2, hour = 8),
            ),
            clock = clock,
            zoneId = zoneId,
        )

        assertEquals(
            listOf(
                HistoryListRow.Header(HistoryDateBucket("day:2026-04-15", HistoryDateLabel.Today)),
                HistoryListRow.Item(historyItem(id = 1, year = 2026, month = 4, day = 15, hour = 9)),
                HistoryListRow.Item(historyItem(id = 2, year = 2026, month = 4, day = 15, hour = 7)),
                HistoryListRow.Header(HistoryDateBucket("day:2026-04-14", HistoryDateLabel.Yesterday)),
                HistoryListRow.Item(historyItem(id = 3, year = 2026, month = 4, day = 14, hour = 20)),
                HistoryListRow.Header(
                    HistoryDateBucket("month:2026-04", HistoryDateLabel.Month(YearMonth.of(2026, 4))),
                ),
                HistoryListRow.Item(historyItem(id = 4, year = 2026, month = 4, day = 10, hour = 12)),
                HistoryListRow.Item(historyItem(id = 5, year = 2026, month = 4, day = 2, hour = 8)),
            ),
            rows,
        )
    }

    @Test
    fun historyDateBucket_usesProvidedZoneNearMidnight() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val clock = Clock.fixed(
            ZonedDateTime.of(2026, 1, 1, 0, 15, 0, 0, losAngeles).toInstant(),
            losAngeles,
        )

        val bucket = historyDateBucket(
            timestamp = millisAt(2025, 12, 31, 23, 45, losAngeles),
            clock = clock,
            zoneId = losAngeles,
        )

        assertEquals("day:2025-12-31", bucket.key)
        assertEquals(HistoryDateLabel.Yesterday, bucket.label)
    }

    private fun fixedClock(month: Int, day: Int): Clock = Clock.fixed(
        ZonedDateTime.of(2026, month, day, 10, 0, 0, 0, zoneId).toInstant(),
        zoneId,
    )

    private fun historyItem(id: Int, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        TranscriptionHistory(
            id = id,
            text = "Transcript $id",
            language = "en",
            displayName = "item-$id.wav",
            timestamp = millisAt(year, month, day, hour, minute),
        )

    private fun millisAt(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = zoneId): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
