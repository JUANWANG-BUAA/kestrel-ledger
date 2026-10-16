package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.DayOfWeek
import kotlin.math.ceil
import kotlin.math.sqrt

data class MaximumDrawdownResult(
    val drawdownPct: BigDecimal? = null,
    val peakDay: LocalDate? = null,
    val troughDay: LocalDate? = null,
    val observationCount: Int = 0,
    val missingDays: List<LocalDate> = emptyList(),
    val missingReason: String? = null
)

data class AssetReturnCorrelation(
    val firstAssetName: String,
    val secondAssetName: String,
    val correlation: BigDecimal,
    val observationCount: Int
)

data class PortfolioRiskSummary(
    val startDay: LocalDate? = null,
    val endDay: LocalDate? = null,
    val observationCount: Int = 0,
    val annualizedVolatilityPct: BigDecimal? = null,
    val sharpeRatio: BigDecimal? = null,
    val sharpeMissingReason: String? = null,
    val sortinoRatio: BigDecimal? = null,
    val sortinoMissingReason: String? = null,
    val historicalVar95Pct: BigDecimal? = null,
    val historicalCvar95Pct: BigDecimal? = null,
    val historicalVar95Amount: BigDecimal? = null,
    val historicalCvar95Amount: BigDecimal? = null,
    val tailMissingReason: String? = null,
    val correlations: List<AssetReturnCorrelation> = emptyList(),
    val correlationMissingReason: String? = null,
    val missingReason: String? = null
)

/** Historical account and holding risk analytics; incomplete observation paths are rejected. */
object PortfolioRiskAnalytics {
    private const val TRADING_DAYS_PER_YEAR = 252.0
    private const val RISK_WINDOW_RETURNS = 252
    private const val MIN_RISK_RETURNS = 60
    private const val MIN_TAIL_RETURNS = 250
    private const val MIN_CORRELATION_RETURNS = 60
    private const val TAIL_PROBABILITY = 0.05

