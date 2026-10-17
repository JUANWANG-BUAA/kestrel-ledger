package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.market.assistant.domain.assistant.AssistantFact
import java.math.BigDecimal

private data class AccountMetric(val label: String, val value: String, val unit: String?)

private data class HoldingBubble(
    val symbol: String,
    val name: String,
    val marketValue: String,
    val weightPct: String?,
    val profit: String?,
    val profitPct: String?,
    val units: String?,
    val source: String?,
    val observedAt: String?
)

/**
 * Renders tool facts as scanable cards in addition to the model's Markdown answer. This keeps the
 * model from being the only place where a long portfolio answer can be read: even a prose-heavy
 * response gets a deterministic account summary and one card per held fund/asset.
 */
@Composable
internal fun AssistantFactsPanel(facts: List<AssistantFact>, modifier: Modifier = Modifier) {
    val unique = facts.distinctBy { it.key to it.observedAt }
    val account = accountMetrics(unique)
    val holdings = holdingBubbles(unique)
    if (account.isEmpty() && holdings.isEmpty()) return
    val palette = MarketTheme.colors
    Surface(
        modifier.fillMaxWidth(),
        color = palette.surfaceAlt,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, palette.line)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本机数据快照", color = palette.ink, style = MarketType.section)
                Spacer(Modifier.weight(1f))
                Text("工具返回 · 非模型生成", color = palette.muted, style = MarketType.caption)
            }
            if (account.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    account.forEach { metric -> AccountMetricCard(metric) }
                }
            }
            if (holdings.isNotEmpty()) {
                Text("持仓明细", color = palette.muted, style = MarketType.label)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    holdings.forEach { holding -> HoldingBubbleCard(holding) }
                }
            }
            val sourceLine = unique.asSequence()
                .mapNotNull { fact -> fact.source?.takeIf { it.isNotBlank() } }
                .distinct().take(3).joinToString(" / ")
            if (sourceLine.isNotBlank()) {
                Text("来源：$sourceLine", color = palette.muted, style = MarketType.caption)
            }
        }
    }
}

@Composable
private fun AccountMetricCard(metric: AccountMetric) {
    val palette = MarketTheme.colors
    Surface(color = palette.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, palette.line.copy(alpha = 0.8f))) {
        Column(Modifier.width(116.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(metric.label, color = palette.muted, style = MarketType.caption)
            Text(
                buildString {
                    append(metric.value)
                    metric.unit?.takeIf { it == "CNY" }?.let { append(" 元") }
                },
                color = palette.ink,
                style = MarketType.numberSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HoldingBubbleCard(holding: HoldingBubble) {
    val palette = MarketTheme.colors
    val profitValue = holding.profit?.let { value -> runCatching { BigDecimal(value) }.getOrNull() }
    val profitColor = profitValue?.let { plColor(it) } ?: palette.ink
    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.line.copy(alpha = 0.85f))
    ) {
        Column(Modifier.width(190.dp).padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(holding.name, color = palette.ink, style = MarketType.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(holding.symbol, color = palette.muted, style = MarketType.caption, maxLines = 1)
            Text("¥${holding.marketValue}", color = palette.ink, style = MarketType.numberSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                holding.weightPct?.let { Text("占比 $it%", color = palette.muted, style = MarketType.caption) }
                holding.profit?.let {
                    val signed = if (it.startsWith("-")) "−¥${it.removePrefix("-")}" else "+¥$it"
                    Text(
                        signed + (holding.profitPct?.let { pct -> " ($pct%)" } ?: ""),
                        color = profitColor,
                        style = MarketType.caption,
                        maxLines = 1
                    )
                }
            }
            holding.units?.let { Text("持有 $it 份", color = palette.muted, style = MarketType.caption, maxLines = 1) }
            Text(
                listOfNotNull(holding.source, holding.observedAt?.let(::shortFactTime)).joinToString(" · "),
                color = palette.muted,
                style = MarketType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun accountMetrics(facts: List<AssistantFact>): List<AccountMetric> {
    val labels = linkedMapOf(
        "total_value" to "总资产",
        "position_value" to "持仓市值",
        "cash" to "现金",
        "position_count" to "持仓数",
        "unpriced_count" to "缺价"
    )
    return facts.asSequence()
        .filter { it.key.startsWith("portfolio.account.") }
        .mapNotNull { fact ->
            val field = fact.key.substringAfterLast('.')
            val label = labels[field] ?: return@mapNotNull null
            AccountMetric(label, fact.value, fact.unit)
        }
        .distinctBy { it.label }
        .toList()
}

private fun holdingBubbles(facts: List<AssistantFact>): List<HoldingBubble> {
    val grouped = facts.asSequence()
        .filter { it.key.startsWith("portfolio.position.") }
        .groupBy { fact -> fact.key.removePrefix("portfolio.position.").substringBeforeLast('.') }
    return grouped.mapNotNull { (symbol, group) ->
        val fields = group.associateBy { it.key.substringAfterLast('.') }
        val marketValue = fields["market_value"]?.value ?: return@mapNotNull null
        val quote = fields["market_value"]
        HoldingBubble(
            symbol = symbol,
            name = fields["name"]?.value ?: symbol,
            marketValue = marketValue,
            weightPct = fields["weight_pct"]?.value,
            profit = fields["profit_cny"]?.value,
            profitPct = fields["profit_pct"]?.value,
            units = fields["units"]?.value,
            source = quote?.source,
            observedAt = quote?.observedAt
        )
    }.sortedByDescending { it.weightPct?.toDoubleOrNull() ?: 0.0 }
}

private fun shortFactTime(value: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.of("Asia/Shanghai"))
        .format(java.time.Instant.parse(value))
}.getOrDefault(value)
