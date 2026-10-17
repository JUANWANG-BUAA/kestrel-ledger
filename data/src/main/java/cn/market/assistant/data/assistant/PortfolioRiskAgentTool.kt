package cn.market.assistant.data.assistant

import cn.market.assistant.Ledger
import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.portfolio.PortfolioRiskAnalytics
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the shipped risk engine over the user's own account history. It is the assistant's way to call
 * the same algorithms the analysis page uses instead of re-deriving statistics in the prompt.
 */
class PortfolioRiskAgentTool(private val ledger: Ledger) : AgentTool {
    override val id = "portfolio.risk"
    override val description =
        "用已实现的组合风险算法计算本机账户近一年的年化波动、Sharpe、Sortino、1 日 95% 历史 VaR/CVaR 与最大回撤；样本不足时返回缺失原因。"
    override val parameters = listOf(
        AgentToolParameter("risk_free_pct", "无风险年利率百分数，默认 0"),
        AgentToolParameter("minimum_return_pct", "最低可接受年收益百分数，默认 0")
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult = withContext(Dispatchers.IO) {
        val riskFree = percent(request.arguments["risk_free_pct"]) ?: BigDecimal.ZERO
        val minimum = percent(request.arguments["minimum_return_pct"]) ?: BigDecimal.ZERO
        if (riskFree <= BigDecimal.ONE.negate() || minimum <= BigDecimal.ONE.negate()) {
            return@withContext AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        }
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val summary = try {
            PortfolioRiskAnalytics.trailingSummary(
                asOf = today,
                snapshots = ledger.portfolioSnapshots(),
                positionsByDay = ledger.positionSnapshots(),
                trades = ledger.trades(),
                cashEvents = ledger.accountCashEvents(),
                riskFreeAnnualRate = riskFree,
                minimumAcceptableAnnualReturn = minimum
            )
        } catch (error: Exception) {
            return@withContext AgentToolResult(emptyList(), "RISK_NOT_AVAILABLE")
        }
        val missingReason = summary.missingReason
        if (summary.annualizedVolatilityPct == null && summary.sharpeRatio == null &&
            summary.historicalVar95Pct == null && missingReason != null
        ) {
            return@withContext AgentToolResult(
                listOf(fact("risk.unavailable", missingReason, "样本不足")),
                "RISK_NOT_AVAILABLE"
            )
        }
        val observedAt = Instant.now().toString()
        val facts = buildList {
            add(fact("risk.window", listOfNotNull(summary.startDay, summary.endDay).joinToString(" 至 "), "日期区间"))
            add(fact("risk.observations", summary.observationCount.toString(), "有效 TWR 日收益"))
            summary.annualizedVolatilityPct?.let { add(fact("risk.annualized_volatility", pct(it), "%")) }
            summary.sharpeRatio?.let { add(fact("risk.sharpe", it.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString(), "ratio")) }
            summary.sortinoRatio?.let { add(fact("risk.sortino", it.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString(), "ratio")) }
            summary.historicalVar95Pct?.let { add(fact("risk.var95_daily", pct(it), "%")) }
            summary.historicalCvar95Pct?.let { add(fact("risk.cvar95_daily", pct(it), "%")) }
            summary.historicalVar95Amount?.let { add(fact("risk.var95_amount", it.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), "CNY")) }
            summary.correlations.take(3).forEachIndexed { index, pair ->
                add(fact("risk.correlation.${index + 1}", "${pair.firstAssetName} / ${pair.secondAssetName} · ${pair.correlation.setScale(2, java.math.RoundingMode.HALF_UP)}（${pair.observationCount} 日）", "coefficient"))
            }
            listOfNotNull(
                summary.missingReason,
                summary.sharpeMissingReason,
                summary.sortinoMissingReason,
                summary.tailMissingReason,
                summary.correlationMissingReason
            ).forEach { add(fact("risk.missing_reason", it, "原因")) }
            add(fact("risk.parameters", "无风险年利率 ${riskFree.multiply(BigDecimal(100)).stripTrailingZeros()}% · 最低可接受年收益 ${minimum.multiply(BigDecimal(100)).stripTrailingZeros()}%", "参数"))
        }
        AgentToolResult(facts)
    }

    private fun percent(raw: String?): BigDecimal? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it).movePointLeft(2) }.getOrNull()
    }

    private fun pct(value: BigDecimal): String = value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    private fun fact(key: String, value: String, unit: String?) = AssistantFact(
        key = key, value = value, unit = unit, source = "本机组合风险算法",
        observedAt = Instant.now().toString(), sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
    )
}
