package cn.market.assistant.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonNegativeElasticNetTest {
    @Test
    fun recoversNonNegativeCoefficientsFromNoiselessData() {
        val rows = (0 until 80).map { index ->
            listOf(1.0 + index % 5, 2.0 - (index % 4) * 0.25, (index % 3).toDouble())
        }
        val responses = rows.map { row -> 0.4 + 0.3 * row[0] + 0.5 * row[1] }

        val fit = NonNegativeElasticNet.fit(rows, responses, alpha = 0.0, maxIterations = 5_000)

        requireNotNull(fit)
        assertTrue(fit.converged)
        assertEquals(listOf(0.3, 0.5, 0.0), fit.coefficients.map { round(it) })
        assertEquals(0.4, fit.intercept, 1e-6)
    }

    @Test
    fun negativeSignalIsClippedToZeroInsteadOfFittingAShort() {
        val rows = (0 until 60).map { index -> listOf(index.toDouble(), (index % 7).toDouble()) }
        val responses = rows.map { row -> 1.0 - 0.02 * row[0] }

        val fit = NonNegativeElasticNet.fit(rows, responses, alpha = 0.0)

        requireNotNull(fit)
        assertEquals(0.0, fit.coefficients[0], 1e-9)
        assertTrue(fit.coefficients.all { it >= 0.0 })
    }

    @Test
    fun ridgeShrinksCoefficientsTowardZero() {
        val rows = (0 until 60).map { index -> listOf((index % 5).toDouble()) }
        val responses = rows.map { row -> 2.0 * row[0] }

        val weak = NonNegativeElasticNet.fit(rows, responses, alpha = 0.0)
        val strong = NonNegativeElasticNet.fit(rows, responses, alpha = 10.0)

        requireNotNull(weak)
        requireNotNull(strong)
        assertTrue(strong.coefficients[0] < weak.coefficients[0])
    }

    @Test
    fun rejectsMismatchedAndNonFiniteInput() {
        assertNull(NonNegativeElasticNet.fit(listOf(listOf(1.0), listOf(2.0)), listOf(1.0)))
        assertNull(NonNegativeElasticNet.fit(listOf(listOf(1.0), listOf(2.0)), listOf(Double.NaN, 1.0)))
        assertNull(NonNegativeElasticNet.fit(listOf(listOf(1.0, 2.0), listOf(1.0)), listOf(1.0, 2.0)))
    }

    private fun round(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0
}
