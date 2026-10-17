package cn.market.assistant.domain.fund

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** One period return; annualizedPct is intentionally null below one year. */
data class FundPeriodReturn(
    val label: String,
    val startDay: String,
    val endDay: String,
    val returnPct: Double?,
    val annualizedPct: Double?,
    val sampleDays: Int,
    val missingReason: String? = null
)

data class FundDrawdownStats(
    val maxDrawdownPct: Double?,
    val peakDay: String?,
    val troughDay: String?,
    val recoveryDay: String?,
    val currentDrawdownPct: Double?,
    val drawdownDays: Long?,
    val recoveryDays: Long?,
    val underwaterDays: Long?,
    val missingReason: String? = null
)

data class FundRiskStats(
    val annualizedVolatilityPct: Double?,
    val sharpe: Double?,
    val sortino: Double?,
    val calmar: Double?,
    val positiveDayRatioPct: Double?,
    val bestDayPct: Double?,
    val worstDayPct: Double?,
    val sampleDays: Int,
    val riskFreeRatePct: Double,
    val missingReason: String? = null
)

data class FundPerformanceReport(
    val basis: String,
    val pointCount: Int,
    val firstDay: String?,
    val lastDay: String?,
    val periodReturns: List<FundPeriodReturn>,
    val drawdown: FundDrawdownStats,
    val risk: FundRiskStats,
    val dataNote: String? = null
)

/**
 * Historical fund performance from the app's own NAV points.
 *
 * The fund's NAV return is deliberately separate from the investor's XIRR/TWR: this object never
 * looks at trades or cash flows. It prefers accumulated NAV when every point has one, because unit NAV
 * drops on distributions and would otherwise look like a loss.
 */
object FundPerformanceAnalytics {
    private val PERIODS = listOf(
        PeriodSpec("1M", 1),
        PeriodSpec("3M", 3),
        PeriodSpec("6M", 6),
        PeriodSpec("1Y", 12),
        PeriodSpec("3Y", 36),
        PeriodSpec("5Y", 60)
    )

    fun analyze(
        points: List<FundNavPoint>,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai")),
        riskFreeRatePct: Double = 0.0
    ): FundPerformanceReport? {
        val samples = points.asSequence()
            .mapNotNull { point ->
                val day = runCatching { LocalDate.parse(point.day) }.getOrNull() ?: return@mapNotNull null
                if (day.isAfter(today)) return@mapNotNull null
                val unit = point.unitNav.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
                val accumulated = point.accumulatedNav?.takeIf { it.isFinite() && it > 0 }
                NavSample(day, unit, accumulated)
            }
            .distinctBy { it.day }
            .sortedBy { it.day }
            .toList()
        if (samples.size < 2) return null

        val useAccumulated = samples.all { it.accumulatedNav != null }
        val values = samples.map { sample ->
            if (useAccumulated) sample.accumulatedNav!! else sample.unitNav
        }
        val basis = if (useAccumulated) "累计净值" else "单位净值"
        val periodReturns = PERIODS.map { period -> periodReturn(period, samples, values) } +
            inceptionReturn(samples, values)
        val drawdown = drawdown(samples, values)
        val risk = risk(samples, values, periodReturns, drawdown, riskFreeRatePct)
        val note = buildString {
            append("$basis 口径；${samples.size} 个观察点")
            if (samples.size < 252) append("，不足一年，1Y/3Y 年化可能不可用")
            if (samples.size >= 252) append("；风险窗口为近 252 个日收益")
            if (!useAccumulated) append("；累计净值缺失，分红/拆分可能影响收益")
        }
        return FundPerformanceReport(
            basis = basis,
            pointCount = samples.size,
            firstDay = samples.first().day.toString(),
            lastDay = samples.last().day.toString(),
            periodReturns = periodReturns,
            drawdown = drawdown,
            risk = risk,
            dataNote = note
        )
    }

    private fun periodReturn(spec: PeriodSpec, samples: List<NavSample>, values: List<Double>): FundPeriodReturn {
        val end = samples.last()
        val target = end.day.minusMonths(spec.months.toLong())
        val startIndex = samples.indexOfFirst { !it.day.isBefore(target) }
        if (startIndex < 0 || startIndex >= samples.lastIndex) {
            return FundPeriodReturn(spec.label, target.toString(), end.day.toString(), null, null, 0, "历史不足")
        }
        val start = samples[startIndex]
        // A weekend/holiday NAV can be a few days after the target; a month gap is not a 1M return.
        if (ChronoUnit.DAYS.between(target, start.day) > 10) {
            return FundPeriodReturn(spec.label, target.toString(), end.day.toString(), null, null, 0, "起点缺失")
        }
        val span = ChronoUnit.DAYS.between(start.day, end.day)
        val startValue = values[startIndex]
        val endValue = values.last()
        val returnPct = (endValue / startValue - 1.0) * 100.0
        val annualized = if (span >= 365) annualize(startValue, endValue, span) else null
        return FundPeriodReturn(spec.label, start.day.toString(), end.day.toString(), returnPct, annualized, (samples.lastIndex - startIndex + 1))
    }

