package cn.market.assistant.domain.quant

import kotlin.math.abs
import kotlin.math.sqrt

data class ThemeExposure(
    val code: String,
    val name: String,
    /** Percentage move of the fund for a one-percent move of the theme factor. */
    val beta: Double,
    /** Average share of the fund's realized move explained by this factor over the sample. */
    val contributionPct: Double
)

data class ThemeExposureResult(
    val observationCount: Int,
    val coveragePct: Double,
    val exposures: List<ThemeExposure>,
    val interceptPct: Double,
    val rSquared: Double?,
    val residualMaePct: Double?,
    val residualAutocorrelation: Double?,
    val reason: String?
)

/**
 * Theme exposure regression used by the PCF-synthesized theme indices from the research pipeline.
 *
 * The fit is a non-negative elastic net so a theme can only be a long exposure; a fund that does not
 * hold a theme reports a zero beta instead of an offsetting short. Residual diagnostics are exposed
 * because synchronized peer residuals are the documented signal that the theme set is incomplete.
 */
object ThemeExposureAnalytics {
    const val DEFAULT_MIN_OBSERVATIONS = 60
    const val DEFAULT_MIN_COVERAGE_PCT = 80.0
    const val DEFAULT_ALPHA = 0.02
    const val DEFAULT_L1_RATIO = 0.5

    fun fit(
        fundReturnsPct: Map<String, Double>,
        themeReturnsPct: Map<String, Map<String, Double>>,
        themeNames: Map<String, String> = emptyMap(),
        minimumObservations: Int = DEFAULT_MIN_OBSERVATIONS,
        minimumCoveragePct: Double = DEFAULT_MIN_COVERAGE_PCT,
        alpha: Double = DEFAULT_ALPHA,
        l1Ratio: Double = DEFAULT_L1_RATIO
    ): ThemeExposureResult {
        val fundReturns = fundReturnsPct.filterValues { it.isFinite() }
        if (fundReturns.size < minimumObservations) {
            return empty("基金日收益样本 ${fundReturns.size} 个，少于 $minimumObservations 个")
        }
        if (themeReturnsPct.isEmpty()) return empty("没有可用的主题因子收益序列")
        val fundDays = fundReturns.keys.sorted()
        val usableThemes = themeReturnsPct.mapValues { (_, series) ->
            series.filterValues { it.isFinite() }
        }.filter { (code, series) ->
            val covered = fundDays.count { it in series }
            covered >= fundDays.size * minimumCoveragePct / 100.0 && series.isNotEmpty()
        }
        if (usableThemes.isEmpty()) return empty("主题因子覆盖率不足，没有可进入回归的因子")
        val usableDays = fundDays.filter { day -> usableThemes.values.all { day in it } }
        if (usableDays.size < minimumObservations) {
            return empty("主题因子与基金净值共同交易日 ${usableDays.size} 个，少于 $minimumObservations 个")
        }

        val factorCodes = usableThemes.keys.sorted()
        val rows = usableDays.map { day -> factorCodes.map { code -> usableThemes.getValue(code).getValue(day) } }
        val responses = usableDays.map { fundReturns.getValue(it) }
        val fitted = NonNegativeElasticNet.fit(
            rows = rows,
            responses = responses,
            alpha = alpha,
            l1Ratio = l1Ratio,
            fitIntercept = true
        ) ?: return empty("非负弹性网无法在当前样本上收敛")
        if (!fitted.converged) return empty("非负弹性网在最大迭代次数内没有收敛")

        val predictions = rows.map(fitted::predict)
        val meanResponse = responses.average()
        val totalSumOfSquares = responses.sumOf { (it - meanResponse) * (it - meanResponse) }
        val residualSumOfSquares = responses.indices.sumOf { index ->
            (responses[index] - predictions[index]) * (responses[index] - predictions[index])
        }
        val rSquared = if (totalSumOfSquares <= 1e-12) null else 1.0 - residualSumOfSquares / totalSumOfSquares
        val residualErrors = responses.indices.map { abs(responses[it] - predictions[it]) }
        val residualMae = if (residualErrors.isEmpty()) null else residualErrors.average()
        val residuals = usableDays.indices.associate { usableDays[it] to responses[it] - predictions[it] }

        val exposures = factorCodes.mapIndexed { index, code ->
            val beta = fitted.coefficients.getOrElse(index) { 0.0 }
            val averageFactorReturn = factorCodes.let { usableThemes.getValue(code).let { series ->
                usableDays.sumOf { series.getValue(it) } / usableDays.size
            } }
            ThemeExposure(
                code = code,
                name = themeNames[code] ?: code,
                beta = beta,
                contributionPct = beta * averageFactorReturn
            )
        }.sortedByDescending { abs(it.contributionPct) }

        return ThemeExposureResult(
            observationCount = usableDays.size,
            coveragePct = usableDays.size * 100.0 / fundDays.size,
            exposures = exposures,
            interceptPct = fitted.intercept,
            rSquared = rSquared?.takeIf { it.isFinite() },
            residualMaePct = residualMae,
            residualAutocorrelation = lagOneAutocorrelation(residuals),
            reason = null
        )
    }

