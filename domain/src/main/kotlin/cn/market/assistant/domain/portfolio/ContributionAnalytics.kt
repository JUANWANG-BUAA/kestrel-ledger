package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class AssetContribution(
    val assetId: String,
    val name: String,
    /** Average beginning-of-day weight over the days the asset could be measured. */
    val averageWeightPct: BigDecimal,
    /** Percentage points the asset added to the portfolio return. */
    val contributionPct: BigDecimal,
    val observationCount: Int,
    val isTop: Boolean = false
)

data class TimeWeightedContributionResult(
    val contributions: List<AssetContribution>,
    val portfolioReturnPct: BigDecimal?,
    /** Cash plus anything the per-asset path could not explain; never silently dropped. */
    val cashAndUnattributedPct: BigDecimal?,
    val startDay: LocalDate?,
    val endDay: LocalDate?,
    val observationCount: Int,
    val missingReason: String?
)

/**
 * Contribution by beginning-of-day weight, matching the documented rule: "贡献优先按每日期初实际
 * 持仓权重乘实际收益累积". Days with a dividend, reinvestment, split or transfer for an asset are
 * excluded from that asset's series because its unit price moves for accounting reasons, not market
 * reasons; the portfolio return itself still comes from the flow-adjusted account path.
 */
object ContributionAnalytics {
    private val HUNDRED = BigDecimal("100")
    private const val SCALE = 4
    private const val MAX_OBSERVATIONS = 400

    fun byBeginningOfDayWeight(
        snapshots: List<PortfolioSnapshot>,
        positionsByDay: Map<LocalDate, List<PositionSnapshot>>,
        cashEvents: List<AccountCashEvent>,
        trades: List<Trade>,
        topCount: Int = 5
    ): TimeWeightedContributionResult {
        require(topCount >= 0) { "topCount 不能为负数" }
        val path = snapshots.asSequence()
            .filter { it.valid }
            .groupBy { it.day }
            .mapValues { (_, sameDay) -> sameDay.maxBy { it.observedAt } }
            .values
            .sortedBy { it.day }
            .takeLast(MAX_OBSERVATIONS + 1)
            .toList()
        if (path.size < 2) {
            return empty("至少需要两个有效观察日才能计算贡献")
        }

        val adjustedDaysByAsset = adjustmentDays(trades)
        val accumulated = linkedMapOf<String, ContributionAccumulator>()
        var relativeWealth = 1.0
        path.zipWithNext().forEach { (previous, current) ->
            if (current.day != previous.day.plusDays(1)) {
                return empty(
                    "观察日不连续：缺少 ${previous.day.plusDays(1)} 的账户快照，贡献路径不完整",
                    startDay = path.first().day,
                    endDay = current.day,
                    observationCount = 0
                )
            }
            if (!previous.accountComplete || !current.accountComplete) {
                return empty("账户现金或持仓估值不完整，无法按日权重计算贡献", previous.day, current.day, 0)
            }
            if (!previous.valuationFresh || !current.valuationFresh) {
                return empty("行情报价过期或不完整，无法按日权重计算贡献", previous.day, current.day, 0)
            }
            val (fraction, reason) = PortfolioAnalytics.accountTwrFraction(previous, current, cashEvents)
            if (reason != null || fraction == null) {
                return empty(reason ?: "${current.day} 的流量调整收益暂不可用", previous.day, current.day, 0)
            }
            val portfolioReturn = fraction.toDouble()
            if (!portfolioReturn.isFinite() || portfolioReturn <= -1.0) {
                return empty("${current.day} 的账户收益不可用于贡献拆分", previous.day, current.day, 0)
            }
            relativeWealth *= 1.0 + portfolioReturn

            val openingTotal = previous.totalValue?.takeIf { it.signum() > 0 } ?: return empty(
                "缺少 ${previous.day} 的账户总资产", previous.day, current.day, 0
            )
            val openingPositions = positionsByDay[previous.day].orEmpty().associateBy { it.asset.symbol }
            val closingPositions = positionsByDay[current.day].orEmpty().associateBy { it.asset.symbol }
            openingPositions.values.forEach { opening ->
                val assetId = opening.asset.symbol
                if (current.day in adjustedDaysByAsset[assetId].orEmpty()) return@forEach
                val closing = closingPositions[assetId] ?: return@forEach
                if (opening.units.signum() <= 0 || closing.units.signum() <= 0) return@forEach
                val openingValue = opening.marketValue ?: return@forEach
                val closingValue = closing.marketValue ?: return@forEach
                val openingUnitPrice = openingValue.divide(opening.units, 18, RoundingMode.HALF_UP)
                val closingUnitPrice = closingValue.divide(closing.units, 18, RoundingMode.HALF_UP)
                if (openingUnitPrice.signum() <= 0 || closingUnitPrice.signum() <= 0) return@forEach
                val weight = openingValue.divide(openingTotal, 18, RoundingMode.HALF_UP).toDouble()
                val assetReturn = closingUnitPrice.divide(openingUnitPrice, 18, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).toDouble()
                if (!weight.isFinite() || !assetReturn.isFinite() || assetReturn <= -1.0) return@forEach
                val accumulator = accumulated.getOrPut(assetId) {
                    ContributionAccumulator(assetId, opening.asset.name)
                }
                accumulator.contribution += weight * assetReturn
                accumulator.weightSum += weight
                accumulator.observationCount += 1
            }
        }

        val portfolioReturnPct = (relativeWealth - 1.0) * 100.0
        val contributions = accumulated.values
            .map { accumulator -> accumulator.toResult() }
            .sortedByDescending { kotlin.math.abs(it.contributionPct.toDouble()) }
        val ranked = contributions.mapIndexed { index, contribution ->
            contribution.copy(isTop = index < topCount)
        }
        val attributed = contributions.sumOf { it.contributionPct }
        val cashAndUnattributed = BigDecimal.valueOf(portfolioReturnPct).setScale(SCALE, RoundingMode.HALF_UP)
            .subtract(attributed)
        return TimeWeightedContributionResult(
            contributions = ranked,
            portfolioReturnPct = BigDecimal.valueOf(portfolioReturnPct).setScale(SCALE, RoundingMode.HALF_UP),
            cashAndUnattributedPct = cashAndUnattributed,
            startDay = path.first().day,
            endDay = path.last().day,
            observationCount = path.size - 1,
            missingReason = null
        )
    }

