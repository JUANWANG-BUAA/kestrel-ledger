package cn.market.assistant.domain.quant

import kotlin.math.sqrt

/** Daily closing prices keyed by an ISO date. Kept independent of Android and storage DTOs. */
data class PriceSeries(
    val id: String,
    val name: String,
    val closesByDay: Map<String, Double>
)

data class RiskContribution(val id: String, val name: String, val percent: Double)

data class CorrelationPair(val first: String, val second: String, val correlation: Double)

data class EqualWeightRiskSummary(
    val observationCount: Int,
    val annualizedVolatilityPct: Double,
    val ewmaVolatilityPct: Double,
    val historicalVar95Pct: Double,
    val historicalCvar95Pct: Double,
    val covarianceShrinkage: Double,
    val contributions: List<RiskContribution>,
    val highestCorrelation: CorrelationPair?
)

sealed interface RiskAnalysisResult {
    data class Ready(val summary: EqualWeightRiskSummary) : RiskAnalysisResult
    data class InsufficientHistory(val available: Int, val required: Int) : RiskAnalysisResult
}

/** Historical risk for an equally weighted reference basket. This is descriptive, not a forecast. */
object MarketRiskAnalytics {
    fun equalWeight(
        series: List<PriceSeries>,
        observationsPerYear: Int = 252,
        minimumObservations: Int = 30,
        ewmaLambda: Double = 0.94
    ): RiskAnalysisResult {
        require(observationsPerYear > 0)
        require(minimumObservations >= 2)
        require(ewmaLambda in 0.0..<1.0)
        require(series.map { it.id }.distinct().size == series.size) { "风险序列 ID 必须唯一" }
        if (series.isEmpty()) return RiskAnalysisResult.InsufficientHistory(0, minimumObservations)

        val closesBySeries = series.map { item ->
            item.closesByDay.filterValues { it.isFinite() && it > 0.0 }.toSortedMap()
        }
        val validCloseDays = closesBySeries.map { it.keys }
        val commonCloseDays = validCloseDays.reduceOrNull(Set<String>::intersect).orEmpty().sorted()
        val dayIndexes = closesBySeries.map { closes -> closes.keys.withIndex().associate { it.value to it.index } }
        val alignedPairs = commonCloseDays.zipWithNext().filter { (previousDay, currentDay) ->
            dayIndexes.all { indexes -> indexes.getValue(currentDay) == indexes.getValue(previousDay) + 1 }
        }
        if (alignedPairs.size < minimumObservations) {
            return RiskAnalysisResult.InsufficientHistory(alignedPairs.size, minimumObservations)
        }

        val dailyReturns = alignedPairs.map { (previousDay, currentDay) ->
            closesBySeries.map { closes -> closes.getValue(currentDay) / closes.getValue(previousDay) - 1.0 }
        }
        val means = series.indices.map { column -> dailyReturns.sumOf { it[column] } / dailyReturns.size }
        val centered = dailyReturns.map { row -> row.indices.map { row[it] - means[it] } }
        val covariance = sampleCovariance(centered)
        val (shrunkCovariance, shrinkage) = shrinkTowardDiagonal(centered, covariance)
        val weight = 1.0 / series.size
        val portfolioVariance = shrunkCovariance.sumOf { row -> row.sumOf { it } * weight * weight }
            .coerceAtLeast(0.0)
        val annualVol = sqrt(portfolioVariance * observationsPerYear) * 100.0

        val covarianceWithBasket = shrunkCovariance.map { row -> row.sum() * weight }
        val contributions = series.indices.map { index ->
            val fraction = if (portfolioVariance == 0.0) 0.0 else weight * covarianceWithBasket[index] / portfolioVariance
            RiskContribution(series[index].id, series[index].name, fraction * 100.0)
        }.sortedByDescending { it.percent }

        val basketReturns = dailyReturns.map { row -> row.average() }
        val ordered = basketReturns.sorted()
        val tailCount = (ordered.size * 0.05).toInt().coerceAtLeast(1)
        val quantileIndex = ((ordered.size * 0.05).toInt()).coerceIn(0, ordered.lastIndex)
        val var95 = -ordered[quantileIndex] * 100.0
        val cvar95 = -ordered.take(tailCount).average() * 100.0

        return RiskAnalysisResult.Ready(
            EqualWeightRiskSummary(
                observationCount = dailyReturns.size,
                annualizedVolatilityPct = annualVol,
                ewmaVolatilityPct = ewmaVolatility(basketReturns, observationsPerYear, ewmaLambda),
                historicalVar95Pct = var95,
                historicalCvar95Pct = cvar95,
                covarianceShrinkage = shrinkage,
                contributions = contributions,
                highestCorrelation = highestCorrelation(series, dailyReturns)
            )
        )
    }

