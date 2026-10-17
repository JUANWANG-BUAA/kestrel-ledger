package cn.market.assistant

import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.backup.EncryptedBackup
import cn.market.assistant.domain.market.MarketBoardSnapshot
import cn.market.assistant.domain.market.MarketBoardType
import cn.market.assistant.domain.market.MarketRepository
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar
import cn.market.assistant.data.market.MarketRepositoryImpl
import cn.market.assistant.domain.fund.FundRepository
import cn.market.assistant.data.fund.FundRepositoryImpl
import cn.market.assistant.snapshot.DailyPortfolioSnapshotScheduler
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.AccountCashEvent
import cn.market.assistant.domain.portfolio.AccountCashLedger
import cn.market.assistant.domain.portfolio.PeriodFacts
import cn.market.assistant.domain.portfolio.PortfolioAnalytics
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.ReportPeriod
import cn.market.assistant.data.assistant.ReadOnlyAgentTools
import cn.market.assistant.alert.AlertEvaluationScheduler
import cn.market.assistant.domain.portfolio.Trade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun OverviewScreen(
    positions: List<Position>, pendingCount: Int,
    total: BigDecimal, cost: BigDecimal, holding: BigDecimal, realized: BigDecimal,
    accountValue: BigDecimal?, cashTrackingEnabled: Boolean,
    todayFacts: PeriodFacts, snapshots: List<PortfolioSnapshot>,
    marketQuotes: Map<String, MarketQuote>, marketLoading: Boolean, marketMessage: String, onRefreshMarket: () -> Unit,
    boardSnapshots: Map<MarketBoardType, MarketBoardSnapshot>,
    boardLoadingTypes: Set<MarketBoardType>, boardMessage: String,
    onRefreshBoards: () -> Unit,
    concealAmounts: Boolean, onToggleAmountVisibility: () -> Unit,
    onRecord: () -> Unit, onOpenAssets: () -> Unit, onOpenWatchlist: () -> Unit, onOpenSectors: () -> Unit, onOpenGold: () -> Unit,
    onOpenMarket: (String) -> Unit, modifier: Modifier = Modifier
) {
    val quoteCount = positions.count { it.quote != null }
    val allPositionsPriced = quoteCount == positions.size
    val noPositionQuotes = positions.isNotEmpty() && quoteCount == 0
    val accountPeriod = todayFacts.opening?.accountComplete == true && todayFacts.closing?.accountComplete == true
    val todayProfit = if (accountPeriod) todayFacts.accountProfit else todayFacts.profit
    val todayRate = if (accountPeriod) todayFacts.accountModifiedDietzPct else todayFacts.modifiedDietzPct
    val todayMissingReason = if (accountPeriod) todayFacts.accountMissingReason else todayFacts.missingReason
    val latestQuoteAt = positions.mapNotNull { it.quoteTime }.maxOrNull()?.let { value ->
        runCatching {
            java.time.Instant.parse(value).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }.getOrDefault(value.take(16).replace('T', ' '))
    }
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    ScreenColumn(modifier, onPullRefresh = { if (!marketLoading) onRefreshMarket() }) {
        MarketLargeTitle("资产", subtitle = "账户 · 持仓 · 行情", leading = { MarketMenuButton() })
        OverviewHeroCard(
            positions = positions,
            total = total,
            holding = holding,
            cost = cost,
            realized = realized,
            accountValue = accountValue,
            cashTrackingEnabled = cashTrackingEnabled,
            todayProfit = todayProfit,
            todayRate = todayRate,
            todayMissingReason = todayMissingReason,
            accountPeriod = accountPeriod,
            allPositionsPriced = allPositionsPriced,
            noPositionQuotes = noPositionQuotes,
            quoteCount = quoteCount,
            latestQuoteAt = latestQuoteAt,
            concealAmounts = concealAmounts,
            onToggleAmountVisibility = onToggleAmountVisibility,
            onRecord = onRecord
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarketQuickLink("全部持仓", "份额 · 成本 · 盈亏", Modifier.weight(1f), icon = "assets", onClick = onOpenAssets)
            MarketQuickLink("黄金行情", "国内外金价 · 折算", Modifier.weight(1f), icon = "coin", onClick = onOpenGold)
            MarketQuickLink("市场行情", "指数 · 板块 · 自选", Modifier.weight(1f), icon = "grid", onClick = onOpenWatchlist)
        }
        if (hasSparklineVariation(snapshots)) {
            val shape = RoundedCornerShape(geometry.card)
            Column(
                Modifier.fillMaxWidth()
                    .marketShadow(shape, MarketElevation.card)
                    .clip(shape)
                    .background(palette.surface)
                    .border(1.dp, palette.line, shape)
                    .padding(geometry.lg),
                verticalArrangement = Arrangement.spacedBy(geometry.sm)
            ) {
                MarketSectionHeader("资产变化", caption = "${snapshots.size} 个观察日 · 按已记录快照") {
                    Text("分析 ›", color = palette.accent, style = MarketType.label,
                        modifier = Modifier.clickable(onClick = onOpenAssets))
                }
                PortfolioProfitSparkline(snapshots, Modifier.fillMaxWidth().height(72.dp))
            }
        }
        MarketSectionHeader("我的持仓", caption = "按确认流水计算份额与成本") {
            Text("全部资产 ›", color = palette.accent, style = MarketType.label,
                modifier = Modifier.clickable(onClick = onOpenAssets))
        }
        if (positions.isEmpty()) {
            MarketEmptyState(
                icon = "assets",
                title = "还没有持仓记录",
                body = "记下第一笔买入或期初持仓，这里会显示份额、成本与盈亏。",
                actionLabel = "去记一笔",
                onAction = onRecord
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MarketCompactStat(
                    "基金持仓",
                    "${positions.count { it.asset.kind == AssetKind.FUND }} 只",
                    Modifier.weight(1f)
                )
                MarketCompactStat(
                    "黄金持仓",
                    "${positions.count { it.asset.kind == AssetKind.GOLD }} 项",
                    Modifier.weight(1f)
                )
            }
            positions.take(5).forEach { PositionCard(it, concealAmounts) }
        }
        if (pendingCount > 0) InfoCard("有 $pendingCount 笔交易待确认", "净值公布后补录确认净值，即可计入持仓。", Blue)
        if (positions.isNotEmpty()) {
            TextButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text("记下买入或卖出") }
        }

        val latestQuote = marketQuotes.values.maxOfOrNull { it.fetchedAt }
        MarketSectionHeader(
            "行情观察",
            caption = if (latestQuote == null) "行情暂未更新" else "行情截至 " + java.time.Instant.ofEpochSecond(latestQuote)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (marketLoading) "刷新中" else "刷新", color = palette.accent, style = MarketType.label,
                    modifier = Modifier.clickable(enabled = !marketLoading, onClick = onRefreshMarket))
                Text("板块 ›", color = palette.accent, style = MarketType.label,
                    modifier = Modifier.clickable(onClick = onOpenSectors))
                Text("自选 ›", color = palette.accent, style = MarketType.label,
                    modifier = Modifier.clickable(onClick = onOpenWatchlist))
            }
        }
        GoldOverviewCard(marketQuotes["GOLD.SGE.AU9999"], marketQuotes[MarketApi.GOLD_SPOT_ID], marketQuotes["1.518880"], onOpenGold = onOpenGold)
        MarketBoardTicker(boardSnapshots, boardLoadingTypes, boardMessage, onOpenSectors, onRefreshBoards)
        MarketSectionHeader("全球大盘指数", caption = "点开看分时与日线")
        if (marketMessage.isNotBlank()) Text(marketMessage, color = Muted, fontSize = 11.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MarketInstrumentCatalog.overview.chunked(3).forEach { trio ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    trio.forEach { instrument ->
                        val quote = marketQuotes[instrument.secid]
                        MarketIndexTile(
                            name = instrument.name,
                            fallbackLabel = when {
                                quote == null -> instrument.market
                                quote.source == "daily-close" -> "近收"
                                else -> instrument.market
                            },
                            price = quote?.price?.let { String.format("%.2f", it) } ?: "—",
                            changePct = quote?.let { it.changePct.asSignedPercent() } ?: "暂无",
                            changeColor = quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenMarket(instrument.secid) }
                        )
                    }
                    repeat(3 - trio.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
@Composable
internal fun MarketIndexCard(instrument: MarketInstrument, quote: MarketQuote?, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = White), border = BorderStroke(1.dp, Color(0xFFD8D8D2)), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(instrument.name, color = Muted, fontSize = 11.sp, maxLines = 1)
                Text(when {
                    quote == null -> instrument.market
                    quote.source == "daily-close" -> "近收 ${quote.displayTime()}"
                    else -> instrument.market
                }, color = Muted, fontSize = 9.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedTicker(quote?.price?.let { String.format("%.2f", it) } ?: "—", Ink, 15.sp)
                AnimatedTicker(quote?.let { it.changePct.asSignedPercent() } ?: "暂无数据",
                    quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted, 10.sp, FontWeight.Medium)
            }
        }
    }
}
