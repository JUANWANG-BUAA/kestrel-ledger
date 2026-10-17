package cn.market.assistant.data.assistant

import cn.market.assistant.Ledger
import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.portfolio.AccountCashLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Reads the encrypted local ledger and returns positions, cash and allocation facts. Per-position rows
 * are PRIVATE_DETAIL and therefore require the session's explicit opt-in; only the account totals are
 * aggregate facts.
 */
class LocalPortfolioAgentTool(private val ledger: Ledger) : AgentTool {
    override val id = "portfolio.positions"
    override val description = "读取本机账本的当前持仓、市值、现金与配置权重；不修改任何数据。"
    override val parameters = listOf(
        AgentToolParameter("scope", "summary 只返回账户合计，detail 额外返回每只持仓"),
        AgentToolParameter("limit", "detail 时最多返回的持仓数量，默认 10")
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult =
        withContext(Dispatchers.IO) { readPortfolio(request) }

    private fun readPortfolio(request: AgentToolRequest): AgentToolResult {
        val scope = request.arguments["scope"]?.trim()?.lowercase() ?: "summary"
        if (scope !in setOf("summary", "detail")) return AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        val limit = request.arguments["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val positions = ledger.positions().filter { it.units.signum() > 0 }
        val cash = AccountCashLedger.balance(ledger.accountCashEvents())
        val priced = positions.filter { it.value != null }
        val observedAt = positions.mapNotNull { it.quoteTime }.maxOrNull() ?: Instant.now().toString()
        val positionValue = priced.fold(BigDecimal.ZERO) { sum, position -> sum + requireNotNull(position.value) }
        val totalValue = cash?.let { positionValue + it.amount }

        val facts = mutableListOf<AssistantFact>()
        fun aggregate(key: String, value: BigDecimal?, unit: String) {
            if (value == null) return
            facts += AssistantFact(
                key = key,
                value = value.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                unit = unit,
                source = "本地加密账本",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
        }
        aggregate("portfolio.account.total_value", totalValue, "CNY")
        aggregate("portfolio.account.position_value", positionValue, "CNY")
        aggregate("portfolio.account.cash", cash?.amount, "CNY")
        if (positions.isEmpty()) {
            return AgentToolResult(
                facts + AssistantFact(
                    key = "portfolio.account.position_count",
                    value = "0",
                    unit = "items",
                    source = "本地加密账本",
                    observedAt = observedAt,
                    sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
                )
            )
        }
        aggregate("portfolio.account.position_count", BigDecimal(positions.size), "items")
        if (positions.size > priced.size) {
            facts += AssistantFact(
                key = "portfolio.account.unpriced_count",
                value = (positions.size - priced.size).toString(),
                unit = "items",
                source = "本地加密账本",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
        }
        if (cash == null) {
            facts += AssistantFact(
                key = "portfolio.account.cash_untracked",
                value = "用户尚未启用现金跟踪，账户总资产不含现金",
                unit = null,
                source = "本地加密账本",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
        }
        if (scope == "detail") {
            priced.take(limit).forEach { position ->
                val value = requireNotNull(position.value)
                val weight = if (positionValue.signum() > 0) {
                    value.multiply(BigDecimal("100")).divide(positionValue, 2, RoundingMode.HALF_UP).toPlainString()
                } else {
                    null
                }
                facts += AssistantFact(
                    key = "portfolio.position.${position.asset.symbol}.market_value",
                    value = value.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    unit = "CNY",
                    source = position.quoteSource ?: "本地账本",
                    observedAt = position.quoteTime ?: observedAt,
                    sensitivity = FactSensitivity.PRIVATE_DETAIL
                )
                facts += AssistantFact(
                    key = "portfolio.position.${position.asset.symbol}.units",
                    value = position.units.stripTrailingZeros().toPlainString(),
                    unit = "units",
                    source = "本地加密账本",
                    observedAt = observedAt,
                    sensitivity = FactSensitivity.PRIVATE_DETAIL
                )
                facts += AssistantFact(
                    key = "portfolio.position.${position.asset.symbol}.cost_cny",
                    value = position.cost.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    unit = "CNY",
                    source = "本地加密账本",
                    observedAt = observedAt,
                    sensitivity = FactSensitivity.PRIVATE_DETAIL
                )
                position.holdingProfit?.let { profit ->
                    facts += AssistantFact(
                        key = "portfolio.position.${position.asset.symbol}.profit_cny",
                        value = profit.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        unit = "CNY",
                        source = "本地账本",
                        observedAt = observedAt,
                        sensitivity = FactSensitivity.PRIVATE_DETAIL
                    )
                    if (position.cost.signum() > 0) {
                        facts += AssistantFact(
                            key = "portfolio.position.${position.asset.symbol}.profit_pct",
                            value = profit.multiply(BigDecimal("100"))
                                .divide(position.cost, 2, RoundingMode.HALF_UP).toPlainString(),
                            unit = "%",
                            source = "本地账本",
                            observedAt = observedAt,
                            sensitivity = FactSensitivity.PRIVATE_DETAIL
                        )
                    }
                }
                weight?.let {
                    facts += AssistantFact(
                        key = "portfolio.position.${position.asset.symbol}.weight_pct",
                        value = it,
                        unit = "%",
                        source = "本地加密账本",
                        observedAt = observedAt,
                        sensitivity = FactSensitivity.PRIVATE_DETAIL
                    )
                }
                facts += AssistantFact(
                    key = "portfolio.position.${position.asset.symbol}.name",
                    value = position.asset.name,
                    unit = null,
                    source = "本地加密账本",
                    observedAt = observedAt,
                    sensitivity = FactSensitivity.PRIVATE_DETAIL
                )
            }
        }
        return AgentToolResult(facts)
    }
}
