package cn.market.assistant.data.assistant

import cn.market.assistant.Ledger
import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Summarises enabled alert rules and recent fires so the assistant can reason about monitoring. */
class AlertsAgentTool(private val ledger: Ledger) : AgentTool {
    override val id = "alerts.summary"
    override val description = "读取本机提醒规则与最近触发记录，用于解释当前监控与静默状态。"
    override val parameters = listOf(AgentToolParameter("recent", "最近触发条数，默认 5，最大 20"))

    override suspend fun execute(request: AgentToolRequest): AgentToolResult = withContext(Dispatchers.IO) {
        val recentCount = request.arguments["recent"]?.trim()?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val rules = ledger.alertRules()
        val events = ledger.alertEvents(recentCount)
        val observedAt = Instant.now().toString()
        if (rules.isEmpty() && events.isEmpty()) {
            return@withContext AgentToolResult(listOf(fact("alerts.none", "尚未配置提醒规则", observedAt)))
        }
        val facts = buildList {
            add(fact("alerts.rules_total", rules.size.toString(), observedAt))
            add(fact("alerts.rules_enabled", rules.count { it.enabled }.toString(), observedAt))
            rules.filter { it.enabled }.take(6).forEachIndexed { index, rule ->
                add(
                    fact(
                        "alerts.rule.${index + 1}",
                        "${rule.kind.name} · 阈值 ${rule.threshold} · 冷却 ${rule.cooldownMinutes} 分钟 · 触发 ${rule.triggerCount} 次",
                        observedAt,
                        sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
                    )
                )
            }
            events.forEachIndexed { index, event ->
                add(
                    fact(
                        "alerts.event.${index + 1}",
                        "${event.title} · ${event.body}",
                        Instant.ofEpochMilli(event.firedAtMillis).toString(),
                        sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
                    )
                )
            }
        }
        AgentToolResult(facts)
    }

    private fun fact(
        key: String,
        value: String,
        observedAt: String,
        sensitivity: FactSensitivity = FactSensitivity.PUBLIC_MARKET
    ) = AssistantFact(key = key, value = value, source = "本机提醒规则库", observedAt = observedAt, sensitivity = sensitivity)
}
