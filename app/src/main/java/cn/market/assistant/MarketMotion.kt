package cn.market.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.roundToInt

/**
 * Motion language shared by every screen. iOS/HyperOS feel comes from three things: springs instead of
 * linear tweens, immediate press feedback on every tappable surface, and a large title that shrinks
 * with the scroll instead of disappearing.
 */
internal object MarketMotion {
    /** Snappy, slightly bouncy — used for presses and small state changes. */
    fun <T> snappy() = spring<T>(dampingRatio = 0.72f, stiffness = 620f)

    /** Calm spring for layout/scroll-driven changes; no overshoot. */
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 320f)

    /** Larger surface changes (sheets, expanding rows). */
    fun <T> expand() = spring<T>(dampingRatio = 0.85f, stiffness = 380f)
}

/** Scroll offset (px) of the enclosing [ScreenColumn]; drives the collapsible title. */
internal val LocalMarketScrollOffset = staticCompositionLocalOf { 0 }

/**
 * iOS-style press feedback: the surface scales down a touch while held and springs back on release.
 * The ripple is suppressed because the scale already communicates the touch.
 */
internal fun Modifier.marketPressable(
    enabled: Boolean = true,
    scaleDown: Float = 0.972f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        scale.animateTo(if (pressed && enabled) scaleDown else 1f, MarketMotion.snappy())
    }
    this
        .scale(scale.value)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Large title that collapses into a compact header as the page scrolls: the size interpolates with a
 * gentle spring, the subtitle fades out first, and the title stays left aligned like iOS/HyperOS.
 */
@Composable
internal fun MarketLargeTitle(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val offset = LocalMarketScrollOffset.current
    val collapseRange = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val progress = (offset.toFloat() / collapseRange).coerceIn(0f, 1f)
    val animated = remember { Animatable(0f) }
    LaunchedEffect(progress) { animated.animateTo(progress, MarketMotion.gentle()) }
    val collapsed = animated.value
    val titleSize = 27f - 8f * collapsed
    val subtitleAlpha = (1f - collapsed * 2.2f).coerceIn(0f, 1f)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = geometry.xs,
                end = geometry.xs,
                top = (28f - 14f * collapsed).dp,
                bottom = (10f - 4f * collapsed).dp
            ),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp)
    ) {
        if (leading != null || trailing != null) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(bottom = (8f - 5f * collapsed).dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                leading?.invoke()
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                title,
                color = palette.ink,
                fontSize = titleSize.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8 + 0.4 * collapsed).sp
            )
        }
        if (subtitle != null && subtitleAlpha > 0.01f) {
            Text(
                subtitle,
                color = palette.muted,
                fontSize = 11.sp,
                modifier = Modifier.alpha(subtitleAlpha)
            )
        }
    }
}

/** Rounds a float offset to the nearest pixel for animated translations. */
internal fun Float.toOffsetPx(): IntOffset = IntOffset(0, roundToInt())