    private fun sampleCovariance(values: List<List<Double>>): Array<DoubleArray> {
        val columns = values.first().size
        val covariance = Array(columns) { DoubleArray(columns) }
        for (row in 0 until columns) for (column in 0 until columns) {
            covariance[row][column] = values.sumOf { it[row] * it[column] } / values.size
        }
        return covariance
    }

    /** Ledoit-Wolf shrinkage toward a diagonal covariance target. */
    private fun shrinkTowardDiagonal(centered: List<List<Double>>, sample: Array<DoubleArray>): Pair<Array<DoubleArray>, Double> {
        val dimension = sample.size
        if (dimension <= 1) return sample to 1.0
        var squaredOffDiagonal = 0.0
        var estimatedNoise = 0.0
        for (row in 0 until dimension) for (column in 0 until dimension) {
            if (row == column) continue
            squaredOffDiagonal += sample[row][column] * sample[row][column]
            val elementNoise = centered.sumOf { observation ->
                val deviation = observation[row] * observation[column] - sample[row][column]
                deviation * deviation
            } / (centered.size * centered.size)
            estimatedNoise += elementNoise
        }
        val shrinkage = if (squaredOffDiagonal <= 1e-24) 1.0 else (estimatedNoise / squaredOffDiagonal).coerceIn(0.0, 1.0)
        val result = Array(dimension) { row -> DoubleArray(dimension) { column ->
            if (row == column) sample[row][column]
            else (1.0 - shrinkage) * sample[row][column]
        } }
        return result to shrinkage
    }

    private fun ewmaVolatility(returns: List<Double>, observationsPerYear: Int, lambda: Double): Double {
        var variance = returns.first() * returns.first()
        returns.drop(1).forEach { value -> variance = lambda * variance + (1.0 - lambda) * value * value }
        return sqrt(variance.coerceAtLeast(0.0) * observationsPerYear) * 100.0
    }

    private fun highestCorrelation(
        series: List<PriceSeries>,
        dailyReturns: List<List<Double>>
    ): CorrelationPair? {
        if (series.size < 2) return null
        var highest: CorrelationPair? = null
        for (first in 0 until series.lastIndex) for (second in first + 1 until series.size) {
            val left = dailyReturns.map { it[first] }
            val right = dailyReturns.map { it[second] }
            val leftMean = left.average()
            val rightMean = right.average()
            val covariance = left.indices.sumOf { (left[it] - leftMean) * (right[it] - rightMean) }
            val leftVariance = left.sumOf { (it - leftMean) * (it - leftMean) }
            val rightVariance = right.sumOf { (it - rightMean) * (it - rightMean) }
            val denominator = sqrt(leftVariance * rightVariance)
            if (denominator == 0.0) continue
            val correlation = (covariance / denominator).coerceIn(-1.0, 1.0)
            if (highest == null || correlation > highest.correlation) {
                highest = CorrelationPair(series[first].name, series[second].name, correlation)
            }
        }
        return highest
    }
}
