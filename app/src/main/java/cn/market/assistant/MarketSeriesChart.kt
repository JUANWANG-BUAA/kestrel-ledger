package cn.market.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** One labelled sample; the label doubles as the x value (dates or short labels). */
internal data class SeriesPoint(val label: String, val value: Double)

/** A named, coloured line on [MarketSeriesChart]. Gaps become visible breaks instead of zeros. */
internal data class SeriesLine(val name: String, val color: Color, val points: List<SeriesPoint>)

internal enum class SeriesChartKind { LINE, AREA, BAR }

/**
 * Shared line chart for gold, the assistant and any other local series.
 *
 * It deliberately owns the interaction instead of leaving it to each caller: press and drag anywhere
 * to move a crosshair, keep the release position so the tooltip stays readable, and every value on the
 * chart comes with a right-side axis label and per-day tooltip. The last point pulses so a refresh is
 * visible even when the line only moves by a pixel.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MarketSeriesChart(
    lines: List<SeriesLine>,
    modifier: Modifier = Modifier,
    unit: String? = null,
    kind: SeriesChartKind = SeriesChartKind.AREA,
    height: Int = 150,
    valueFormatter: (Double) -> String = ::formatSeriesValue,
    pulseLatest: Boolean = true
) {
    val palette = MarketTheme.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = palette.muted, fontWeight = FontWeight.Normal)
    val tooltipStyle = TextStyle(fontSize = 11.sp, color = palette.ink, fontWeight = FontWeight.Medium)
    val reveal = remember(lines) { Animatable(0f) }
    // Two short pulses after the series changes, then the chart settles: a refresh is visible without
    // an infinite animation redrawing and re-measuring text every frame.
    val halo = remember { Animatable(0f) }
    LaunchedEffect(lines) {
        reveal.animateTo(1f, tween(460))
        if (pulseLatest) {
            repeat(2) {
                halo.snapTo(0f)
                halo.animateTo(1f, tween(600))
            }
        }
    }
    val labels = remember(lines) { buildLabels(lines) }
    var selected by remember(labels) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier.fillMaxWidth().height(height.dp)
            // Mouse/trackpad hover works on tablets and the emulator; touch uses press-and-drag.
            .pointerInput(labels) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Move) continue
                        val position = event.changes.firstOrNull()?.position ?: continue
                        if (labels.isEmpty()) continue
                        val left = 4.dp.toPx()
                        val right = size.width - 50.dp.toPx()
                        val fraction = ((position.x - left) / (right - left)).coerceIn(0f, 1f)
                        selected = (fraction * (labels.size - 1)).roundToInt()
                    }
                }
            }
            .pointerInput(labels) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDx = 0f
                    var totalDy = 0f
                    var selecting = false
                    fun pick(x: Float) {
                        if (labels.isEmpty()) return
                        val left = 4.dp.toPx()
                        val right = size.width - 50.dp.toPx()
                        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
                        selected = (fraction * (labels.size - 1)).roundToInt()
                    }
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            val delta = change.position - change.previousPosition
                            totalDx += delta.x
                            totalDy += delta.y
                            // A vertical drag belongs to the page scroll; only claim mostly-horizontal
                            // gestures for the crosshair after the touch slop is crossed.
                            if (!selecting && kotlin.math.abs(totalDx) > touchSlop &&
                                kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)
                            ) selecting = true
                            if (selecting) {
                                pick(change.position.x)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!selecting && kotlin.math.abs(totalDx) <= touchSlop && kotlin.math.abs(totalDy) <= touchSlop) {
                        pick(down.position.x)
                    }
                }
            }
    ) {
        if (labels.isEmpty()) return@Canvas
        val left = 4.dp.toPx()
        val right = size.width - 50.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val values = lines.flatMap { it.points.map { point -> point.value } }
        if (values.isEmpty()) return@Canvas
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val span = (max - min).coerceAtLeast(abs(max).coerceAtLeast(1.0) * 0.012)
        val low = min - span * 0.10
        val high = max + span * 0.10
        fun xAt(index: Int) = left + (right - left) * index / (labels.size - 1).coerceAtLeast(1)
        fun yAt(value: Double) = bottom - ((value - low) / (high - low)).toFloat() * (bottom - top)
        val indexByLabel = labels.withIndex().associate { it.value to it.index }

        // The latest-value pill sits on the right axis; skip the grid label it would cover.
        val primarySeries = lines.firstOrNull { it.points.isNotEmpty() }
        val primaryLast = primarySeries?.points?.lastOrNull()
        val primaryLastIndex = primaryLast?.let { indexByLabel[it.label] }
        val primaryLastY = primaryLast?.let { yAt(it.value) }
        // Horizontal grid + right-hand value labels: every line can be read without the tooltip.
        repeat(4) { row ->
            val y = top + (bottom - top) * row / 3f
            drawLine(palette.line.copy(alpha = 0.75f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            val coveredByPill = primaryLastY != null && kotlin.math.abs(y - primaryLastY) < 16.dp.toPx()
            if (!coveredByPill) {
                val value = high - (high - low) * row / 3.0
                val text = measurer.measure(valueFormatter(value), axisStyle)
                drawText(text, topLeft = Offset(right + 5.dp.toPx(), y - text.size.height / 2f))
            }
        }
        // Vertical grid + date labels at both ends and the middle.
        val tickIndexes = listOf(0, (labels.lastIndex / 2), labels.lastIndex).distinct()
        tickIndexes.forEach { index ->
            val x = xAt(index)
            drawLine(palette.line.copy(alpha = 0.5f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            val label = labels[index]
            val text = measurer.measure(label, axisStyle)
            val tx = (x - text.size.width / 2f).coerceIn(left, right - text.size.width)
            drawText(text, topLeft = Offset(tx, bottom + 4.dp.toPx()))
        }

        val revealValue = reveal.value
        lines.forEach { line ->
            val mapped = line.points.mapNotNull { point ->
                val index = indexByLabel[point.label] ?: return@mapNotNull null
                if (index > (labels.lastIndex * revealValue).toInt()) return@mapNotNull null
                index to point.value
            }
            if (mapped.isEmpty()) return@forEach
            if (kind == SeriesChartKind.BAR) {
                val barWidth = ((right - left) / labels.size * 0.55f).coerceAtLeast(2.dp.toPx())
                mapped.forEach { (index, value) ->
                    drawLine(
                        line.color,
                        Offset(xAt(index), bottom),
                        Offset(xAt(index), yAt(value)),
                        barWidth,
                        StrokeCap.Round
                    )
                }
                return@forEach
            }
            val path = Path()
            mapped.forEachIndexed { position, (index, value) ->
                val x = xAt(index)
                val y = yAt(value)
                if (position == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (kind == SeriesChartKind.AREA && mapped.size >= 2) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(xAt(mapped.last().first), bottom)
                    lineTo(xAt(mapped.first().first), bottom)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(line.color.copy(alpha = 0.20f), Color.Transparent), top, bottom))
            }
            drawPath(path, line.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            mapped.lastOrNull()?.let { (index, value) ->
                drawCircle(line.color, radius = 2.6.dp.toPx(), center = Offset(xAt(index), yAt(value)))
            }
        }

        // Latest value tag on the right axis, with a pulsing halo while data keeps refreshing.
        val primary = primarySeries ?: return@Canvas
        primaryLast?.let { last ->
            val index = primaryLastIndex ?: return@let
            val y = primaryLastY ?: yAt(last.value)
            if (pulseLatest && halo.value > 0.01f) {
                drawCircle(
                    primary.color.copy(alpha = (1f - halo.value) * 0.35f),
                    radius = (3.dp + 6.dp * halo.value).toPx(),
                    center = Offset(xAt(index), y)
                )
            }
            drawCircle(primary.color, radius = 3.dp.toPx(), center = Offset(xAt(index), y))
            val tag = measurer.measure(valueFormatter(last.value), tooltipStyle.copy(color = Color.White))
            val tagWidth = tag.size.width + 10.dp.toPx()
            val tagHeight = tag.size.height + 4.dp.toPx()
            val tagTop = (y - tagHeight / 2f).coerceIn(top, bottom - tagHeight)
            drawRoundRect(
                color = primary.color,
                topLeft = Offset(right + 2.dp.toPx(), tagTop),
                size = Size(tagWidth, tagHeight),
                cornerRadius = CornerRadius(tagHeight / 2f)
            )
            drawText(tag, topLeft = Offset(right + 7.dp.toPx(), tagTop + 2.dp.toPx()))
        }

        selected?.let { index ->
            val safeIndex = index.coerceIn(labels.indices)
            val x = xAt(safeIndex)
            drawLine(
                palette.ink.copy(alpha = 0.45f),
                Offset(x, top),
                Offset(x, bottom),
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
            val linesAtPoint = lines.mapNotNull { line ->
                line.points.firstOrNull { it.label == labels[safeIndex] }?.let { line to it.value }
            }
            linesAtPoint.forEach { (line, value) ->
                drawCircle(palette.surface, radius = 4.dp.toPx(), center = Offset(x, yAt(value)))
                drawCircle(line.color, radius = 3.dp.toPx(), center = Offset(x, yAt(value)))
            }
            drawTooltip(
                measurer = measurer,
                label = labels[safeIndex],
                rows = linesAtPoint.map { (line, value) -> line.color to "${line.name}  ${valueFormatter(value)}" },
                unit = unit,
                anchorX = x,
                top = top,
                bottom = bottom,
                width = size.width,
                height = size.height,
                surface = palette.surface,
                outline = palette.line,
                muted = palette.muted,
                ink = palette.ink
            )
        }
    }
}

/** Keeps labels in ascending order and de-duplicates them; no value is invented on missing days. */
private fun buildLabels(lines: List<SeriesLine>): List<String> =
    lines.flatMap { line -> line.points.map { it.label } }
        .distinct()
        .sortedWith(compareBy({ runCatching { LocalDate.parse(it) }.getOrNull() }, { it }))

