package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class MaximumDrawdownResult(
    val drawdownPct: BigDecimal? = null,
    val peakDay: LocalDate? = null,
    val troughDay: LocalDate? = null,
    val observationCount: Int = 0,
    val missingDays: List<LocalDate> = emptyList(),
    val missingReason: String? = null
)

/** Flow-adjusted observed drawdown for an account; incomplete calendar paths are rejected. */
object PortfolioRiskAnalytics {
    fun maximumDrawdown(
        periodStart: LocalDate,
        asOf: LocalDate,
        snapshots: List<PortfolioSnapshot>,
        cashEvents: List<AccountCashEvent>
    ): MaximumDrawdownResult {
        val opening = snapshots.filter { it.day.isBefore(periodStart) }.maxByOrNull { it.day }
            ?: return MaximumDrawdownResult(missingReason = "缺少最大回撤起点快照")
        if (opening.day != periodStart.minusDays(1)) {
            return MaximumDrawdownResult(missingReason = "缺少 ${periodStart.minusDays(1)} 的回撤基准快照")
        }
        val periodSnapshots = snapshots.filter { !it.day.isBefore(periodStart) && !it.day.isAfter(asOf) }
            .groupBy { it.day }.mapValues { (_, sameDay) -> sameDay.maxBy { it.observedAt } }
        val closing = periodSnapshots.values.maxByOrNull { it.day }
            ?: return MaximumDrawdownResult(missingReason = "本周期没有观察快照，无法计算最大回撤")
        val expectedDays = generateSequence(periodStart) { previous -> previous.plusDays(1) }
            .takeWhile { !it.isAfter(closing.day) }.toList()
        val missingDays = expectedDays.filterNot(periodSnapshots::containsKey)
        if (missingDays.isNotEmpty()) {
            return MaximumDrawdownResult(
                missingDays = missingDays,
                missingReason = "本周期缺少 ${missingDays.size} 天快照，最大回撤路径不完整"
            )
        }

        var previous = opening
        var relativeWealth = BigDecimal.ONE
        var highWater = BigDecimal.ONE
        var peakDay = opening.day
        var troughDay = opening.day
        var maximumDrop = BigDecimal.ZERO
        expectedDays.forEach { day ->
            val current = requireNotNull(periodSnapshots[day])
            val (dailyReturn, reason) = PortfolioAnalytics.accountTwrFraction(previous, current, cashEvents)
            if (reason != null || dailyReturn == null) {
                return MaximumDrawdownResult(
                    observationCount = expectedDays.indexOf(day),
                    missingReason = reason ?: "${day} 的流量调整收益暂不可用"
                )
            }
            val growthFactor = BigDecimal.ONE + dailyReturn
            if (growthFactor.signum() < 0) {
                return MaximumDrawdownResult(
                    observationCount = expectedDays.indexOf(day),
                    missingReason = "${day} 的账户收益低于 -100%，无法计算回撤路径"
                )
            }
            relativeWealth = relativeWealth.multiply(growthFactor)
            if (relativeWealth > highWater) {
                highWater = relativeWealth
                peakDay = day
            }
            if (highWater.signum() > 0) {
                val drop = BigDecimal.ONE.subtract(relativeWealth.divide(highWater, 24, RoundingMode.HALF_UP))
                if (drop > maximumDrop) {
                    maximumDrop = drop
                    troughDay = day
                }
            }
            previous = current
        }
        return MaximumDrawdownResult(
            drawdownPct = maximumDrop.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
            peakDay = peakDay,
            troughDay = troughDay,
            observationCount = expectedDays.size
        )
    }
}
