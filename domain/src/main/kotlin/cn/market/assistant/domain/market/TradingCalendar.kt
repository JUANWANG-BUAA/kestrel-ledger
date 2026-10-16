package cn.market.assistant.domain.market

import java.time.DayOfWeek
import java.time.LocalDate

enum class MarketVenue(val label: String) {
    SHANGHAI("A股"),
    HONG_KONG("港股"),
    NEW_YORK("美股"),
    TOKYO("日股"),
    SEOUL("韩股"),
    UNKNOWN("未知")
}

/**
 * A session calendar for one venue. `inferred = true` means only the weekend rule is known, so the set
 * can never prove that a weekday was a holiday; `inferred = false` is reconstructed from observed index
 * bars and is authoritative inside `firstDay..lastDay`.
 */
data class TradingDaySet(
    val venue: MarketVenue,
    val days: Set<LocalDate>,
    val inferred: Boolean,
    val source: String? = null,
    val firstDay: LocalDate? = null,
    val lastDay: LocalDate? = null
) {
    fun isWeekend(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY

    fun isTradingDay(day: LocalDate): Boolean = !isWeekend(day) && (inferred || day in days)

    /** `null` when this calendar cannot decide (outside coverage, or weekend-rule only). */
    fun isKnownNonTradingDay(day: LocalDate): Boolean? = when {
        isWeekend(day) -> true
        inferred -> null
        firstDay == null || lastDay == null -> null
        day.isBefore(firstDay) || day.isAfter(lastDay) -> null
        else -> day !in days
    }
}

/**
 * Reconstructs trading calendars from cached index bars instead of hardcoding holiday tables. The
 * published index daily series is the same evidence the Python research pipeline used, so the Android
 * side never claims a holiday list it cannot verify.
 */
object TradingCalendar {
    fun venueForSecid(secid: String): MarketVenue = when (secid.substringBefore('.')) {
        "1", "0" -> MarketVenue.SHANGHAI
        "100", "105", "106", "107" -> MarketVenue.NEW_YORK
        "176" -> MarketVenue.TOKYO
        "177" -> MarketVenue.SEOUL
        else -> MarketVenue.UNKNOWN
    }

    fun fromBars(venue: MarketVenue, bars: List<MarketOhlcBar>, source: String? = null): TradingDaySet {
        val days = bars.asSequence()
            .mapNotNull { normalizeDay(it.day) }
            .filterNot { isWeekend(it) }
            .toSortedSet()
        return TradingDaySet(
            venue = venue,
            days = days,
            inferred = days.isEmpty(),
            source = source,
            firstDay = days.firstOrNull(),
            lastDay = days.lastOrNull()
        )
    }

    fun weekendOnly(venue: MarketVenue): TradingDaySet =
        TradingDaySet(venue, emptySet(), inferred = true, source = "weekday-rule")

    /** Previous session: exact inside the reconstructed range, weekend-aware outside it. */
    fun previousTradingDay(calendar: TradingDaySet?, day: LocalDate): LocalDate? {
        if (calendar == null || calendar.inferred) return previousWeekday(day)
        val floor = calendar.firstDay ?: return previousWeekday(day)
        if (day <= floor) return null
        var cursor = day.minusDays(1)
        while (!cursor.isBefore(floor)) {
            if (calendar.isTradingDay(cursor)) return cursor
            cursor = cursor.minusDays(1)
        }
        return previousWeekday(day)
    }

    /** Expected sessions in `[start, end]`, or weekday fill when the calendar is inferred. */
    fun expectedSessions(calendar: TradingDaySet?, start: LocalDate, end: LocalDate): List<LocalDate> {
        if (start.isAfter(end)) return emptyList()
        if (calendar == null || calendar.inferred) {
            return generateSequence(start) { it.plusDays(1) }
                .takeWhile { !it.isAfter(end) }
                .filterNot { isWeekend(it) }
                .toList()
        }
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .filter { calendar.isTradingDay(it) }
            .toList()
    }

    /** Sessions in range that are missing from `observed`; empty when the calendar cannot decide. */
    fun missingSessions(
        calendar: TradingDaySet?,
        start: LocalDate,
        end: LocalDate,
        observed: Set<LocalDate>
    ): List<LocalDate> {
        if (calendar == null || calendar.inferred) return emptyList()
        return expectedSessions(calendar, start, end).filterNot { it in observed }
    }

    fun normalizeDay(raw: String): LocalDate? = runCatching {
        val trimmed = raw.trim().take(10)
        if (trimmed.length == 8 && trimmed.all(Char::isDigit)) {
            LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        } else {
            LocalDate.parse(trimmed)
        }
    }.getOrNull()

    private fun previousWeekday(day: LocalDate): LocalDate? = when (day.dayOfWeek) {
        DayOfWeek.MONDAY -> day.minusDays(3)
        DayOfWeek.SUNDAY -> day.minusDays(2)
        DayOfWeek.SATURDAY -> day.minusDays(1)
        else -> day.minusDays(1)
    }

    private fun isWeekend(day: LocalDate): Boolean =
        day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
}
