package cn.market.assistant.domain.quant

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One named return series; the caller guarantees that every series is aligned by observation. */
data class ReturnSeries(val id: String, val name: String, val returns: List<Double>)

data class ShrunkCovariance(
    val assets: List<String>,
    val observationCount: Int,
    val shrinkage: Double,
    val covariance: List<List<Double>>,
    val annualizedVolatilityPct: Double
)

data class PortfolioWeights(
    val weights: List<Double>,
    val annualizedVolatilityPct: Double,
    val riskContributionsPct: List<Double>,
    val iterationCount: Int
)

/**
 * Ledoit-Wolf shrinkage covariance plus long-only minimum-variance weights.
 *
 * The research scripts already proved that covariance shrinkage and non-negative weights are the two
 * changes that survive out-of-sample; this object ports them to the phone so portfolio risk does not
 * depend on an inverted sample covariance from a few dozen observations.
 */
object CovarianceAnalytics {
    const val DEFAULT_MIN_OBSERVATIONS = 60
    const val DEFAULT_MAX_WEIGHT = 1.0

    /**
     * Shrinks the sample covariance toward `mu * I` (`mu` is the average sample variance) with the
     * analytical Ledoit-Wolf intensity. Inputs are decimal returns, not percentages.
     */
    fun ledoitWolf(
        series: List<ReturnSeries>,
        observationsPerYear: Int = 252,
        minimumObservations: Int = DEFAULT_MIN_OBSERVATIONS
    ): ShrunkCovariance? {
        require(observationsPerYear > 0) { "年化交易日必须为正数" }
        require(minimumObservations >= 2) { "最小样本数必须不少于 2" }
        if (series.size < 2) return null
        if (series.map { it.id }.distinct().size != series.size) return null
        val observationCount = series.first().returns.size
        if (observationCount < minimumObservations) return null
        if (series.any { it.returns.size != observationCount }) return null
        if (series.any { row -> row.returns.any { !it.isFinite() } }) return null

        val dimension = series.size
        val means = DoubleArray(dimension) { column -> series[column].returns.sum() / observationCount }
        val centered = Array(observationCount) { row ->
            DoubleArray(dimension) { column -> series[column].returns[row] - means[column] }
        }
        val sample = Array(dimension) { row -> DoubleArray(dimension) { column ->
            var sum = 0.0
            for (observation in 0 until observationCount) {
                sum += centered[observation][row] * centered[observation][column]
            }
            sum / observationCount
        } }
        val averageVariance = (0 until dimension).sumOf { sample[it][it] } / dimension
        val target = Array(dimension) { row -> DoubleArray(dimension) { column ->
            if (row == column) averageVariance else 0.0
        } }

        var squaredDistance = 0.0
        for (row in 0 until dimension) for (column in 0 until dimension) {
            val difference = sample[row][column] - target[row][column]
            squaredDistance += difference * difference
        }
        var noise = 0.0
        for (observation in 0 until observationCount) {
            for (row in 0 until dimension) for (column in 0 until dimension) {
                val element = centered[observation][row] * centered[observation][column]
                val difference = element - sample[row][column]
                noise += difference * difference
            }
        }
        noise /= (observationCount * observationCount).toDouble()
        val shrinkage = if (squaredDistance <= 1e-24) 1.0 else (noise / squaredDistance).coerceIn(0.0, 1.0)
        val covariance = Array(dimension) { row -> DoubleArray(dimension) { column ->
            shrinkage * target[row][column] + (1.0 - shrinkage) * sample[row][column]
        } }
        val weights = List(dimension) { 1.0 / dimension }
        val variance = weights.indices.sumOf { row ->
            weights.indices.sumOf { column -> covariance[row][column] * weights[row] * weights[column] }
        }.coerceAtLeast(0.0)
        return ShrunkCovariance(
            assets = series.map { it.id },
            observationCount = observationCount,
            shrinkage = shrinkage,
            covariance = covariance.map { it.toList() },
            annualizedVolatilityPct = sqrt(variance * observationsPerYear) * 100.0
        )
    }

