package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import cn.market.assistant.domain.market.MarketOhlcBar

/** A point-in-time copy of one holding. Missing quotes stay missing; they are never filled with zero. */
data class PositionSnapshot(
    val day: LocalDate,
    val asset: AssetInfo,
    val units: BigDecimal,
    val cost: BigDecimal,
    val marketValue: BigDecimal?,
    val holdingProfit: BigDecimal?,
    val realizedProfit: BigDecimal,
    val quoteObservedAt: String?,
    val quoteSource: String?
) {
    val totalProfit: BigDecimal? get() = holdingProfit?.add(realizedProfit)
}

/** Portfolio totals for a day on which the app observed the local ledger. */
data class PortfolioSnapshot(
    val day: LocalDate,
    val observedAt: String,
    val positionCount: Int,
    val pricedPositionCount: Int,
    val marketValue: BigDecimal?,
    val cost: BigDecimal,
    val holdingProfit: BigDecimal?,
    val realizedProfit: BigDecimal,
    val totalProfit: BigDecimal?,
    val cashBalance: BigDecimal? = null,
    val totalValue: BigDecimal? = null,
    val externalNetFlow: BigDecimal? = null,
    val valuationFresh: Boolean = false,
    val valid: Boolean = true
) {
    val complete: Boolean get() = pricedPositionCount == positionCount
    val accountComplete: Boolean get() = complete && cashBalance != null && totalValue != null && externalNetFlow != null
    val coveragePct: Double get() = if (positionCount == 0) 100.0 else pricedPositionCount * 100.0 / positionCount
}

enum class ReportPeriod(val title: String) { DAY("今日"), WEEK("本周"), MONTH("本月") }

data class PeriodContribution(val assetName: String, val amount: BigDecimal)

enum class AllocationKind { FUND, GOLD, CASH }

data class PortfolioAllocation(
    val id: String,
    val name: String,
    val kind: AllocationKind,
    val marketValue: BigDecimal,
    val weightPct: BigDecimal
)

data class PortfolioAllocationChange(
    val id: String,
    val name: String,
    val changePercentagePoints: BigDecimal
)

data class PeriodFacts(
    val period: ReportPeriod,
    val start: LocalDate,
    val end: LocalDate,
    val opening: PortfolioSnapshot?,
    val closing: PortfolioSnapshot?,
    val profit: BigDecimal?,
    val modifiedDietzPct: BigDecimal?,
    val transactionCount: Int,
    val buyAmount: BigDecimal,
    val sellAmount: BigDecimal,
    val missingReason: String?,
    val contributors: List<PeriodContribution>,
    val accountProfit: BigDecimal? = null,
    val accountModifiedDietzPct: BigDecimal? = null,
    val accountMissingReason: String? = null,
    val accountXirr: XirrResult? = null,
    val accountTwrPct: BigDecimal? = null,
    val accountTwrMissingReason: String? = null,
    val accountMaxDrawdownPct: BigDecimal? = null,
    val accountMaxDrawdownPeakDay: LocalDate? = null,
    val accountMaxDrawdownTroughDay: LocalDate? = null,
    val accountMaxDrawdownObservationCount: Int = 0,
    val accountMaxDrawdownMissingDays: List<LocalDate> = emptyList(),
    val accountMaxDrawdownMissingReason: String? = null,
    val accountAllocations: List<PortfolioAllocation> = emptyList(),
    val accountAllocationChanges: List<PortfolioAllocationChange> = emptyList(),
    val accountAllocationHhi: BigDecimal? = null,
    val accountEffectiveAssetCount: BigDecimal? = null,
    val accountAllocationMissingReason: String? = null,
    val benchmarkReturnPct: BigDecimal? = null,
    val benchmarkName: String? = null,
    val benchmarkSource: String? = null,
    val benchmarkStartDay: LocalDate? = null,
    val benchmarkEndDay: LocalDate? = null,
    val accountExcessReturnPct: BigDecimal? = null,
    val benchmarkMissingReason: String? = null
)

