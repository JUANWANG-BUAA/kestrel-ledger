package cn.market.assistant

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * First screen while the encrypted ledger opens. It is not decoration for its own sake: SQLCipher has
 * to derive the database key before any content can exist, and on a cold start that is the longest
 * blocking step. A calm, branded animation explains the wait instead of leaving a blank frame or an
 * unlabelled spinner, and it names the two states (opening vs. retry) explicitly.
 */
@Composable
internal fun MarketSplashScreen(errorMessage: String?, onRetry: () -> Unit) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    val transition = rememberInfiniteTransition(label = "splash")
    val breathe by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "splash_breathe"
    )
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "splash_ring"
    )
    var slowNote by remember { mutableStateOf(false) }
    LaunchedEffect(errorMessage) {
        if (errorMessage == null) {
            delay(3500)
            slowNote = true
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(palette.paper, palette.paper, palette.accentSoft.copy(alpha = 0.55f))
            )
        )
    ) {
        // Soft accent glow behind the mark, drawn once at very low alpha.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(
                    listOf(palette.accent.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.38f),
                    radius = size.minDimension * 0.7f
                ),
                radius = size.minDimension * 0.7f,
                center = Offset(size.width * 0.5f, size.height * 0.38f)
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension * (0.30f + ring * 0.22f)
                    drawCircle(
                        color = palette.accent.copy(alpha = (1f - ring) * 0.28f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
                Box(
                    Modifier
                        .scale(if (errorMessage == null) breathe else 1f)
                        .size(84.dp)
                        .marketShadow(RoundedCornerShape(geometry.panel), MarketElevation.raised)
                        .clip(RoundedCornerShape(geometry.panel))
                        .background(
                            Brush.linearGradient(
                                listOf(palette.accent, palette.accent.shade(0.74f)),
                                start = Offset.Zero,
                                end = Offset(300f, 300f)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Rising line + dot: the product's one gesture, drawn instead of shipping a bitmap.
                    Canvas(Modifier.size(46.dp)) {
                        val stroke = 3.2.dp.toPx()
                        val points = listOf(0.10f to 0.72f, 0.38f to 0.50f, 0.62f to 0.60f, 0.90f to 0.24f)
                        points.zipWithNext { a, b ->
                            drawLine(
                                Color.White,
                                Offset(size.width * a.first, size.height * a.second),
                                Offset(size.width * b.first, size.height * b.second),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                        drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(size.width * 0.90f, size.height * 0.24f))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("养基助手", color = palette.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
            Text("本机加密账本 · 行情与持仓", color = palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(30.dp))

            if (errorMessage == null) {
                SplashProgressBar(Modifier.fillMaxWidth(0.62f), progress = ring)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (slowNote) "加密账本仍在打开，首次启动或数据升级会多花几秒" else "正在打开加密账本…",
                    color = palette.muted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(geometry.card))
                        .background(palette.surface)
                        .border(1.dp, palette.line, RoundedCornerShape(geometry.card))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("本地账本暂时无法打开", color = palette.ink, style = MarketType.section)
                    Text(errorMessage, color = palette.up, style = MarketType.caption, textAlign = TextAlign.Center)
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(geometry.chip),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                    ) { Text("重试") }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MarketIcon("assets", size = 13.dp, tint = palette.muted)
            Text("持仓与流水只保存在本机，不上传", color = palette.muted, fontSize = 10.5.sp)
            Spacer(Modifier.width(2.dp))
            Text("·", color = palette.muted.copy(alpha = 0.6f), fontSize = 10.5.sp)
            val version = androidx.compose.ui.platform.LocalContext.current.packageManager
                .getPackageInfo(androidx.compose.ui.platform.LocalContext.current.packageName, 0)
                .versionName
            Text("v$version", color = palette.muted.copy(alpha = 0.75f), fontSize = 10.5.sp)
        }
    }
}

/** A thin capsule bar whose highlight sweeps left to right while the ledger opens. */
@Composable
private fun SplashProgressBar(modifier: Modifier, progress: Float) {
    val palette = MarketTheme.colors
    Canvas(modifier.height(4.dp)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(color = palette.accentSoft, size = size, cornerRadius = radius)
        val barWidth = size.width * 0.42f
        val x = (size.width + barWidth) * progress - barWidth
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, palette.accent, Color.Transparent),
                startX = x,
                endX = x + barWidth
            ),
            topLeft = Offset(x, 0f),
            size = androidx.compose.ui.geometry.Size(barWidth, size.height),
            cornerRadius = radius
        )
    }
}
