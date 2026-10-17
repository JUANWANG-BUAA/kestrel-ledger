package cn.market.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Appearance settings: theme mode, accent, density, font scale and corner radius. Everything is a
 * display-only preference stored on this device; it never touches ledger data or backups.
 */
@Composable
internal fun AppearanceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state = MarketTheme.state ?: return
    val geometry = MarketTheme.geometry
    val colors = MarketTheme.colors

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
            .padding(bottom = MarketNavBarClearance),
        verticalArrangement = Arrangement.spacedBy(geometry.md)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MarketBackButton(label = "总览", onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("外观", color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("配色、密度、字号与圆角 · 只影响本机显示", color = colors.muted, fontSize = 10.sp)
            }
            TextButton(onClick = { state.reset() }) { Text("恢复默认", color = colors.muted, fontSize = 11.sp) }
        }

        AppearancePreview(state.accent)
        AppearanceSection("主题模式", MarketThemeMode.entries.map { it.label to (state.mode == it) }) { index ->
            state.mode = MarketThemeMode.entries[index]
        }
        AppearanceSection("强调色", MarketAccent.entries.map { "${it.label}  ${it.seed.hexLabel()}" to (state.accent == it) }) { index ->
            state.accent = MarketAccent.entries[index]
        }
        AppearanceSection("信息密度", MarketDensity.entries.map { it.label to (state.density == it) }) { index ->
            state.density = MarketDensity.entries[index]
        }
        AppearanceSection("字号", MarketFontScale.entries.map { it.label to (state.fontScale == it) }) { index ->
            state.fontScale = MarketFontScale.entries[index]
        }
        AppearanceSection("圆角", MarketCorner.entries.map { it.label to (state.corner == it) }) { index ->
            state.corner = MarketCorner.entries[index]
        }

        Text(
            "换肤即时生效并保存在本机；清空应用数据会回到默认的电蓝浅色主题。深色模式用分层表面与描边" +
                "承担层次，涨跌色与强调色会自动提高对比度。",
            color = colors.muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun AppearanceSection(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit
) {
    val colors = MarketTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEachIndexed { index, (label, selected) ->
                MarketChoiceChip(
                    label = label,
                    selected = selected,
                    modifier = Modifier.heightIn(min = 34.dp)
                ) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun AppearancePreview(selectedAccent: MarketAccent) {
    val colors = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(geometry.card),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.line)
    ) {
        Column(Modifier.padding(geometry.lg), verticalArrangement = Arrangement.spacedBy(geometry.sm)) {
            Text("预览 · 持仓卡片", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("标题与正文的层级、边框与圆角、涨跌色都会跟随下面的设置。", color = colors.muted, fontSize = 11.sp, lineHeight = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(geometry.sm)) {
                PreviewStat("持有收益", "+¥1,286.40", colors.up)
                PreviewStat("今日估算", "-0.42%", colors.down)
                PreviewStat("集中度", "42.6%", colors.warn)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MarketAccent.entries.forEach { accent ->
                    val selected = accent == selectedAccent
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(accent.seed)
                            .border(if (selected) 2.dp else 1.dp, colors.ink.copy(alpha = if (selected) 0.8f else 0.15f), CircleShape)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("强调色样本", color = colors.muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun PreviewStat(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    val colors = MarketTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = colors.muted, fontSize = 9.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun androidx.compose.ui.graphics.Color.hexLabel(): String =
    "#%02X%02X%02X".format(
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255)
    )