    /**
     * Describes the latest contiguous account return path. Annual rates are fractions (0.02 = 2%);
     * the defaults use a 0% risk-free rate and a 0% minimum acceptable return.
     */
    fun trailingSummary(
        asOf: LocalDate,
        snapshots: List<PortfolioSnapshot>,
        positionsByDay: Map<LocalDate, List<PositionSnapshot>>,
        trades: List<Trade>,
        cashEvents: List<AccountCashEvent>,
        riskFreeAnnualRate: BigDecimal = BigDecimal.ZERO,
        minimumAcceptableAnnualReturn: BigDecimal = BigDecimal.ZERO
    ): PortfolioRiskSummary {
        require(riskFreeAnnualRate > BigDecimal.ONE.negate()) { "无风险年利率必须大于 -100%" }
        require(minimumAcceptableAnnualReturn > BigDecimal.ONE.negate()) { "目标年收益率必须大于 -100%" }

        val snapshotsByDay = snapshots.asSequence()
            .filter { it.valid && !it.day.isAfter(asOf) }
            .groupBy { it.day }
            .mapValues { (_, sameDay) -> sameDay.maxBy { it.observedAt } }
        val lastWeekday = weekdayOnOrBefore(asOf)
        val closing = snapshotsByDay[lastWeekday]
            ?: return PortfolioRiskSummary(missingReason = "缺少最近交易日 $lastWeekday 的组合观察快照")
        if (!closing.accountComplete) {
            return PortfolioRiskSummary(endDay = closing.day, missingReason = "账户风险需要已启用现金跟踪且持仓估值完整")
        }
        if (!closing.valuationFresh) {
            return PortfolioRiskSummary(endDay = closing.day, missingReason = "最近交易日快照报价过期或不完整")
        }

        val descendingPath = mutableListOf<PortfolioSnapshot>()
        var cursor = lastWeekday
        var pathBreakReason: String? = null
        while (descendingPath.size < RISK_WINDOW_RETURNS + 1) {
            if (isWeekday(cursor)) {
                val snapshot = snapshotsByDay[cursor]
                if (snapshot == null) {
                    pathBreakReason = "缺少 $cursor 的连续交易日快照"
                    break
                }
                if (!snapshot.accountComplete) {
                    pathBreakReason = "$cursor 的账户现金或持仓估值不完整"
                    break
                }
                if (!snapshot.valuationFresh) {
                    pathBreakReason = "$cursor 的行情报价过期或不完整"
                    break
                }
                descendingPath += snapshot
            }
            cursor = cursor.minusDays(1)
        }
        val path = descendingPath.asReversed()
        val returns = mutableListOf<Pair<LocalDate, Double>>()
        for ((previous, current) in path.zipWithNext()) {
            val (fraction, reason) = PortfolioAnalytics.accountTwrFraction(previous, current, cashEvents)
            if (reason != null || fraction == null) {
                return PortfolioRiskSummary(
                    startDay = path.firstOrNull()?.day,
                    endDay = current.day,
                    observationCount = returns.size,
                    missingReason = reason ?: "${current.day} 的流量调整收益暂不可用"
                )
            }
            val dailyReturn = fraction.toDouble()
            if (!dailyReturn.isFinite() || dailyReturn < -1.0) {
                return PortfolioRiskSummary(
                    startDay = path.firstOrNull()?.day,
                    endDay = current.day,
                    observationCount = returns.size,
                    missingReason = "${current.day} 的账户收益低于 -100%，无法生成风险统计"
                )
            }
            returns += current.day to dailyReturn
        }

        if (returns.size < MIN_RISK_RETURNS) {
            val reason = pathBreakReason ?: "连续有效日收益不足"
            return PortfolioRiskSummary(
                startDay = path.firstOrNull()?.day,
                endDay = closing.day,
                observationCount = returns.size,
                missingReason = "$reason：${returns.size}/$MIN_RISK_RETURNS 个，至少需要 $MIN_RISK_RETURNS 个连续有效日收益"
            )
        }

        val values = returns.map { it.second }
        val mean = values.average()
        val variance = values.sumOf { value -> (value - mean) * (value - mean) } / (values.size - 1)
        val dailyVolatility = sqrt(variance.coerceAtLeast(0.0))
        val dailyRiskFree = annualToDaily(riskFreeAnnualRate.toDouble())
        val dailyTarget = annualToDaily(minimumAcceptableAnnualReturn.toDouble())
        val annualizedVolatility = roundedPct(dailyVolatility * sqrt(TRADING_DAYS_PER_YEAR))
        val sharpe = if (dailyVolatility > 0.0) {
            roundedRatio((mean - dailyRiskFree) / dailyVolatility * sqrt(TRADING_DAYS_PER_YEAR))
        } else null
        val downsideDeviation = sqrt(values.sumOf { value ->
            val downside = (value - dailyTarget).coerceAtMost(0.0)
            downside * downside
        } / values.size)
        val sortino = if (downsideDeviation > 0.0) {
            roundedRatio((mean - dailyTarget) / downsideDeviation * sqrt(TRADING_DAYS_PER_YEAR))
        } else null

        val tailMissing = if (returns.size < MIN_TAIL_RETURNS) {
            "95% 历史 VaR/CVaR 至少需要 $MIN_TAIL_RETURNS 个连续有效日收益，当前 ${returns.size} 个"
        } else null
        val sortedReturns = values.sorted()
        val tailCount = ceil(sortedReturns.size * TAIL_PROBABILITY).toInt().coerceAtLeast(1)
        val tailLosses = sortedReturns.take(tailCount).map { (-it).coerceAtLeast(0.0) }
        val var95 = if (tailMissing == null) tailLosses.last() else null
        val cvar95 = if (tailMissing == null) tailLosses.average() else null
        val currentValue = closing.totalValue?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        val correlations = assetCorrelations(path, positionsByDay, trades)

        return PortfolioRiskSummary(
            startDay = returns.first().first,
            endDay = closing.day,
            observationCount = returns.size,
            annualizedVolatilityPct = annualizedVolatility,
            sharpeRatio = sharpe,
            sharpeMissingReason = if (sharpe == null) "日收益标准差为零，Sharpe 不可定义" else null,
            sortinoRatio = sortino,
            sortinoMissingReason = if (sortino == null) "样本内没有目标收益以下的有效波动" else null,
            historicalVar95Pct = var95?.let(::roundedPct),
            historicalCvar95Pct = cvar95?.let(::roundedPct),
            historicalVar95Amount = var95?.let { currentValue?.let { value -> roundedMoney(value * it) } },
            historicalCvar95Amount = cvar95?.let { currentValue?.let { value -> roundedMoney(value * it) } },
            tailMissingReason = tailMissing,
            correlations = correlations.first,
            correlationMissingReason = correlations.second,
            missingReason = null
        )
    }

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

