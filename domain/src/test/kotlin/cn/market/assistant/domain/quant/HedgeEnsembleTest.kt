package cn.market.assistant.domain.quant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HedgeEnsembleTest {
    private val experts = listOf("index-ols", "bond-ols", "holdings-ols")

    @Test
    fun initialStateIsUniform() {
        val state = HedgeEnsemble.initialState(experts)

        assertEquals(experts, state.experts)
        state.weights.forEach { assertEquals(1.0 / 3.0, it, 1e-12) }
        assertEquals(null, state.updatedThroughDay)
    }

    @Test
    fun accurateExpertGainsWeightAfterRepeatedRounds() {
        var state = HedgeEnsemble.initialState(experts)
        repeat(40) { round ->
            val actual = 0.8
            val predictions = mapOf(
                "index-ols" to actual,
                "bond-ols" to -actual,
                "holdings-ols" to 0.0
            )
            state = HedgeEnsemble.update(state, predictions, actual, day = "2026-03-${round + 1}").state
        }

        val accurate = state.weights[0]
        assertTrue("准专家权重应最高：$accurate", accurate > state.weights[1])
        assertTrue(accurate > state.weights[2])
        assertEquals(40, state.roundCount)
        assertEquals("2026-03-40", state.updatedThroughDay)
    }

    @Test
    fun combineIgnoresMissingExpertsAndRenormalizes() {
        val state = HedgeEnsemble.State(experts, listOf(0.5, 0.3, 0.2), null, 1)

        val combined = HedgeEnsemble.combine(state, mapOf("index-ols" to 1.0, "holdings-ols" to 3.0))

        assertEquals((0.5 * 1.0 + 0.2 * 3.0) / 0.7, combined!!, 1e-12)
        assertEquals(null, HedgeEnsemble.combine(state, emptyMap()))
    }

    @Test
    fun missingPredictionTakesTheMaximumLossAndNonFiniteActualIsSkipped() {
        val state = HedgeEnsemble.initialState(experts)

        val update = HedgeEnsemble.update(
            state,
            mapOf("index-ols" to 0.1, "bond-ols" to -0.1, "holdings-ols" to 0.0),
            actualPct = 0.1
        )
        assertTrue(update.state.weights[0] > update.state.weights[1])
        assertTrue(update.state.weights[1] < update.state.weights[2])

        val skipped = HedgeEnsemble.update(
            state,
            mapOf("index-ols" to 0.1),
            actualPct = Double.NaN
        )
        assertTrue(skipped.skipped)
        assertEquals(state, skipped.state)
    }

    @Test
    fun weightsStayNormalizedWithFloorAfterALargeMiss() {
        var state = HedgeEnsemble.initialState(experts)
        repeat(30) {
            state = HedgeEnsemble.update(
                state,
                mapOf("index-ols" to 10.0, "bond-ols" to 10.0, "holdings-ols" to 10.0),
                actualPct = 0.0
            ).state
        }

        assertNotNull(state.weights)
        assertEquals(1.0, state.weights.sum(), 1e-9)
        state.weights.forEach { assertTrue(it >= HedgeEnsemble.DEFAULT_FLOOR_WEIGHT - 1e-12) }
    }
}
