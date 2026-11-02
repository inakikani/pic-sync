package com.inakikani.picsync.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One month of the gallery: its header and the items taken in it, newest first. */
data class MonthGroup<T>(val month: YearMonth, val items: List<T>)

/**
 * Splits [items] (sorted newest first by [takenAt], millis) into months in [zone].
 * Consecutive items of the same month form one group, so a correctly sorted list
 * gives each month exactly once.
 */
fun <T> groupByMonth(items: List<T>, zone: ZoneId, takenAt: (T) -> Long): List<MonthGroup<T>> {
    val groups = ArrayList<MonthGroup<T>>()
    var current: YearMonth? = null
    var bucket = ArrayList<T>()
    for (item in items) {
        val month = YearMonth.from(Instant.ofEpochMilli(takenAt(item)).atZone(zone))
        if (month != current) {
            current?.let { groups += MonthGroup(it, bucket) }
            current = month
            bucket = ArrayList()
        }
        bucket += item
    }
    current?.let { groups += MonthGroup(it, bucket) }
    return groups
}

/** "November 2026", in the phone's language ("novembre 2026", "November 2026", …). */
fun monthLabel(month: YearMonth, locale: Locale = Locale.getDefault()): String {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    return "$name ${month.year}"
}

/** Short form for the scroll label: "Nov 2026". */
fun shortMonthLabel(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.format(DateTimeFormatter.ofPattern("LLL yyyy", locale))

/**
 * Tapping a month header: selects the month's items that still need uploading
 * ([pending]), or deselects them if they're all selected already. Other
 * selected items are kept.
 */
fun toggleMonthSelection(selected: Set<Long>, monthIds: List<Long>, pending: (Long) -> Boolean): Set<Long> {
    val ids = monthIds.filterTo(HashSet(), pending)
    return when {
        ids.isEmpty() -> selected
        selected.containsAll(ids) -> selected - ids
        else -> selected + ids
    }
}
