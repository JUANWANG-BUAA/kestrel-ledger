package cn.market.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bottom space every screen leaves so its last row stays reachable above the floating nav bar. */
internal val MarketNavBarClearance = 104.dp

/**
 * Elevation scale. Depth comes from a soft, warm-tinted shadow on floating layers and from a hairline
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
            ambientColor = scrim.copy(alpha = 0.14f),
            spotColor = scrim.copy(alpha = 0.26f)
        )
    }
}

/**
 * Translucent material for surfaces that sit above scrolling content: the page stays faintly visible
 * through it and the top edge catches a hairline sheen, which is what reads as glass. There is no
 * backdrop-blur pass, so the alpha ramp does the work instead.
 */
internal fun Modifier.marketGlass(
    shape: Shape,
    alphaTop: Float = 0.90f,
    alphaBottom: Float = 0.76f
): Modifier = composed {
    val palette = MarketTheme.colors
    val top = if (MarketTheme.isDark) 0.96f else alphaTop
    val bottom = if (MarketTheme.isDark) 0.86f else alphaBottom
    val sheen = Color.White.copy(alpha = if (MarketTheme.isDark) 0.05f else 0.55f)
    clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(palette.surface.copy(alpha = top), palette.surface.copy(alpha = bottom))
            )
        )
        .drawWithContent {
            drawContent()
            drawLine(sheen, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
        }
        .border(1.dp, palette.line.copy(alpha = if (MarketTheme.isDark) 0.9f else 0.7f), shape)
}

/**
 * The page recorded into a graphics layer by the host screen. Re-drawing that same layer behind the
 * bar (offset and blurred) is what turns the translucent material into real frosted glass: no second
 * composition, just one extra layer draw.
 */
internal val LocalMarketBackdrop = staticCompositionLocalOf<androidx.compose.ui.graphics.layer.GraphicsLayer?> { null }

/** Radius of the backdrop blur; ignored below API 31 where RenderEffect does not exist. */
private val MarketGlassBlur = 20.dp

/** One destination in the floating nav bar. */
internal data class MarketNavItem(val label: String, val icon: String)

/**
 * Drawer trigger for top-level tabs. It keeps the platform position (top-left of the header) and a
 * 44dp touch target around a 38dp surface, so it is reachable with the same thumb motion on every tab.
 */
@Composable
internal fun MarketMenuButton(modifier: Modifier = Modifier) {
    val openDrawer = LocalOpenDrawer.current
    val palette = MarketTheme.colors
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier
            .size(44.dp)
            .marketPressable(scaleDown = 0.9f, onClick = openDrawer)
            .semantics { contentDescription = "打开导航菜单" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(38.dp)
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
 * Floating capsule bar drawn *over* the page instead of reserving space in the scaffold, so content
 * keeps scrolling underneath the translucent material and fades out before it reaches the bar.
 */
@Composable
internal fun MarketNavBar(
    items: List<MarketNavItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    val palette = MarketTheme.colors
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, palette.paper.copy(alpha = 0.90f))
                    )
                )
        )
        val backdrop = LocalMarketBackdrop.current
        val blurPx = with(androidx.compose.ui.platform.LocalDensity.current) { MarketGlassBlur.toPx() }
        var barOrigin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 10.dp)
                .marketShadow(shape, MarketElevation.floating)
                .marketGlass(shape)
                .onGloballyPositioned { barOrigin = it.positionInRoot() }
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            if (backdrop != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            renderEffect = BlurEffect(blurPx, blurPx, androidx.compose.ui.graphics.TileMode.Clamp)
                        }
                        .drawWithContent {
                            // Re-draw the recorded page shifted so this box shows exactly the pixels
                            // that sit behind the bar, then let the layer's RenderEffect blur them.
                            clipRect {
                                translate(left = -barOrigin.x, top = -barOrigin.y) {
                                    drawLayer(backdrop)
                                }
                            }
                        }
                )
            }
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
                            .clip(RoundedCornerShape(20.dp))
                            .background(capsule)
                            .marketPressable(scaleDown = 0.93f) { onSelect(index) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        NavigationGlyph(item.icon, selected, tint = if (selected) palette.ink else palette.muted)
                        Text(
                            item.label,
                            color = if (selected) palette.ink else palette.muted,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
