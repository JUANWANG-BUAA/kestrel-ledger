package cn.market.assistant.domain.quant

import kotlin.math.abs
import kotlin.math.max

/**
 * Non-negative elastic-net regression fitted by cyclic coordinate descent.
 *
 * Objective, scaled by the number of observations `n`:
 * `(1 / 2n) * ||y - Xb||^2 + alpha * l1Ratio * ||b||_1 + alpha * (1 - l1Ratio) / 2 * ||b||_2^2`
 * subject to `b >= 0`. The non-negative constraint keeps factor exposures interpretable: a bond or
 * theme factor can only contribute with a long position, so a negative fit is reported as no exposure
 * instead of a short that the underlying fund cannot take.
 */
object NonNegativeElasticNet {
    data class Fit(
        val coefficients: List<Double>,
        val intercept: Double,
        val iterationCount: Int,
        val converged: Boolean,
        val alpha: Double,
        val l1Ratio: Double
    ) {
        fun predict(row: List<Double>): Double = intercept + row.indices.sumOf { index ->
            (row.getOrNull(index) ?: 0.0) * (coefficients.getOrNull(index) ?: 0.0)
        }
    }

    /**
     * @param rows one observation per row, every row must have the same number of columns
     * @param responses one response per row
     * @param alpha overall regularization strength; `0.0` fits ordinary non-negative least squares
     * @param l1Ratio `1.0` is lasso, `0.0` is ridge, values in between are elastic net
     * @param fitIntercept center the response and columns before fitting
     */
    fun fit(
        rows: List<List<Double>>,
        responses: List<Double>,
        alpha: Double = 0.01,
        l1Ratio: Double = 1.0,
        fitIntercept: Boolean = true,
        maxIterations: Int = 1_000,
        tolerance: Double = 1e-9
    ): Fit? {
        require(alpha >= 0.0 && alpha.isFinite()) { "alpha 必须是非负有限数" }
        require(l1Ratio in 0.0..1.0) { "l1Ratio 必须位于 [0,1]" }
        require(maxIterations > 0) { "maxIterations 必须为正数" }
        require(tolerance > 0.0 && tolerance.isFinite()) { "tolerance 必须为正有限数" }
        val observationCount = rows.size
        if (observationCount < 2 || responses.size != observationCount) return null
        val featureCount = rows.first().size
        if (featureCount == 0) return null
        if (rows.any { it.size != featureCount }) return null
        if (rows.any { row -> row.any { !it.isFinite() } }) return null
        if (responses.any { !it.isFinite() }) return null

        val columnMeans = DoubleArray(featureCount) { column ->
            rows.sumOf { it[column] } / observationCount
        }
        val responseMean = responses.sumOf { it } / observationCount
        val centeredResponses = DoubleArray(observationCount) { index ->
            if (fitIntercept) responses[index] - responseMean else responses[index]
        }
        val centeredRows = Array(observationCount) { index ->
            DoubleArray(featureCount) { column ->
                if (fitIntercept) rows[index][column] - columnMeans[column] else rows[index][column]
            }
        }
        val columnNorm = DoubleArray(featureCount) { column ->
            centeredRows.sumOf { row -> row[column] * row[column] }
        }
        if (columnNorm.any { it <= 1e-18 }) {
            // A constant column carries no return information; the intercept already covers it.
            return null
        }

        val coefficients = DoubleArray(featureCount)
        val residuals = centeredResponses.copyOf()
        val l1Penalty = alpha * l1Ratio
        val l2Penalty = alpha * (1.0 - l1Ratio)
        var converged = false
        var iteration = 0
        while (iteration < maxIterations) {
            iteration++
            var maximumChange = 0.0
            for (column in 0 until featureCount) {
                val current = coefficients[column]
                var crossProduct = 0.0
                for (row in 0 until observationCount) {
                    crossProduct += centeredRows[row][column] * residuals[row]
                }
                crossProduct /= observationCount
                val covariance = crossProduct + current * columnNorm[column] / observationCount
                val denominator = columnNorm[column] / observationCount + l2Penalty
                val updated = max(0.0, (covariance - l1Penalty) / denominator)
                if (updated != current) {
                    val delta = updated - current
                    coefficients[column] = updated
                    for (row in 0 until observationCount) {
                        residuals[row] -= delta * centeredRows[row][column]
                    }
                    maximumChange = max(maximumChange, abs(delta))
                }
            }
            val scale = max(1.0, coefficients.maxOfOrNull { abs(it) } ?: 0.0)
            if (maximumChange <= tolerance * scale) {
                converged = true
                break
            }
        }

        val intercept = if (fitIntercept) {
            responseMean - columnMeans.indices.sumOf { column -> columnMeans[column] * coefficients[column] }
        } else 0.0
        return Fit(
            coefficients = coefficients.toList(),
            intercept = intercept,
            iterationCount = iteration,
            converged = converged,
            alpha = alpha,
            l1Ratio = l1Ratio
        )
    }
}
