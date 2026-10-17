package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.fund.FundRepository
import java.time.Instant

/** Searches the full local/Eastmoney fund catalog by name, code or pinyin. */
class FundCatalogAgentTool(private val repository: FundRepository) : AgentTool {
    override val id = "fund.search"
    override val description = "按基金名称、六位代码或拼音搜索全市场基金目录，只读取公开基金资料。"
    override val parameters = listOf(
        AgentToolParameter("query", "基金名称、代码或拼音", required = true),
        AgentToolParameter("limit", "最多返回多少个结果，默认 10")
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val query = request.arguments["query"]?.trim().orEmpty()
        if (query.length < 2) return AgentToolResult(emptyList(), "QUERY_TOO_SHORT")
        val limit = request.arguments["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 10
        val matches = repository.searchFunds(query).take(limit)
        if (matches.isEmpty()) return AgentToolResult(emptyList(), "NO_MATCHES")
        val facts = matches.map { fund ->
            AssistantFact(
                key = "fund.search.${fund.code}",
                value = "${fund.name}（${fund.code}，${fund.category}）",
                source = fund.source,
                observedAt = Instant.ofEpochSecond(fund.fetchedAt).toString(),
                sensitivity = FactSensitivity.PUBLIC_MARKET
            )
        }
        return AgentToolResult(facts)
    }
}
