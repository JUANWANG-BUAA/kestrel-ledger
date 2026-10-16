package cn.market.assistant

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.market.DailyHistoryCoverage
import cn.market.assistant.domain.market.DataFetchStatus
import cn.market.assistant.domain.market.DataSourceRun
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.portfolio.AccountCashBalance
import cn.market.assistant.domain.portfolio.PortfolioSnapshot
import cn.market.assistant.domain.portfolio.Position
import cn.market.assistant.domain.portfolio.Trade
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HealthError = Color(0xFFB8424B)
private val HealthSoftBlue = Color(0xFFF0E8DC)
private val HealthPaper = Color(0xFFF2F1ED)

private fun BigDecimal.healthMoney() = setScale(2, RoundingMode.HALF_UP).toPlainString()

@Composable
internal fun DataHealthScreen(
    positions: List<Position>,
    trades: List<Trade>,
    cashBalance: AccountCashBalance?,
    validSnapshots: List<PortfolioSnapshot>,
    invalidatedSnapshotCount: Int,
    quotes: Map<String, MarketQuote>,
    dataSourceRuns: List<DataSourceRun>,
    dailyHistoryCoverage: List<DailyHistoryCoverage>,
    backfillLoadingIds: Set<String>,
    backfillStatus: String,
    healthChecking: Boolean,
    healthCheck: HealthCheckSummary?,
    onRunCheck: () -> Unit,
    onBackfillDaily: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var diagnosticCopied by remember(dataSourceRuns) { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000
    val supported = MarketInstrumentCatalog.items
    val cachedCount = supported.count { it.secid in quotes }
    val missing = supported.filter { it.secid !in quotes }
    val stale = supported.mapNotNull { instrument -> quotes[instrument.secid]?.let { instrument to it } }
        .filter { (_, quote) -> !quote.isFreshAt(now) }
    val freshCount = cachedCount - stale.size
    val confirmedCount = trades.count { it.confirmed && !it.isReversed }
    val pendingCount = trades.count { !it.confirmed && !it.isReversed }
    val reversedCount = trades.count { it.isReversed }
    val pricedPositions = positions.count { it.value != null }
    val sourceGroups = quotes.values.groupBy { it.source }.entries.sortedByDescending { (_, values) ->
        values.maxOfOrNull { it.quoteTime } ?: 0L
    }
    val successfulFetches = dataSourceRuns.count { it.status == DataFetchStatus.SUCCESS }
    val partialFetches = dataSourceRuns.count { it.status == DataFetchStatus.PARTIAL }
    val failedFetches = dataSourceRuns.count { it.status == DataFetchStatus.FAILURE }
    val dailyCoverageById = dailyHistoryCoverage.associateBy { it.instrumentId }
    val coverageInstruments = MarketInstrumentCatalog.items.sortedWith(
        compareBy<MarketInstrument> { dailyCoverageById[it.secid]?.barCount ?: 0 }.thenBy { it.name }
    )

    ScreenColumn(modifier, spacing = 14.dp, onPullRefresh = onRunCheck) {
        BrandHeader("数据健康", "本机诊断")
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = Ink),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("行情与账本自检", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            healthCheck?.let { "上次检查 ${healthCheck.checkedAt.healthTime()}" } ?: "检查公共行情源，并读取本机账本状态",
                            color = Color(0xFFBBC1C1), fontSize = 11.sp
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = when {
                            healthChecking || healthCheck == null -> Color(0xFF465057)
                            healthCheck.hasWarnings -> Color(0xFF6B5230)
                            else -> Color(0xFF245C4D)
                        }
                    ) {
                        Text(
                            when {
                                healthChecking -> "检查中"
                                healthCheck == null -> "未检查"
                                healthCheck.hasWarnings -> "有缺口"
                                else -> "已更新"
                            },
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = White, fontSize = 10.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
                healthCheck?.let { check ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("市场行情  ·  ${check.marketStatus}", color = Color(0xFFE0E3E1), fontSize = 11.sp, lineHeight = 16.sp)
                        Text("黄金行情  ·  ${check.goldStatus}", color = Color(0xFFE0E3E1), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                } ?: Text(
                    "行情缓存会显示来源时间；自检后可看到本次刷新结果。",
                    color = Color(0xFFBBC1C1), fontSize = 11.sp, lineHeight = 16.sp
                )
                Button(
                    onClick = onRunCheck,
                    enabled = !healthChecking,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HealthSoftBlue, contentColor = Ink)
                ) { Text(if (healthChecking) "正在检查…" else "立即自检", fontWeight = FontWeight.SemiBold) }
            }
        }

        Text("数据完整性", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                DataHealthRow(
                    "预设行情覆盖",
                    "$cachedCount / ${supported.size} 个标的有缓存",
                    when {
                        supported.isEmpty() -> "暂无预设标的"
                        missing.isNotEmpty() -> "缺少：${missing.take(3).joinToString("、") { it.name }}${if (missing.size > 3) "等 ${missing.size} 项" else ""}"
                        else -> "预设标的均有行情缓存"
                    },
                    if (missing.isEmpty()) MarketDown else Gold
                )
                DataHealthRow(
                    "报价时点",
                    "$freshCount 项在 72 小时窗口内",
                    if (stale.isEmpty()) "没有超出 72 小时的预设行情" else
                        "已过期：${stale.take(3).joinToString("、") { it.first.name }}${if (stale.size > 3) "等 ${stale.size} 项" else ""}",
                    if (stale.isEmpty()) MarketDown else Gold
                )
                DataHealthRow(
                    "持仓估值",
                    if (positions.isEmpty()) "暂无持仓" else "$pricedPositions / ${positions.size} 项有价格",
                    if (positions.isEmpty()) "录入交易后会在这里核对持仓报价" else
                        if (pricedPositions == positions.size) "所有当前持仓都有可用估值" else "缺少价格的持仓不会计入总市值",
                    if (positions.isEmpty() || pricedPositions == positions.size) Muted else Gold
                )
            }
        }

        Text("本机账本", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                DataHealthRow("有效流水", "$confirmedCount 笔已确认 · $pendingCount 笔待确认", "$reversedCount 笔冲正记录保留审计")
                DataHealthRow(
                    "现金跟踪",
                    cashBalance?.let { "¥${it.amount.healthMoney()}" } ?: "未启用",
                    if (cashBalance == null) "启用后会校验余额并纳入账户总资产" else "当前现金余额通过非负校验"
                )
                DataHealthRow(
                    "组合观察",
                    "${validSnapshots.size} 个有效观察日",
                    if (invalidatedSnapshotCount == 0) "没有因历史更正停用的快照" else "$invalidatedSnapshotCount 个旧观察日已停用并排除分析",
                    if (invalidatedSnapshotCount == 0) Muted else Gold
                )
            }
        }

        Text("行情来源", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (sourceGroups.isEmpty()) EmptyCard("暂无行情缓存", "联网自检成功后，这里会显示行情来源和报价时点。")
        sourceGroups.forEach { (source, sourceQuotes) ->
            val latest = sourceQuotes.maxByOrNull { it.quoteTime }
            Card(
                Modifier.fillMaxWidth(), shape = CutCornerShape(topStart = 2.dp, topEnd = 14.dp, bottomEnd = 2.dp, bottomStart = 14.dp),
                colors = CardDefaults.cardColors(containerColor = White),
                border = BorderStroke(1.dp, Color(0xFFD8D8D2))
            ) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source.displayHealthSource(), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${sourceQuotes.size} 项 · ${latest?.displayTime() ?: "报价时点未知"}", color = Muted, fontSize = 11.sp)
                    }
                    Text(
                        if (sourceQuotes.all { it.isFreshAt(now) }) "时点正常" else "有旧报价",
                        color = if (sourceQuotes.all { it.isFreshAt(now) }) MarketDown else Gold,
                        fontSize = 10.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Text("日线覆盖与回填", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("先读取同市场参考指数，再按最近 20 个观测交易日识别缺口并请求标的缺失日期区间。", color = Muted, fontSize = 11.sp)
        coverageInstruments.forEach { instrument ->
            val coverage = dailyCoverageById[instrument.secid]
            val barCount = coverage?.barCount ?: 0
            val referenceName = coverage?.calendarReferenceId?.let { MarketInstrumentCatalog.byId[it]?.name ?: it } ?: "同市场指数"
            Row(
                Modifier.fillMaxWidth().background(White, RoundedCornerShape(12.dp)).padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(instrument.name, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (coverage == null) "暂无缓存日线" else
                            "$barCount 根 · ${coverage.firstDay}—${coverage.lastDay} · 更新 ${coverage.lastFetchedAt.healthTime()}",
                        color = Muted, fontSize = 10.sp
                    )
                    Text(
                        when {
                            coverage == null -> "参考日历未就绪 · $referenceName"
                            !coverage.calendarAvailable -> "参考日历 ${coverage.expectedSessions}/20 · $referenceName"
                            coverage.missingSessions.isEmpty() -> "近 ${coverage.expectedSessions} 个参考交易日完整 · $referenceName"
                            else -> "近 ${coverage.expectedSessions} 个参考交易日缺 ${coverage.missingSessions.size} 日：${coverage.missingSessions.take(4).joinToString("、")}${if (coverage.missingSessions.size > 4) "…" else ""}"
                        },
                        color = if (coverage?.calendarAvailable == true && coverage.missingSessions.isEmpty()) MarketDown else Gold,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
                TextButton(
                    onClick = { onBackfillDaily(instrument.secid) },
                    enabled = instrument.secid !in backfillLoadingIds
                ) {
                    Text(
                        when {
                            instrument.secid in backfillLoadingIds -> "处理中"
                            coverage?.calendarAvailable != true -> "识别缺口"
                            coverage?.missingSessions?.isNotEmpty() == true -> "补 ${coverage?.missingSessions?.size ?: 0} 日"
                            else -> "核对"
                        },
                        color = if (coverage?.calendarAvailable != true || coverage?.missingSessions?.isNotEmpty() == true) Gold else Ink
                    )
                }
            }
        }
        if (backfillStatus.isNotBlank()) Text(backfillStatus, color = Muted, fontSize = 11.sp)
        Text("接口请求历史", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Card(
            Modifier.fillMaxWidth(), shape = TacticalPanel,
            colors = CardDefaults.cardColors(containerColor = White),
            border = BorderStroke(1.dp, Color(0xFFD8D8D2))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val availableFetches = successfulFetches + partialFetches
                val availability = if (dataSourceRuns.isEmpty()) "—" else
                    "${availableFetches * 100 / dataSourceRuns.size}%"
                DataHealthRow(
                    "最近 ${dataSourceRuns.size} 次请求可用率",
                    availability,
                    "$successfulFetches 成功 · $partialFetches 部分 · $failedFetches 失败。错误只保留异常类型，不保存 URL 或账户信息。"
                )
                if (dataSourceRuns.isEmpty()) {
                    Text("暂无请求记录；联网刷新后会自动累计。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    dataSourceRuns.take(12).forEach { run ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${run.source.displayHealthSource()} · ${run.dataset.displayHealthDataset()}", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${run.itemCount} 项${run.expectedCount?.let { " / $it" } ?: ""} · ${run.requestedAt.healthTime()}${run.errorCode?.let { " · $it" } ?: ""}",
                                    color = Muted, fontSize = 10.sp
                                )
                            }
                            Text(
                                run.status.displayHealthStatus(),
                                color = when (run.status) {
                                    DataFetchStatus.SUCCESS -> MarketDown
                                    DataFetchStatus.PARTIAL -> Gold
                                    DataFetchStatus.FAILURE -> HealthError
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                            "Market 数据诊断",
                            buildRedactedDiagnostic(quotes, dataSourceRuns, now)
                        ))
                        diagnosticCopied = true
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text(if (diagnosticCopied) "诊断信息已复制" else "复制脱敏诊断", color = Ink) }
            }
        }
        HealthInfoCard(
            "检查范围",
            "请求日志最多保留最近 500 条，覆盖行情、基金、板块和图表请求。日线缺口按同市场指数的已观察交易日核对，不等同于官方完整交易日历。",
            HealthSoftBlue
        )
    }
}

@Composable
private fun DataHealthRow(label: String, value: String, detail: String, detailColor: Color = Muted) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
            Text(value, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(detail, color = detailColor, fontSize = 10.sp, lineHeight = 15.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE9E8E2)))
}

