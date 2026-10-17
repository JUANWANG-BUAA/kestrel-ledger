package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.size
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
        Column(
            Modifier.padding(horizontal = geometry.lg, vertical = geometry.md),
            verticalArrangement = Arrangement.spacedBy(geometry.sm),
            content = content
        )
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

/** Filled search field: one rounded track, leading glyph and an inline clear affordance. */
@Composable
internal fun MarketSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.card)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MarketType.body.copy(color = palette.ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(shape)
                    .background(palette.surfaceAlt)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                MarketIcon("search", size = 18.dp, tint = palette.muted)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = palette.muted, style = MarketType.body)
                    inner()
                }
                if (value.isNotEmpty() && onClear != null) {
                    Box(
                        Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(palette.line)
                            .marketPressable(onClick = onClear),
                        contentAlignment = Alignment.Center
                    ) {
                        MarketIcon("close", size = 12.dp, tint = palette.inkSoft)
                    }
                }
            }
        }
    )
}

/** Pill quick link with a hint line; replaces a bare row of text buttons. */
@Composable
internal fun MarketQuickLink(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    onClick: () -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.card)
    Column(
        modifier
            .marketShadow(shape, MarketElevation.card)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.line, shape)
            .marketPressable(onClick = onClick)
            .padding(horizontal = geometry.md, vertical = geometry.md),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Box(
                Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(palette.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                MarketIcon(icon, size = 16.dp, tint = palette.accent)
            }
        }
        Text(label, color = palette.ink, style = MarketType.numberSmall, maxLines = 1)
        Text(hint, color = palette.muted, style = MarketType.caption, maxLines = 1)
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
 * Home hero: one accent-gradient panel that carries the account value, today's move and the three
 * accounting totals. White text on a saturated surface is the only place the app lets colour lead, so
 * the rest of the page stays quiet. Decorative circles are drawn at single-digit opacity to keep the
 * panel from reading as a flat rectangle.
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
        else -> "¥${(accountValue ?: total).portfolioMoneyGrouped()}"
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
    }.let { base ->
        if (!allPositionsPriced && positions.isNotEmpty()) "$base · 部分持仓缺报价" else base
    }
    val todayText = when {
        amountHidden -> "今日 ••••"
        todayProfit == null -> "今日暂不可用"
        else -> buildString {
            append("今日 ")
            if (todayProfit.signum() > 0) append("+")
            append("¥${todayProfit.portfolioMoney()}")
            todayRate?.let { rate ->
                append("   ")
                append(if (rate.signum() > 0) "+" else "")
                append(rate.setScale(2, java.math.RoundingMode.HALF_UP))
                append("%")
            }
        }
    }
    val onHero = Color.White
    val onHeroMuted = Color.White.copy(alpha = 0.74f)
    val heroShape = RoundedCornerShape(geometry.panel)
    val heroGradient = if (MarketTheme.isDark) {
        listOf(palette.accentSoft, palette.surface)
    } else {
        listOf(palette.accent, palette.accent.shade(0.74f))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .marketShadow(heroShape, MarketElevation.raised)
            .clip(heroShape)
            .background(
                Brush.linearGradient(
                    heroGradient,
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(1200f, 1400f)
                )
            )
    ) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension * 0.62f, center = androidx.compose.ui.geometry.Offset(size.width * 1.04f, size.height * 0.12f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.minDimension * 0.34f, center = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.96f))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = geometry.xl, vertical = geometry.lg),
            verticalArrangement = Arrangement.spacedBy(geometry.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, color = onHeroMuted, style = MarketType.heroLabel)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(geometry.chip))
                        .background(Color.White.copy(alpha = 0.16f))
                        .marketPressable(scaleDown = 0.94f, onClick = onToggleAmountVisibility)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (amountHidden) "显示金额" else "隐藏金额",
                        color = onHero,
                        style = MarketType.caption,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(
                if (amountHidden) "¥••••••" else headline,
                color = onHero,
                style = if (positions.isEmpty() && accountValue == null) MarketType.title else MarketType.heroAmount
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(geometry.sm)) {
                Box(
                    Modifier.clip(RoundedCornerShape(geometry.chip))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(todayText, color = onHero, style = MarketType.numberSmall)
                }
                if (positions.isEmpty()) {
                    Text("先记一笔买入，收益就从这里开始", color = onHeroMuted, style = MarketType.caption)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.20f)))
            if (positions.isEmpty() && accountValue == null) {
                Text(
                    "录入持仓后，这里自动汇总持有收益、成本与已实现。",
                    color = onHeroMuted,
                    style = MarketType.caption
                )
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    HeroMetric(
                        "持有收益",
                        if (amountHidden) "••••" else "${if (holding.signum() > 0) "+" else ""}¥${holding.portfolioMoneyGrouped()}",
                        Modifier.weight(1f)
                    )
                    HeroMetric("成本", if (amountHidden) "••••" else "¥${cost.portfolioMoneyGrouped()}", Modifier.weight(1f), divider = true)
                    HeroMetric("已实现", if (amountHidden) "••••" else "¥${realized.portfolioMoneyGrouped()}", Modifier.weight(1f), divider = true)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    coverage,
                    color = onHeroMuted, style = MarketType.caption,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(geometry.sm))
                Box(
                    Modifier.clip(RoundedCornerShape(geometry.chip))
                        .background(Color.White)
                        .marketPressable(scaleDown = 0.95f, onClick = onRecord)
                        .padding(horizontal = geometry.lg, vertical = 9.dp)
                ) {
                    Text(
                        "＋ 记一笔",
                        color = palette.accent,
                        style = MarketType.label,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (todayProfit == null && !todayMissingReason.isNullOrBlank()) {
                Text(
                    todayMissingReason,
                    color = onHeroMuted,
                    style = MarketType.caption,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (accountPeriod) {
                Text("按完整账户口径计算今日盈亏", color = onHeroMuted, style = MarketType.caption)
            }
        }
    }
}

/** One label/value column inside the hero; the divider is a translucent white hairline. */
@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier, divider: Boolean = false) {
    Row(modifier) {
        if (divider) {
            Box(Modifier.width(1.dp).height(30.dp).background(Color.White.copy(alpha = 0.20f)))
        }
        Column(
            Modifier.weight(1f).padding(start = if (divider) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, color = Color.White.copy(alpha = 0.70f), style = MarketType.caption)
            Text(value, color = Color.White, style = MarketType.number)
        }
    }
}

