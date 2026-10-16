package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionAnalyticsTest {
    private val fund = AssetInfo("000001", "甲基金", AssetKind.FUND, "混合型")
    private val gold = AssetInfo("GOLD.SGE.AU9999", "Au99.99", AssetKind.GOLD, "黄金")
    private val start = LocalDate.parse("2026-10-05")

    @Test
    fun contributionsUseBeginningOfDayWeightsAndLeaveACashRemainder() {
        val day1 = start
        val day2 = start.plusDays(1)
        val snapshots = listOf(snapshot(day1, "2000"), snapshot(day2, "2000"))
        val positions = mapOf(
            day1 to listOf(position(day1, fund, "100", "1000"), position(day1, gold, "100", "1000")),
            day2 to listOf(position(day2, fund, "100", "1100"), position(day2, gold, "100", "900"))
        )

        val result = ContributionAnalytics.byBeginningOfDayWeight(
            snapshots, positions, emptyList(), emptyList()
        )

        assertNull(result.missingReason)
        assertEquals(BigDecimal("0.0000"), result.portfolioReturnPct)
        val fundRow = result.contributions.first { it.assetId == fund.symbol }
        val goldRow = result.contributions.first { it.assetId == gold.symbol }
        assertEquals(BigDecimal("5.0000"), fundRow.contributionPct)
        assertEquals(BigDecimal("-5.0000"), goldRow.contributionPct)
        assertEquals(BigDecimal("50.00"), fundRow.averageWeightPct)
        assertEquals(1, fundRow.observationCount)
        assertEquals(BigDecimal("0.0000"), result.cashAndUnattributedPct)
        assertTrue(result.contributions.first().isTop)
    }

    @Test
    fun compoundedPortfolioReturnIsReportedAndAttributed() {
        val day1 = start
        val day2 = start.plusDays(1)
        val day3 = start.plusDays(2)
        val snapshots = listOf(snapshot(day1, "1000"), snapshot(day2, "1100"), snapshot(day3, "1210"))
        val positions = mapOf(
            day1 to listOf(position(day1, fund, "1000", "1000")),
            day2 to listOf(position(day2, fund, "1000", "1100")),
            day3 to listOf(position(day3, fund, "1000", "1210"))
        )

        val result = ContributionAnalytics.byBeginningOfDayWeight(
            snapshots, positions, emptyList(), emptyList()
        )

        assertNull(result.missingReason)
        // 账户 TWR 复利为 21.00%；按日权重相加为 20.00%，差额 1pp 是复利效应而非漏算。
        assertEquals(BigDecimal("21.0000"), result.portfolioReturnPct)
        assertEquals(BigDecimal("20.0000"), result.contributions.first().contributionPct)
        assertEquals(BigDecimal("1.0000"), result.cashAndUnattributedPct)
        assertEquals(2, result.observationCount)
    }

    @Test
    fun aMissingDayRefusesThePathInsteadOfReusingOldWeights() {
        val day1 = start
        val day3 = start.plusDays(2)
        val snapshots = listOf(snapshot(day1, "1000"), snapshot(day3, "1100"))
        val positions = mapOf(
            day1 to listOf(position(day1, fund, "1000", "1000")),
            day3 to listOf(position(day3, fund, "1000", "1100"))
        )

        val result = ContributionAnalytics.byBeginningOfDayWeight(snapshots, positions, emptyList(), emptyList())

        assertNull(result.portfolioReturnPct)
        assertNotNull(result.missingReason)
        assertTrue(result.missingReason!!.contains("不连续"))
        assertTrue(result.contributions.isEmpty())
    }

    @Test
    fun dividendAndSplitDaysAreExcludedFromTheAssetSeries() {
        val day1 = start
        val day2 = start.plusDays(1)
        val snapshots = listOf(snapshot(day1, "1000"), snapshot(day2, "900"))
        val positions = mapOf(
            day1 to listOf(position(day1, fund, "100", "1000")),
            day2 to listOf(position(day2, fund, "100", "900"))
        )
        val dividend = Trade(
            id = 1,
            asset = fund,
            side = TradeSide.CASH_DIVIDEND,
            day = day2.toString(),
            amount = BigDecimal("50"),
            units = null,
            price = null,
            fee = BigDecimal.ZERO,
            confirmed = true
        )

        val result = ContributionAnalytics.byBeginningOfDayWeight(snapshots, positions, emptyList(), listOf(dividend))

        assertNull(result.missingReason)
        assertTrue("除息日不应用单位价格解释收益", result.contributions.isEmpty())
        assertEquals(BigDecimal("-10.0000"), result.portfolioReturnPct)
        assertEquals(BigDecimal("-10.0000"), result.cashAndUnattributedPct)
    }

    private fun snapshot(day: LocalDate, total: String): PortfolioSnapshot {
        val value = BigDecimal(total)
        return PortfolioSnapshot(
            day = day,
            observedAt = "${day}T16:00:00Z",
            positionCount = 1,
            pricedPositionCount = 1,
            marketValue = value,
            cost = value,
            holdingProfit = BigDecimal.ZERO,
            realizedProfit = BigDecimal.ZERO,
            totalProfit = BigDecimal.ZERO,
            cashBalance = BigDecimal.ZERO,
            totalValue = value,
            externalNetFlow = BigDecimal.ZERO,
            valuationFresh = true,
            valid = true
        )
    }

    private fun position(day: LocalDate, asset: AssetInfo, units: String, marketValue: String) = PositionSnapshot(
        day = day,
        asset = asset,
        units = BigDecimal(units),
        cost = BigDecimal(marketValue),
        marketValue = BigDecimal(marketValue),
        holdingProfit = BigDecimal.ZERO,
        realizedProfit = BigDecimal.ZERO,
        quoteObservedAt = "${day}T16:00:00Z",
        quoteSource = "test"
    )
}
