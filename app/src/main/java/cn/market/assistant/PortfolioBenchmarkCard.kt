package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PortfolioBenchmarkCard(state: PortfolioBenchmarkFeatureState) {
    val chart = state.chart
    val option = state.selectedOption
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, Color(0xFFD8D8D2))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("参考指数", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("价格收益对比仅作通用参照，不代表个性化投资基准", color = Muted, fontSize = 11.sp)
                }
                if (option != null) {
                    TextButton(onClick = { state.load(forceRefresh = true) }, enabled = !state.loading) {
                        Text(if (state.loading) "更新中" else "刷新")
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                FilterChip(
                    selected = state.selectedId == null,
                    onClick = { state.select(null) },
                    label = { Text("不对照") },
                    colors = assetChipColors()
                )
                state.options.forEach { candidate ->
                    FilterChip(
                        selected = candidate.secid == state.selectedId,
                        onClick = { state.select(candidate.secid) },
                        label = { Text(candidate.name) },
                        colors = assetChipColors()
                    )
                }
            }
            when {
                option == null -> Text("选择一个指数后，周报和月报会展示基准价格收益与账户 TWR 超额收益。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                state.loading && chart?.bars.isNullOrEmpty() -> Text("正在读取本机日线并检查更新…", color = Muted, fontSize = 11.sp)
                chart?.bars?.isNotEmpty() == true -> {
                    val range = "${chart.bars.first().day} — ${chart.bars.last().day}"
                    val cachedAt = chart.cachedAt.takeIf { it > 0L }?.let {
                        runCatching {
                            Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Shanghai"))
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        }.getOrNull()
                    }
                    Text(
                        "${chart.bars.size} 根日线 · $range · ${chart.source ?: "来源未记录"}" +
                            (cachedAt?.let { " · 本机更新 $it" } ?: ""),
                        color = Muted, fontSize = 11.sp, lineHeight = 15.sp
                    )
                    state.error?.let { Text(it, color = Gold, fontSize = 11.sp, lineHeight = 15.sp) }
                }
                state.error != null -> Text("${state.error}。周报与月报会说明基准缺口。", color = Gold, fontSize = 11.sp, lineHeight = 15.sp)
                else -> Text("尚无可用日线。周报与月报会说明基准缺口。", color = Muted, fontSize = 11.sp)
            }
        }
    }
}
