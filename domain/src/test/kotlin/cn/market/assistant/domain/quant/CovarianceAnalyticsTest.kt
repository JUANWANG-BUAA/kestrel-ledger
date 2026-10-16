package cn.market.assistant.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CovarianceAnalyticsTest {
    @Test
    fun ledoitWolfKeepsShrinkageInRangeAndCovarianceSymmetric() {
        val first = ReturnSeries("a", "甲", deterministicReturns(size = 120, period = 5, amplitude = 0.012))
        val second = ReturnSeries("b", "乙", deterministicReturns(size = 120, period = 9, amplitude = 0.008))

        val result = CovarianceAnalytics.ledoitWolf(listOf(first, second))

        requireNotNull(result)
        assertEquals(120, result.observationCount)
        assertTrue(result.shrinkage in 0.0..1.0)
        assertEquals(result.covariance[0][1], result.covariance[1][0], 1e-15)
        assertTrue(result.annualizedVolatilityPct.isFinite() && result.annualizedVolatilityPct >= 0.0)
    }

    @Test
    fun independentAssetsProduceBalancedMinimumVarianceWeights() {
        val first = ReturnSeries("a", "甲", deterministicReturns(size = 120, period = 1, amplitude = 0.01))
        val second = ReturnSeries("b", "乙", deterministicReturns(size = 120, period = 1, amplitude = 0.01, phase = 60.0))

        val shrunk = CovarianceAnalytics.ledoitWolf(listOf(first, second))
        requireNotNull(shrunk)
        val weights = CovarianceAnalytics.minimumVariance(shrunk.covariance)

        requireNotNull(weights)
        assertEquals(1.0, weights.weights.sum(), 1e-9)
        weights.weights.forEach { assertTrue(it >= -1e-12) }
        assertEquals(0.5, weights.weights[0], 0.15)
        assertEquals(100.0, weights.riskContributionsPct.sum(), 1e-6)
    }

    @Test
    fun maximumWeightCapIsRespected() {
        val series = (0 until 3).map { index ->
            ReturnSeries("asset-$index", "资产$index", deterministicReturns(size = 120, period = 3 + index, amplitude = 0.01 + index * 0.002))
        }

        val shrunk = CovarianceAnalytics.ledoitWolf(series)
        requireNotNull(shrunk)
        val weights = CovarianceAnalytics.minimumVariance(shrunk.covariance, maxWeight = 0.5)

        requireNotNull(weights)
        weights.weights.forEach { assertTrue("权重 $it 超过上限", it <= 0.5 + 1e-9) }
        assertEquals(1.0, weights.weights.sum(), 1e-9)
    }

    @Test
    fun insufficientHistoryAndBadInputAreRejected() {
        val short = ReturnSeries("a", "甲", List(20) { 0.001 })
        assertNull(CovarianceAnalytics.ledoitWolf(listOf(short, ReturnSeries("b", "乙", List(20) { 0.002 }))))
        assertNull(CovarianceAnalytics.ledoitWolf(listOf(ReturnSeries("a", "甲", List(80) { 0.001 }))))
        assertNull(CovarianceAnalytics.minimumVariance(emptyList()))
    }

    private fun deterministicReturns(
        size: Int,
        period: Int,
        amplitude: Double,
        phase: Double = 0.0
    ): List<Double> = List(size) { index ->
        kotlin.math.sin((index + phase) * period * 0.05) * amplitude
    }
}
