package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioAllocationAnalyticsTest {
    private val fundA = AssetInfo("A", "基金甲", AssetKind.FUND, "股票型")
    private val fundB = AssetInfo("B", "基金乙", AssetKind.FUND, "指数型")

    @Test
    fun comparesSecurityAndCashWeightsAndComputesConcentration() {
        val day0 = LocalDate.parse("2026-10-11")
        val day1 = day0.plusDays(1)
        val opening = snapshot(day0, "900", "100")
        val closing = snapshot(day1, "800", "200")
        val rows = mapOf(
            day0 to listOf(position(day0, fundA, "600"), position(day0, fundB, "300")),
            day1 to listOf(position(day1, fundA, "400"), position(day1, fundB, "400"))
        )

        val result = PortfolioAllocationAnalytics.compare(opening, closing, rows)

        assertNull(result.missingReason)
        assertEquals(BigDecimal("40.0000"), result.closing.first { it.id == "A" }.weightPct)
        assertEquals(BigDecimal("20.0000"), result.closing.first { it.kind == AllocationKind.CASH }.weightPct)
        assertEquals(BigDecimal("0.360000"), result.hhi)
        assertEquals(BigDecimal("2.78"), result.effectiveAssetCount)
        assertEquals(BigDecimal("-20.00"), result.changes.first { it.id == "A" }.changePercentagePoints)
        assertEquals(BigDecimal("10.00"), result.changes.first { it.id == "B" }.changePercentagePoints)
    }

    @Test
    fun incompleteOrUnreconciledAccountDoesNotExposeFullAllocation() {
        val day0 = LocalDate.parse("2026-10-11")
        val day1 = day0.plusDays(1)
        val result = PortfolioAllocationAnalytics.compare(
            snapshot(day0, "1000", "100"), snapshot(day1, "1100", "200"),
            mapOf(day0 to listOf(position(day0, fundA, "600")), day1 to listOf(position(day1, fundA, "900")))
        )

        assertTrue(result.missingReason?.contains("无法对账") == true)
        assertTrue(result.closing.isEmpty())
        assertNull(result.hhi)
    }

    @Test
    fun dailyFactsCompareOnlyYesterdayAndTodaySnapshots() {
        val yesterday = LocalDate.parse("2026-10-15")
        val today = yesterday.plusDays(1)
        val opening = snapshot(yesterday, "900", "100")
        val closing = snapshot(today, "800", "200")
        val rows = mapOf(
            yesterday to listOf(position(yesterday, fundA, "900"), position(yesterday, fundB, "0")),
            today to listOf(position(today, fundA, "800"), position(today, fundB, "0"))
        )

        val exactDailyFacts = PortfolioAnalytics.facts(ReportPeriod.DAY, today, listOf(opening, closing), rows, emptyList())
        val skippedDayFacts = PortfolioAnalytics.facts(
            ReportPeriod.DAY, today, listOf(opening.copy(day = yesterday.minusDays(1)), closing),
            rows + (yesterday.minusDays(1) to listOf(
                position(yesterday.minusDays(1), fundA, "900"), position(yesterday.minusDays(1), fundB, "0")
            )), emptyList()
        )

        assertNull(exactDailyFacts.accountAllocationMissingReason)
        assertEquals(BigDecimal("-10.00"), exactDailyFacts.accountAllocationChanges.first { it.id == "A" }.changePercentagePoints)
        assertEquals("日报配置变化需要昨日和今日的快照", skippedDayFacts.accountAllocationMissingReason)
    }

    @Test
    fun rejectsSnapshotWhenRowsDoNotMatchRecordedHoldingMarketValue() {
        val day0 = LocalDate.parse("2026-10-11")
        val day1 = day0.plusDays(1)
        val opening = snapshot(day0, "900", "100")
        val closing = snapshot(day1, "900", "100").copy(totalValue = BigDecimal("950"))
        val rows = mapOf(
            day0 to listOf(position(day0, fundA, "900"), position(day0, fundB, "0")),
            day1 to listOf(position(day1, fundA, "850"), position(day1, fundB, "0"))
        )

        val result = PortfolioAllocationAnalytics.compare(opening, closing, rows)

        assertTrue(result.missingReason?.contains("无法对账") == true)
        assertTrue(result.closing.isEmpty())
    }

    private fun snapshot(day: LocalDate, market: String, cash: String) = PortfolioSnapshot(
        day = day, observedAt = day.atTime(18, 0).toInstant(java.time.ZoneOffset.UTC).toString(),
        positionCount = 2, pricedPositionCount = 2,
        marketValue = BigDecimal(market), cost = BigDecimal(market), holdingProfit = BigDecimal.ZERO,
        realizedProfit = BigDecimal.ZERO, totalProfit = BigDecimal.ZERO, cashBalance = BigDecimal(cash),
        totalValue = BigDecimal(market) + BigDecimal(cash), externalNetFlow = BigDecimal.ZERO, valuationFresh = true
    )

    private fun position(day: LocalDate, asset: AssetInfo, value: String) = PositionSnapshot(
        day = day, asset = asset, units = BigDecimal.ONE, cost = BigDecimal(value), marketValue = BigDecimal(value),
        holdingProfit = BigDecimal.ZERO, realizedProfit = BigDecimal.ZERO,
        quoteObservedAt = day.atTime(17, 0).toInstant(java.time.ZoneOffset.UTC).toString(), quoteSource = "fixture"
    )
}
