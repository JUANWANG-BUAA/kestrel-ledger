package cn.market.assistant.domain.fund

import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FundPerformanceAnalyticsTest {
    @Test
    fun computesPeriodReturnsAnnualizationAndDrawdown() {
        val start = LocalDate.parse("2025-01-01")
        val points = listOf(
            point(start, 1.0),
            point(start.plusDays(1), 1.2),
            point(start.plusDays(2), 0.9),
            point(start.plusDays(3), 1.1),
            point(start.plusDays(4), 1.3)
        )
        val report = requireNotNull(
            FundPerformanceAnalytics.analyze(points, today = LocalDate.parse("2025-01-06"))
        )
        assertEquals(25.0, report.drawdown.maxDrawdownPct!!, 0.0001)
        assertEquals(0.0, report.drawdown.currentDrawdownPct!!, 0.0001)
        assertEquals("2025-01-02", report.drawdown.peakDay)
        assertEquals("2025-01-03", report.drawdown.troughDay)
        assertEquals("2025-01-05", report.drawdown.recoveryDay)
        assertEquals(30.0, report.periodReturns.last().returnPct!!, 0.0001)
        assertNull(report.periodReturns.first { it.label == "1Y" }.annualizedPct)
    }

    @Test
    fun annualizesOnlyAfterOneYear() {
        val start = LocalDate.parse("2025-01-02")
        val points = (0..365).map { offset ->
            point(start.plusDays(offset.toLong()), 1.0 + offset * 0.1 / 365.0)
        }
        val report = requireNotNull(
            FundPerformanceAnalytics.analyze(points, today = LocalDate.parse("2026-01-02"))
        )
        val oneYear = report.periodReturns.first { it.label == "1Y" }
        assertNotNull(oneYear.returnPct)
        assertNotNull(oneYear.annualizedPct)
        assertTrue(oneYear.returnPct!! > 9.9 && oneYear.returnPct!! < 10.1)
        assertTrue(oneYear.annualizedPct!! > 9.8 && oneYear.annualizedPct!! < 10.2)
    }

    @Test
    fun usesAccumulatedNavWhenEveryPointProvidesIt() {
        val points = listOf(
            FundNavPoint("2025-01-01", unitNav = 1.0, accumulatedNav = 1.0, changePct = 0.0),
            FundNavPoint("2025-01-02", unitNav = 1.0, accumulatedNav = 1.1, changePct = 10.0)
        )
        val report = requireNotNull(
            FundPerformanceAnalytics.analyze(points, today = LocalDate.parse("2025-01-03"))
        )
        assertEquals("累计净值", report.basis)
        assertEquals(10.0, report.periodReturns.last().returnPct!!, 0.0001)
    }

    @Test
    fun refusesSharpeWithTooFewDailyReturns() {
        val start = LocalDate.parse("2025-01-01")
        val points = (0..20).map { point(start.plusDays(it.toLong()), 1.0 + it * 0.001) }
        val report = requireNotNull(
            FundPerformanceAnalytics.analyze(points, today = LocalDate.parse("2025-01-22"))
        )
        assertNull(report.risk.annualizedVolatilityPct)
        assertNull(report.risk.sharpe)
        assertNotNull(report.risk.missingReason)
    }

    @Test
    fun returnsNullForFewerThanTwoPoints() {
        assertNull(
            FundPerformanceAnalytics.analyze(
                listOf(point(LocalDate.parse("2025-01-01"), 1.0)),
                today = LocalDate.parse("2025-01-02")
            )
        )
    }

    private fun point(day: LocalDate, value: Double): FundNavPoint =
        FundNavPoint(day.toString(), value, value, 0.0)
}
