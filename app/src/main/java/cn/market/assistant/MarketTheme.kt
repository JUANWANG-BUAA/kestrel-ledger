package cn.market.assistant

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Accent presets. These are the modern product colors (electric blue by default); the previous
 * warm-paper/amber identity is gone, together with the brown buttons that came with it.
 */
internal enum class MarketAccent(
    val label: String,
    val seed: Color,
    val softLight: Color,
    val softDark: Color
) {
    BLUE("电蓝", Color(0xFF2563EB), Color(0xFFE3ECFE), Color(0xFF152744)),
    TEAL("青碧", Color(0xFF0D9488), Color(0xFFDCF2EF), Color(0xFF0F2F2C)),
    VIOLET("紫罗兰", Color(0xFF7C5CFC), Color(0xFFECE7FE), Color(0xFF221B40)),
    CRIMSON("胭脂", Color(0xFFE5484D), Color(0xFFFCE5E6), Color(0xFF3B1A1D)),
    SLATE("石墨", Color(0xFF334155), Color(0xFFE6EAEF), Color(0xFF1C2531))
}

internal enum class MarketThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

internal enum class MarketDensity(val label: String, val scale: Float) {
    COMPACT("紧凑", 0.9f),
    STANDARD("标准", 1.0f),
    COMFORTABLE("宽松", 1.15f)
}

internal enum class MarketFontScale(val label: String, val scale: Float) {
    COMPACT("小", 0.94f),
    STANDARD("标准", 1.0f),
    LARGE("大", 1.12f)
}

internal enum class MarketCorner(val label: String, val card: Dp, val panel: Dp) {
    SHARP("直角", 10.dp, 14.dp),
    STANDARD("标准", 16.dp, 22.dp),
    ROUND("圆润", 22.dp, 28.dp)
}

/**
 * One palette per mode: cool neutral canvas, white surfaces, a single strong accent and semantic
 * status colors. Border contrast carries hierarchy in dark mode instead of shadow.
 */
internal data class MarketColors(
    val ink: Color,
    val inkSoft: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val paper: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val up: Color,
    val down: Color,
    val warn: Color,
    val scrim: Color
)

internal data class MarketGeometry(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val card: Dp,
    val panel: Dp,
    val chip: Dp
) {
    companion object {
        fun of(corner: MarketCorner, density: MarketDensity): MarketGeometry {
            fun space(value: Dp) = value * density.scale
            return MarketGeometry(
                xs = space(3.dp),
                sm = space(6.dp),
                md = space(10.dp),
                lg = space(12.dp),
                xl = space(16.dp),
                card = corner.card,
                panel = corner.panel,
                chip = corner.card * 0.72f
            )
        }
    }
}

/**
 * One type scale for every screen. Numbers use tabular figures so quotes and amounts stay aligned in
 * columns, which is what makes a finance layout feel precise instead of hand-placed.
 */
internal object MarketType {
    val heroAmount = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp, fontFeatureSettings = "tnum")
    val amount = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.0).sp, fontFeatureSettings = "tnum")
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val section = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val numberLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, fontFeatureSettings = "tnum")
    val number = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val numberSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val nav = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    val label = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp)
    val heroLabel = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
}

/** Darker (factor < 1) or lighter (factor > 1) version of a colour, used for the hero gradient. */
internal fun Color.shade(factor: Float): Color = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha
)

@Stable
internal class MarketThemeState(
    accent: MarketAccent = MarketAccent.BLUE,
    mode: MarketThemeMode = MarketThemeMode.SYSTEM,
    density: MarketDensity = MarketDensity.STANDARD,
    fontScale: MarketFontScale = MarketFontScale.STANDARD,
    corner: MarketCorner = MarketCorner.STANDARD
) {
    var accent by mutableStateOf(accent)
    var mode by mutableStateOf(mode)
    var density by mutableStateOf(density)
    var fontScale by mutableStateOf(fontScale)
    var corner by mutableStateOf(corner)

    fun reset() {
        accent = MarketAccent.BLUE
        mode = MarketThemeMode.SYSTEM
        density = MarketDensity.STANDARD
        fontScale = MarketFontScale.STANDARD
        corner = MarketCorner.STANDARD
    }
}

