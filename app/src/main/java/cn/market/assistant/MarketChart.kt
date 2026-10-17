package cn.market.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.domain.quant.MacdValue
import cn.market.assistant.domain.quant.MarketIndicators
import kotlin.math.roundToInt
import androidx.compose.runtime.ReadOnlyComposable

private val ChartInk: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.ink
private val ChartMuted: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.muted
private val ChartAccent: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accent
private val ChartSoftAccent: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.accentSoft
private val ChartGold: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.warn
private val ChartWhite: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.surface
private val ChartUp: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.up
private val ChartDown: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.down

internal enum class MarketChartStyle(val label: String) { LINE("走势"), CANDLE("K线") }

@Composable
internal fun MarketChart(
    visibleBars: List<MarketOhlcBar>, style: MarketChartStyle, showMa5: Boolean, showMa20: Boolean, showVolume: Boolean,
    selectedIndex: Int, modifier: Modifier = Modifier, onIndexSelected: (Int) -> Unit
) {
    if (visibleBars.isEmpty()) return
    val chartReveal = remember(visibleBars.size, visibleBars.first().day, visibleBars.last().day) { Animatable(0f) }
    LaunchedEffect(chartReveal) { chartReveal.animateTo(1f, tween(360)) }
    val selected = selectedIndex.coerceIn(0, visibleBars.lastIndex)
    val touchMod = modifier.graphicsLayer {
        alpha = chartReveal.value
        scaleY = .92f + .08f * chartReveal.value
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
    }.pointerInput(visibleBars.size) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalDx = 0f
            var totalDy = 0f
            var selecting = false
            fun select(x: Float) {
                val padding = 6.dp.toPx()
                val usable = (size.width - padding * 2).coerceAtLeast(1f)
                val fraction = ((x - padding) / usable).coerceIn(0f, 1f)
                onIndexSelected((fraction * (visibleBars.size - 1)).roundToInt())
            }
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.pressed) {
                    val delta = change.position - change.previousPosition
                    totalDx += delta.x
                    totalDy += delta.y
                    if (!selecting && kotlin.math.abs(totalDx) > touchSlop &&
                        kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)
                    ) selecting = true
                    if (selecting) {
                        select(change.position.x)
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
            if (!selecting && kotlin.math.abs(totalDx) <= touchSlop && kotlin.math.abs(totalDy) <= touchSlop) {
                select(down.position.x)
            }
        }
    }
    val softAccent = ChartSoftAccent
    val accent = ChartAccent
    val gold = ChartGold
    val up = ChartUp
    val down = ChartDown
    val surface = ChartWhite
    val muted = ChartMuted
    val gridColor = MarketTheme.colors.line.copy(alpha = 0.75f)
    Canvas(touchMod) {
        val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx()
        val volumeHeight = if (showVolume) 30.dp.toPx() else 0f
        val top = 10.dp.toPx(); val bottom = size.height - 8.dp.toPx() - volumeHeight
        val min = visibleBars.minOf { it.low }
        val max = visibleBars.maxOf { it.high }
        val range = (max - min).coerceAtLeast(0.000001)
        repeat(4) { row ->
            val y = top + (bottom - top) * row / 3f
                drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        fun y(value: Double) = bottom - ((value - min) / range).toFloat() * (bottom - top)
        if (style == MarketChartStyle.LINE) {
            val path = Path()
            visibleBars.forEachIndexed { index, bar ->
                val x = left + (right - left) * index / (visibleBars.size - 1).coerceAtLeast(1)
                val pointY = y(bar.close)
                if (index == 0) path.moveTo(x, pointY) else path.lineTo(x, pointY)
            }
            if (visibleBars.size > 1) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(right, bottom); lineTo(left, bottom); close()
                }
                drawPath(fill, softAccent.copy(alpha = .52f), style = Fill)
            }
            drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            if (visibleBars.size == 1) drawCircle(accent, 4.dp.toPx(), Offset(left, y(visibleBars.first().close)))
            fun movingAverage(period: Int, color: Color) {
                if (visibleBars.size < period) return
                val average = Path()
                for (i in period - 1 until visibleBars.size) {
                    val mean = visibleBars.subList(i - period + 1, i + 1).map { it.close }.average()
                    val x = left + (right - left) * i / (visibleBars.size - 1).coerceAtLeast(1)
                    if (i == period - 1) average.moveTo(x, y(mean)) else average.lineTo(x, y(mean))
                }
                drawPath(average, color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
            }
            if (showMa5) movingAverage(5, gold)
            if (showMa20) movingAverage(20, accent)
        } else {
            val slot = (right - left) / visibleBars.size
            val bodyWidth = (slot * .58f).coerceAtMost(8.dp.toPx()).coerceAtLeast(2.dp.toPx())
            visibleBars.forEachIndexed { index, bar ->
                val x = left + slot * (index + .5f)
                val color = if (bar.close >= bar.open) up else down
                drawLine(color, Offset(x, y(bar.high)), Offset(x, y(bar.low)), 1.2.dp.toPx())
                val topBody = y(maxOf(bar.open, bar.close))
                val bottomBody = y(minOf(bar.open, bar.close))
                drawRect(color, topLeft = Offset(x - bodyWidth / 2, topBody), size = androidx.compose.ui.geometry.Size(bodyWidth, (bottomBody - topBody).coerceAtLeast(1.5.dp.toPx())))
            }
            fun movingAverage(period: Int, color: Color) {
                if (visibleBars.size < period) return
                val average = Path()
                for (i in period - 1 until visibleBars.size) {
                    val mean = visibleBars.subList(i - period + 1, i + 1).map { it.close }.average()
                    val x = left + slot * (i + .5f)
                    if (i == period - 1) average.moveTo(x, y(mean)) else average.lineTo(x, y(mean))
                }
                drawPath(average, color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
            }
            if (showMa5) movingAverage(5, gold)
            if (showMa20) movingAverage(20, accent)
        }
        if (showVolume) {
            val maxVolume = visibleBars.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0
            val volTop = size.height - volumeHeight
            val volBottom = size.height - 3.dp.toPx()
            val slot = (right - left) / visibleBars.size
            visibleBars.forEachIndexed { index, bar ->
                val height = ((bar.volume / maxVolume).toFloat() * (volBottom - volTop)).coerceAtLeast(1.dp.toPx())
                val x = left + slot * (index + .5f)
                val color = if (bar.close >= bar.open) up else down
                drawRect(color.copy(alpha = .5f), Offset(x - (slot * .55f) / 2, volBottom - height), Size((slot * .55f).coerceAtLeast(1.dp.toPx()), height))
            }
        }
        if (visibleBars.isNotEmpty()) {
            val slot = (right - left) / visibleBars.size
            val x = if (style == MarketChartStyle.LINE) left + (right - left) * selected / (visibleBars.size - 1).coerceAtLeast(1) else left + slot * (selected + .5f)
            drawLine(muted.copy(alpha = 0.6f), Offset(x, top), Offset(x, if (showVolume) size.height - volumeHeight else bottom), 1.dp.toPx())
            val selectedBar = visibleBars[selected]
            val pointY = if (style == MarketChartStyle.LINE) y(selectedBar.close) else y((selectedBar.open + selectedBar.close) / 2.0)
            drawCircle(surface, 5.dp.toPx(), Offset(x, pointY))
            drawCircle(accent, 3.dp.toPx(), Offset(x, pointY))
        }
    }
}

