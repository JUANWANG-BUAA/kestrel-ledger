package cn.market.assistant

import cn.market.assistant.domain.alert.AlertRule
import cn.market.assistant.domain.alert.AlertRuleKind
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.Position
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertObservationsTest {
    @Test
    fun missingHoldingQuoteSuppressesAccountProfitInsteadOfUsingZero() {
        val position = Position(
            asset = AssetInfo("FUND.TEST", "测试基金", AssetKind.FUND, "基金"),
            units = BigDecimal.ONE,
            cost = BigDecimal("100"),
            realized = BigDecimal.ZERO,
            quote = null,
            quoteTime = null
        )
        val rule = AlertRule("profit", AlertRuleKind.HOLDING_PROFIT_ABOVE, threshold = 5.0)
        val observation = AlertObservations.build(listOf(rule), emptyMap(), listOf(position)).getValue("profit")
        assertNull(observation.value)
    }

    @Test
    fun accountProfitIncludesRealizedAndUsesCompleteValuation() {
        val position = Position(
            asset = AssetInfo("FUND.TEST", "测试基金", AssetKind.FUND, "基金"),
            units = BigDecimal.ONE,
            cost = BigDecimal("100"),
            realized = BigDecimal("5"),
            quote = BigDecimal("120"),
            quoteTime = "2026-10-18T15:00:00+08:00",
            quoteSource = "test"
        )
        val rule = AlertRule("profit", AlertRuleKind.HOLDING_PROFIT_ABOVE, threshold = 5.0)
        val observation = AlertObservations.build(listOf(rule), emptyMap(), listOf(position)).getValue("profit")
        assertEquals(25.0, observation.value!!, 0.0001)
    }

    @Test
    fun staleRuleUsesOldestCachedQuote() {
        val older = quote("OLD", 1_000L)
        val newer = quote("NEW", 2_000L)
        val rule = AlertRule("stale", AlertRuleKind.QUOTE_STALE, threshold = 1.0)
        val observation = AlertObservations.build(
            rules = listOf(rule),
            quotes = mapOf("OLD" to older, "NEW" to newer),
            positions = emptyList(),
            nowMillis = 3_600_000L
        ).getValue("stale")
        assertEquals((3_600_000.0 - 1_000_000.0) / 3_600_000.0, observation.value!!, 0.0001)
        assertEquals(1_000_000L, observation.observedAtMillis)
    }

    private fun quote(secid: String, fetchedAt: Long) = MarketQuote(
        secid = secid,
        name = secid,
        price = 1.0,
        change = 0.0,
        changePct = 0.0,
        quoteTime = fetchedAt,
        fetchedAt = fetchedAt,
        source = "test"
    )
}
