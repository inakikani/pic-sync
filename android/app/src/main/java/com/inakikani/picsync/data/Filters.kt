package com.inakikani.picsync.data

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the filters look at. [MediaItem] implements it; tests use plain data classes. */
interface Filterable {
    val isVideo: Boolean
    val takenAt: Long // millis since epoch: date taken, or date added if unknown
    val size: Long    // bytes
}

enum class MediaTypeFilter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos") }

enum class DatePreset(val label: String) {
    ANY("Any time"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_YEAR("This year"),
    OLDER_THAN_1_YEAR("Older than 1 year"),
    CUSTOM("Custom…"),
}

/** Bytes per MB, decimal (10^6) to match how Android's Files app shows sizes. */
const val BYTES_PER_MB = 1_000_000L

data class MediaFilters(
    val type: MediaTypeFilter = MediaTypeFilter.ALL,
    val date: DatePreset = DatePreset.ANY,
    val customFrom: LocalDate? = null, // inclusive, used when date == CUSTOM
    val customTo: LocalDate? = null,   // inclusive, used when date == CUSTOM
    val minSizeMb: Double? = null,
) {
    val activeCount: Int
        get() = listOf(type != MediaTypeFilter.ALL, date != DatePreset.ANY, minSizeMb != null).count { it }

    val isActive: Boolean get() = activeCount > 0

    /** Half-open [from, to) bounds in millis for the date filter; null means unbounded. */
    fun dateBounds(now: ZonedDateTime): Pair<Long?, Long?> {
        val zone = now.zone
        val today = now.toLocalDate()
        fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (date) {
            DatePreset.ANY -> null to null
            DatePreset.LAST_7_DAYS -> startOf(today.minusDays(6)) to null
            DatePreset.LAST_30_DAYS -> startOf(today.minusDays(29)) to null
            DatePreset.THIS_YEAR -> startOf(today.withDayOfYear(1)) to null
            DatePreset.OLDER_THAN_1_YEAR -> null to now.minusYears(1).toInstant().toEpochMilli()
            DatePreset.CUSTOM -> customFrom?.let(::startOf) to customTo?.let { startOf(it.plusDays(1)) }
        }
    }

    /** Builds a predicate; compute once per list, not per item. */
    fun matcher(now: ZonedDateTime): (Filterable) -> Boolean {
        val (from, to) = dateBounds(now)
        val minBytes = minSizeMb?.let { (it * BYTES_PER_MB).toLong() }
        return { item ->
            when (type) {
                MediaTypeFilter.ALL -> true
                MediaTypeFilter.PHOTOS -> !item.isVideo
                MediaTypeFilter.VIDEOS -> item.isVideo
            } &&
                (from == null || item.takenAt >= from) &&
                (to == null || item.takenAt < to) &&
                (minBytes == null || item.size >= minBytes)
        }
    }

    /** Short description of the active filters, e.g. "Videos · Last 30 days · ≥ 10 MB". */
    fun summary(locale: Locale = Locale.getDefault()): String = buildList {
        if (type != MediaTypeFilter.ALL) add(type.label)
        when (date) {
            DatePreset.ANY -> Unit
            DatePreset.CUSTOM -> add(customRangeLabel(locale))
            else -> add(date.label)
        }
        minSizeMb?.let { add("≥ ${formatMb(it)} MB") }
    }.joinToString(" · ")

    fun customRangeLabel(locale: Locale = Locale.getDefault()): String {
        val f = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        val from = customFrom
        val to = customTo
        if (from == null) return if (to == null) "Custom dates" else "until ${to.format(f)}"
        if (to == null || from == to) return from.format(f)
        return "${from.format(f)} – ${to.format(f)}"
    }
}

/** Parses the minimum-size field: "10", "2.5" or "2,5". Blank, zero or invalid input means no minimum. */
fun parseMinSizeMb(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

fun formatMb(mb: Double): String =
    if (mb == Math.floor(mb)) mb.toLong().toString() else mb.toString()
