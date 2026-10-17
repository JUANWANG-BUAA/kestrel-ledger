package cn.market.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.fund.FundRepository
import cn.market.assistant.domain.fund.FundWatchlistEntry
import cn.market.assistant.domain.market.MarketQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal

internal enum class WatchlistSort(val label: String) {
    ADDED("加入顺序"), GAINERS("涨幅靠前"), LOSERS("跌幅靠前")
}

@Composable
private fun BreadthLegend(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = MarketTheme.colors.muted, style = MarketType.caption)
        Text("$count", color = color, style = MarketType.numberSmall)
    }
}

@Composable
internal fun WatchlistScreen(
    watchedOrder: List<String>, fundRepository: FundRepository, quotes: Map<String, MarketQuote>, loading: Boolean, message: String,
    onRefresh: () -> Unit, onToggle: (String, Boolean) -> Unit, onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences("market_ui_preferences", android.content.Context.MODE_PRIVATE)
    }
    var sortMode by remember(preferences) {
        val savedSort = preferences.getString("watchlist_sort", WatchlistSort.ADDED.name)
        mutableStateOf(WatchlistSort.entries.firstOrNull { it.name == savedSort } ?: WatchlistSort.ADDED)
    }
    var query by remember { mutableStateOf("") }
    var marketFilter by remember { mutableStateOf("全部") }
    var fundEntries by remember(fundRepository) { mutableStateOf(emptyList<FundWatchlistEntry>()) }
    var refreshingFundCode by remember { mutableStateOf<String?>(null) }
    var fundMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fundRepository) {
        try {
            fundEntries = fundRepository.watchlistedFunds()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fundMessage = error.message ?: "无法读取基金自选"
        }
    }
    val watched = watchedOrder.toSet()
    val instruments = watchedOrder.mapNotNull(MarketInstrumentCatalog.byId::get)
    val addedOrder = watchedOrder.withIndex().associate { it.value to it.index }
    val sortedInstruments = remember(instruments, quotes, sortMode) {
        instruments.sortedWith(when (sortMode) {
            WatchlistSort.ADDED -> compareBy<MarketInstrument> { addedOrder[it.secid] ?: Int.MAX_VALUE }
                .thenBy { it.name }
            WatchlistSort.GAINERS -> compareBy<MarketInstrument> {
                quotes[it.secid]?.changePct?.let { change -> -change } ?: Double.POSITIVE_INFINITY
            }.thenBy { it.name }
            WatchlistSort.LOSERS -> compareBy<MarketInstrument> {
                quotes[it.secid]?.changePct ?: Double.POSITIVE_INFINITY
            }.thenBy { it.name }
        })
    }
    val now = System.currentTimeMillis() / 1000
    val freshQuotes = instruments.mapNotNull { quotes[it.secid] }.filter { it.isFreshAt(now) }
    val advancing = freshQuotes.count { it.changePct > 0.000001 }
    val declining = freshQuotes.count { it.changePct < -0.000001 }
    val unchanged = freshQuotes.size - advancing - declining
    val latestQuote = freshQuotes.maxByOrNull { it.quoteTime }
        ?: instruments.mapNotNull { quotes[it.secid] }.maxByOrNull { it.quoteTime }
    val candidates = MarketInstrumentCatalog.items.filter { instrument ->
        instrument.secid !in watched && (marketFilter == "全部" || instrument.market == marketFilter) &&
            (query.isBlank() || instrument.name.contains(query.trim(), ignoreCase = true))
    }
    ScreenColumn(modifier, onPullRefresh = { if (!loading) onRefresh() }) {
        BrandHeader("自选", "${instruments.size + fundEntries.size} 个")
        if (instruments.isNotEmpty()) {
            val palette = MarketTheme.colors
            val geometry = MarketTheme.geometry
            Card(
                Modifier.fillMaxWidth().animateContentSize(tween(240))
                    .marketShadow(RoundedCornerShape(geometry.panel), MarketElevation.card),
                shape = RoundedCornerShape(geometry.panel),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = BorderStroke(1.dp, palette.line.copy(alpha = 0.85f))
            ) {
                Column(
                    Modifier.padding(geometry.xl),
                    verticalArrangement = Arrangement.spacedBy(geometry.md)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("自选市场广度", color = palette.ink, style = MarketType.section)
                            Text(
                                latestQuote?.let { quote ->
                                    "${freshQuotes.size}/${instruments.size} 个有效报价 · 最近时点 ${quote.displayTime()}"
                                } ?: "${instruments.size} 个关注标的 · 等待行情刷新",
                                color = palette.muted, style = MarketType.caption
                            )
                        }
                        Text("${freshQuotes.size}/${instruments.size}", color = palette.ink, style = MarketType.numberLarge)
                    }
                    if (freshQuotes.isEmpty()) {
                        Text("收到有效报价后，这里会汇总自选标的的涨跌分布。", color = palette.muted, style = MarketType.caption)
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(palette.surfaceAlt)
                                .semantics {
                                    contentDescription = "自选市场广度：" +
                                        "上涨 $advancing 个，持平 $unchanged 个，下跌 $declining 个，" +
                                        "共 ${freshQuotes.size} 个有效报价"
                                }
                        ) {
                            if (advancing > 0) Box(
                                Modifier.weight(advancing.toFloat()).fillMaxHeight().background(palette.up)
                            )
                            if (unchanged > 0) Box(
                                Modifier.weight(unchanged.toFloat()).fillMaxHeight().background(palette.muted.copy(alpha = 0.55f))
                            )
                            if (declining > 0) Box(
                                Modifier.weight(declining.toFloat()).fillMaxHeight().background(palette.down)
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(geometry.lg)) {
                            BreadthLegend("上涨", advancing, palette.up)
                            BreadthLegend("持平", unchanged, palette.muted)
                            BreadthLegend("下跌", declining, palette.down)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("关注指数和市场标的", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
            TextButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "刷新中" else "刷新行情") }
        }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
        if (instruments.isEmpty() && fundEntries.isEmpty()) EmptyCard(
            "暂时没有自选标的", "可以在下方添加市场标的，或在基金画像中加入基金自选。"
        )
        if (instruments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                WatchlistSort.entries.forEach { option ->
                    MarketChoiceChip(
                        label = option.label,
                        selected = sortMode == option,
                        onClick = {
                            sortMode = option
                            preferences.edit().putString("watchlist_sort", option.name).apply()
                        }
                    )
                }
            }
            AnimatedContent(
                targetState = sortedInstruments,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                label = "watchlist_order"
            ) { visibleInstruments ->
                val palette = MarketTheme.colors
                val geometry = MarketTheme.geometry
                Column(verticalArrangement = Arrangement.spacedBy(geometry.sm)) {
                    visibleInstruments.forEach { instrument ->
                        val quote = quotes[instrument.secid]
                        Card(
                            onClick = { onOpen(instrument.secid) },
                            modifier = Modifier.fillMaxWidth()
                                .marketShadow(RoundedCornerShape(geometry.card), MarketElevation.card),
                            shape = RoundedCornerShape(geometry.card),
                            colors = CardDefaults.cardColors(containerColor = palette.surface),
                            border = BorderStroke(1.dp, palette.line.copy(alpha = 0.85f))
                        ) {
                            Row(
                                Modifier.padding(start = geometry.lg, end = geometry.sm, top = geometry.md, bottom = geometry.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(geometry.sm)
                                    ) {
                                        Text(instrument.name, color = palette.ink, style = MarketType.number)
                                        Text(instrument.market, color = palette.accent, style = MarketType.caption)
                                    }
                                    Text(
                                        quote?.let {
                                            if (it.source == "daily-close") "近收 ${it.displayTime()}"
                                            else "行情 ${it.displayTime()}"
                                        } ?: "暂无报价",
                                        color = palette.muted, style = MarketType.caption
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        quote?.let { String.format("%.2f", it.price) } ?: "—",
                                        color = palette.ink, style = MarketType.numberLarge
                                    )
                                    Text(
                                        quote?.let {
                                            "${if (it.changePct > 0) "+" else ""}" +
                                                "${String.format("%.2f", it.changePct)}%"
                                        } ?: "等待行情",
                                        color = quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: palette.muted,
                                        style = MarketType.numberSmall
                                    )
                                }
                                Text(
                                    "移除",
                                    color = palette.muted.copy(alpha = 0.85f),
                                    fontSize = 11.5.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(geometry.chip))
                                        .marketPressable { onToggle(instrument.secid, false) }
                                        .padding(horizontal = geometry.sm, vertical = geometry.sm)
                                )
                            }
                        }
                    }
                }
            }
        }
        FundWatchlistPanel(
            entries = fundEntries,
            refreshingCode = refreshingFundCode,
            message = fundMessage,
            onRefresh = { entry ->
                if (refreshingFundCode == null) scope.launch {
                    refreshingFundCode = entry.code
                    fundMessage = null
                    try {
                        val updated = fundRepository.refreshFundNav(entry.code)
                        fundEntries = fundEntries.map { if (it.code == updated.code) updated else it }
                        fundMessage = "${updated.name} 净值已更新"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fundMessage = error.message ?: "基金净值刷新失败"
                    } finally {
                        refreshingFundCode = null
                    }
                }
            },
            onRemove = { entry ->
                scope.launch {
                    try {
                        fundRepository.setFundWatchlisted(entry.code, entry.name, false)
                        fundEntries = fundRepository.watchlistedFunds()
                        fundMessage = "已移除 ${entry.name}"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fundMessage = error.message ?: "移除基金自选失败"
                    }
                }
            }
        )
        MarketSectionHeader("添加关注", caption = "指数、股票与市场标的")
        MarketSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "按名称或代码搜索指数、股票",
            modifier = Modifier.fillMaxWidth(),
            onClear = { query = "" }
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("全部", "A股", "美股", "日股", "韩股", "港股").forEach { market ->
                MarketChoiceChip(
                    label = market,
                    selected = marketFilter == market,
                    onClick = { marketFilter = market }
                )
            }
        }
        if (candidates.isEmpty()) EmptyCard("没有匹配标的", "试试名称关键词，或切换市场分类。")
        candidates.forEach { instrument ->
            val palette = MarketTheme.colors
            val geometry = MarketTheme.geometry
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(geometry.card),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = BorderStroke(1.dp, palette.line.copy(alpha = 0.85f))
            ) {
                Row(
                    Modifier.padding(horizontal = geometry.lg, vertical = geometry.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(instrument.name, color = palette.ink, style = MarketType.number)
                        Text(instrument.market, color = palette.muted, style = MarketType.caption)
                    }
                    Text(
                        "＋ 关注",
                        color = palette.accent, style = MarketType.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(geometry.chip))
                            .marketPressable { onToggle(instrument.secid, true) }
                            .padding(horizontal = geometry.md, vertical = geometry.sm)
                    )
                }
            }
        }
        Text("报价与日线缓存在本机；行情源不可用时会显示最近缓存及其时间。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
