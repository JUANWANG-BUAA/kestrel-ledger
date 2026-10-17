package cn.market.assistant.data.assistant

import cn.market.assistant.Ledger
import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantChart
import cn.market.assistant.domain.assistant.AssistantChartKind
import cn.market.assistant.domain.assistant.AssistantChartPoint
import cn.market.assistant.domain.assistant.AssistantChartSeries
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.market.ChartPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws a local series for the assistant: gold observations or a cached daily close line. The tool
 * only reads the encrypted ledger; it never triggers a network refresh, so the chart is always
 * labelled with the observation time it actually has.
 */
class MarketChartAgentTool(private val ledger: Ledger) : AgentTool {
    override val id = "market.chart"
    override val description =
        "用本机已有数据画走势图：黄金（国内 Au99.99、国际 XAU/USD、人民币折算、内外盘溢价）或已缓存日线的指数/ETF。"
    override val parameters = listOf(
        AgentToolParameter("symbol", "gold.domestic、gold.international、gold.cny、gold.premium，或行情 secid（如 1.518880）", true),
        AgentToolParameter("days", "回看天数，默认 30，最大 120")
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult = withContext(Dispatchers.IO) {
        val symbol = request.arguments["symbol"]?.trim().orEmpty()
        val days = request.arguments["days"]?.trim()?.toIntOrNull()?.coerceIn(7, 120) ?: 30
        if (symbol.isBlank()) return@withContext AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        val metric = GOLD_METRICS[symbol.lowercase()]
        val points: List<Pair<String, Double>> = if (metric != null) {
            ledger.goldDailyHistory(metric.metric, limit = 400).map { it.day to it.value }
        } else {
            val cached = ledger.chartBars(symbol, ChartPeriod.DAY)
            if (cached.isNotEmpty()) cached.map { it.day to it.close }
            else ledger.marketBars(symbol).map { it.day to it.close }
        }
        val trimmed = points.takeLast(days)
        if (trimmed.size < 2) return@withContext AgentToolResult(emptyList(), "NOT_ENOUGH_HISTORY")
        val name = metric?.label ?: symbol
        val unit = metric?.unit ?: "本机缓存日线"
        val source = metric?.source ?: "本机日线缓存"
        val observedAt = Instant.now().toString()
        val latest = trimmed.last().second
        val first = trimmed.first().second
        val change = latest - first
        val changePct = if (first != 0.0) change / first * 100 else 0.0
        val chart = AssistantChart(
            title = "$name · 近 ${trimmed.size} 个记录日",
            subtitle = "${trimmed.first().first} 至 ${trimmed.last().first} · $source",
            unit = unit,
            kind = AssistantChartKind.AREA,
            series = listOf(
                AssistantChartSeries(
                    name = name,
                    points = trimmed.map { (day, value) -> AssistantChartPoint(day.takeLast(5), value) }
                )
            ),
            source = source,
            observedAt = observedAt
        )
        AgentToolResult(
            facts = listOf(
                fact("chart.symbol", symbol, null, source, observedAt),
                fact("chart.points", trimmed.size.toString(), "observations", source, observedAt),
                fact("chart.first_day", trimmed.first().first, null, source, observedAt),
                fact("chart.last_day", trimmed.last().first, null, source, observedAt),
                fact("chart.latest", BigDecimal.valueOf(latest).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(), unit, source, observedAt),
                fact("chart.change", BigDecimal.valueOf(change).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(), unit, source, observedAt),
                fact("chart.change_pct", BigDecimal.valueOf(changePct).setScale(2, RoundingMode.HALF_UP).toPlainString(), "%", source, observedAt)
            ),
            charts = listOf(chart)
        )
    }

    private fun fact(key: String, value: String, unit: String?, source: String, observedAt: String) = AssistantFact(
        key = key, value = value, unit = unit, source = source, observedAt = observedAt,
        sensitivity = FactSensitivity.PUBLIC_MARKET
    )

    private data class MetricInfo(val label: String, val metric: String, val unit: String, val source: String)

    private companion object {
        /** symbol -> metric metadata; metrics match Ledger.recordGoldDailyHistory. */
        val GOLD_METRICS = mapOf(
            "gold.domestic" to MetricInfo("国内 Au99.99", "sge_cny_gram", "CNY / 克", "上金所公开报价（本机每日记录）"),
            "gold.international" to MetricInfo("国际黄金 XAU/USD", "xau_usd_oz", "USD / 金衡盎司", "gold-api（本机每日记录）"),
            "gold.cny" to MetricInfo("国际黄金人民币折算", "xau_cny_gram", "CNY / 克", "gold-api CNY 换算（本机每日记录）"),
            "gold.premium" to MetricInfo("内外盘溢价", "premium_cny_gram", "CNY / 克", "由国内与国际折算价相减得到")
        )
    }
}
