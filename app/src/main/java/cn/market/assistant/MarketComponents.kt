package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.portfolio.Position
import java.math.BigDecimal

/**
 * Shared visual language: one section header, one card surface, one quick link and one metric block.
 * Using these instead of ad-hoc rows is what keeps spacing, weights and corners consistent.
 */
@Composable
internal fun MarketSectionHeader(
    title: String,
    caption: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val palette = MarketTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = palette.ink, style = MarketType.section)
            caption?.let { Text(it, color = palette.muted, style = MarketType.caption) }
        }
        trailing?.invoke(this)
    }
}

@Composable
internal fun MarketCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.card)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(geometry.md), verticalArrangement = Arrangement.spacedBy(geometry.sm), content = content)
    }
    if (onClick == null) {
        Card(
            modifier.fillMaxWidth().marketShadow(shape, MarketElevation.card),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.line.copy(alpha = 0.85f))
        ) { body() }
    } else {
        Card(
            modifier.fillMaxWidth().marketShadow(shape, MarketElevation.card).marketPressable(onClick = onClick),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.line)
        ) { body() }
    }
}

/** Pill quick link with a hint line; replaces a bare row of text buttons. */
@Composable
internal fun MarketQuickLink(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Row(
        modifier
            .marketShadow(RoundedCornerShape(geometry.chip), MarketElevation.card)
            .clip(RoundedCornerShape(geometry.chip))
            .background(palette.surface)
            .marketPressable(onClick = onClick)
            .padding(horizontal = geometry.md, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, color = palette.ink, style = MarketType.numberSmall, maxLines = 1)
            Text(hint, color = palette.muted, style = MarketType.caption, maxLines = 1)
        }
        Text("›", color = palette.muted, style = MarketType.numberSmall)
    }
}

/** Two-line compact stat used for counts and small aggregates. */
@Composable
internal fun MarketCompactStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Column(
        modifier
            .clip(RoundedCornerShape(geometry.chip))
            .background(palette.surface)
            .padding(horizontal = geometry.md, vertical = geometry.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = palette.muted, style = MarketType.caption)
        Text(value, color = valueColor ?: palette.ink, style = MarketType.numberLarge)
    }
}

/** Label / value block used in rows; the hairline divider carries the grouping. */
@Composable
internal fun MarketMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    withDivider: Boolean = false
) {
    val palette = MarketTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (withDivider) {
            Box(Modifier.width(1.dp).height(30.dp).background(palette.line))
            Spacer(Modifier.width(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = palette.muted, style = MarketType.caption)
            Text(value, color = valueColor, style = MarketType.number)
        }
    }
}

/**
 * Home hero: one white panel, one dominant number and a single accent action. Numbers use tabular
 * figures so they stay aligned as they change, and the accounting state is carried by hairline
 * dividers instead of decorated sub-panels.
 */
