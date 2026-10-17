package cn.market.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.assistant.AssistantChart
import cn.market.assistant.domain.assistant.AssistantChartKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Inline chart produced by a tool. The assistant only supplies shapes and values; drawing and
 * interaction come from [MarketSeriesChart], so the conversation chart behaves like every other
 * chart in the app (crosshair, tooltip, right-hand axis, refresh pulse).
 */
@Composable
internal fun AssistantChartCard(chart: AssistantChart, modifier: Modifier = Modifier) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.card)
    val seriesColors = listOf(palette.accent, palette.warn, palette.down, palette.muted)
    val lines = remember(chart) {
        chart.series.mapIndexed { index, series ->
            SeriesLine(
                name = series.name,
                color = seriesColors[index % seriesColors.size],
                points = series.points.map { SeriesPoint(it.label, it.value) }
            )
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.line, shape)
            .padding(geometry.lg),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(chart.title, color = palette.ink, style = MarketType.numberSmall, fontWeight = FontWeight.SemiBold)
                Text(chart.subtitle ?: chart.source, color = palette.muted, style = MarketType.caption, maxLines = 2)
            }
            chart.unit?.let {
                Box(
                    Modifier.clip(RoundedCornerShape(geometry.chip))
                        .background(palette.surfaceAlt)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) { Text(it, color = palette.muted, style = MarketType.caption) }
            }
        }
        MarketSeriesChart(
            lines = lines,
            unit = chart.unit,
            kind = when (chart.kind) {
                AssistantChartKind.BAR -> SeriesChartKind.BAR
                AssistantChartKind.LINE -> SeriesChartKind.LINE
                AssistantChartKind.AREA -> SeriesChartKind.AREA
            },
            height = 140
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(geometry.md)
        ) {
            chart.series.forEachIndexed { index, series ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(seriesColors[index % seriesColors.size]))
                    Text(
                        "${series.name} ${series.points.lastOrNull()?.value?.let(::formatSeriesValue) ?: "—"}",
                        color = palette.muted, style = MarketType.caption, maxLines = 1
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(chartObservedTime(chart.observedAt), color = palette.muted, style = MarketType.caption)
        }
    }
}

private fun chartObservedTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value.take(16).replace('T', ' '))

/** Three pulsing dots shown while the model is thinking and no tool has reported yet. */
@Composable
internal fun AssistantTypingDots(modifier: Modifier = Modifier) {
    val palette = MarketTheme.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1100),
            androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "typing_phase"
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val active = phase.toInt() == index
            Box(
                Modifier.size(if (active) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = if (active) 0.9f else 0.35f))
            )
        }
    }
}
