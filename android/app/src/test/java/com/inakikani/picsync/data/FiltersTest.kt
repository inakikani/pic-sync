package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class FiltersTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val now = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, zone)

    private data class Item(
        override val isVideo: Boolean = false,
        override val takenAt: Long,
        override val size: Long = 1_000,
    ) : Filterable

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun MediaFilters.keeps(item: Item) = matcher(now)(item)

    @Test fun defaultsKeepEverything() {
        val f = MediaFilters()
        assertFalse(f.isActive)
        assertTrue(f.keeps(Item(isVideo = true, takenAt = 0, size = 0)))
        assertEquals("", f.summary())
    }

    @Test fun mediaType() {
        val photo = Item(isVideo = false, takenAt = at(2026, 1, 1))
        val video = Item(isVideo = true, takenAt = at(2026, 1, 1))
        val photos = MediaFilters(type = MediaTypeFilter.PHOTOS)
        val videos = MediaFilters(type = MediaTypeFilter.VIDEOS)
        assertTrue(photos.keeps(photo)); assertFalse(photos.keeps(video))
        assertTrue(videos.keeps(video)); assertFalse(videos.keeps(photo))
    }

    @Test fun last7DaysIncludesTodayAndSixDaysBack() {
        val f = MediaFilters(date = DatePreset.LAST_7_DAYS)
        assertTrue(f.keeps(Item(takenAt = at(2026, 11, 1, 1))))
        assertTrue(f.keeps(Item(takenAt = at(2026, 10, 26, 0))))  // 6 days back, midnight
        assertFalse(f.keeps(Item(takenAt = at(2026, 10, 25, 23)))) // 7 days back
    }

    @Test fun last30Days() {
        val f = MediaFilters(date = DatePreset.LAST_30_DAYS)
        assertTrue(f.keeps(Item(takenAt = at(2026, 10, 3, 0))))
        assertFalse(f.keeps(Item(takenAt = at(2026, 10, 2, 23))))
    }

    @Test fun thisYear() {
        val f = MediaFilters(date = DatePreset.THIS_YEAR)
        assertTrue(f.keeps(Item(takenAt = at(2026, 1, 1, 0))))
        assertFalse(f.keeps(Item(takenAt = at(2025, 12, 31, 23))))
    }

    @Test fun olderThanOneYear() {
        val f = MediaFilters(date = DatePreset.OLDER_THAN_1_YEAR)
        assertTrue(f.keeps(Item(takenAt = at(2025, 11, 1, 11))))
        assertFalse(f.keeps(Item(takenAt = at(2025, 11, 1, 13))))
    }

    @Test fun customRangeIsInclusiveOfBothDays() {
        val f = MediaFilters(
            date = DatePreset.CUSTOM,
            customFrom = LocalDate.of(2026, 7, 1),
            customTo = LocalDate.of(2026, 7, 31),
        )
        assertTrue(f.keeps(Item(takenAt = at(2026, 7, 1, 0))))
        assertTrue(f.keeps(Item(takenAt = at(2026, 7, 31, 23))))
        assertFalse(f.keeps(Item(takenAt = at(2026, 6, 30, 23))))
        assertFalse(f.keeps(Item(takenAt = at(2026, 8, 1, 0))))
        assertEquals("1 Jul 2026 – 31 Jul 2026", f.summary(Locale.US))
    }

    @Test fun customSingleDay() {
        val day = LocalDate.of(2026, 7, 14)
        val f = MediaFilters(date = DatePreset.CUSTOM, customFrom = day, customTo = day)
        assertTrue(f.keeps(Item(takenAt = at(2026, 7, 14, 20))))
        assertFalse(f.keeps(Item(takenAt = at(2026, 7, 15, 0))))
        assertEquals("14 Jul 2026", f.summary(Locale.US))
    }

    @Test fun minimumSizeUsesDecimalMegabytes() {
        val f = MediaFilters(minSizeMb = 10.0)
        assertTrue(f.keeps(Item(takenAt = 0, size = 10_000_000)))
        assertFalse(f.keeps(Item(takenAt = 0, size = 9_999_999)))
        assertTrue(MediaFilters(minSizeMb = 2.5).keeps(Item(takenAt = 0, size = 2_500_000)))
    }

    @Test fun combinedFiltersAndSummary() {
        val f = MediaFilters(type = MediaTypeFilter.VIDEOS, date = DatePreset.LAST_30_DAYS, minSizeMb = 100.0)
        assertEquals(3, f.activeCount)
        assertEquals("Videos · Last 30 days · ≥ 100 MB", f.summary(Locale.US))
        assertTrue(f.keeps(Item(isVideo = true, takenAt = at(2026, 10, 20), size = 200_000_000)))
        assertFalse(f.keeps(Item(isVideo = false, takenAt = at(2026, 10, 20), size = 200_000_000)))
        assertFalse(f.keeps(Item(isVideo = true, takenAt = at(2026, 1, 20), size = 200_000_000)))
        assertFalse(f.keeps(Item(isVideo = true, takenAt = at(2026, 10, 20), size = 50_000_000)))
    }

    @Test fun parsesMinSize() {
        assertEquals(10.0, parseMinSizeMb("10")!!, 0.0)
        assertEquals(2.5, parseMinSizeMb(" 2.5 ")!!, 0.0)
        assertEquals(2.5, parseMinSizeMb("2,5")!!, 0.0)
        assertNull(parseMinSizeMb(""))
        assertNull(parseMinSizeMb("0"))
        assertNull(parseMinSizeMb("-3"))
        assertNull(parseMinSizeMb("abc"))
        assertEquals("2.5", formatMb(2.5))
        assertEquals("10", formatMb(10.0))
    }
}
