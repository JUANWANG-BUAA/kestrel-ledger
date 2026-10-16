package cn.market.assistant.domain.market

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingCalendarTest {
    private val bars = listOf(
        bar("2026-09-28"), bar("2026-09-29"), bar("2026-09-30"),
        // 国庆假期：10-01 至 10-07 没有行情
        bar("2026-10-08"), bar("2026-10-09"),
        bar("2026-10-12"), bar("2026-10-13")
    )

    @Test
    fun reconstructsHolidaysFromObservedIndexBars() {
        val calendar = TradingCalendar.fromBars(MarketVenue.SHANGHAI, bars, "1.000300")

        assertFalse(calendar.inferred)
        assertEquals(LocalDate.parse("2026-09-28"), calendar.firstDay)
        assertEquals(LocalDate.parse("2026-10-13"), calendar.lastDay)
        assertTrue(calendar.isTradingDay(LocalDate.parse("2026-10-08")))
        assertEquals(true, calendar.isKnownNonTradingDay(LocalDate.parse("2026-10-01")))
        assertEquals(true, calendar.isKnownNonTradingDay(LocalDate.parse("2026-10-03")))
        assertEquals(true, calendar.isKnownNonTradingDay(LocalDate.parse("2026-10-04")))
    }

    @Test
    fun outsideTheObservedRangeTheCalendarAdmitsItCannotDecide() {
        val calendar = TradingCalendar.fromBars(MarketVenue.SHANGHAI, bars)

        assertNull(calendar.isKnownNonTradingDay(LocalDate.parse("2026-11-26")))
        assertNull(TradingCalendar.weekendOnly(MarketVenue.SHANGHAI).isKnownNonTradingDay(LocalDate.parse("2026-10-01")))
        assertEquals(true, TradingCalendar.weekendOnly(MarketVenue.SHANGHAI).isKnownNonTradingDay(LocalDate.parse("2026-10-03")))
    }

    @Test
    fun previousTradingDaySkipsTheHolidayBlock() {
        val calendar = TradingCalendar.fromBars(MarketVenue.SHANGHAI, bars)

        assertEquals(LocalDate.parse("2026-09-30"), TradingCalendar.previousTradingDay(calendar, LocalDate.parse("2026-10-08")))
        assertEquals(LocalDate.parse("2026-10-09"), TradingCalendar.previousTradingDay(calendar, LocalDate.parse("2026-10-12")))
        assertEquals(
            LocalDate.parse("2026-10-02"),
            TradingCalendar.previousTradingDay(TradingCalendar.weekendOnly(MarketVenue.SHANGHAI), LocalDate.parse("2026-10-05"))
        )
    }

    @Test
    fun expectedAndMissingSessionsUseTheReconstructedCalendar() {
        val calendar = TradingCalendar.fromBars(MarketVenue.SHANGHAI, bars)
        val expected = TradingCalendar.expectedSessions(
            calendar,
            LocalDate.parse("2026-10-08"),
            LocalDate.parse("2026-10-13")
        )
        assertEquals(
            listOf("2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13"),
            expected.map(LocalDate::toString)
        )
        assertEquals(
            listOf(LocalDate.parse("2026-10-09")),
            TradingCalendar.missingSessions(
                calendar,
                LocalDate.parse("2026-10-08"),
                LocalDate.parse("2026-10-13"),
                setOf(LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-13"))
            )
        )
        assertTrue(
            TradingCalendar.missingSessions(
                TradingCalendar.weekendOnly(MarketVenue.SHANGHAI),
                LocalDate.parse("2026-10-08"),
                LocalDate.parse("2026-10-13"),
                emptySet()
            ).isEmpty()
        )
    }

    @Test
    fun venueResolutionFollowsTheSecidPrefix() {
        assertEquals(MarketVenue.SHANGHAI, TradingCalendar.venueForSecid("1.000300"))
        assertEquals(MarketVenue.SHANGHAI, TradingCalendar.venueForSecid("0.159869"))
        assertEquals(MarketVenue.NEW_YORK, TradingCalendar.venueForSecid("100.SPX"))
        assertEquals(MarketVenue.NEW_YORK, TradingCalendar.venueForSecid("105.AAPL"))
        assertEquals(MarketVenue.TOKYO, TradingCalendar.venueForSecid("176.6758"))
        assertEquals(MarketVenue.SEOUL, TradingCalendar.venueForSecid("177.005930"))
    }

    private fun bar(day: String) = MarketOhlcBar(day, 100.0, 101.0, 102.0, 99.0, 1_000.0)
}