@Composable
internal fun IndicatorPane(bars: List<MarketOhlcBar>, selectedIndex: Int, macd: Boolean) {
    if (bars.isEmpty()) return
    val index = selectedIndex.coerceIn(0, bars.lastIndex)
    val muted = ChartMuted
    if (!macd) {
        val accent = ChartAccent
        val values = MarketIndicators.rsi(bars)
        val selected = values.getOrNull(index)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RSI(14)", color = ChartInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(selected?.let { String.format("%.1f", it) } ?: "样本不足", color = ChartAccent, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("参考 30 / 70", color = ChartMuted, fontSize = 11.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(62.dp)) {
            val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx()
            fun y(value: Double) = size.height * (1.0 - value / 100.0).toFloat()
            listOf(30.0, 50.0, 70.0).forEach { value ->
                drawLine(muted.copy(alpha = 0.25f), Offset(left, y(value)), Offset(right, y(value)), 1.dp.toPx())
            }
            val path = Path(); var started = false
            values.forEachIndexed { i, value ->
                if (value == null) return@forEachIndexed
                val x = left + (right - left) * i / (bars.size - 1).coerceAtLeast(1)
                if (!started) { path.moveTo(x, y(value)); started = true } else path.lineTo(x, y(value))
            }
            drawPath(path, accent, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round))
            val cursorX = left + (right - left) * index / (bars.size - 1).coerceAtLeast(1)
            drawLine(muted.copy(alpha = 0.6f), Offset(cursorX, 0f), Offset(cursorX, size.height), 1.dp.toPx())
        }
    } else {
        val values = MarketIndicators.macd(bars)
        val selected = values.getOrNull(index)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MACD(12,26,9)", color = ChartInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(selected?.let { "DIF ${String.format("%.3f", it.dif)} · DEA ${String.format("%.3f", it.dea)}" } ?: "—",
                color = ChartAccent, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("柱 ${selected?.let { String.format("%.3f", it.histogram) } ?: "—"}", color = ChartMuted, fontSize = 11.sp)
        }
        val up = ChartUp
        val down = ChartDown
        val accent = ChartAccent
        val gold = ChartGold
        Canvas(Modifier.fillMaxWidth().height(62.dp)) {
            val left = 6.dp.toPx(); val right = size.width - 6.dp.toPx(); val center = size.height / 2
            val maxAbs = values.maxOfOrNull { maxOf(kotlin.math.abs(it.dif), kotlin.math.abs(it.dea), kotlin.math.abs(it.histogram)) }
                ?.coerceAtLeast(0.000001) ?: 1.0
            drawLine(muted.copy(alpha = 0.25f), Offset(left, center), Offset(right, center), 1.dp.toPx())
            val slot = (right - left) / values.size
            values.forEachIndexed { i, value ->
                val height = (kotlin.math.abs(value.histogram) / maxAbs).toFloat() * (size.height * .43f)
                val x = left + slot * (i + .5f)
                drawRect(if (value.histogram >= 0) up.copy(alpha = .7f) else down.copy(alpha = .7f),
                    Offset(x - (slot * .55f).coerceAtLeast(1.dp.toPx()) / 2, if (value.histogram >= 0) center - height else center),
                    Size((slot * .55f).coerceAtLeast(1.dp.toPx()), height.coerceAtLeast(1.dp.toPx())))
            }
            fun lineFor(value: (MacdValue) -> Double, color: Color) {
                val path = Path()
                values.forEachIndexed { i, item ->
                    val x = left + (right - left) * i / (values.size - 1).coerceAtLeast(1)
                    val y = center - (value(item) / maxAbs).toFloat() * (size.height * .43f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round))
            }
            lineFor({ it.dif }, accent); lineFor({ it.dea }, gold)
            val cursorX = left + (right - left) * index / (values.size - 1).coerceAtLeast(1)
            drawLine(muted.copy(alpha = 0.6f), Offset(cursorX, 0f), Offset(cursorX, size.height), 1.dp.toPx())
        }
    }
}

internal fun filteredChartBars(bars: List<MarketOhlcBar>, period: ChartPeriod): List<MarketOhlcBar> = when (period) {
    ChartPeriod.INTRADAY -> {
        val lastDay = bars.lastOrNull()?.day?.take(8)
        bars.filter { it.day.take(8) == lastDay }
    }
    ChartPeriod.FIVE_DAY -> {
        val days = bars.map { it.day.take(8) }.distinct().takeLast(5).toSet()
        bars.filter { it.day.take(8) in days }
    }
    else -> bars
}

internal fun chartDate(value: String): String = when {
    value.length >= 12 && value.all(Char::isDigit) -> "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)} ${value.substring(8, 10)}:${value.substring(10, 12)}"
    value.length == 8 && value.all(Char::isDigit) -> "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
    else -> value
}

internal fun formatVolume(value: Double): String = when {
    value >= 100_000_000 -> String.format("%.2f亿", value / 100_000_000)
    value >= 10_000 -> String.format("%.1f万", value / 10_000)
    value > 0 -> String.format("%.0f", value)
    else -> "—"
}
