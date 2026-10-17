package cn.market.assistant

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.Position
import java.math.BigDecimal

/**
 * One holding. The hierarchy is name -> market value -> profit, with the kind as a quiet pill and the
 * quote provenance as the last line, so a long holdings list scans as a table of numbers.
 */
@Composable
internal fun PositionCard(p: Position, concealAmounts: Boolean = false) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.card)
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

    Card(
        Modifier.fillMaxWidth().marketShadow(shape, MarketElevation.card),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        border = BorderStroke(1.dp, palette.line)
    ) {
        Column(
            Modifier.padding(geometry.lg),
            verticalArrangement = Arrangement.spacedBy(geometry.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.asset.name, color = palette.ink, style = MarketType.number, maxLines = 1)
                    Text(p.asset.symbol, color = palette.muted, style = MarketType.caption)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(geometry.chip))
                        .background(if (isGold) Color(0xFFFDF3DF) else palette.accentSoft)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (isGold) "黄金" else "基金",
                        color = if (isGold) palette.warn else palette.accent,
                        style = MarketType.caption,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("市值", color = palette.muted, style = MarketType.caption)
                    Text(
                        valueText,
                        color = if (marketValue == null) palette.inkSoft else palette.ink,
                        style = MarketType.numberLarge
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("持仓盈亏", color = palette.muted, style = MarketType.caption)
                    Text(
                        profitText,
                        color = if (concealAmounts) palette.muted else plColor(holdingProfit ?: BigDecimal.ZERO),
                        style = MarketType.number,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 2.dp).background(palette.line).height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (concealAmounts) "持有 ••••  ·  成本 ¥••••"
                    else "持有 ${p.units.portfolioQtyDisplay()} ${if (isGold) "克" else "份"}  ·  成本 ¥${p.cost.portfolioMoneyGrouped()}",
                    color = palette.muted, style = MarketType.caption, modifier = Modifier.weight(1f)
                )
                val source = p.quote?.let {
                    "${portfolioQuoteSourceLabel(p.quoteSource)} · ${portfolioQuoteTimeLabel(p.quoteTime)}"
                } ?: "无报价"
                Text(source, color = palette.muted.copy(alpha = 0.9f), style = MarketType.caption)
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
}
