package cn.market.assistant.domain.portfolio

import cn.market.assistant.domain.market.MarketOhlcBar
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class BenchmarkSeries(
    val id: String,
    val name: String,
    val source: String,
    val bars: List<MarketOhlcBar>
)

data class PortfolioBenchmarkComparison(
    val benchmarkId: String? = null,
    val benchmarkName: String? = null,
    val benchmarkSource: String? = null,
    val benchmarkReturnPct: BigDecimal? = null,
    val accountExcessReturnPct: BigDecimal? = null,
    val startBarDay: LocalDate? = null,
    val endBarDay: LocalDate? = null,
    val missingReason: String? = null
)

/** Price return comparison against a named reference series; this is not a personalized benchmark. */
object PortfolioBenchmarkAnalytics {
    private const val MAX_BOUNDARY_AGE_DAYS = 7L
    private val HUNDRED = BigDecimal("100")

    fun compare(
        series: BenchmarkSeries?,
        periodStart: LocalDate,
        asOf: LocalDate,
        accountTwrPct: BigDecimal?
    ): PortfolioBenchmarkComparison {
        if (series == null) return PortfolioBenchmarkComparison(missingReason = "没有配置参考基准日线")
        val bars = series.bars.mapNotNull { bar ->
            val day = runCatching { LocalDate.parse(bar.day) }.getOrNull() ?: return@mapNotNull null
            if (!bar.close.isFinite() || bar.close <= 0.0) return@mapNotNull null
            day to BigDecimal.valueOf(bar.close)
        }.sortedBy { it.first }
        if (bars.map { it.first }.distinct().size != bars.size) {
            return PortfolioBenchmarkComparison(missingReason = "${series.name} 日线日期重复，无法比较")
        }
        val opening = bars.lastOrNull { it.first.isBefore(periodStart) }
            ?: return PortfolioBenchmarkComparison(missingReason = "缺少周期开始前的 ${series.name} 收盘价")
        val ending = bars.lastOrNull { !it.first.isAfter(asOf) }
            ?: return PortfolioBenchmarkComparison(missingReason = "缺少周期内的 ${series.name} 收盘价")
        if (ending.first <= opening.first) {
            return PortfolioBenchmarkComparison(missingReason = "${series.name} 周期内没有两个不同交易日的收盘价")
        }
        if (ChronoUnit.DAYS.between(opening.first, periodStart) > MAX_BOUNDARY_AGE_DAYS) {
            return PortfolioBenchmarkComparison(missingReason = "${series.name} 期初基准日线过旧")
        }
        if (ChronoUnit.DAYS.between(ending.first, asOf) > MAX_BOUNDARY_AGE_DAYS) {
            return PortfolioBenchmarkComparison(missingReason = "${series.name} 期末基准日线过旧")
        }
        val returnPct = ending.second.subtract(opening.second)
            .multiply(HUNDRED)
            .divide(opening.second, 8, RoundingMode.HALF_UP)
            .setScale(2, RoundingMode.HALF_UP)
        return PortfolioBenchmarkComparison(
            benchmarkId = series.id,
            benchmarkName = series.name,
            benchmarkSource = series.source,
            benchmarkReturnPct = returnPct,
            accountExcessReturnPct = accountTwrPct?.subtract(returnPct)?.setScale(2, RoundingMode.HALF_UP),
            startBarDay = opening.first,
            endBarDay = ending.first
        )
    }
}
