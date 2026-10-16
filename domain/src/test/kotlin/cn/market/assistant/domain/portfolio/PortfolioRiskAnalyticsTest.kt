package cn.market.assistant.domain.portfolio

import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.market.MarketVenue
import cn.market.assistant.domain.market.TradingCalendar
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioRiskAnalyticsTest {
    @Test
    fun maximumDrawdownLinksDailyReturnsAcrossExternalDeposits() {
        val day0 = LocalDate.parse("2026-10-04")
        val opening = snapshot(day0, "100", "0")
        val day1 = snapshot(day0.plusDays(1), "110", "0")
        val day2 = snapshot(day0.plusDays(2), "1110", "0")
        val day3 = snapshot(day0.plusDays(3), "999", "0")
        val deposit = AccountCashEvent(
            id = 1, day = day0.plusDays(2), type = AccountCashEventType.DEPOSIT, amount = BigDecimal("1000"),
            occurredAt = "2026-10-06T10:00:00Z", portfolioValueBefore = BigDecimal("110"), valuationComplete = true
        )

        val result = PortfolioRiskAnalytics.maximumDrawdown(day0.plusDays(1), day0.plusDays(3),
            listOf(opening, day1, day2, day3), listOf(deposit))

        assertEquals(BigDecimal("10.00"), result.drawdownPct)
        assertEquals(day1.day, result.peakDay)
        assertEquals(day3.day, result.troughDay)
        assertEquals(3, result.observationCount)
        assertNull(result.missingReason)
    }

    @Test
    fun missingCalendarSnapshotHidesDrawdownAndNamesTheGap() {
        val day0 = LocalDate.parse("2026-10-04")
        val result = PortfolioRiskAnalytics.maximumDrawdown(day0.plusDays(1), day0.plusDays(2),
            listOf(snapshot(day0, "100", "0"), snapshot(day0.plusDays(2), "90", "0")), emptyList())

        assertNull(result.drawdownPct)
        assertEquals(listOf(day0.plusDays(1)), result.missingDays)
        assertTrue(result.missingReason?.contains("缺少 1 天快照") == true)
    }

    @Test
    fun staleValuationBoundaryHidesDrawdown() {
        val day0 = LocalDate.parse("2026-10-04")
        val staleOpening = snapshot(day0, "100", "0").copy(valuationFresh = false)

        val result = PortfolioRiskAnalytics.maximumDrawdown(day0.plusDays(1), day0.plusDays(1),
            listOf(staleOpening, snapshot(day0.plusDays(1), "90", "0")), emptyList())

        assertNull(result.drawdownPct)
        assertTrue(result.missingReason?.contains("过期") == true)
    }

    @Test
    fun periodFactsExposeMaxDrawdownFromFlowAdjustedAccountPath() {
        val day0 = LocalDate.parse("2026-10-04")
        val snapshots = listOf(
            snapshot(day0, "100", "0"), snapshot(day0.plusDays(1), "110", "0"),
            snapshot(day0.plusDays(2), "100", "0")
        )
        val result = PortfolioAnalytics.facts(
            ReportPeriod.WEEK, day0.plusDays(2), snapshots, emptyMap(), emptyList()
        )

        assertEquals(BigDecimal("9.09"), result.accountMaxDrawdownPct)
        assertEquals(day0.plusDays(1), result.accountMaxDrawdownPeakDay)
        assertEquals(day0.plusDays(2), result.accountMaxDrawdownTroughDay)
        assertNull(result.accountMaxDrawdownMissingReason)
        assertNotNull(result.accountTwrPct)
        assertTrue(result.accountMaxDrawdownMissingDays.isEmpty())
    }

    @Test
    fun authoritativeSessionCalendarSkipsHolidaysInsteadOfReportingMissingSnapshots() {
        val weekdays = (0 until 90).mapNotNull { offset ->
            val day = LocalDate.parse("2026-01-05").plusDays(offset.toLong())
            day.takeIf { it.dayOfWeek.value <= 5 }
        }
        val holiday = LocalDate.parse("2026-02-17")
        val sessions = weekdays.filterNot { it == holiday }
        val calendar = TradingCalendar.fromBars(
            MarketVenue.SHANGHAI,
            sessions.map { MarketOhlcBar(it.toString(), 100.0, 100.0, 100.0, 100.0, 0.0) }
        )
        val snapshots = sessions.mapIndexed { index, day -> snapshot(day, (100 + index).toString(), "0") }

        val result = PortfolioRiskAnalytics.trailingSummary(
            asOf = sessions.last(),
            snapshots = snapshots,
            positionsByDay = emptyMap(),
            trades = emptyList(),
            cashEvents = emptyList(),
            sessionCalendar = calendar
        )

        assertNull(result.missingReason)
        assertEquals(sessions.size - 1, result.observationCount)
        assertNotNull(result.annualizedVolatilityPct)
    }

    @Test
    fun calendarNamesAMissingRealSessionAsThePathBreak() {
        val weekdays = (0 until 90).mapNotNull { offset ->
            val day = LocalDate.parse("2026-01-05").plusDays(offset.toLong())
            day.takeIf { it.dayOfWeek.value <= 5 }
        }
        val blocked = weekdays[weekdays.size - 3]
        // The benchmark calendar saw the session; only the account snapshot is missing for that day.
        val calendar = TradingCalendar.fromBars(
            MarketVenue.SHANGHAI,
            weekdays.map { MarketOhlcBar(it.toString(), 100.0, 100.0, 100.0, 100.0, 0.0) }
        )
        val sessions = weekdays.filterNot { it == blocked }
        val snapshots = sessions.mapIndexed { index, day -> snapshot(day, (100 + index).toString(), "0") }

        val result = PortfolioRiskAnalytics.trailingSummary(
            asOf = sessions.last(),
            snapshots = snapshots,
            positionsByDay = emptyMap(),
            trades = emptyList(),
            cashEvents = emptyList(),
            sessionCalendar = calendar
        )

        assertNull(result.annualizedVolatilityPct)
        assertTrue(result.missingReason!!.contains(blocked.toString()))
    }

    private fun snapshot(day: LocalDate, market: String, cash: String) = PortfolioSnapshot(
        day = day,
        observedAt = day.atTime(18, 0).toInstant(java.time.ZoneOffset.UTC).toString(),
        positionCount = 1,
        pricedPositionCount = 1,
        marketValue = BigDecimal(market),
        cost = BigDecimal(market),
        holdingProfit = BigDecimal.ZERO,
        realizedProfit = BigDecimal.ZERO,
        totalProfit = BigDecimal.ZERO,
        cashBalance = BigDecimal(cash),
        totalValue = BigDecimal(market) + BigDecimal(cash),
        externalNetFlow = BigDecimal.ZERO,
        valuationFresh = true
    )
}