@Composable
internal fun OverviewHeroCard(
    positions: List<Position>,
    total: BigDecimal,
    holding: BigDecimal,
    cost: BigDecimal,
    realized: BigDecimal,
    accountValue: BigDecimal?,
    cashTrackingEnabled: Boolean,
    todayProfit: BigDecimal?,
    todayRate: BigDecimal?,
    todayMissingReason: String?,
    accountPeriod: Boolean,
    allPositionsPriced: Boolean,
    noPositionQuotes: Boolean,
    quoteCount: Int,
    latestQuoteAt: String?,
    concealAmounts: Boolean,
    onToggleAmountVisibility: () -> Unit,
    onRecord: () -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val headline = when {
        cashTrackingEnabled && accountValue == null -> "待补价格"
        positions.isEmpty() && accountValue == null -> "尚未录入持仓"
        accountValue == null && noPositionQuotes -> "暂无有效报价"
        else -> "¥${(accountValue ?: total).portfolioMoney()}"
    }
    val hasPrivateAmount = accountValue != null || positions.any { it.value != null }
    val amountHidden = concealAmounts && hasPrivateAmount
    val subtitle = when {
        accountValue != null -> "总资产"
        cashTrackingEnabled -> "总资产暂不可用"
        else -> "持仓市值"
    }
    val coverage = when {
        positions.isEmpty() -> "尚无持仓报价"
        latestQuoteAt != null -> "报价 $quoteCount/${positions.size} · 最近 $latestQuoteAt"
        else -> "报价 $quoteCount/${positions.size} · 尚无报价时间"
    }
    val todayColor = todayProfit?.let { value -> plColor(value) } ?: palette.muted
    val todayText = when {
        amountHidden -> "今日 ••••"
        todayProfit == null -> "今日暂不可用"
        else -> buildString {
            append("今日 ")
            if (todayProfit.signum() > 0) append("+")
            append("¥${todayProfit.portfolioMoney()}")
            todayRate?.let { rate ->
                append("  ")
                append(if (rate.signum() > 0) "+" else "")
                append(rate.setScale(2, java.math.RoundingMode.HALF_UP))
                append("%")
            }
        }
    }

    Card(
        Modifier.fillMaxWidth().marketShadow(RoundedCornerShape(geometry.panel), MarketElevation.raised),
        shape = RoundedCornerShape(geometry.panel),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        border = BorderStroke(1.dp, palette.line.copy(alpha = 0.85f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(geometry.xl),
            verticalArrangement = Arrangement.spacedBy(geometry.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, color = palette.muted, style = MarketType.label)
                Spacer(Modifier.weight(1f))
                Text(
                    if (amountHidden) "显示金额" else "隐藏金额",
                    color = palette.accent, style = MarketType.label,
                    modifier = Modifier.clickable(onClick = onToggleAmountVisibility)
                )
            }
            Text(
                if (amountHidden) "¥••••••" else headline,
                color = palette.ink,
                style = MarketType.amount
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(geometry.sm)) {
                Box(
                    Modifier.clip(RoundedCornerShape(geometry.chip))
                        .background(todayColor.copy(alpha = 0.10f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(todayText, color = todayColor, style = MarketType.numberSmall)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MarketMetric(
                    label = "持有收益",
                    value = if (amountHidden) "••••" else "${if (holding.signum() > 0) "+" else ""}¥${holding.portfolioMoney()}",
                    valueColor = plColor(holding),
                    modifier = Modifier.weight(1f)
                )
                MarketMetric(
                    label = "成本",
                    value = if (amountHidden) "••••" else "¥${cost.portfolioMoney()}",
                    valueColor = palette.inkSoft,
                    modifier = Modifier.weight(1f),
                    withDivider = true
                )
                MarketMetric(
                    label = "已实现",
                    value = if (amountHidden) "••••" else "¥${realized.portfolioMoney()}",
                    valueColor = palette.muted,
                    modifier = Modifier.weight(1f),
                    withDivider = true
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        coverage,
                        todayMissingReason.takeIf { todayProfit == null },
                        "部分持仓缺少报价，金额只统计有报价的持仓"
                            .takeIf { !allPositionsPriced && positions.isNotEmpty() }
                    ).joinToString(" · "),
                    color = palette.muted, style = MarketType.caption,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier.clip(RoundedCornerShape(geometry.chip))
                        .background(palette.accent)
                        .marketPressable(onClick = onRecord)
                        .padding(horizontal = geometry.lg, vertical = 8.dp)
                ) {
                    Text("＋ 记一笔", color = Color.White, style = MarketType.label, fontWeight = FontWeight.SemiBold)
                }
            }
            if (accountPeriod) {
                Text("按完整账户口径计算今日盈亏", color = palette.muted, style = MarketType.caption)
            }
        }
    }
}

/**
 * Compact 3-column quote tile: name, value and change are read top-to-bottom in one glance, matching
 * how mainstream finance apps fit a whole market board into one viewport.
 */
@Composable
internal fun MarketIndexTile(
    name: String,
    fallbackLabel: String,
    price: String,
    changePct: String,
    changeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Column(
        modifier
            .marketShadow(RoundedCornerShape(geometry.chip), MarketElevation.card)
            .clip(RoundedCornerShape(geometry.chip))
            .background(palette.surface)
            .marketPressable(onClick = onClick)
            .padding(horizontal = geometry.md, vertical = geometry.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(name, color = palette.muted, style = MarketType.caption, maxLines = 1)
        Text(price, color = palette.ink, style = MarketType.number, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(changePct, color = changeColor, style = MarketType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(fallbackLabel, color = palette.muted, style = MarketType.caption, maxLines = 1)
        }
    }
}

/**
 * iOS-style segmented control: one rounded track, one sliding indicator, no per-item borders.
 * Used for mutually exclusive switches (board type, report period, range).
 */
@Composable
internal fun MarketSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    if (options.isEmpty()) return
    val trackShape = RoundedCornerShape(geometry.chip)
    Box(
        modifier
            .clip(trackShape)
            .background(palette.surfaceAlt)
            .padding(3.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .clip(trackShape)
                        .background(if (selected) palette.surface else Color.Transparent)
                        .marketPressable(scaleDown = 0.96f) { onSelect(index) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) palette.ink else palette.muted,
                        style = MarketType.label,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
