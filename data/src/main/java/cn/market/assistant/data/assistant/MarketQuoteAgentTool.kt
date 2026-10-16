package cn.market.assistant.data.assistant

import cn.market.assistant.MarketInstrumentCatalog
import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.market.MarketRepository
import java.math.BigDecimal
import java.time.Instant

/** Looks up only locally cached quotes by instrument name, ID, or provider code. */
class MarketQuoteAgentTool(
    private val marketRepository: MarketRepository
) : AgentTool {
    override val id = "market.quote"
    override val description = "按名称或代码查询本机缓存的市场报价和来源时间；不触发刷新。"
    override val parameters = listOf(AgentToolParameter("query", "Instrument name, market ID, or provider code", required = true))

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val query = request.arguments["query"]?.trim().orEmpty()
        if (query.isBlank()) return AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        val normalized = query.uppercase()
        val matches = lookupItems().filter { instrument ->
            instrument.id.equals(normalized, ignoreCase = true) || instrument.providerCode?.equals(normalized, ignoreCase = true) == true ||
                instrument.name.contains(query, ignoreCase = true) || instrument.aliases.any { alias ->
                    alias.equals(query, ignoreCase = true) || alias.contains(query, ignoreCase = true)
                }
        }.take(MAX_MATCHES)
        if (matches.isEmpty()) return AgentToolResult(emptyList(), "INSTRUMENT_NOT_FOUND")

        if (matches.size > 1) {
            val observedAt = Instant.now().toString()
            return AgentToolResult(matches.mapIndexed { index, instrument ->
                AssistantFact(
                    key = "market.search.candidate.${index + 1}", value = "${instrument.name} (${instrument.id}) · ${instrument.unit}",
                    unit = instrument.market, source = "本地行情标的目录", observedAt = observedAt,
                    sensitivity = FactSensitivity.PUBLIC_MARKET
                )
            })
        }

        val quotes = marketRepository.cachedQuotes()
        val instrument = matches.single()
        val quote = quotes[instrument.id]
        if (quote == null) {
            val observedAt = Instant.now().toString()
            return AgentToolResult(listOf(AssistantFact(
                key = "market.search.candidate.1", value = "${instrument.name} (${instrument.id}) · ${instrument.unit}",
                unit = instrument.market, source = "本地行情标的目录", observedAt = observedAt,
                sensitivity = FactSensitivity.PUBLIC_MARKET
            )))
        }

        val observedAt = runCatching { Instant.ofEpochSecond(quote.quoteTime).toString() }.getOrNull()
            ?: return AgentToolResult(emptyList(), "INVALID_QUOTE_TIME")
        if (listOf(quote.price, quote.change, quote.changePct).any { !it.isFinite() } || quote.price <= 0) {
            return AgentToolResult(emptyList(), "INVALID_QUOTE")
        }
        val freshnessSeconds = Instant.now().epochSecond - quote.quoteTime
        val source = quote.source.ifBlank { "unknown" }
        return AgentToolResult(listOf(
            fact("market.quote.instrument_id", instrument.id, null, source, observedAt),
            fact("market.quote.instrument_name", instrument.name, null, source, observedAt),
            fact("market.quote.price", BigDecimal.valueOf(quote.price).stripTrailingZeros().toPlainString(), instrument.unit, source, observedAt),
            fact("market.quote.change", BigDecimal.valueOf(quote.change).stripTrailingZeros().toPlainString(), instrument.unit, source, observedAt),
            fact("market.quote.change_pct", BigDecimal.valueOf(quote.changePct).stripTrailingZeros().toPlainString(), "%", source, observedAt),
            fact("market.quote.age_seconds", freshnessSeconds.toString(), "seconds", source, observedAt),
            fact("market.quote.fresh_within_72h", (freshnessSeconds in 0..MAX_FRESH_SECONDS).toString(), "boolean", source, observedAt)
        ))
    }

    private fun fact(key: String, value: String, unit: String?, source: String, observedAt: String) = AssistantFact(
        key = key, value = value, unit = unit, source = source, observedAt = observedAt,
        sensitivity = FactSensitivity.PUBLIC_MARKET
    )

    private fun lookupItems(): List<LookupInstrument> = MarketInstrumentCatalog.items.map { instrument ->
        LookupInstrument(
            id = instrument.secid,
            name = instrument.name,
            market = instrument.market,
            providerCode = instrument.tencentCode,
            unit = if (instrument.name.contains("ETF", ignoreCase = true)) "CNY / 份" else "市场原生报价单位"
        )
    } + listOf(
        LookupInstrument(
            id = "GOLD.XAU.USD.OZ", name = "国际黄金 XAU/USD", market = "黄金", unit = "USD / troy oz",
            aliases = listOf("XAU/USD", "国际金价", "美元/盎司")
        ),
        LookupInstrument(
            id = "GOLD.SGE.AU9999", name = "上金所 Au99.99", market = "黄金", unit = "CNY / 克",
            aliases = listOf("Au99.99", "国内金价", "上金所黄金")
        ),
        LookupInstrument(
            id = "GOLD.XAU.CNY.GRAM", name = "国际黄金人民币折算", market = "换算参考", unit = "CNY / 克",
            aliases = listOf("人民币折算", "CNY/g")
        )
    )

    private data class LookupInstrument(
        val id: String,
        val name: String,
        val market: String,
        val unit: String,
        val providerCode: String? = null,
        val aliases: List<String> = emptyList()
    )

    private companion object {
        const val MAX_MATCHES = 10
        const val MAX_FRESH_SECONDS = 72 * 60 * 60L
    }
}