    private fun adjustmentDays(trades: List<Trade>): Map<String, Set<LocalDate>> {
        val result = mutableMapOf<String, MutableSet<LocalDate>>()
        trades.asSequence().filter { it.confirmed && !it.isReversed }.forEach { trade ->
            val day = runCatching { LocalDate.parse(trade.day) }.getOrNull() ?: return@forEach
            val affected = when (trade.side) {
                TradeSide.CASH_DIVIDEND, TradeSide.DIVIDEND_REINVESTMENT, TradeSide.SHARE_SPLIT ->
                    listOf(trade.asset.symbol)
                TradeSide.ASSET_TRANSFER -> listOfNotNull(trade.asset.symbol, trade.relatedAsset?.symbol)
                else -> emptyList()
            }
            affected.forEach { assetId -> result.getOrPut(assetId) { mutableSetOf() }.add(day) }
        }
        return result
    }

    private fun empty(
        reason: String,
        startDay: LocalDate? = null,
        endDay: LocalDate? = null,
        observationCount: Int = 0
    ) = TimeWeightedContributionResult(
        contributions = emptyList(),
        portfolioReturnPct = null,
        cashAndUnattributedPct = null,
        startDay = startDay,
        endDay = endDay,
        observationCount = observationCount,
        missingReason = reason
    )

    private class ContributionAccumulator(val assetId: String, val name: String) {
        var contribution = 0.0
        var weightSum = 0.0
        var observationCount = 0

        fun toResult(): AssetContribution {
            val averageWeight = if (observationCount == 0) 0.0 else weightSum / observationCount * 100.0
            return AssetContribution(
                assetId = assetId,
                name = name,
                averageWeightPct = BigDecimal.valueOf(averageWeight).setScale(2, RoundingMode.HALF_UP),
                contributionPct = BigDecimal.valueOf(contribution * 100.0).setScale(SCALE, RoundingMode.HALF_UP),
                observationCount = observationCount
            )
        }
    }
}
