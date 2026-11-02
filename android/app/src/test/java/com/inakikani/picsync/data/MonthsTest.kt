package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class MonthsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, paris).toInstant().toEpochMilli()

    @Test fun groupsConsecutiveItemsByMonth() {
        val taken = listOf(at(2026, 11, 2), at(2026, 11, 1), at(2026, 10, 31), at(2025, 12, 24), at(2025, 12, 1))
        val groups = groupByMonth(taken, paris) { it }
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 10), YearMonth.of(2025, 12)), groups.map { it.month })
        assertEquals(listOf(2, 1, 2), groups.map { it.items.size })
    }

    @Test fun usesTheGivenTimeZone() {
        // 00:30 on 1 Nov in Paris is still 31 Oct in New York.
        val t = listOf(at(2026, 11, 1, h = 0) + 30 * 60_000)
        assertEquals(YearMonth.of(2026, 11), groupByMonth(t, paris) { it }.single().month)
        assertEquals(YearMonth.of(2026, 10), groupByMonth(t, ZoneId.of("America/New_York")) { it }.single().month)
    }

    @Test fun emptyListHasNoGroups() {
        assertEquals(0, groupByMonth(emptyList<Long>(), paris) { it }.size)
    }

    @Test fun labelsAreLocalisedAndCapitalised() {
        assertEquals("November 2026", monthLabel(YearMonth.of(2026, 11), Locale.ENGLISH))
        assertEquals("Novembre 2026", monthLabel(YearMonth.of(2026, 11), Locale.FRENCH))
        assertEquals("Nov 2026", shortMonthLabel(YearMonth.of(2026, 11), Locale.ENGLISH))
    }
}

class MonthSelectionTest {
    private val uploaded = setOf(2L)
    private val skipped = setOf(3L)
    private val pending: (Long) -> Boolean = { it !in uploaded && it !in skipped }
    private val month = listOf(1L, 2L, 3L, 4L)

    @Test fun selectsOnlyWhatStillNeedsUploading() {
        assertEquals(setOf(9L, 1L, 4L), toggleMonthSelection(setOf(9L), month, pending))
    }

    @Test fun secondTapDeselectsTheMonthButKeepsOthers() {
        assertEquals(setOf(9L), toggleMonthSelection(setOf(9L, 1L, 4L), month, pending))
    }

    @Test fun partlySelectedMonthGetsCompleted() {
        assertEquals(setOf(1L, 4L), toggleMonthSelection(setOf(1L), month, pending))
    }

    @Test fun nothingToUploadChangesNothing() {
        assertEquals(setOf(9L), toggleMonthSelection(setOf(9L), listOf(2L, 3L), pending))
    }
}
