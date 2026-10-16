package cn.market.assistant.domain.quant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRiskAnalyticsTest {
    @Test
    fun equalWeightReportsRiskAndContributions() {
        val firstReturns = List(80) { index -> ((index % 9) - 4) * 0.002 }
        val secondReturns = firstReturns.mapIndexed { index, value -> value * 0.8 + ((index % 5) - 2) * 0.0004 }
        val result = MarketRiskAnalytics.equalWeight(
            listOf(
                series("semi", "半导体", firstReturns),
                series("ai", "人工智能", secondReturns)
            )
        )

        assertTrue(result is RiskAnalysisResult.Ready)
        val summary = (result as RiskAnalysisResult.Ready).summary
        assertEquals(80, summary.observationCount)
        assertTrue(summary.annualizedVolatilityPct > 0.0)
        assertTrue(summary.ewmaVolatilityPct > 0.0)
        assertTrue(summary.covarianceShrinkage in 0.0..1.0)
        assertEquals(100.0, summary.contributions.sumOf { it.percent }, 1e-6)
        assertTrue((summary.highestCorrelation?.correlation ?: -1.0) > 0.9)
        assertTrue(summary.historicalCvar95Pct >= summary.historicalVar95Pct)
    }

    @Test
    fun insufficientHistoryIsExplicit() {
        val result = MarketRiskAnalytics.equalWeight(
            listOf(series("semi", "半导体", List(10) { 0.001 }))
        )

        assertEquals(RiskAnalysisResult.InsufficientHistory(10, 30), result)
    }

    @Test
    fun missingCloseDateDoesNotCreateAMultiSessionDailyReturn() {
        val dailyReturns = List(80) { index -> ((index % 9) - 4) * 0.002 }
        val full = series("semi", "半导体", dailyReturns)
        val gapDay = LocalDate.of(2026, 1, 1).plusDays(40).toString()
        val withGap = series("ai", "人工智能", dailyReturns).let { item ->
            item.copy(closesByDay = item.closesByDay - gapDay)
        }

        val result = MarketRiskAnalytics.equalWeight(listOf(full, withGap), minimumObservations = 2)

        assertTrue(result is RiskAnalysisResult.Ready)
        assertEquals(78, (result as RiskAnalysisResult.Ready).summary.observationCount)
    }

    private fun series(id: String, name: String, returns: List<Double>): PriceSeries {
        val closes = linkedMapOf<String, Double>()
        var price = 1.0
        closes[LocalDate.of(2026, 1, 1).toString()] = price
        returns.forEachIndexed { index, dailyReturn ->
            price *= 1.0 + dailyReturn
            closes[LocalDate.of(2026, 1, 1).plusDays((index + 1).toLong()).toString()] = price
        }
        return PriceSeries(id, name, closes)
    }
}
