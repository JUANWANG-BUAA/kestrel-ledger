package cn.market.assistant.domain.quant

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Online Hedge (exponential weights) ensemble over candidate estimate routes.
 *
 * Each round every expert predicts a percentage change; the ensemble compares the squared loss of
 * every expert against the realized change and down-weights the worse ones. Weights live in a plain
 * immutable state so they can be persisted in the encrypted ledger between sessions.
 *
 * The loss is normalized to `[0, 1]` before the exponential update, which is the requirement for the
 * standard Hedge regret bound and prevents a single large miss from wiping out an expert.
 */
object HedgeEnsemble {
    const val DEFAULT_LEARNING_RATE = 0.5
    const val DEFAULT_LOSS_SCALE_PCT = 1.0
    const val DEFAULT_FLOOR_WEIGHT = 0.02

    data class State(
        val experts: List<String>,
        val weights: List<Double>,
        val updatedThroughDay: String?,
        val roundCount: Int
    )

    data class Update(
        val state: State,
        val combinedPredictionPct: Double?,
        val expertLosses: Map<String, Double>,
        val skipped: Boolean
    )

    fun initialState(experts: List<String>): State {
        val distinct = experts.distinct()
        require(distinct.isNotEmpty()) { "Hedge 集成至少需要一个专家" }
        val uniform = 1.0 / distinct.size
        return State(distinct, List(distinct.size) { uniform }, null, 0)
    }

    /** Weighted average over the experts that produced a finite prediction. */
    fun combine(state: State, predictions: Map<String, Double>): Double? {
        var weightedSum = 0.0
        var weightTotal = 0.0
        state.experts.forEachIndexed { index, expert ->
            val prediction = predictions[expert]?.takeIf { it.isFinite() } ?: return@forEachIndexed
            val weight = state.weights.getOrElse(index) { 0.0 }
            weightedSum += weight * prediction
            weightTotal += weight
        }
        if (weightTotal <= 1e-12) return null
        return weightedSum / weightTotal
    }

    fun update(
        state: State,
        predictions: Map<String, Double>,
        actualPct: Double,
        day: String? = null,
        learningRate: Double = DEFAULT_LEARNING_RATE,
        lossScalePct: Double = DEFAULT_LOSS_SCALE_PCT,
        floorWeight: Double = DEFAULT_FLOOR_WEIGHT
    ): Update {
        require(learningRate > 0.0 && learningRate.isFinite()) { "学习率必须为正有限数" }
        require(lossScalePct > 0.0 && lossScalePct.isFinite()) { "损失尺度必须为正有限数" }
        require(floorWeight in 0.0..(1.0 / state.experts.size)) { "权重下限必须位于 [0, 1/专家数]" }
        val combined = combine(state, predictions)
        if (!actualPct.isFinite() || predictions.isEmpty()) {
            return Update(state, combined, emptyMap(), skipped = true)
        }

        val losses = mutableMapOf<String, Double>()
        state.experts.forEach { expert ->
            val prediction = predictions[expert]?.takeIf { it.isFinite() }
            losses[expert] = if (prediction == null) {
                1.0
            } else {
                (abs(prediction - actualPct) / lossScalePct).coerceIn(0.0, 1.0)
            }
        }
        val updated = state.experts.indices.map { index ->
            val expert = state.experts[index]
            state.weights[index] * exp(-learningRate * (losses[expert] ?: 1.0))
        }
        val floored = updated.map { max(it, floorWeight) }
        val total = floored.sum()
        val weights = if (total <= 1e-12) {
            List(state.experts.size) { 1.0 / state.experts.size }
        } else {
            floored.map { it / total }
        }
        return Update(
            state = State(state.experts, weights, day ?: state.updatedThroughDay, state.roundCount + 1),
            combinedPredictionPct = combined,
            expertLosses = losses,
            skipped = false
        )
    }
}