private fun DrawScope.drawTooltip(
    measurer: TextMeasurer,
    label: String,
    rows: List<Pair<Color, String>>,
    unit: String?,
    anchorX: Float,
    top: Float,
    bottom: Float,
    width: Float,
    height: Float,
    surface: Color,
    outline: Color,
    muted: Color,
    ink: Color
) {
    val title = measurer.measure(label, TextStyle(fontSize = 9.sp, color = muted))
    val rowLayouts = rows.map { (_, text) -> measurer.measure(text, TextStyle(fontSize = 10.sp, color = ink)) }
    val contentWidth = (listOf(title.size.width) + rowLayouts.map { it.size.width }).maxOrNull() ?: 0
    val lineHeight = rowLayouts.firstOrNull()?.size?.height ?: title.size.height
    val boxWidth = contentWidth + 20.dp.toPx()
    val boxHeight = title.size.height + rowLayouts.size * (lineHeight + 3.dp.toPx()) + 12.dp.toPx()
    var boxX = anchorX - boxWidth / 2f
    if (boxX < 2.dp.toPx()) boxX = 2.dp.toPx()
    if (boxX + boxWidth > width - 2.dp.toPx()) boxX = width - boxWidth - 2.dp.toPx()
    var boxY = top + 4.dp.toPx()
    if (boxY + boxHeight > height - 2.dp.toPx()) boxY = bottom - boxHeight
    val shape = CornerRadius(8.dp.toPx())
    drawRoundRect(
        surface.copy(alpha = 0.97f),
        topLeft = Offset(boxX, boxY),
        size = Size(boxWidth, boxHeight),
        cornerRadius = shape
    )
    drawRoundRect(
        outline,
        topLeft = Offset(boxX, boxY),
        size = Size(boxWidth, boxHeight),
        cornerRadius = shape,
        style = Stroke(1.dp.toPx())
    )
    drawText(title, topLeft = Offset(boxX + 10.dp.toPx(), boxY + 5.dp.toPx()))
    rowLayouts.forEachIndexed { index, layout ->
        val rowY = boxY + 7.dp.toPx() + title.size.height + index * (lineHeight + 3.dp.toPx())
        val color = rows[index].first
        drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(boxX + 8.dp.toPx(), rowY + lineHeight / 2f))
        drawText(layout, topLeft = Offset(boxX + 14.dp.toPx(), rowY))
    }
    // The unit lives on the card header and the right axis; repeating it here would collide
    // with a long date label.
}

/** Compact axis/tooltip number: keeps enough precision to see small gold moves. */
internal fun formatSeriesValue(value: Double): String = when {
    abs(value) >= 100_000 -> String.format("%,.0f", value)
    abs(value) >= 1_000 -> String.format("%,.1f", value)
    abs(value) >= 10 -> String.format("%.2f", value)
    abs(value) >= 1 -> String.format("%.3f", value)
    else -> String.format("%.4f", value)
}
