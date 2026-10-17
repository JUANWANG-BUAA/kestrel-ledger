package cn.market.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.Position
import java.math.BigDecimal

/**
 * A flat ledger table for positions: one surface, hairline dividers and right-aligned numbers. The
 * previous per-row elevated cards hid the column relationships and made a portfolio look like a feed
 * of unrelated cards.
 */
@Composable
internal fun PositionTable(positions: List<Position>, concealAmounts: Boolean = false) {
    if (positions.isEmpty()) return
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.card)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.line, shape)
    ) {
        positions.forEachIndexed { index, position ->
            PositionRow(position, concealAmounts)
            if (index < positions.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line.copy(alpha = 0.68f)))
            }
        }
    }
}

@Composable
private fun PositionRow(p: Position, concealAmounts: Boolean) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val marketValue = p.value
    val holdingProfit = p.holdingProfit
    val isGold = p.asset.kind == AssetKind.GOLD
    val valueText = when {
        p.quoteExpired -> "报价已过期"
        marketValue == null -> "待更新价格"
        concealAmounts -> "¥••••"
        else -> "¥${marketValue.portfolioMoneyGrouped()}"
    }
    val profitText = when {
        concealAmounts -> "••••"
        holdingProfit == null -> "待估值"
        else -> "${if (holdingProfit.signum() > 0) "+" else ""}¥${holdingProfit.portfolioMoneyGrouped()}"
    }
    val source = p.quote?.let {
        "${portfolioQuoteSourceLabel(p.quoteSource)} · ${portfolioQuoteTimeLabel(p.quoteTime)}"
    } ?: "无报价"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = geometry.lg, vertical = geometry.md),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(p.asset.name, color = palette.ink, style = MarketType.number, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${p.asset.symbol} · ${if (isGold) "黄金" else "基金"}",
                    color = palette.muted,
                    style = MarketType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    valueText,
                    color = if (marketValue == null) palette.inkSoft else palette.ink,
                    style = MarketType.numberLarge,
                    maxLines = 1
                )
                Text(
                    profitText,
                    color = if (concealAmounts) palette.muted else plColor(holdingProfit ?: BigDecimal.ZERO),
                    style = MarketType.numberSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (concealAmounts) "持有 •••• · 成本 ¥••••"
                else "持有 ${p.units.portfolioQtyDisplay()} ${if (isGold) "克" else "份"} · 成本 ¥${p.cost.portfolioMoneyGrouped()}",
                color = palette.muted,
                style = MarketType.caption,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                source,
                color = palette.muted.copy(alpha = 0.9f),
                style = MarketType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (p.quote != null && (p.quoteExpired || p.manualGoldQuote != null)) {
            Text(
                buildString {
                    p.manualGoldQuote?.let { append("银行报价 · ${portfolioQuoteValidityLabel(it.validUntil)}") }
                    if (p.quoteExpired) {
                        if (isNotEmpty()) append(" · ")
                        append("已过期，不计入市值")
                    }
                },
                color = if (p.quoteExpired) palette.warn else palette.muted,
                style = MarketType.caption
            )
        }
    }
}
