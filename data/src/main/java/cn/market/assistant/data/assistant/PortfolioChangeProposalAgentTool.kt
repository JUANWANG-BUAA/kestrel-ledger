package cn.market.assistant.data.assistant

import cn.market.assistant.Ledger
import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantActionKind
import cn.market.assistant.domain.assistant.AssistantActionProposal
import cn.market.assistant.domain.assistant.AssistantFact
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds a reviewable local-change proposal. This tool has no write path. */
class PortfolioChangeProposalAgentTool(private val ledger: Ledger) : AgentTool {
    override val id = "portfolio.propose_change"
    override val description =
        "准备添加/移除基金自选、记录本地买卖流水、更新风险分析参数、保存使用偏好或新增一个可复用技能；只生成预览，不会直接写入。"
    override val parameters = listOf(
        AgentToolParameter("action", "watch_add、watch_remove、record_buy、record_sell、risk_parameters、save_preference、save_skill 或 save_recipe", true),
        AgentToolParameter("fund_code", "六位基金代码"),
        AgentToolParameter("fund_name", "基金名称"),
        AgentToolParameter("category", "基金分类"),
        AgentToolParameter("amount", "交易金额，人民币元"),
        AgentToolParameter("units", "交易份额"),
        AgentToolParameter("unit_price", "成交净值/单价"),
        AgentToolParameter("fee", "手续费，人民币元；默认 0"),
        AgentToolParameter("risk_free_annual_pct", "Sharpe 无风险年利率，百分数形式"),
        AgentToolParameter("minimum_acceptable_annual_pct", "Sortino 最低可接受年收益，百分数形式"),
        AgentToolParameter("preference", "希望助手以后遵循的分析方法或偏好"),
        AgentToolParameter("skill_name", "技能名称，例如「周度复盘」"),
        AgentToolParameter("skill_instruction", "技能执行要求，写给未来的自己看"),
        AgentToolParameter("skill_trigger", "触发词，多个用顿号分隔"),
        AgentToolParameter("recipe_spec", "配方 JSON（version/id/name/summary/steps；steps 仅支持 tool 与 write）")
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult =
        withContext(Dispatchers.IO) { createProposal(request) }

    private fun createProposal(request: AgentToolRequest): AgentToolResult {
        val args = request.arguments
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        val code = args["fund_code"]?.trim()?.takeIf { it.matches(Regex("\\d{6}")) }
        val name = args["fund_name"]?.trim()?.takeIf(String::isNotBlank)
        val category = args["category"]?.trim()?.takeIf(String::isNotBlank) ?: "公募基金"
        val proposal = when (action) {
            "watch_add", "watch_remove" -> {
                if (code == null || name == null) return invalid()
                val kind = if (action == "watch_add") AssistantActionKind.ADD_FUND_WATCHLIST
                    else AssistantActionKind.REMOVE_FUND_WATCHLIST
                val intent = if (kind == AssistantActionKind.ADD_FUND_WATCHLIST) "添加到自选" else "从自选移除"
                AssistantActionProposal(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    summary = "$intent「$name」($code)",
                    fundCode = code,
                    fundName = name,
                    fundCategory = category
                )
            }
            "record_buy", "record_sell" -> tradeProposal(action, code, name, category, args) ?: return invalid()
            "risk_parameters" -> riskProposal(args) ?: return invalid()
            "save_preference" -> {
                val preference = args["preference"]?.trim()?.takeIf { it.length in 4..500 } ?: return invalid()
                AssistantActionProposal(
                    id = UUID.randomUUID().toString(),
                    kind = AssistantActionKind.SAVE_PREFERENCE,
                    summary = "记住这条分析偏好：$preference",
                    preference = preference
                )
            }
            "save_recipe" -> {
                val spec = args["recipe_spec"]?.trim()?.takeIf { it.length in 10..4_000 }
                    ?: return invalid("recipe_spec 缺失或长度不在 10–4000")
                val decoded = RecipeCodec.decodeDetailed(spec)
                val recipe = decoded.recipe
                    ?: return invalid("配方校验失败：${decoded.error ?: "未知原因"}")
                AssistantActionProposal(
                    id = UUID.randomUUID().toString(),
                    kind = AssistantActionKind.SAVE_RECIPE,
                    summary = "保存配方「${recipe.name}」：${recipe.steps.size} 步，调用 ${recipe.toolIds.joinToString("、")}",
                    recipeId = recipe.id,
                    recipeName = recipe.name,
                    recipeSpec = spec
                )
            }
            "save_skill" -> {
                val skillName = args["skill_name"]?.trim()?.takeIf { it.length in 2..40 } ?: return invalid()
                val instruction = args["skill_instruction"]?.trim()?.takeIf { it.length in 4..2_000 } ?: return invalid()
                val trigger = args["skill_trigger"]?.trim()?.take(120).orEmpty()
                AssistantActionProposal(
                    id = UUID.randomUUID().toString(),
                    kind = AssistantActionKind.SAVE_SKILL,
                    summary = "新建技能「$skillName」：$instruction" +
                        if (trigger.isBlank()) "" else "（触发词：$trigger）",
                    skillName = skillName,
                    skillInstruction = instruction,
                    skillTrigger = trigger
                )
            }
            else -> return invalid()
        }
        return AgentToolResult(emptyList(), actionProposal = proposal)
    }

    private fun tradeProposal(
        action: String,
        code: String?,
        name: String?,
        category: String,
        args: Map<String, String>
    ): AssistantActionProposal? {
        if (code == null || name == null) return null
        val units = args.decimal("units")?.takeIf { it > BigDecimal.ZERO } ?: return null
        val unitPrice = args.decimal("unit_price")?.takeIf { it > BigDecimal.ZERO } ?: return null
        val fee = args.decimal("fee") ?: BigDecimal.ZERO
        if (fee < BigDecimal.ZERO) return null
        val sideIsBuy = action == "record_buy"
        val amount = args.decimal("amount") ?: if (!sideIsBuy) units.multiply(unitPrice) else return null
        if (amount <= BigDecimal.ZERO || (sideIsBuy && fee >= amount)) return null
        if (!sideIsBuy) {
            val position = ledger.positions().firstOrNull { it.asset.symbol == code } ?: return null
            if (position.units < units) return null
        }
        val kind = if (sideIsBuy) AssistantActionKind.RECORD_FUND_BUY else AssistantActionKind.RECORD_FUND_SELL
        val verb = if (sideIsBuy) "记录本地买入" else "记录本地卖出"
        val summary = "$verb「$name」($code)：金额 ¥${amount.toPlainString()}，份额 ${units.toPlainString()}，" +
            "单价 ¥${unitPrice.toPlainString()}，手续费 ¥${fee.toPlainString()}。只写入本机账本，不会提交交易。"
        return AssistantActionProposal(
            id = UUID.randomUUID().toString(),
            kind = kind,
            summary = summary,
            fundCode = code,
            fundName = name,
            fundCategory = category,
            amount = amount,
            units = units,
            unitPrice = unitPrice,
            fee = fee
        )
    }

    private fun riskProposal(args: Map<String, String>): AssistantActionProposal? {
        val riskFree = args.decimal("risk_free_annual_pct") ?: return null
        val minimumReturn = args.decimal("minimum_acceptable_annual_pct") ?: return null
        if (riskFree < BigDecimal("-100") || riskFree > BigDecimal("100") ||
            minimumReturn < BigDecimal("-100") || minimumReturn > BigDecimal("100")
        ) return null
        val riskDecimal = riskFree.movePointLeft(2)
        val minimumDecimal = minimumReturn.movePointLeft(2)
        return AssistantActionProposal(
            id = UUID.randomUUID().toString(),
            kind = AssistantActionKind.UPDATE_RISK_PARAMETERS,
            summary = "调整风险分析参数：Sharpe 无风险年利率 ${riskFree.toPlainString()}%，" +
                "Sortino 最低可接受年收益 ${minimumReturn.toPlainString()}%",
            riskFreeAnnualRate = riskDecimal,
            minimumAcceptableAnnualReturn = minimumDecimal
        )
    }

    private fun Map<String, String>.decimal(key: String): BigDecimal? = this[key]
        ?.trim()
        ?.takeIf { it.length <= 32 }
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun invalid(reason: String? = null): AgentToolResult {
        val detail = reason?.let {
            listOf(
                AssistantFact(
                    key = "proposal.error",
                    value = it,
                    source = "本机提议校验",
                    observedAt = java.time.Instant.now().toString()
                )
            )
        }.orEmpty()
        return AgentToolResult(detail, "INVALID_CHANGE_PROPOSAL")
    }
}
