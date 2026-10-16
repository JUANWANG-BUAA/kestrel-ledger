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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
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
        if (instruments.isNotEmpty()) Card(
            Modifier.fillMaxWidth().animateContentSize(tween(240)),
            shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = Ink),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("自选市场广度", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            latestQuote?.let { quote ->
                                "${freshQuotes.size}/${instruments.size} 个有效报价 · " +
                                    "最近时点 ${quote.displayTime()}"
                            } ?: "${instruments.size} 个关注标的 · 等待行情刷新",
                            color = Color(0xFFBBC1C1), fontSize = 10.sp
                        )
                    }
                    Text(
                        "${freshQuotes.size}/${instruments.size}", color = White,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (freshQuotes.isEmpty()) {
                    Text(
                        "收到有效报价后，这里会汇总自选标的的涨跌分布。",
                        color = Color(0xFFBBC1C1), fontSize = 11.sp
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("上涨 $advancing", Modifier.weight(1f), color = Color(0xFFFFB1B5), fontSize = 11.sp)
                        Text("持平 $unchanged", Modifier.weight(1f), color = Color(0xFFD0D3D0), fontSize = 11.sp)
                        Text("下跌 $declining", color = Color(0xFF83D1B9), fontSize = 11.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color(0xFF465057))
                            .semantics {
                                contentDescription = "自选市场广度：" +
                                    "上涨 $advancing 个，持平 $unchanged 个，下跌 $declining 个，" +
                                    "共 ${freshQuotes.size} 个有效报价"
                            }
                    ) {
                        if (advancing > 0) Box(
                            Modifier.weight(advancing.toFloat()).fillMaxHeight().background(MarketUp)
                        )
                        if (unchanged > 0) Box(
                            Modifier.weight(unchanged.toFloat()).fillMaxHeight().background(Color(0xFF9DA5A3))
                        )
                        if (declining > 0) Box(
                            Modifier.weight(declining.toFloat()).fillMaxHeight().background(MarketDown)
                        )
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WatchlistSort.entries.forEach { option ->
                    FilterChip(
                        selected = sortMode == option,
                        onClick = {
                            sortMode = option
                            preferences.edit().putString("watchlist_sort", option.name).apply()
                        },
                        label = { Text(option.label) }, colors = assetChipColors()
                    )
                }
            }
            AnimatedContent(
                targetState = sortedInstruments,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                label = "watchlist_order"
            ) { visibleInstruments ->
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    visibleInstruments.forEach { instrument ->
                        val quote = quotes[instrument.secid]
                        Card(
                            onClick = { onOpen(instrument.secid) }, modifier = Modifier.fillMaxWidth(),
                            shape = CutCornerShape(
                                topStart = 2.dp, topEnd = 15.dp, bottomEnd = 2.dp, bottomStart = 15.dp
                            ),
                            colors = CardDefaults.cardColors(containerColor = White),
                            border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        Text(instrument.name, color = Ink, fontWeight = FontWeight.SemiBold)
                                        Text(instrument.market, color = Blue, fontSize = 10.sp)
                                    }
                                    Text(
                                        "${quote?.let {
                                            if (it.source == "daily-close") "近收 ${it.displayTime()}"
                                            else "行情 ${it.displayTime()}"
                                        } ?: "暂无报价"}  ·  点开看日线",
                                        color = Muted, fontSize = 11.sp
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        quote?.let { String.format("%.2f", it.price) } ?: "—",
                                        color = Ink, fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        quote?.let {
                                            "${if (it.changePct > 0) "+" else ""}" +
                                                "${String.format("%.2f", it.changePct)}%"
                                        } ?: "等待行情",
                                        color = quote?.let { plColor(BigDecimal.valueOf(it.changePct)) } ?: Muted,
                                        fontSize = 11.sp
                                    )
                                }
                                TextButton(onClick = { onToggle(instrument.secid, false) }) {
                                    Text("移除", color = Muted)
                                }
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
        Text("添加关注", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("按名称搜索指数或股票") }, singleLine = true,
            shape = RoundedCornerShape(15.dp), colors = fieldColors()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("全部", "A股", "美股", "日股", "韩股", "港股").forEach { market ->
                FilterChip(selected = marketFilter == market, onClick = { marketFilter = market },
                    label = { Text(market) }, colors = assetChipColors())
            }
        }
        if (candidates.isEmpty()) EmptyCard("没有匹配标的", "试试名称关键词，或切换市场分类。")
        candidates.forEach { instrument ->
            Card(Modifier.fillMaxWidth(), shape = CutCornerShape(3.dp),
                colors = CardDefaults.cardColors(containerColor = White), border = BorderStroke(1.dp, Color(0xFFD8D8D2))) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(instrument.name, color = Ink, fontWeight = FontWeight.Medium)
                        Text(instrument.market, color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { onToggle(instrument.secid, true) }) { Text("＋ 关注") }
                }
            }
        }
        Text("报价与日线缓存在本机；行情源不可用时会显示最近缓存及其时间。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