    /**
     * Long-only minimum-variance weights for the given covariance matrix. Weights sum to one and the
     * per-asset cap is enforced by re-projecting the clipped mass onto the uncapped assets.
     */
    fun minimumVariance(
        covariance: List<List<Double>>,
        maxWeight: Double = DEFAULT_MAX_WEIGHT,
        maxIterations: Int = 5_000,
        tolerance: Double = 1e-10
    ): PortfolioWeights? {
        val dimension = covariance.size
        if (dimension == 0 || covariance.any { it.size != dimension }) return null
        if (covariance.any { row -> row.any { !it.isFinite() } }) return null
        require(maxWeight in (1.0 / dimension)..1.0) { "单资产权重上限必须位于 [1/n, 1]" }
        require(maxIterations > 0) { "迭代上限必须为正数" }

        val ridge = covarianceTrace(covariance) / dimension * 1e-10 + 1e-18
        val regularized = Array(dimension) { row -> DoubleArray(dimension) { column ->
            covariance[row][column] + if (row == column) ridge else 0.0
        } }
        val largestEigenvalue = powerIteration(regularized, iterations = 100)
        val step = 1.0 / (2.0 * max(largestEigenvalue, 1e-18))
        var weights = DoubleArray(dimension) { 1.0 / dimension }
        var iteration = 0
        var converged = false
        while (iteration < maxIterations) {
            iteration++
            val gradient = DoubleArray(dimension) { row ->
                2.0 * (0 until dimension).sumOf { column -> regularized[row][column] * weights[column] }
            }
            val candidates = DoubleArray(dimension) { index -> weights[index] - step * gradient[index] }
            val projected = projectSimplex(candidates, maxWeight)
            var maximumChange = 0.0
            for (index in 0 until dimension) {
                maximumChange = max(maximumChange, kotlin.math.abs(projected[index] - weights[index]))
            }
            weights = projected
            if (maximumChange <= tolerance) {
                converged = true
                break
            }
        }
        if (!converged) return null
        return summarize(covariance, weights.toList(), iteration)
    }

    /** Percentage contribution of every position to total portfolio variance. */
    fun summarize(
        covariance: List<List<Double>>,
        weights: List<Double>,
        iterationCount: Int = 0
    ): PortfolioWeights? {
        val dimension = covariance.size
        if (weights.size != dimension || covariance.any { it.size != dimension }) return null
        val variance = weights.indices.sumOf { row ->
            weights.indices.sumOf { column -> covariance[row][column] * weights[row] * weights[column] }
        }.coerceAtLeast(0.0)
        val marginal = List(dimension) { row ->
            weights.indices.sumOf { column -> covariance[row][column] * weights[column] }
        }
        val contributions = if (variance <= 1e-24) {
            List(dimension) { 0.0 }
        } else {
            marginal.mapIndexed { index, value -> weights[index] * value / variance * 100.0 }
        }
        return PortfolioWeights(
            weights = weights,
            annualizedVolatilityPct = sqrt(variance * 252.0) * 100.0,
            riskContributionsPct = contributions,
            iterationCount = iterationCount
        )
    }

    private fun covarianceTrace(covariance: List<List<Double>>): Double =
        covariance.indices.sumOf { covariance[it][it].coerceAtLeast(0.0) }

    /** Power iteration for the dominant eigenvalue of a symmetric positive semi-definite matrix. */
    private fun powerIteration(matrix: Array<DoubleArray>, iterations: Int): Double {
        val dimension = matrix.size
        var vector = DoubleArray(dimension) { 1.0 / sqrt(dimension.toDouble()) }
        var eigenvalue = 0.0
        repeat(iterations) {
            val next = DoubleArray(dimension) { row ->
                (0 until dimension).sumOf { column -> matrix[row][column] * vector[column] }
            }
            val norm = sqrt(next.sumOf { it * it })
            if (norm <= 1e-18) return@repeat
            eigenvalue = norm
            vector = DoubleArray(dimension) { next[it] / norm }
        }
        return eigenvalue
    }

    /** Euclidean projection onto `{w : sum(w) = 1, 0 <= w_i <= cap}`. */
    private fun projectSimplex(values: DoubleArray, cap: Double): DoubleArray {
        val dimension = values.size
        val result = DoubleArray(dimension)
        var remaining = 1.0
        var active = (0 until dimension).toMutableSet()
        var iterations = 0
        while (remaining > 1e-12 && active.isNotEmpty() && iterations < dimension + 1) {
            iterations++
            val weight = remaining / active.size
            val capped = active.filter { values[it] - weight > cap - 1e-12 || (values[it] - weight > 0.0 && cap <= weight) }
            if (capped.isEmpty()) break
            capped.forEach { index ->
                result[index] = cap
                remaining -= cap
                active.remove(index)
            }
        }
        if (active.isNotEmpty() && remaining > 0.0) {
            // Shifted projection: the simplex projection is monotone in the values, so adding a common
            // offset preserves the ranking and lets a bisection find the level that consumes `remaining`.
            val lowerSource = active.minOf { values[it] } - remaining
            val upperSource = active.maxOf { values[it] } + remaining
            var low = lowerSource
            var high = upperSource
            repeat(80) {
                val middle = (low + high) / 2.0
                val consumed = active.sumOf { (values[it] - middle).coerceIn(0.0, cap) }
                if (consumed > remaining) low = middle else high = middle
            }
            val level = (low + high) / 2.0
            active.forEach { index -> result[index] = (values[index] - level).coerceIn(0.0, cap) }
        }
        val total = result.sum()
        if (total <= 1e-12) return DoubleArray(dimension) { min(1.0 / dimension, cap) }
        return DoubleArray(dimension) { result[it] / total }
    }
}
