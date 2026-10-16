package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioReportRepository
import cn.market.assistant.domain.portfolio.ReportPeriod
import cn.market.assistant.domain.portfolio.XirrResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Exposes the app's existing deterministic period analytics as sourced private facts. */
class PortfolioPeriodReportAgentTool(
    private val reportRepository: PortfolioReportRepository
) : AgentTool {
    override val id = "portfolio.period_report"
    override val description = "查询本地日、周或月组合报告事实，包括收益、资金流、风险缺口和贡献。"
    override val parameters = listOf(
        AgentToolParameter("period", "One of day, week, month", required = true),
        AgentToolParameter("as_of", "Optional report end date in ISO-8601 yyyy-MM-dd")
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val period = parsePeriod(request.arguments["period"])
            ?: return AgentToolResult(emptyList(), "INVALID_REPORT_PERIOD")
        val asOf = request.arguments["as_of"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: if ("as_of" in request.arguments) return AgentToolResult(emptyList(), "INVALID_REPORT_DATE")
            else LocalDate.now(SHANGHAI_ZONE)
        val report = reportRepository.facts(period, asOf)
        val observedAt = report.closing?.observedAt ?: Instant.now().toString()
        return AgentToolResult(reportFacts(report, observedAt))
    }

    private fun reportFacts(report: PeriodFacts, observedAt: String): List<AssistantFact> = buildList {
        add(fact("portfolio.report.period", report.period.name, null, observedAt))
        add(fact("portfolio.report.start", report.start.toString(), null, observedAt))
        add(fact("portfolio.report.end", report.end.toString(), null, observedAt))
        report.profit?.let { add(fact("portfolio.report.holdings_profit", it.money(), "CNY", observedAt)) }
        report.modifiedDietzPct?.let { add(fact("portfolio.report.holdings_modified_dietz_pct", it.money(), "%", observedAt)) }
        report.accountProfit?.let { add(fact("portfolio.report.account_profit", it.money(), "CNY", observedAt)) }
        report.accountModifiedDietzPct?.let { add(fact("portfolio.report.account_modified_dietz_pct", it.money(), "%", observedAt)) }
        report.accountTwrPct?.let { add(fact("portfolio.report.account_twr_pct", it.money(), "%", observedAt)) }
        report.accountMaxDrawdownPct?.let { add(fact("portfolio.report.account_max_drawdown_pct", it.money(), "% 最大回撤幅度", observedAt)) }
        report.accountMaxDrawdownPeakDay?.let { add(fact("portfolio.report.account_max_drawdown_peak_day", it.toString(), null, observedAt)) }
        report.accountMaxDrawdownTroughDay?.let { add(fact("portfolio.report.account_max_drawdown_trough_day", it.toString(), null, observedAt)) }
        add(fact("portfolio.report.account_max_drawdown_observation_count", report.accountMaxDrawdownObservationCount.toString(), "daily intervals", observedAt))
        if (report.accountMaxDrawdownMissingDays.isNotEmpty()) {
            add(fact("portfolio.report.account_max_drawdown_missing_days", report.accountMaxDrawdownMissingDays.joinToString(), null, observedAt))
        }
        report.accountMaxDrawdownMissingReason?.let { add(fact("portfolio.report.account_max_drawdown_missing_reason", it, null, observedAt)) }
        report.accountAllocationHhi?.let { add(fact("portfolio.report.account_allocation_hhi", it.toPlainString(), "0-1", observedAt)) }
        report.accountEffectiveAssetCount?.let { add(fact("portfolio.report.account_effective_asset_count", it.toPlainString(), "assets", observedAt)) }
        report.accountAllocationMissingReason?.let { add(fact("portfolio.report.account_allocation_missing_reason", it, null, observedAt)) }
        report.accountAllocations.take(MAX_ALLOCATIONS).forEach { allocation ->
            add(fact("portfolio.report.allocation.${allocation.id}.weight_pct", allocation.weightPct.toPlainString(), "%", observedAt))
            add(fact("portfolio.report.allocation.${allocation.id}.market_value", allocation.marketValue.money(), "CNY", observedAt))
        }
        if (report.accountAllocations.size > MAX_ALLOCATIONS) {
            add(fact("portfolio.report.allocations_truncated", "true", "boolean", observedAt))
        }
        report.accountAllocationChanges.take(MAX_ALLOCATIONS).forEach { change ->
            add(fact("portfolio.report.allocation_change.${change.id}_percentage_points",
                change.changePercentagePoints.toPlainString(), "percentage points", observedAt))
        }
        if (report.accountAllocationChanges.size > MAX_ALLOCATIONS) {
            add(fact("portfolio.report.allocation_changes_truncated", "true", "boolean", observedAt))
        }
        report.accountXirr?.let { result ->
            when (result) {
                is XirrResult.Available -> add(fact("portfolio.report.account_xirr_annualized_pct", (result.annualizedRate * 100).toBigDecimal().money(), "% 年化", observedAt))
                is XirrResult.Unavailable -> add(fact("portfolio.report.account_xirr_unavailable_reason", result.reason.name, null, observedAt))
            }
        }
        report.missingReason?.let { add(fact("portfolio.report.holdings_missing_reason", it, null, observedAt)) }
        report.accountMissingReason?.let { add(fact("portfolio.report.account_missing_reason", it, null, observedAt)) }
        report.accountTwrMissingReason?.let { add(fact("portfolio.report.account_twr_missing_reason", it, null, observedAt)) }
        add(fact("portfolio.report.confirmed_trade_count", report.transactionCount.toString(), "trades", observedAt))
        add(fact("portfolio.report.buy_amount", report.buyAmount.money(), "CNY", observedAt))
        add(fact("portfolio.report.sell_amount", report.sellAmount.money(), "CNY", observedAt))
        report.contributors.take(MAX_CONTRIBUTORS).forEachIndexed { index, contribution ->
            add(fact("portfolio.report.contributor.${index + 1}", "${contribution.assetName}: ${contribution.amount.money()} CNY", null, observedAt))
        }
        if (report.contributors.size > MAX_CONTRIBUTORS) {
            add(fact("portfolio.report.contributors_truncated", "true", "boolean", observedAt))
        }
    }

    private fun fact(key: String, value: String, unit: String?, observedAt: String) = AssistantFact(
        key = key, value = value, unit = unit, source = "本地快照与交易流水", observedAt = observedAt,
        sensitivity = FactSensitivity.PRIVATE_DETAIL
    )

    private fun parsePeriod(value: String?): ReportPeriod? = when (value?.trim()?.lowercase()) {
        "day" -> ReportPeriod.DAY
        "week" -> ReportPeriod.WEEK
        "month" -> ReportPeriod.MONTH
        else -> null
    }

    private fun BigDecimal.money(): String = setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    private companion object {
        const val MAX_CONTRIBUTORS = 20
        const val MAX_ALLOCATIONS = 12
        val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
