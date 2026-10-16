package cn.market.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.Position
import java.math.BigDecimal

@Composable
internal fun PositionCard(p: Position, concealAmounts: Boolean = false) {
    val marketValue = p.value
    val holdingProfit = p.holdingProfit
    Card(Modifier.fillMaxWidth(), shape = CutCornerShape(topStart = 2.dp, topEnd = 15.dp, bottomEnd = 2.dp, bottomStart = 15.dp), colors = CardDefaults.cardColors(containerColor = White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.asset.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.asset.symbol, color = Muted, fontSize = 11.sp)
                }
                Surface(shape = CutCornerShape(3.dp), color = if (p.asset.kind == AssetKind.GOLD) Color(0xFFF0E6D3) else Color(0xFFE7EBE8)) {
                    Text(if (p.asset.kind == AssetKind.GOLD) "黄金" else "基金", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = if (p.asset.kind == AssetKind.GOLD) Blue else Muted, fontSize = 10.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        p.quoteExpired -> "报价已过期"
                        marketValue == null -> "待更新价格"
                        concealAmounts -> "市值 ¥••••••"
                        else -> "市值 ¥${marketValue.portfolioMoney()}"
                    },
                    Modifier.weight(1f), color = Ink, fontSize = 13.sp
                )
                Text(
                    when {
                        concealAmounts -> "盈亏 ••••"
                        holdingProfit == null -> "估值待补 · 成本 ¥${p.cost.portfolioMoney()}"
                        else -> "盈亏 ${if (holdingProfit.signum() > 0) "+" else ""}¥${holdingProfit.portfolioMoney()}"
                    },
                    color = if (concealAmounts) Muted else plColor(holdingProfit ?: BigDecimal.ZERO),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
            Text(
                if (concealAmounts) "持有 ••••  ·  成本 ¥••••••" else
                    "持有 ${p.units.portfolioQty()} ${if (p.asset.kind == AssetKind.GOLD) "克" else "份"}  ·  成本 ¥${p.cost.portfolioMoney()}",
                color = Muted, fontSize = 11.sp
            )
            if (p.quote != null) Text(
                buildString {
                    append("${portfolioQuoteSourceLabel(p.quoteSource)} · ${portfolioQuoteTimeLabel(p.quoteTime)}")
                    p.manualGoldQuote?.let { append(" · ${portfolioQuoteValidityLabel(it.validUntil)}") }
                    if (p.quoteExpired) append(" · 已过期")
                },
                color = Muted, fontSize = 10.sp
            )
        }
    }
}