/** Deterministic portfolio analytics shared by the daily, weekly and monthly report UI. */
object PortfolioAnalytics {
    fun capture(
        day: LocalDate,
        observedAt: String,
        positions: List<Position>,
        cashBalance: BigDecimal? = null,
        externalNetFlow: BigDecimal? = null,
        valuationFresh: Boolean = false
    ): Pair<PortfolioSnapshot, List<PositionSnapshot>> {
        val rows = positions.map { position ->
            PositionSnapshot(
                day = day,
                asset = position.asset,
                units = position.units,
                cost = position.cost,
                marketValue = position.value,
                holdingProfit = position.holdingProfit,
                realizedProfit = position.realized,
                quoteObservedAt = position.quoteTime,
                quoteSource = position.quoteSource
            )
        }
        val quoted = rows.count { it.marketValue != null }
        val complete = quoted == rows.size
        val marketValue = rows.takeIf { complete }?.fold(BigDecimal.ZERO) { sum, row -> sum + requireNotNull(row.marketValue) }
        val holdingProfit = rows.takeIf { complete }?.fold(BigDecimal.ZERO) { sum, row -> sum + requireNotNull(row.holdingProfit) }
        val cost = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.cost }
        val realized = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.realizedProfit }
        val snapshot = PortfolioSnapshot(
            day, observedAt, rows.size, quoted, marketValue, cost, holdingProfit, realized,
            holdingProfit?.add(realized), cashBalance,
            marketValue?.let { value -> cashBalance?.let(value::add) }, externalNetFlow, valuationFresh
        )
        return snapshot to rows
    }

    fun facts(
        period: ReportPeriod,
        asOf: LocalDate,
        snapshots: List<PortfolioSnapshot>,
        positionsByDay: Map<LocalDate, List<PositionSnapshot>>,
        trades: List<Trade>,
        cashEvents: List<AccountCashEvent> = emptyList(),
        benchmarkSeries: BenchmarkSeries? = null
    ): PeriodFacts {
        val validSnapshots = snapshots.filter { it.valid }
        val validSnapshotDays = validSnapshots.mapTo(mutableSetOf()) { it.day }
        val validPositionsByDay = positionsByDay.filterKeys { it in validSnapshotDays }
        val activeTrades = trades.filterNot { it.isReversed }
        val start = when (period) {
            ReportPeriod.DAY -> asOf
            ReportPeriod.WEEK -> asOf.minusDays((asOf.dayOfWeek.value - 1).toLong())
            ReportPeriod.MONTH -> asOf.withDayOfMonth(1)
        }
        val opening = validSnapshots.filter { it.day.isBefore(start) }.maxByOrNull { it.day }
        val closing = validSnapshots.filter { !it.day.isBefore(start) && !it.day.isAfter(asOf) }.maxByOrNull { it.day }
        val cutoff = closing?.day ?: asOf
        val periodTrades = activeTrades.filter { trade ->
            trade.confirmed && runCatching { LocalDate.parse(trade.day) }.getOrNull()?.let {
                opening != null && it.isAfter(opening.day) && !it.isAfter(cutoff)
            } == true
        }
        val openingPositionAdded = periodTrades.any { it.side == TradeSide.OPENING_POSITION }
        val activityTrades = periodTrades.filter { it.side != TradeSide.OPENING_POSITION }
        val buys = activityTrades.filter { it.side == TradeSide.BUY }.fold(BigDecimal.ZERO) { sum, trade -> sum + trade.amount }
        val sells = activityTrades.filter { it.side == TradeSide.SELL }.fold(BigDecimal.ZERO) { sum, trade -> sum + trade.amount }

        val reason = when {
            openingPositionAdded -> "本周期录入了期初持仓，周期盈亏从下一期快照开始计算"
            opening == null -> "缺少周期开始前的基准快照"
            closing == null -> "本周期还没有观察快照"
            !opening.complete -> "基准快照报价覆盖 ${opening.pricedPositionCount}/${opening.positionCount}，无法核算总盈亏"
            !closing.complete -> "最新快照报价覆盖 ${closing.pricedPositionCount}/${closing.positionCount}，无法核算总盈亏"
            opening.totalProfit == null || closing.totalProfit == null -> "快照缺少可核算的盈亏数据"
            else -> null
        }
        val profit = if (reason == null) requireNotNull(closing?.totalProfit) - requireNotNull(opening?.totalProfit) else null
        val dietz = if (reason == null) modifiedDietz(profit!!, opening!!, closing!!, periodTrades) else null
        val accountPerformance = if (openingPositionAdded) {
            Triple(null, null, "本周期录入了期初持仓，账户收益从下一期快照开始计算")
        } else accountPerformance(opening, closing, cashEvents)
        val accountXirr = if (openingPositionAdded) null else accountXirr(opening, closing, cashEvents)
        val accountTwr = if (openingPositionAdded) {
            null to "本周期录入了期初持仓，账户 TWR 从下一期快照开始计算"
        } else accountTwr(opening, closing, cashEvents)
        val maxDrawdown = if (openingPositionAdded) {
            MaximumDrawdownResult(missingReason = "本周期录入了期初持仓，最大回撤从下一期快照开始计算")
        } else {
            PortfolioRiskAnalytics.maximumDrawdown(
                periodStart = start, asOf = asOf, snapshots = validSnapshots, cashEvents = cashEvents
            )
        }
        val allocation = if (period == ReportPeriod.DAY &&
            (opening?.day != start.minusDays(1) || closing?.day != asOf)) {
            PortfolioAllocationResult(missingReason = "日报配置变化需要昨日和今日的快照")
        } else {
            PortfolioAllocationAnalytics.compare(opening, closing, validPositionsByDay)
        }
        val benchmark = if (period == ReportPeriod.DAY) {
            PortfolioBenchmarkComparison(missingReason = "基准比较仅用于周报和月报")
        } else {
            PortfolioBenchmarkAnalytics.compare(benchmarkSeries, start, asOf, accountTwr.first)
        }
        val contributions = if (reason == null) {
            val before = validPositionsByDay[opening!!.day].orEmpty().associateBy { it.asset.symbol }
            val after = validPositionsByDay[closing!!.day].orEmpty().associateBy { it.asset.symbol }
            (before.keys + after.keys).mapNotNull { assetId ->
                val old = before[assetId]?.totalProfit ?: BigDecimal.ZERO
                val new = after[assetId]?.totalProfit ?: BigDecimal.ZERO
                val name = after[assetId]?.asset?.name ?: before[assetId]?.asset?.name ?: assetId
                PeriodContribution(name, new - old)
            }.sortedByDescending { it.amount }
        } else emptyList()
        return PeriodFacts(period, start, asOf, opening, closing, profit, dietz, activityTrades.size, buys, sells, reason, contributions,
            accountPerformance.first, accountPerformance.second, accountPerformance.third, accountXirr,
            accountTwr.first, accountTwr.second, maxDrawdown.drawdownPct, maxDrawdown.peakDay, maxDrawdown.troughDay,
            maxDrawdown.observationCount, maxDrawdown.missingDays, maxDrawdown.missingReason,
            allocation.closing, allocation.changes, allocation.hhi, allocation.effectiveAssetCount, allocation.missingReason,
            benchmark.benchmarkReturnPct, benchmark.benchmarkName, benchmark.benchmarkSource,
            benchmark.startBarDay, benchmark.endBarDay, benchmark.accountExcessReturnPct, benchmark.missingReason)
    }

    /** Links subperiod returns at captured external-flow valuation boundaries. */
    fun accountTwr(
        opening: PortfolioSnapshot?,
        closing: PortfolioSnapshot?,
        cashEvents: List<AccountCashEvent>
    ): Pair<BigDecimal?, String?> {
        val (fraction, missing) = accountTwrFraction(opening, closing, cashEvents)
        val percentage = fraction?.multiply(BigDecimal("100"))?.setScale(2, RoundingMode.HALF_UP)
        return percentage to missing
    }

    /** Unrounded TWR fraction shared by report display and path-dependent risk metrics. */
    internal fun accountTwrFraction(
        opening: PortfolioSnapshot?,
        closing: PortfolioSnapshot?,
        cashEvents: List<AccountCashEvent>
    ): Pair<BigDecimal?, String?> {
        val missing = when {
            opening == null -> "缺少 TWR 周期起点快照"
            closing == null -> "缺少 TWR 周期终点快照"
            !opening.accountComplete || !closing.accountComplete -> "TWR 需要现金和全部持仓估值完整的边界快照"
            !opening.valuationFresh || !closing.valuationFresh -> "TWR 边界快照报价已过期或不完整"
            closing.day <= opening.day -> "TWR 需要两个不同日期的账户估值"
            else -> null
        }
        if (missing != null) return null to missing
        val start = requireNotNull(opening)
        val end = requireNotNull(closing)
        val startTime = runCatching { Instant.parse(start.observedAt) }.getOrNull()
            ?: return null to "TWR 起点缺少有效观测时间"
        val endTime = runCatching { Instant.parse(end.observedAt) }.getOrNull()
            ?: return null to "TWR 终点缺少有效观测时间"
        val externalEvents = cashEvents.filter {
            it.type == AccountCashEventType.DEPOSIT || it.type == AccountCashEventType.WITHDRAWAL
        }
        val ambiguous = externalEvents.any { event ->
            event.occurredAt == null && !event.day.isBefore(start.day) && !event.day.isAfter(end.day)
        }
        if (ambiguous) return null to "有外部资金流水缺少精确时间，无法定位 TWR 子区间"
        val invalidTime = externalEvents.any { event ->
            event.occurredAt != null && runCatching { Instant.parse(event.occurredAt) }.isFailure &&
                !event.day.isBefore(start.day) && !event.day.isAfter(end.day)
        }
        if (invalidTime) return null to "有外部资金流水包含无效的发生时间"
        val flows = externalEvents.mapNotNull { event ->
            val time = event.occurredAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            if (time.isAfter(startTime) && !time.isAfter(endTime)) event to time else null
        }.sortedWith(compareBy<Pair<AccountCashEvent, Instant>> { it.second }.thenBy { it.first.id })

        var previousValue = requireNotNull(start.totalValue)
        if (previousValue.signum() <= 0) return null to "TWR 起点总资产必须大于零"
        var growth = BigDecimal.ONE
        flows.forEach { (event, _) ->
            if (!event.valuationComplete) return null to "资金流 ${event.day} 前的持仓估值不完整或报价已过期"
            val beforeFlow = event.portfolioValueBefore ?: return null to "资金流缺少发生前的账户估值"
            if (beforeFlow.signum() <= 0) return null to "资金流发生前账户总资产必须大于零"
            growth = growth.multiply(beforeFlow.divide(previousValue, 20, RoundingMode.HALF_UP))
            previousValue = beforeFlow + event.balanceDelta
            if (previousValue.signum() <= 0) return null to "资金流后账户总资产不大于零，无法计算 TWR"
        }
        val endingValue = requireNotNull(end.totalValue)
        val growthFactor = growth.multiply(endingValue.divide(previousValue, 24, RoundingMode.HALF_UP))
        return growthFactor.subtract(BigDecimal.ONE) to null
    }

    fun accountXirr(
        opening: PortfolioSnapshot?,
        closing: PortfolioSnapshot?,
        cashEvents: List<AccountCashEvent>
    ): XirrResult? {
        if (opening?.accountComplete != true || closing?.accountComplete != true || closing.day <= opening.day) return null
        if (!opening.valuationFresh || !closing.valuationFresh) {
            return XirrResult.Unavailable(XirrFailureReason.INCOMPLETE_VALUATION)
        }
        val flows = buildList {
            add(CashFlow(opening.day, requireNotNull(opening.totalValue).negate()))
            cashEvents.asSequence()
                .filter { it.type == AccountCashEventType.DEPOSIT || it.type == AccountCashEventType.WITHDRAWAL }
                .filter { it.day.isAfter(opening.day) && !it.day.isAfter(closing.day) }
                .forEach { event -> add(CashFlow(event.day, requireNotNull(event.investorCashFlow))) }
            add(CashFlow(closing.day, requireNotNull(closing.totalValue)))
        }
        return XirrCalculator.calculate(flows)
    }

    fun accountPerformance(
        opening: PortfolioSnapshot?,
        closing: PortfolioSnapshot?,
        cashEvents: List<AccountCashEvent>
    ): Triple<BigDecimal?, BigDecimal?, String?> {
        val missing = when {
            opening == null -> "缺少现金跟踪起点之前的基准快照"
            closing == null -> "本周期还没有观察快照"
            !opening.accountComplete -> "基准快照未包含完整持仓估值和现金余额"
            !closing.accountComplete -> "最新快照未包含完整持仓估值和现金余额"
            !opening.valuationFresh -> "基准快照报价超过 72 小时或不完整，无法核算账户收益"
            !closing.valuationFresh -> "最新快照报价超过 72 小时或不完整，无法核算账户收益"
            closing.day <= opening.day -> "周期至少需要两个不同日期的完整账户快照"
            else -> null
        }
        if (missing != null) return Triple(null, null, missing)
        val start = requireNotNull(opening)
        val end = requireNotNull(closing)
        val flows = cashEvents.filter { event ->
            event.type == AccountCashEventType.DEPOSIT || event.type == AccountCashEventType.WITHDRAWAL
        }.filter { it.day.isAfter(start.day) && !it.day.isAfter(end.day) }
        val netFlow = flows.fold(BigDecimal.ZERO) { sum, event -> sum + event.balanceDelta }
        val profit = requireNotNull(end.totalValue) - requireNotNull(start.totalValue) - netFlow
        val days = ChronoUnit.DAYS.between(start.day, end.day).coerceAtLeast(1)
        val weightedFlows = flows.fold(BigDecimal.ZERO) { sum, event ->
            val remainingDays = ChronoUnit.DAYS.between(event.day, end.day).coerceIn(0, days)
            val weight = BigDecimal.valueOf(remainingDays).divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP)
            val signed = if (event.type == AccountCashEventType.DEPOSIT) event.amount else event.amount.negate()
            sum + signed.multiply(weight)
        }
        val denominator = requireNotNull(start.totalValue) + weightedFlows
        if (denominator.signum() <= 0) return Triple(profit, null, "资金流调整后的期初资产不大于零，无法计算收益率")
        val rate = profit.multiply(BigDecimal("100")).divide(denominator, 2, RoundingMode.HALF_UP)
        return Triple(profit, rate, null)
    }

    private fun modifiedDietz(
        profit: BigDecimal,
        opening: PortfolioSnapshot,
        closing: PortfolioSnapshot,
        trades: List<Trade>
    ): BigDecimal? {
        val startValue = opening.marketValue ?: return null
        val days = ChronoUnit.DAYS.between(opening.day, closing.day).coerceAtLeast(1)
        var weightedFlows = BigDecimal.ZERO
        trades.forEach { trade ->
            if (trade.side == TradeSide.OPENING_POSITION) return@forEach
            val tradeDay = runCatching { LocalDate.parse(trade.day) }.getOrNull() ?: return@forEach
            val netAmount = if (trade.side == TradeSide.BUY) trade.amount else trade.amount - trade.fee
            val signed = if (trade.side == TradeSide.BUY) netAmount else netAmount.negate()
            val remainingDays = ChronoUnit.DAYS.between(tradeDay, closing.day).coerceIn(0, days)
            val weight = BigDecimal.valueOf(remainingDays).divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP)
            weightedFlows += signed.multiply(weight)
        }
        val denominator = startValue + weightedFlows
        if (denominator.signum() <= 0) return null
        return profit.multiply(BigDecimal("100")).divide(denominator, 2, RoundingMode.HALF_UP)
    }
}