    private fun assetCorrelations(
        path: List<PortfolioSnapshot>,
        positionsByDay: Map<LocalDate, List<PositionSnapshot>>,
        trades: List<Trade>
    ): Pair<List<AssetReturnCorrelation>, String?> {
        val closingDay = path.lastOrNull()?.day
            ?: return emptyList<AssetReturnCorrelation>() to "缺少持仓快照"
        val currentAssets = positionsByDay[closingDay].orEmpty()
            .filter { it.units.signum() > 0 }
            .associateBy { it.asset.symbol }
            .values
            .sortedBy { it.asset.name }
        if (currentAssets.size < 2) return emptyList<AssetReturnCorrelation>() to "至少需要两项当前持仓才能计算相关性"

        val adjustedDaysByAsset = mutableMapOf<String, MutableSet<LocalDate>>()
        trades.asSequence().filter { it.confirmed && !it.isReversed }.forEach { trade ->
            val day = runCatching { LocalDate.parse(trade.day) }.getOrNull() ?: return@forEach
            val affectedAssets = when (trade.side) {
                TradeSide.CASH_DIVIDEND, TradeSide.DIVIDEND_REINVESTMENT, TradeSide.SHARE_SPLIT ->
                    listOf(trade.asset.symbol)
                TradeSide.ASSET_TRANSFER -> listOfNotNull(trade.asset.symbol, trade.relatedAsset?.symbol)
                else -> emptyList()
            }
            affectedAssets.forEach { assetId -> adjustedDaysByAsset.getOrPut(assetId) { mutableSetOf() }.add(day) }
        }

        val perAssetReturns = currentAssets.associate { currentAsset ->
            val assetId = currentAsset.asset.symbol
            val series = path.zipWithNext().mapNotNull { (previousSnapshot, currentSnapshot) ->
                if (currentSnapshot.day in adjustedDaysByAsset[assetId].orEmpty()) return@mapNotNull null
                val previous = positionsByDay[previousSnapshot.day].orEmpty().firstOrNull {
                    it.asset.symbol == assetId && it.units.signum() > 0
                } ?: return@mapNotNull null
                val current = positionsByDay[currentSnapshot.day].orEmpty().firstOrNull {
                    it.asset.symbol == assetId && it.units.signum() > 0
                } ?: return@mapNotNull null
                val previousValue = previous.marketValue ?: return@mapNotNull null
                val currentValue = current.marketValue ?: return@mapNotNull null
                val previousUnitPrice = previousValue.divide(previous.units, 18, RoundingMode.HALF_UP)
                val currentUnitPrice = currentValue.divide(current.units, 18, RoundingMode.HALF_UP)
                if (previousUnitPrice.signum() <= 0 || currentUnitPrice.signum() <= 0) return@mapNotNull null
                val value = currentUnitPrice.divide(previousUnitPrice, 18, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).toDouble()
                if (!value.isFinite() || value < -1.0) null else currentSnapshot.day to value
            }.toMap()
            assetId to series
        }

        val rows = mutableListOf<AssetReturnCorrelation>()
        val totalPairs = currentAssets.size * (currentAssets.size - 1) / 2
        for (firstIndex in 0 until currentAssets.lastIndex) {
            for (secondIndex in firstIndex + 1 until currentAssets.size) {
                val first = currentAssets[firstIndex]
                val second = currentAssets[secondIndex]
                val firstReturns = perAssetReturns[first.asset.symbol].orEmpty()
                val secondReturns = perAssetReturns[second.asset.symbol].orEmpty()
                val sharedDays = firstReturns.keys.intersect(secondReturns.keys).sorted()
                if (sharedDays.size < MIN_CORRELATION_RETURNS) continue
                val coefficient = pearsonCorrelation(
                    sharedDays.map { firstReturns.getValue(it) },
                    sharedDays.map { secondReturns.getValue(it) }
                ) ?: continue
                rows += AssetReturnCorrelation(
                    first.asset.name,
                    second.asset.name,
                    BigDecimal.valueOf(coefficient).setScale(3, RoundingMode.HALF_UP),
                    sharedDays.size
                )
            }
        }
        val ordered = rows.sortedByDescending { kotlin.math.abs(it.correlation.toDouble()) }.take(5)
        val reason = when {
            ordered.isEmpty() -> "持仓对之间至少需要 $MIN_CORRELATION_RETURNS 个共同有效日收益，且收益不能恒定"
            rows.size < totalPairs -> "部分持仓组合未达到 $MIN_CORRELATION_RETURNS 个共同有效日收益"
            else -> null
        }
        return ordered to reason
    }

    private fun pearsonCorrelation(first: List<Double>, second: List<Double>): Double? {
        if (first.size != second.size || first.size < 2) return null
        val firstMean = first.average()
        val secondMean = second.average()
        var covariance = 0.0
        var firstVariance = 0.0
        var secondVariance = 0.0
        first.indices.forEach { index ->
            val firstDelta = first[index] - firstMean
            val secondDelta = second[index] - secondMean
            covariance += firstDelta * secondDelta
            firstVariance += firstDelta * firstDelta
            secondVariance += secondDelta * secondDelta
        }
        val denominator = sqrt(firstVariance * secondVariance)
        if (denominator <= 0.0 || !denominator.isFinite()) return null
        return (covariance / denominator).coerceIn(-1.0, 1.0)
    }

    private fun annualToDaily(rate: Double): Double = Math.pow(1.0 + rate, 1.0 / TRADING_DAYS_PER_YEAR) - 1.0

    private fun roundedPct(value: Double): BigDecimal? = value.takeIf(Double::isFinite)?.let {
        BigDecimal.valueOf(it * 100.0).setScale(2, RoundingMode.HALF_UP)
    }

    private fun roundedRatio(value: Double): BigDecimal? = value.takeIf(Double::isFinite)?.let {
        BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP)
    }

    private fun roundedMoney(value: Double): BigDecimal? = value.takeIf(Double::isFinite)?.let {
        BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP)
    }

    private fun weekdayOnOrBefore(day: LocalDate): LocalDate = when (day.dayOfWeek) {
        DayOfWeek.SATURDAY -> day.minusDays(1)
        DayOfWeek.SUNDAY -> day.minusDays(2)
        else -> day
    }

    private fun isWeekday(day: LocalDate): Boolean = day.dayOfWeek.value <= DayOfWeek.FRIDAY.value
}
