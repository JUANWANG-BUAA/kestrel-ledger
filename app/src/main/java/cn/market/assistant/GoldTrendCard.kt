package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.GoldDailyPoint
import java.math.BigDecimal

/**
 * Daily gold trend built from the app's own observations. Domestic (CNY/g) and the international
 * quote converted to CNY/g share one axis, so the gap between them is the premium instead of a
 * meaningless overlay of two different units. Press and drag the chart to read a day's values.
 */
@Composable
internal fun GoldTrendCard(
    domestic: List<GoldDailyPoint>,
    internationalCny: List<GoldDailyPoint>,
    premium: List<GoldDailyPoint>,
    modifier: Modifier = Modifier
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    var rangeDays by remember { mutableStateOf(30) }
    val shape = RoundedCornerShape(geometry.card)
    val domesticSlice = if (rangeDays > 0) domestic.takeLast(rangeDays) else domestic
    val internationalSlice = if (rangeDays > 0) internationalCny.takeLast(rangeDays) else internationalCny
    val lines = buildList {
        if (domesticSlice.size >= 1) {
            add(SeriesLine("国内 Au99.99", palette.accent, domesticSlice.map { SeriesPoint(it.day, it.value) }))
        }
        if (internationalSlice.size >= 1) {
            add(SeriesLine("国际折算", palette.warn, internationalSlice.map { SeriesPoint(it.day, it.value) }))
        }
    }
    val domesticChange = domesticSlice.takeIf { it.size >= 2 }?.let { it.last().value - it.first().value }
    val premiumLast = premium.lastOrNull()

    Card(
        modifier.fillMaxWidth().marketShadow(shape, MarketElevation.card),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        border = BorderStroke(1.dp, palette.line)
    ) {
        Column(
            Modifier.padding(geometry.lg),
            verticalArrangement = Arrangement.spacedBy(geometry.sm)
        ) {
            MarketSectionHeader(
                "金价走势",
                caption = if (domesticSlice.isEmpty()) "尚无每日记录" else "本机每日观测 · ${domesticSlice.size} 天 · 按住拖动查看"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7 to "7日", 30 to "30日", 90 to "90日", 0 to "全部").forEach { (days, label) ->
                        MarketChoiceChip(label = label, selected = rangeDays == days) { rangeDays = days }
                    }
                }
            }
            if (lines.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(geometry.chip))
                        .background(palette.surfaceAlt)
                        .padding(geometry.lg),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("再积累一个观测日即可画出走势", color = palette.ink, style = MarketType.numberSmall)
                    Text(
                        "金价源只提供最新报价，应用会在每次刷新时按上海日期记录一条；国内与人民币折算的国际金价共用人民币/克坐标。",
                        color = palette.muted, style = MarketType.caption, lineHeight = 16.sp
                    )
                }
            } else {
                MarketSeriesChart(
                    lines = lines,
                    unit = "CNY / 克",
                    kind = SeriesChartKind.AREA,
                    height = 158
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GoldLegendDot(palette.accent, "国内 Au99.99", domesticSlice.lastOrNull()?.value?.let { "¥${it.twoDecimals()}" } ?: "—")
                    Box(Modifier.size(10.dp))
                    GoldLegendDot(
                        palette.warn,
                        if (internationalSlice.size >= 2) "国际折算" else "国际折算 · 今日起记录",
                        internationalSlice.lastOrNull()?.value?.let { "¥${it.twoDecimals()}" } ?: "—"
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    domesticChange?.let {
                        Text(
                            (if (it > 0) "+" else "") + it.twoDecimals() + " 元/克",
                            color = plColor(BigDecimal.valueOf(it)),
                            style = MarketType.caption,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "国内外均折算为人民币/克；不是交易所历史，只反映本机观测。",
                        color = palette.muted, style = MarketType.caption, modifier = Modifier.weight(1f)
                    )
                    premiumLast?.let {
                        Text(
                            "溢价 ${if (it.value >= 0) "+" else ""}${it.value.twoDecimals()}",
                            color = if (it.value >= 0) palette.up else palette.down,
                            style = MarketType.caption,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoldLegendDot(color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(6.dp)) { drawCircle(color) }
        Text("$label $value", color = MarketTheme.colors.muted, style = MarketType.caption)
    }
}

private fun Double.twoDecimals(): String = String.format("%.2f", this)