    /** Signed residual per observation day; used to compare peers for missing-theme drift. */
    fun residualSeries(
        fundReturnsPct: Map<String, Double>,
        themeReturnsPct: Map<String, Map<String, Double>>,
        result: ThemeExposureResult,
        themeNames: Map<String, String> = emptyMap()
    ): Map<String, Double> {
        val fundReturns = fundReturnsPct.filterValues { it.isFinite() }
        val factors = result.exposures.filter { it.beta != 0.0 || it.contributionPct != 0.0 }
        if (factors.isEmpty()) return emptyMap()
        val usableDays = fundReturns.keys.filter { day ->
            factors.all { factor -> themeReturnsPct[factor.code]?.get(day)?.isFinite() == true }
        }.sorted()
        return usableDays.associateWith { day ->
            var predicted = result.interceptPct
            factors.forEach { factor ->
                predicted += factor.beta * (themeReturnsPct.getValue(factor.code).getValue(day))
            }
            fundReturns.getValue(day) - predicted
        }
    }

    /** Pearson correlation of two residual series on their shared days. */
    fun residualCorrelation(first: Map<String, Double>, second: Map<String, Double>): Double? {
        val sharedDays = first.keys.intersect(second.keys).filter { day ->
            first.getValue(day).isFinite() && second.getValue(day).isFinite()
        }.sorted()
        if (sharedDays.size < 20) return null
        val left = sharedDays.map { first.getValue(it) }
        val right = sharedDays.map { second.getValue(it) }
        val leftMean = left.average()
        val rightMean = right.average()
        var covariance = 0.0
        var leftVariance = 0.0
        var rightVariance = 0.0
        left.indices.forEach { index ->
            val leftDelta = left[index] - leftMean
            val rightDelta = right[index] - rightMean
            covariance += leftDelta * rightDelta
            leftVariance += leftDelta * leftDelta
            rightVariance += rightDelta * rightDelta
        }
        val denominator = sqrt(leftVariance * rightVariance)
        if (denominator <= 0.0 || !denominator.isFinite()) return null
        return (covariance / denominator).coerceIn(-1.0, 1.0)
    }

    private fun lagOneAutocorrelation(residuals: Map<String, Double>): Double? {
        val orderedDays = residuals.keys.sorted()
        if (orderedDays.size < 3) return null
        val current = orderedDays.map { residuals.getValue(it) }
        val lagged = current.drop(1)
        val lead = current.dropLast(1)
        val laggedMean = lagged.average()
        val leadMean = lead.average()
        var covariance = 0.0
        var laggedVariance = 0.0
        var leadVariance = 0.0
        lagged.indices.forEach { index ->
            val laggedDelta = lagged[index] - laggedMean
            val leadDelta = lead[index] - leadMean
            covariance += laggedDelta * leadDelta
            laggedVariance += laggedDelta * laggedDelta
            leadVariance += leadDelta * leadDelta
        }
        val denominator = sqrt(laggedVariance * leadVariance)
        if (denominator <= 0.0 || !denominator.isFinite()) return null
        return (covariance / denominator).coerceIn(-1.0, 1.0)
    }

    private fun empty(reason: String) = ThemeExposureResult(
        observationCount = 0,
        coveragePct = 0.0,
        exposures = emptyList(),
        interceptPct = 0.0,
        rSquared = null,
        residualMaePct = null,
        residualAutocorrelation = null,
        reason = reason
    )
}
