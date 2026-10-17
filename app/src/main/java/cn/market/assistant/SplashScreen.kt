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
import androidx.compose.runtime.mutableIntStateOf
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
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(errorMessage) {
        if (errorMessage == null) {
            while (true) {
                delay(1000)
                elapsedSeconds++
                if (elapsedSeconds >= 3) slowNote = true
            }
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
                        .background(Color(0xFF0B1220)),
                    contentAlignment = Alignment.Center
                ) {
                    // Same mark as the launcher icon: a rising line ending in a coin.
                    Canvas(Modifier.size(52.dp)) {
                        val gold = Color(0xFFE8C47A)
                        val cream = Color(0xFFF4E3BE)
                        drawCircle(gold.copy(alpha = 0.22f), radius = size.minDimension * 0.52f, style = Stroke(1.dp.toPx()))
                        val points = listOf(0.16f to 0.72f, 0.40f to 0.50f, 0.60f to 0.60f, 0.84f to 0.30f)
                        points.zipWithNext { a, b ->
                            drawLine(
                                cream,
                                Offset(size.width * a.first, size.height * a.second),
                                Offset(size.width * b.first, size.height * b.second),
                                strokeWidth = 3.4.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                        drawCircle(gold, radius = 4.2.dp.toPx(), center = Offset(size.width * 0.84f, size.height * 0.30f))
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
                    if (slowNote) "加密账本仍在打开 · 已等待 ${elapsedSeconds}s" else "正在打开加密账本…",
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
            Text("持仓与流水只保存在本机，不上传", color = palette.muted, fontSize = 11.5.sp)
            Spacer(Modifier.width(2.dp))
            Text("·", color = palette.muted.copy(alpha = 0.6f), fontSize = 11.5.sp)
            val version = androidx.compose.ui.platform.LocalContext.current.packageManager
                .getPackageInfo(androidx.compose.ui.platform.LocalContext.current.packageName, 0)
                .versionName
            Text("v$version", color = palette.muted.copy(alpha = 0.75f), fontSize = 11.5.sp)
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

/**
 * A non-data shell shown while the encrypted ledger opens. It deliberately contains no portfolio
 * numbers: the encrypted ledger is not readable yet, so showing stale/private values here would be a
 * privacy and correctness regression. The navigation is a preview and explains that it unlocks after
 * the ledger is ready.
 */
@Composable
internal fun MarketStartupShell(errorMessage: String?, onRetry: () -> Unit) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(errorMessage) {
        if (errorMessage == null) {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }
    Box(Modifier.fillMaxSize().background(palette.paper)) {
        ScreenColumn(verticalPadding = 14.dp) {
            MarketLargeTitle(
                "资产",
                subtitle = "账户 · 持仓 · 行情",
                leading = { MarketMenuButton() }
            )
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(geometry.card))
                    .background(palette.surface)
                    .border(1.dp, palette.line, RoundedCornerShape(geometry.card))
                    .padding(horizontal = geometry.lg, vertical = geometry.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (errorMessage == null) "正在打开加密账本" else "加密账本打开失败",
                        color = if (errorMessage == null) palette.ink else palette.up,
                        style = MarketType.label,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        errorMessage ?: notice ?: "已等待 ${elapsedSeconds}s · 账本打开后自动进入",
                        color = palette.muted,
                        style = MarketType.caption,
                        maxLines = 2
                    )
                }
                if (errorMessage != null || elapsedSeconds >= 15) {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text(if (errorMessage == null) "重试" else "重试", fontSize = 12.sp) }
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .height(184.dp)
                    .clip(RoundedCornerShape(geometry.card))
                    .background(
                        Brush.linearGradient(
                            listOf(palette.accent, palette.accent.copy(alpha = 0.82f))
                        )
                    )
                    .padding(geometry.xl)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(geometry.md)) {
                    Text("总资产", color = Color.White.copy(alpha = 0.72f), style = MarketType.caption)
                    SkeletonBar(196.dp, 34.dp, Color.White.copy(alpha = 0.22f))
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.md)) {
                        SkeletonBar(0.dp, 12.dp, Color.White.copy(alpha = 0.16f), Modifier.weight(1f))
                        SkeletonBar(0.dp, 12.dp, Color.White.copy(alpha = 0.16f), Modifier.weight(1f))
                        SkeletonBar(0.dp, 12.dp, Color.White.copy(alpha = 0.16f), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(3.dp))
                    SkeletonBar(120.dp, 9.dp, Color.White.copy(alpha = 0.14f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarketQuickLink("全部持仓", "份额 · 成本 · 盈亏", Modifier.weight(1f), icon = "assets") {
                    notice = "账本打开后即可查看持仓"
                }
                MarketQuickLink("黄金行情", "国内外金价 · 折算", Modifier.weight(1f), icon = "coin") {
                    notice = "账本打开后即可查看行情"
                }
                MarketQuickLink("市场行情", "指数 · 板块 · 自选", Modifier.weight(1f), icon = "grid") {
                    notice = "账本打开后即可查看行情"
                }
            }
            MarketSectionHeader("我的持仓", caption = "账本打开后显示本机持仓")
            repeat(2) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(geometry.card))
                        .background(palette.surface)
                        .border(1.dp, palette.line, RoundedCornerShape(geometry.card))
                        .padding(geometry.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(geometry.md)
                ) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(palette.surfaceAlt))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkeletonBar(150.dp, 11.dp, palette.line)
                        SkeletonBar(220.dp, 8.dp, palette.line.copy(alpha = 0.7f))
                    }
                    SkeletonBar(62.dp, 18.dp, palette.surfaceAlt)
                }
            }
            Text(
                "账本打开后会自动替换此页；这里不显示旧数据，避免把过期或未解锁的资产当成最新值。",
                color = palette.muted,
                style = MarketType.caption,
                lineHeight = 16.sp
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            MarketNavBar(
                items = listOf(
                    MarketNavItem("总览", "home"),
                    MarketNavItem("自选", "star"),
                    MarketNavItem("记账", "plus"),
                    MarketNavItem("持仓", "assets"),
                    MarketNavItem("分析", "chart")
                ),
                selectedIndex = 0,
                onSelect = { notice = "账本打开后即可使用" }
            )
        }
    }
}

@Composable
private fun SkeletonBar(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.then(if (width > 0.dp) Modifier.width(width) else Modifier)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}
