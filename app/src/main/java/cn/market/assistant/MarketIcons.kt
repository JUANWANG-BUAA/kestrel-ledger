package cn.market.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One line-icon set for the whole app. Every glyph is drawn on the same 24dp grid with the same 1.8dp
 * rounded stroke and the same default 20dp box, so a drawer row, a nav tab, a header button and a
 * search field finally read as one product instead of a pile of hand-tuned canvases.
 *
 * Names are deliberately close to Material naming so unfamiliar glyphs are guessable:
 * menu, home, star, plus, assets, chart, coin, grid, pulse, bell, spark, sliders, search, eye, eyeOff,
 * back, forward, refresh, close, check, warn, clock, sort, trash.
 */
internal object MarketIcons {
    const val VIEWPORT = 24f
    const val STROKE = 1.8f
    const val DEFAULT_SIZE = 20
}

@Composable
internal fun MarketIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color? = null,
    filled: Boolean = false,
    onFilled: Color? = null
) {
    val palette = MarketTheme.colors
    val ink = tint ?: palette.ink
    val knockOut = onFilled ?: palette.surface
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / MarketIcons.VIEWPORT
        drawMarketIcon(name, unit, ink, knockOut, filled)
    }
}

private fun DrawScope.drawMarketIcon(name: String, unit: Float, tint: Color, knockOut: Color, filled: Boolean) {
    fun x(value: Float) = value * unit
    fun y(value: Float) = value * unit
    val stroke = Stroke(width = MarketIcons.STROKE * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = 1.6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (name) {
        "menu" -> {
            drawLine(tint, Offset(x(3f), y(6.5f)), Offset(x(21f), y(6.5f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(3f), y(12f)), Offset(x(15.5f), y(12f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(3f), y(17.5f)), Offset(x(21f), y(17.5f)), stroke.width, StrokeCap.Round)
        }
        "home" -> {
            val path = Path().apply {
                moveTo(x(3.5f), y(10.5f)); lineTo(x(12f), y(3.5f)); lineTo(x(20.5f), y(10.5f))
                lineTo(x(19f), y(10.5f)); lineTo(x(19f), y(20f)); lineTo(x(5f), y(20f)); lineTo(x(5f), y(10.5f)); close()
            }
            if (filled) {
                drawPath(path, tint)
                drawLine(knockOut, Offset(x(10f), y(20f)), Offset(x(10f), y(14f)), 1.7f * unit, StrokeCap.Round)
                drawLine(knockOut, Offset(x(14f), y(20f)), Offset(x(14f), y(14f)), 1.7f * unit, StrokeCap.Round)
            } else {
                drawPath(path, tint, style = stroke)
            }
        }
        "star" -> {
            val path = Path()
            repeat(10) { index ->
                val radius = if (index % 2 == 0) x(9.5f) else x(4.2f)
                val angle = Math.PI * index / 5 - Math.PI / 2
                val px = size.width / 2 + kotlin.math.cos(angle).toFloat() * radius
                val py = size.height / 2 + kotlin.math.sin(angle).toFloat() * radius
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, tint, style = if (filled) Fill else stroke)
        }
        "plus" -> {
            drawCircle(tint, radius = x(9f), style = stroke)
            drawLine(tint, Offset(x(12f), y(7.5f)), Offset(x(12f), y(16.5f)), 1.9f * unit, StrokeCap.Round)
            drawLine(tint, Offset(x(7.5f), y(12f)), Offset(x(16.5f), y(12f)), 1.9f * unit, StrokeCap.Round)
        }
        "assets" -> {
            drawRoundRect(
                tint, topLeft = Offset(x(3.5f), y(4f)), size = Size(x(17f), y(16f)),
                cornerRadius = CornerRadius(x(3f)), style = stroke
            )
            drawLine(tint, Offset(x(8f), y(9f)), Offset(x(17f), y(9f)), 1.7f * unit, StrokeCap.Round)
            drawLine(tint, Offset(x(8f), y(13f)), Offset(x(17f), y(13f)), 1.7f * unit, StrokeCap.Round)
            drawCircle(tint, radius = x(1.05f), center = Offset(x(6f), y(9f)))
            drawCircle(tint, radius = x(1.05f), center = Offset(x(6f), y(13f)))
        }
        "chart" -> {
            drawLine(tint, Offset(x(4f), y(20f)), Offset(x(4f), y(4f)), 1.8f * unit, StrokeCap.Round)
            drawLine(tint, Offset(x(4f), y(20f)), Offset(x(21f), y(20f)), 1.8f * unit, StrokeCap.Round)
            val path = Path().apply { moveTo(x(6.5f), y(16f)); lineTo(x(10f), y(12f)); lineTo(x(13.5f), y(14f)); lineTo(x(19f), y(7f)) }
            drawPath(path, tint, style = stroke)
            drawCircle(tint, x(1.3f), Offset(x(19f), y(7f)))
        }
        "coin" -> {
            drawCircle(tint, radius = x(8.6f), center = Offset(x(12f), y(12f)), style = stroke)
            drawCircle(tint, radius = x(4.6f), center = Offset(x(12f), y(12f)), style = stroke)
            drawLine(tint, Offset(x(12f), y(8.4f)), Offset(x(12f), y(15.6f)), thin.width, StrokeCap.Round)
        }
        "grid" -> {
            val cell = Size(x(6.6f), y(6.6f))
            val radius = CornerRadius(x(1.8f))
            drawRoundRect(tint, topLeft = Offset(x(3.5f), y(3.5f)), size = cell, cornerRadius = radius, style = stroke)
            drawRoundRect(tint, topLeft = Offset(x(13.9f), y(3.5f)), size = cell, cornerRadius = radius, style = stroke)
            drawRoundRect(tint, topLeft = Offset(x(3.5f), y(13.9f)), size = cell, cornerRadius = radius, style = stroke)
            drawRoundRect(tint, topLeft = Offset(x(13.9f), y(13.9f)), size = cell, cornerRadius = radius, style = stroke)
        }
        "pulse" -> {
            val path = Path().apply {
                moveTo(x(2.5f), y(12.5f)); lineTo(x(7f), y(12.5f)); lineTo(x(9.5f), y(6.5f))
                lineTo(x(13f), y(18f)); lineTo(x(15.5f), y(12.5f)); lineTo(x(21.5f), y(12.5f))
            }
            drawPath(path, tint, style = stroke)
        }
        "bell" -> {
            val path = Path().apply {
                moveTo(x(5.5f), y(16.5f)); lineTo(x(18.5f), y(16.5f)); lineTo(x(17f), y(14f)); lineTo(x(17f), y(10.5f))
                cubicTo(x(17f), y(6.5f), x(14.8f), y(4.5f), x(12f), y(4.5f))
                cubicTo(x(9.2f), y(4.5f), x(7f), y(6.5f), x(7f), y(10.5f))
                lineTo(x(7f), y(14f)); close()
            }
            drawPath(path, tint, style = stroke)
            drawLine(tint, Offset(x(10.2f), y(19f)), Offset(x(13.8f), y(19f)), 1.7f * unit, StrokeCap.Round)
        }
        "spark" -> {
            val path = Path().apply {
                moveTo(x(10f), y(3.5f)); lineTo(x(12f), y(9.5f)); lineTo(x(18f), y(11.5f))
                lineTo(x(12f), y(13.5f)); lineTo(x(10f), y(19.5f)); lineTo(x(8f), y(13.5f))
                lineTo(x(2f), y(11.5f)); lineTo(x(8f), y(9.5f)); close()
            }
            drawPath(path, tint, style = if (filled) Fill else stroke)
            drawCircle(tint, x(1.1f), Offset(x(18.5f), y(5.5f)))
        }
        "sliders" -> {
            drawLine(tint, Offset(x(3.5f), y(8f)), Offset(x(20.5f), y(8f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(3.5f), y(16f)), Offset(x(20.5f), y(16f)), stroke.width, StrokeCap.Round)
            drawCircle(tint, x(2.6f), Offset(x(9f), y(8f)))
            drawCircle(tint, x(2.6f), Offset(x(15.5f), y(16f)))
        }
        "search" -> {
            drawCircle(tint, radius = x(6.6f), center = Offset(x(10.5f), y(10.5f)), style = stroke)
            drawLine(tint, Offset(x(15.4f), y(15.4f)), Offset(x(20f), y(20f)), stroke.width, StrokeCap.Round)
        }
        "eye" -> {
            val path = Path().apply {
                moveTo(x(2f), y(12f)); quadraticTo(x(12f), y(3.5f), x(22f), y(12f))
                quadraticTo(x(12f), y(20.5f), x(2f), y(12f)); close()
            }
            drawPath(path, tint, style = stroke)
            drawCircle(tint, x(3f), Offset(x(12f), y(12f)), style = stroke)
        }
        "eyeOff" -> {
            val path = Path().apply {
                moveTo(x(2f), y(12f)); quadraticTo(x(12f), y(3.5f), x(22f), y(12f))
                quadraticTo(x(12f), y(20.5f), x(2f), y(12f)); close()
            }
            drawPath(path, tint, style = stroke)
            drawCircle(tint, x(3f), Offset(x(12f), y(12f)), style = stroke)
            drawLine(tint, Offset(x(4f), y(20f)), Offset(x(20f), y(4f)), 1.9f * unit, StrokeCap.Round)
        }
        "back" -> {
            drawLine(tint, Offset(x(14.5f), y(5f)), Offset(x(8f), y(12f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(8f), y(12f)), Offset(x(14.5f), y(19f)), stroke.width, StrokeCap.Round)
        }
        "forward" -> {
            drawLine(tint, Offset(x(9.5f), y(5f)), Offset(x(16f), y(12f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(16f), y(12f)), Offset(x(9.5f), y(19f)), stroke.width, StrokeCap.Round)
        }
        "refresh" -> {
            drawArc(
                tint, startAngle = -60f, sweepAngle = 250f, useCenter = false,
                topLeft = Offset(x(4f), y(4f)), size = Size(x(16f), y(16f)), style = stroke
            )
            val arrow = Path().apply {
                moveTo(x(15.2f), y(1.8f)); lineTo(x(19.6f), y(5.4f)); lineTo(x(14.2f), y(7.2f))
            }
            drawPath(arrow, tint, style = stroke)
        }
        "close" -> {
            drawLine(tint, Offset(x(6f), y(6f)), Offset(x(18f), y(18f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(18f), y(6f)), Offset(x(6f), y(18f)), stroke.width, StrokeCap.Round)
        }
        "check" -> {
            drawLine(tint, Offset(x(5f), y(12.5f)), Offset(x(10f), y(17.5f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(10f), y(17.5f)), Offset(x(19f), y(6.5f)), stroke.width, StrokeCap.Round)
        }
        "warn" -> {
            val path = Path().apply {
                moveTo(x(12f), y(3.5f)); lineTo(x(21.5f), y(19.5f)); lineTo(x(2.5f), y(19.5f)); close()
            }
            drawPath(path, tint, style = stroke)
            drawLine(tint, Offset(x(12f), y(9.5f)), Offset(x(12f), y(14.5f)), 1.8f * unit, StrokeCap.Round)
            drawCircle(tint, x(1.05f), Offset(x(12f), y(17f)))
        }
        "clock" -> {
            drawCircle(tint, radius = x(8.6f), center = Offset(x(12f), y(12f)), style = stroke)
            drawLine(tint, Offset(x(12f), y(7.2f)), Offset(x(12f), y(12f)), thin.width, StrokeCap.Round)
            drawLine(tint, Offset(x(12f), y(12f)), Offset(x(15.6f), y(14f)), thin.width, StrokeCap.Round)
        }
        "sort" -> {
            drawLine(tint, Offset(x(7f), y(4.5f)), Offset(x(7f), y(19.5f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(3.6f), y(16f)), Offset(x(7f), y(19.6f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(17f), y(19.5f)), Offset(x(17f), y(4.5f)), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(x(13.6f), y(8f)), Offset(x(17f), y(4.4f)), stroke.width, StrokeCap.Round)
        }
        "trash" -> {
            drawLine(tint, Offset(x(4.5f), y(6.5f)), Offset(x(19.5f), y(6.5f)), stroke.width, StrokeCap.Round)
            drawRoundRect(
                tint, topLeft = Offset(x(6.5f), y(6.5f)), size = Size(x(11f), y(13.5f)),
                cornerRadius = CornerRadius(x(2f)), style = stroke
            )
            drawLine(tint, Offset(x(9.5f), y(3.5f)), Offset(x(14.5f), y(3.5f)), thin.width, StrokeCap.Round)
        }
        else -> {
            drawCircle(tint, radius = x(8.6f), center = Offset(x(12f), y(12f)), style = stroke)
        }
    }
}