internal class MarketThemeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): MarketThemeState = MarketThemeState(
        accent = enumOf(preferences.getString(KEY_ACCENT, null), MarketAccent.BLUE),
        mode = enumOf(preferences.getString(KEY_MODE, null), MarketThemeMode.SYSTEM),
        density = enumOf(preferences.getString(KEY_DENSITY, null), MarketDensity.STANDARD),
        fontScale = enumOf(preferences.getString(KEY_FONT_SCALE, null), MarketFontScale.STANDARD),
        corner = enumOf(preferences.getString(KEY_CORNER, null), MarketCorner.STANDARD)
    )

    fun save(state: MarketThemeState) {
        preferences.edit()
            .putString(KEY_ACCENT, state.accent.name)
            .putString(KEY_MODE, state.mode.name)
            .putString(KEY_DENSITY, state.density.name)
            .putString(KEY_FONT_SCALE, state.fontScale.name)
            .putString(KEY_CORNER, state.corner.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: fallback.name) }.getOrDefault(fallback)

    private companion object {
        const val PREFERENCES = "market_theme"
        const val KEY_ACCENT = "accent"
        const val KEY_MODE = "mode"
        const val KEY_DENSITY = "density"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_CORNER = "corner"
    }
}

private val LocalMarketColors = staticCompositionLocalOf {
    lightColors(MarketAccent.BLUE)
}
private val LocalMarketGeometry = staticCompositionLocalOf { MarketGeometry.of(MarketCorner.STANDARD, MarketDensity.STANDARD) }
private val LocalMarketDark = staticCompositionLocalOf { false }
private val LocalMarketThemeState = staticCompositionLocalOf<MarketThemeState?> { null }

internal object MarketTheme {
    val colors: MarketColors
        @Composable @ReadOnlyComposable get() = LocalMarketColors.current

    val geometry: MarketGeometry
        @Composable @ReadOnlyComposable get() = LocalMarketGeometry.current

    /** True when the resolved palette is dark; material helpers switch shadow for hairline. */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalMarketDark.current

    val state: MarketThemeState?
        @Composable @ReadOnlyComposable get() = LocalMarketThemeState.current
}

/** Light palette: cool neutral canvas, white surfaces, ink text and one strong accent. */
internal fun lightColors(accent: MarketAccent) = MarketColors(
    ink = Color(0xFF0E1520),
    inkSoft = Color(0xFF48535F),
    muted = Color(0xFF7C8794),
    accent = accent.seed,
    accentSoft = accent.softLight,
    paper = Color(0xFFF2F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEDF1F7),
    line = Color(0xFFE3E8F0),
    up = Color(0xFFE5484D),
    down = Color(0xFF12A150),
    warn = Color(0xFFD97706),
    scrim = Color(0xFF0E1520)
)

/** Dark palette: same hue family, lowered luminance, borders instead of shadows. */
internal fun darkColors(accent: MarketAccent) = MarketColors(
    ink = Color(0xFFE9EEF4),
    inkSoft = Color(0xFFB9C4D0),
    muted = Color(0xFF8B98A6),
    accent = accent.let { lighten(it.seed) },
    accentSoft = accent.softDark,
    paper = Color(0xFF0B0F14),
    surface = Color(0xFF141A21),
    surfaceAlt = Color(0xFF1B232C),
    line = Color(0xFF27313C),
    up = Color(0xFFFF6369),
    down = Color(0xFF30C48D),
    warn = Color(0xFFE0A94A),
    scrim = Color(0xFF000000)
)

private fun lighten(color: Color): Color = Color(
    red = (color.red + 0.18f).coerceAtMost(1f),
    green = (color.green + 0.18f).coerceAtMost(1f),
    blue = (color.blue + 0.18f).coerceAtMost(1f),
    alpha = color.alpha
)

/**
 * Provides palette, geometry and the user font scale for the whole app. The font scale is applied
 * through [LocalDensity], so every existing screen scales without per-text edits.
 */
@Composable
internal fun MarketThemeProvider(state: MarketThemeState, content: @Composable () -> Unit) {
    val dark = when (state.mode) {
        MarketThemeMode.SYSTEM -> isSystemInDarkTheme()
        MarketThemeMode.LIGHT -> false
        MarketThemeMode.DARK -> true
    }
    val colors = remember(state.accent, dark) {
        if (dark) darkColors(state.accent) else lightColors(state.accent)
    }
    val geometry = remember(state.corner, state.density) { MarketGeometry.of(state.corner, state.density) }
    val density = LocalDensity.current
    val scaled = remember(density, state.fontScale) {
        Density(density.density, density.fontScale * state.fontScale.scale)
    }
    CompositionLocalProvider(
        LocalMarketColors provides colors,
        LocalMarketGeometry provides geometry,
        LocalMarketDark provides dark,
        LocalMarketThemeState provides state,
        LocalDensity provides scaled,
        content = content
    )
}

/** Persists theme choices; called by the appearance screen. */
@Composable
internal fun rememberMarketThemeStore(context: Context): MarketThemeStore =
    remember(context) { MarketThemeStore(context) }