internal fun MarketQuote.isFreshAt(now: Long): Boolean =
    quoteTime <= now + 5 * 60 && now - quoteTime <= 72 * 60 * 60

private fun String.displayHealthSource(): String = when (this) {
    "eastmoney" -> "东方财富"
    "tencent" -> "腾讯行情"
    "daily-close" -> "日线收盘价"
    "sge-au9999" -> "上海黄金交易所 · Au99.99"
    "gold-api-xau-usd" -> "Gold API · XAU/USD"
    "gold-api-xau-cny" -> "Gold API · 人民币折算"
    "quote-router" -> "行情聚合路由"
    "chart-router" -> "K 线聚合路由"
    "tencent-etf" -> "腾讯行情 · 黄金 ETF"
    else -> this
}

private fun String.displayHealthDataset(): String = when {
    this == "market-overview" -> "市场行情"
    this == "gold-quote" -> "黄金行情"
    this == "industry-boards" -> "行业板块"
    this == "concept-boards" -> "概念板块"
    this == "listed-fund-market-price" -> "场内基金行情"
    this.startsWith("chart-") -> "${removePrefix("chart-").uppercase()} K 线"
    this == "instrument-quote" -> "单项行情"
    else -> this
}

private fun DataFetchStatus.displayHealthStatus(): String = when (this) {
    DataFetchStatus.SUCCESS -> "成功"
    DataFetchStatus.PARTIAL -> "部分"
    DataFetchStatus.FAILURE -> "失败"
}

private fun buildRedactedDiagnostic(
    quotes: Map<String, MarketQuote>,
    runs: List<DataSourceRun>,
    now: Long
): String = buildString {
    appendLine("Market 数据源诊断 · ${now.healthTime()}")
    appendLine("行情缓存 ${quotes.size} 项 / ${MarketInstrumentCatalog.items.size} 个预设标的")
    runs.take(20).forEach { run ->
        appendLine(
            "${run.requestedAt.healthTime()} · ${run.source} · ${run.dataset} · ${run.status.name} · " +
                "${run.itemCount}/${run.expectedCount ?: "?"} · ${run.errorCode.orEmpty()}"
        )
    }
}

private fun Long.healthTime(): String = runCatching {
    java.time.Instant.ofEpochSecond(this).atZone(java.time.ZoneId.of("Asia/Shanghai"))
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault("时间未知")

@Composable
private fun HealthInfoCard(title: String, body: String, color: Color) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = HealthSoftBlue)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 12.sp)
        }
    }
}
