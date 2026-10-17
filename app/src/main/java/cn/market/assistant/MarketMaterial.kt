package cn.market.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bottom space every screen leaves so its last row stays reachable above the floating nav bar. */
internal val MarketNavBarClearance = 104.dp

/**
 * Elevation scale. Depth comes from a soft, cool-tinted shadow on floating layers and from a hairline
 * on flat ones; dark mode drops the shadow and lets border contrast carry the same hierarchy.
 */
internal object MarketElevation {
    val card = 2.dp
    val raised = 7.dp
    val floating = 16.dp
    val modal = 28.dp
}

/** Soft drop shadow. No-op in dark mode, where surfaces separate by hairline instead of by light. */
internal fun Modifier.marketShadow(shape: Shape, level: Dp): Modifier = composed {
    if (MarketTheme.isDark) {
        this
    } else {
        val scrim = MarketTheme.colors.scrim
        shadow(
            elevation = level,
            shape = shape,
            clip = false,
            ambientColor = scrim.copy(alpha = 0.10f),
            spotColor = scrim.copy(alpha = 0.18f)
        )
    }
}

/**
 * Back affordance for sub-pages, in the same slot and with the same footprint as [MarketMenuButton]
 * so the top-left corner always means "go up one level".
 */
@Composable
internal fun MarketBackButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = MarketTheme.colors
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier
            .height(40.dp)
            .marketShadow(shape, MarketElevation.card)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.line, shape)
            .marketPressable(scaleDown = 0.9f, onClick = onClick)
            .semantics { contentDescription = "返回$label" }
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MarketIcon("back", size = 17.dp, tint = palette.accent)
        Text(label, color = palette.accent, style = MarketType.label, maxLines = 1)
    }
}

/** One destination in the floating nav bar. */
internal data class MarketNavItem(val label: String, val icon: String)

/**
 * Drawer trigger for top-level tabs. It keeps the platform position (top-left of the header) and a
 * 44dp touch target around a 40dp surface, so it is reachable with the same thumb motion on every tab.
 */
@Composable
internal fun MarketMenuButton(modifier: Modifier = Modifier) {
    val openDrawer = LocalOpenDrawer.current
    val palette = MarketTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(44.dp)
            .marketPressable(scaleDown = 0.9f, onClick = openDrawer)
            .semantics { contentDescription = "打开导航菜单" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(40.dp)
                .marketShadow(shape, MarketElevation.card)
                .clip(shape)
                .background(palette.surface)
                .border(1.dp, palette.line, shape),
            contentAlignment = Alignment.Center
        ) {
            MarketIcon("menu", size = 19.dp, tint = palette.ink)
        }
    }
}

/**
 * Floating capsule bar drawn *over* the page. The bar is opaque on purpose: a translucent bar over a
 * scrolling list reads as a rendering glitch on devices that skip the blur pass, and it also costs a
 * full-screen layer record per frame. A gradient scrim above it still dissolves the last row.
 */
@Composable
internal fun MarketNavBar(
    items: List<MarketNavItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, palette.paper.copy(alpha = 0.96f))
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 10.dp)
                .marketShadow(shape, MarketElevation.floating)
                .clip(shape)
                .background(palette.surface)
                .border(1.dp, palette.line.copy(alpha = 0.9f), shape)
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    val capsule by animateColorAsState(
                        if (selected) palette.accentSoft else Color.Transparent,
                        MarketMotion.snappy(), label = "nav_capsule"
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(geometry.card))
                            .background(capsule)
                            .marketPressable(scaleDown = 0.93f) { onSelect(index) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        MarketIcon(
                            item.icon,
                            size = 22.dp,
                            tint = if (selected) palette.accent else palette.muted,
                            filled = selected,
                            onFilled = palette.accent
                        )
                        Text(
                            item.label,
                            color = if (selected) palette.accent else palette.muted,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