    private fun inceptionReturn(samples: List<NavSample>, values: List<Double>): FundPeriodReturn {
        val span = ChronoUnit.DAYS.between(samples.first().day, samples.last().day)
        val returnPct = (values.last() / values.first() - 1.0) * 100.0
        return FundPeriodReturn(
            label = "成立以来",
            startDay = samples.first().day.toString(),
            endDay = samples.last().day.toString(),
            returnPct = returnPct,
            annualizedPct = if (span >= 365) annualize(values.first(), values.last(), span) else null,
            sampleDays = samples.size
        )
    }

    private fun drawdown(samples: List<NavSample>, values: List<Double>): FundDrawdownStats {
        var peakIndex = 0
        var maxDrawdown = 0.0
        var maxPeak = 0
        var maxTrough = 0
        for (index in values.indices) {
            if (values[index] > values[peakIndex]) peakIndex = index
            val dd = values[index] / values[peakIndex] - 1.0
            if (dd < maxDrawdown) {
                maxDrawdown = dd
                maxPeak = peakIndex
                maxTrough = index
            }
        }
        val currentPeak = values.indices.maxByOrNull { values[it] } ?: 0
        val current = values.last() / values[currentPeak] - 1.0
        val recoveryIndex = if (maxDrawdown < 0) {
            (maxTrough + 1 until values.size).firstOrNull { values[it] >= values[maxPeak] }
        } else null
        val drawdownDays = ChronoUnit.DAYS.between(samples[maxPeak].day, samples[maxTrough].day)
        val recoveryDays = recoveryIndex?.let { ChronoUnit.DAYS.between(samples[maxTrough].day, samples[it].day) }
        val underwaterEnd = recoveryIndex ?: samples.lastIndex
        val underwaterDays = ChronoUnit.DAYS.between(samples[maxPeak].day, samples[underwaterEnd].day)
        return FundDrawdownStats(
            maxDrawdownPct = abs(maxDrawdown) * 100.0,
            peakDay = samples[maxPeak].day.toString(),
            troughDay = samples[maxTrough].day.toString(),
            recoveryDay = recoveryIndex?.let { samples[it].day.toString() },
            currentDrawdownPct = abs(current) * 100.0,
            drawdownDays = drawdownDays,
            recoveryDays = recoveryDays,
            underwaterDays = underwaterDays
        )
    }

    private fun risk(
        samples: List<NavSample>,
        values: List<Double>,
        periods: List<FundPeriodReturn>,
        drawdown: FundDrawdownStats,
        riskFreeRatePct: Double
    ): FundRiskStats {
        val returns = values.zipWithNext { previous, next -> next / previous - 1.0 }
        // Keep volatility/Sharpe on the same trailing window as the 1Y annualized return. Using
        // full-history volatility with a 1Y return produces a number that looks precise but is not.
        val window = returns.takeLast(252)
        if (window.size < 60) {
            return FundRiskStats(null, null, null, null, null, null, null, window.size, riskFreeRatePct, "少于 60 个日收益，暂不计算波动/夏普")
        }
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / window.size
        val dailyVol = sqrt(variance)
        val annualVol = dailyVol * sqrt(252.0) * 100.0
        val annualReturn = periods.firstOrNull { it.label == "1Y" }?.annualizedPct
        val downside = window.map { minOf(0.0, it) }
        val downsideDev = sqrt(downside.sumOf { it * it } / downside.size) * sqrt(252.0) * 100.0
        val sharpe = annualReturn?.takeIf { annualVol > 1e-9 }?.let { (it - riskFreeRatePct) / annualVol }
        val sortino = annualReturn?.takeIf { downsideDev > 1e-9 }?.let { (it - riskFreeRatePct) / downsideDev }
        // Calmar is conventionally full-history CAGR over the full-history max drawdown, not the
        // trailing 1Y return divided by a drawdown that happened years earlier.
        val calmar = periods.lastOrNull { it.label == "成立以来" }?.annualizedPct?.let { annual ->
            drawdown.maxDrawdownPct?.takeIf { dd -> dd > 1e-9 }?.let { dd -> annual / dd }
        }
        return FundRiskStats(
            annualizedVolatilityPct = annualVol,
            sharpe = sharpe,
            sortino = sortino,
            calmar = calmar,
            positiveDayRatioPct = window.count { it > 0 } * 100.0 / window.size,
            bestDayPct = window.maxOrNull()?.times(100.0),
            worstDayPct = window.minOrNull()?.times(100.0),
            sampleDays = window.size,
            riskFreeRatePct = riskFreeRatePct
        )
    }

    private fun annualize(start: Double, end: Double, days: Long): Double? {
        if (start <= 0 || end <= 0 || days <= 0) return null
        val years = days / 365.25
        if (years <= 0) return null
        return ((end / start).pow(1.0 / years) - 1.0) * 100.0
    }

    private data class NavSample(val day: LocalDate, val unitNav: Double, val accumulatedNav: Double?)
    private data class PeriodSpec(val label: String, val months: Int)
}
