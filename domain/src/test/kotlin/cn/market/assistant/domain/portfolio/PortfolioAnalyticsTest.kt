package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioAnalyticsTest {
    private val asset = AssetInfo("FUND-1", "测试基金", AssetKind.FUND, "混合型")

    @Test
    fun capturesMissingQuotesAsUnknownInsteadOfZero() {
        val holding = Position(asset, BigDecimal("10"), BigDecimal("100"), BigDecimal.ZERO, null, null)
        val (snapshot, rows) = PortfolioAnalytics.capture(LocalDate.parse("2026-10-15"), "2026-10-15T08:00:00Z", listOf(holding))

        assertFalse(snapshot.complete)
        assertEquals(0, snapshot.pricedPositionCount)
        assertNull(snapshot.marketValue)
        assertNull(snapshot.totalProfit)
        assertNull(rows.single().holdingProfit)
    }

    @Test
    fun capturesAccountTotalOnlyWhenCashTrackingAndSecurityValuationAreComplete() {
        val holding = Position(asset, BigDecimal("10"), BigDecimal("100"), BigDecimal.ZERO,
            BigDecimal("12"), "2026-10-15T08:00:00Z", "MANUAL")
        val captured = PortfolioAnalytics.capture(LocalDate.parse("2026-10-15"), "2026-10-15T08:00:00Z",
            listOf(holding), BigDecimal("25"), BigDecimal.ZERO, valuationFresh = true).first
        val missing = PortfolioAnalytics.capture(LocalDate.parse("2026-10-15"), "2026-10-15T08:00:00Z",
            listOf(holding.copy(quote = null)), BigDecimal("25"), BigDecimal.ZERO, valuationFresh = true).first

        assertEquals(BigDecimal("145"), captured.totalValue)
        assertTrue(captured.accountComplete)
        assertNull(missing.totalValue)
        assertFalse(missing.accountComplete)
    }

    @Test
    fun computesPeriodProfitAndModifiedDietzFromSnapshotsAndFlows() {
        val openingDay = LocalDate.parse("2026-10-04")
        val closingDay = LocalDate.parse("2026-10-07")
        val openingPosition = Position(asset, BigDecimal("10"), BigDecimal("100"), BigDecimal.ZERO,
            BigDecimal("11"), "2026-10-04T07:00:00Z", "MANUAL")
        val closingPosition = Position(asset, BigDecimal("15"), BigDecimal("150"), BigDecimal.ZERO,
            BigDecimal("11"), "2026-10-07T07:00:00Z", "MANUAL")
        val (opening, openingRows) = PortfolioAnalytics.capture(openingDay, "2026-10-04T08:00:00Z", listOf(openingPosition))
        val (closing, closingRows) = PortfolioAnalytics.capture(closingDay, "2026-10-07T08:00:00Z", listOf(closingPosition))
        val buy = Trade(1, asset, TradeSide.BUY, "2026-10-06", BigDecimal("50"), BigDecimal("5"),
            BigDecimal("10"), BigDecimal.ZERO, true)
        val laterBuy = buy.copy(id = 2, day = "2026-10-08", amount = BigDecimal("90"))

        val facts = PortfolioAnalytics.facts(
            ReportPeriod.WEEK, closingDay.plusDays(2), listOf(opening, closing),
            mapOf(openingDay to openingRows, closingDay to closingRows), listOf(buy, laterBuy)
        )

        assertNull(facts.missingReason)
        assertEquals(BigDecimal("5"), facts.profit)
        assertEquals(BigDecimal("3.95"), facts.modifiedDietzPct)
        assertEquals(1, facts.transactionCount)
        assertEquals(BigDecimal("50"), facts.buyAmount)
        assertEquals("测试基金", facts.contributors.single().assetName)
        assertEquals(BigDecimal("5"), facts.contributors.single().amount)
    }

    @Test
    fun refusesAggregateProfitWhenEitherBoundarySnapshotIsIncomplete() {
        val firstDay = LocalDate.parse("2026-10-04")
        val lastDay = LocalDate.parse("2026-10-07")
        val completePosition = Position(asset, BigDecimal("10"), BigDecimal("100"), BigDecimal.ZERO,
            BigDecimal("11"), "2026-10-07T07:00:00Z", "MANUAL")
        val incompletePosition = completePosition.copy(quote = null, quoteTime = null, quoteSource = null)
        val opening = PortfolioAnalytics.capture(firstDay, "2026-10-04T08:00:00Z", listOf(completePosition)).first
        val closing = PortfolioAnalytics.capture(lastDay, "2026-10-07T08:00:00Z", listOf(incompletePosition)).first
        val facts = PortfolioAnalytics.facts(ReportPeriod.WEEK, lastDay, listOf(opening, closing), emptyMap(), emptyList())

        assertTrue(facts.missingReason?.contains("报价覆盖") == true)
        assertNull(facts.profit)
        assertNull(facts.modifiedDietzPct)
    }

    @Test
    fun openingPositionDoesNotCountAsPeriodTradeOrProfit() {
        val openingDay = LocalDate.parse("2026-10-14")
        val entryDay = LocalDate.parse("2026-10-15")
        val openingPosition = Position(asset, BigDecimal("10"), BigDecimal("100"), BigDecimal.ZERO,
            BigDecimal("10"), "2026-10-14T08:00:00Z", "MANUAL")
        val enteredPosition = openingPosition.copy(cost = BigDecimal("100"), quote = BigDecimal("12"),
            quoteTime = "2026-10-15T08:00:00Z")
        val (opening, openingRows) = PortfolioAnalytics.capture(openingDay, "2026-10-14T08:00:00Z", listOf(openingPosition))
        val (closing, closingRows) = PortfolioAnalytics.capture(entryDay, "2026-10-15T08:00:00Z", listOf(enteredPosition))
        val entry = Trade(7, asset, TradeSide.OPENING_POSITION, entryDay.toString(), BigDecimal("100"),
            BigDecimal("10"), BigDecimal("10"), BigDecimal.ZERO, true)

        val facts = PortfolioAnalytics.facts(ReportPeriod.DAY, entryDay, listOf(opening, closing),
            mapOf(openingDay to openingRows, entryDay to closingRows), listOf(entry))

        assertTrue(facts.missingReason?.contains("期初持仓") == true)
        assertNull(facts.profit)
        assertNull(facts.modifiedDietzPct)
        assertEquals(0, facts.transactionCount)
        assertEquals(BigDecimal.ZERO, facts.buyAmount)
        assertEquals(BigDecimal.ZERO, facts.sellAmount)
        assertTrue(facts.accountMissingReason?.contains("期初持仓") == true)
        assertNull(facts.accountProfit)
        assertNull(facts.accountTwrPct)
        assertTrue(facts.accountMaxDrawdownMissingReason?.contains("期初持仓") == true)
        assertNull(facts.accountMaxDrawdownPct)
    }

    @Test
    fun ignoresReversedTradesAndInvalidatedSnapshots() {
        val openingDay = LocalDate.parse("2026-10-14")
        val closingDay = LocalDate.parse("2026-10-15")
        val position = Position(asset, BigDecimal("10"), BigDecimal("100"), BigDecimal.ZERO,
            BigDecimal("11"), "2026-10-15T07:00:00Z", "MANUAL")
        val opening = PortfolioAnalytics.capture(openingDay, "2026-10-14T08:00:00Z", listOf(position)).first
        val (closing, closingRows) = PortfolioAnalytics.capture(closingDay, "2026-10-15T08:00:00Z", listOf(position))
        val invalidatedClosing = closing.copy(valid = false)
        val reversedBuy = Trade(9, asset, TradeSide.BUY, closingDay.toString(), BigDecimal("50"),
            BigDecimal("5"), BigDecimal("10"), BigDecimal.ZERO, true,
            reversedAt = "2026-10-15T09:00:00Z", reversalReason = "录入错误")

        val facts = PortfolioAnalytics.facts(
            ReportPeriod.DAY, closingDay, listOf(opening, invalidatedClosing),
            mapOf(openingDay to emptyList(), closingDay to closingRows), listOf(reversedBuy)
        )

        assertNull(facts.closing)
        assertNull(facts.profit)
        assertTrue(facts.missingReason?.contains("观察快照") == true)
        assertEquals(0, facts.transactionCount)
        assertEquals(BigDecimal.ZERO, facts.buyAmount)
    }

    @Test
    fun accountModifiedDietzRemovesExternalDepositsAndIgnoresInternalTrades() {
        val openingDay = LocalDate.parse("2026-10-01")
        val closingDay = LocalDate.parse("2026-10-11")
        val opening = accountSnapshot(openingDay, "1000", "100")
        val closing = accountSnapshot(closingDay, "1500", "100")
        val events = listOf(
            AccountCashEvent(1, LocalDate.parse("2026-10-06"), AccountCashEventType.DEPOSIT, BigDecimal("500")),
            AccountCashEvent(2, LocalDate.parse("2026-10-07"), AccountCashEventType.TRADE_BUY, BigDecimal("400"), transactionId = 7)
        )

        val result = PortfolioAnalytics.accountPerformance(opening, closing, events)

        assertEquals(BigDecimal.ZERO, result.first)
        assertEquals(BigDecimal.ZERO.setScale(2), result.second)
        assertNull(result.third)
    }

    @Test
    fun accountXirrUsesOpeningWealthAndClosingWealth() {
        val opening = accountSnapshot(LocalDate.parse("2025-01-01"), "1000", "0")
        val closing = accountSnapshot(LocalDate.parse("2026-01-01"), "1100", "0")

        val result = PortfolioAnalytics.accountXirr(opening, closing, emptyList())

        assertEquals(0.1, (result as XirrResult.Available).annualizedRate, 1e-6)
    }

    @Test
    fun accountTwrLinksReturnsAroundCapturedExternalFlowValuations() {
        val opening = accountSnapshot(LocalDate.parse("2026-01-01"), "1000", "0").copy(
            observedAt = "2026-01-01T09:00:00Z", valuationFresh = true
        )
        val closing = accountSnapshot(LocalDate.parse("2026-01-11"), "1650", "0").copy(
            observedAt = "2026-01-11T09:00:00Z", valuationFresh = true
        )
        val deposit = AccountCashEvent(
            id = 1, day = LocalDate.parse("2026-01-06"), type = AccountCashEventType.DEPOSIT,
            amount = BigDecimal("500"), occurredAt = "2026-01-06T09:00:01Z",
            portfolioValueBefore = BigDecimal("1100"), valuationComplete = true
        )
        val withdrawal = AccountCashEvent(
            id = 2, day = LocalDate.parse("2026-01-09"), type = AccountCashEventType.WITHDRAWAL,
            amount = BigDecimal("260"), occurredAt = "2026-01-09T09:00:01Z",
            portfolioValueBefore = BigDecimal("1760"), valuationComplete = true
        )

        val result = PortfolioAnalytics.accountTwr(opening, closing, listOf(deposit, withdrawal))

        assertEquals(BigDecimal("33.10"), result.first)
        assertNull(result.second)
    }

    @Test
    fun accountTwrRefusesFlowWithoutBoundaryValuation() {
        val opening = accountSnapshot(LocalDate.parse("2026-01-01"), "1000", "0").copy(
            observedAt = "2026-01-01T09:00:00Z", valuationFresh = true
        )
        val closing = accountSnapshot(LocalDate.parse("2026-01-11"), "1500", "0").copy(
            observedAt = "2026-01-11T09:00:00Z", valuationFresh = true
        )
        val deposit = AccountCashEvent(1, LocalDate.parse("2026-01-06"), AccountCashEventType.DEPOSIT,
            BigDecimal("500"), occurredAt = "2026-01-06T09:00:01Z")

        val result = PortfolioAnalytics.accountTwr(opening, closing, listOf(deposit))

        assertNull(result.first)
        assertTrue(result.second?.contains("估值不完整") == true)
    }

    @Test
    fun accountTwrWithoutExternalFlowsMatchesPortfolioReturn() {
        val opening = accountSnapshot(LocalDate.parse("2026-01-01"), "1000", "0").copy(
            observedAt = "2026-01-01T09:00:00Z", valuationFresh = true
        )
        val closing = accountSnapshot(LocalDate.parse("2026-01-11"), "1200", "0").copy(
            observedAt = "2026-01-11T09:00:00Z", valuationFresh = true
        )

        val result = PortfolioAnalytics.accountTwr(opening, closing, emptyList())

        assertEquals(BigDecimal("20.00"), result.first)
        assertNull(result.second)
    }

    private fun accountSnapshot(day: LocalDate, marketValue: String, cash: String) = PortfolioSnapshot(
        day = day, observedAt = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(), positionCount = 1, pricedPositionCount = 1,
        marketValue = BigDecimal(marketValue), cost = BigDecimal(marketValue), holdingProfit = BigDecimal.ZERO,
        realizedProfit = BigDecimal.ZERO, totalProfit = BigDecimal.ZERO, cashBalance = BigDecimal(cash),
        totalValue = BigDecimal(marketValue) + BigDecimal(cash), externalNetFlow = BigDecimal.ZERO,
        valuationFresh = true
    )
}