/**
 * Empty state with one glyph, one sentence and one action. Replaces the old pattern of showing a row
 * of zeroes, which read as broken data rather than as "nothing recorded yet".
 */
@Composable
internal fun MarketEmptyState(
    icon: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.card)
    Column(
        modifier
            .fillMaxWidth()
            .marketShadow(shape, MarketElevation.card)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.line, shape)
            .padding(horizontal = geometry.lg, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier.size(54.dp).clip(androidx.compose.foundation.shape.CircleShape).background(palette.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            MarketIcon(icon, size = 24.dp, tint = palette.accent)
        }
        Text(title, color = palette.ink, style = MarketType.section)
        Text(
            body,
            color = palette.muted,
            style = MarketType.caption,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(geometry.chip))
                    .background(palette.accent)
                    .marketPressable(scaleDown = 0.95f, onClick = onAction)
                    .padding(horizontal = geometry.xl, vertical = 9.dp)
            ) {
                Text(actionLabel, color = Color.White, style = MarketType.label, fontWeight = FontWeight.SemiBold)
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
 * Filter/sort chip on the shared scale: 36dp tall, accent-soft when selected, hairline when not, so
 * filter rows stop looking like a different app from the rest of the screen.
 */
@Composable
internal fun MarketChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(geometry.chip)
    Box(
        modifier
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(if (selected) palette.accentSoft else palette.surface)
            .then(if (selected) Modifier else Modifier.border(1.dp, palette.line.copy(alpha = 0.9f), shape))
            .marketPressable(scaleDown = 0.95f, onClick = onClick)
            .padding(horizontal = geometry.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) palette.accent else palette.inkSoft,
            style = MarketType.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
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
