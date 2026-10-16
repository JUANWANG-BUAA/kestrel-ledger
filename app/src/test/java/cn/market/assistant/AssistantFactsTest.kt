package cn.market.assistant

import cn.market.assistant.domain.assistant.AssistantDataSharingConsent
import cn.market.assistant.domain.assistant.AssistantEngine
import cn.market.assistant.domain.assistant.AssistantSessionState
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.portfolio.AccountCashBalance
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.ReportPeriod
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFactsTest {
    private val report = PeriodFacts(
        period = ReportPeriod.WEEK,
        start = LocalDate.parse("2026-10-12"),
        end = LocalDate.parse("2026-10-18"),
        opening = snapshot("2026-10-12", "10000"),
        closing = snapshot("2026-10-18", "10300"),
        profit = BigDecimal("200"),
        modifiedDietzPct = BigDecimal("2.04"),
        transactionCount = 3,
        buyAmount = BigDecimal("500"),
        sellAmount = BigDecimal("0"),
        missingReason = null,
        contributors = emptyList(),
        accountTwrPct = BigDecimal("2.10")
    )
    private val quote = MarketQuote(
        secid = "1.518880",
        name = "华安黄金 ETF",
        price = 5.1234,
        change = 0.01,
        changePct = 0.2,
        quoteTime = 1_763_000_000,
        fetchedAt = 1_763_000_060,
        source = "tencent"
    )
    private val position = Position(
        asset = AssetInfo("000001", "甲基金", AssetKind.FUND, "混合型"),
        units = BigDecimal("100.5"),
        cost = BigDecimal("1000"),
        realized = BigDecimal.ZERO,
        quote = BigDecimal("11.2"),
        quoteTime = "2026-10-18T07:00:00Z",
        quoteSource = "eastmoney"
    )

    @Test
    fun periodFactsAreAggregateAndCarryTheSnapshotObservationTime() {
        val facts = AssistantFacts.build(
            listOf(report),
            emptyMap(),
            emptyList(),
            AccountCashBalance(BigDecimal("500"), LocalDate.parse("2026-10-01"))
        )

        val twr = facts.single { it.key == "portfolio.report.week.account_twr_pct" }
        assertEquals(FactSensitivity.AGGREGATE_PORTFOLIO, twr.sensitivity)
        assertEquals("2026-10-18T16:00:00Z", twr.observedAt)
        assertEquals("本地快照与交易流水", twr.source)
        assertTrue(facts.any { it.key == "portfolio.report.week.range" && it.value == "2026-10-12~2026-10-18" })
        assertTrue(facts.any { it.key == "portfolio.report.week.cash_balance" })
    }

    @Test
    fun holdingAndQuoteFactsKeepTheirPrivacyBoundary() {
        val facts = AssistantFacts.build(
            listOf(report),
            mapOf(quote.secid to quote),
            listOf(position),
            AccountCashBalance(BigDecimal("500"), LocalDate.parse("2026-10-01"))
        )

        val holding = facts.single { it.key == "portfolio.holding.000001.market_value" }
        assertEquals(FactSensitivity.PRIVATE_DETAIL, holding.sensitivity)
        assertEquals("1125.6000", holding.value)
        assertEquals("eastmoney", holding.source)

        val price = facts.single { it.key == "market.quote.1.518880.price" }
        assertEquals(FactSensitivity.PUBLIC_MARKET, price.sensitivity)
        assertEquals("tencent", price.source)
        assertTrue(price.observedAt!!.startsWith("2025-"))
    }

    @Test
    fun assistantNarratesAggregateFactsButHidesPrivateOnesByDefault() {
        val facts = AssistantFacts.build(
            listOf(report),
            mapOf(quote.secid to quote),
            listOf(position),
            AccountCashBalance(BigDecimal("500"), LocalDate.parse("2026-10-01"))
        )

        val (_, aggregate) = AssistantEngine.ask(
            AssistantSessionState("test"),
            "我持有哪些资产",
            facts
        )
        assertTrue(aggregate.text.contains("2.10"))
        assertFalse(aggregate.text.contains("1125.60"))

        val (_, detailed) = AssistantEngine.ask(
            AssistantSessionState("test", AssistantDataSharingConsent.INCLUDE_PRIVATE_DETAILS),
            "我持有哪些资产",
            facts
        )
        assertTrue(detailed.text.contains("1125.6"))
    }

    @Test
    fun marketQuestionAnswersFromPublicQuoteFactsOnly() {
        val facts = AssistantFacts.build(emptyList(), mapOf(quote.secid to quote), listOf(position), null)

        val (_, reply) = AssistantEngine.ask(AssistantSessionState("test"), "黄金 ETF 什么价格", facts)

        assertTrue(reply.text.contains("market.quote.1.518880.price"))
        assertTrue(reply.text.contains("tencent"))
        assertFalse(reply.citations.any { it.factKey.startsWith("portfolio.holding") })
    }

    private fun snapshot(day: String, total: String): PortfolioSnapshot {
        val value = BigDecimal(total)
        return PortfolioSnapshot(
            day = LocalDate.parse(day),
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
            valuationFresh = true
        )
    }
}
