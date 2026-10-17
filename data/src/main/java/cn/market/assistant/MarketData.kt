package cn.market.assistant

import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.market.DataFetchStatus
import cn.market.assistant.domain.market.ChartPeriod
import cn.market.assistant.domain.market.MarketOhlcBar

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.regex.Pattern

data class MarketInstrument(
    val secid: String,
    val name: String,
    val market: String,
    val tencentCode: String,
    val yahooSymbol: String? = null
)

object MarketInstrumentCatalog {
    val items = listOf(
        MarketInstrument("1.000001", "上证指数", "A股", "sh000001"),
        MarketInstrument("0.399001", "深证成指", "A股", "sz399001"),
        MarketInstrument("1.000300", "沪深300", "A股", "sh000300"),
        MarketInstrument("0.399006", "创业板指", "A股", "sz399006"),
        MarketInstrument("1.000688", "科创50", "A股", "sh000688"),
        MarketInstrument("100.NDX", "纳斯达克", "美股", "usNDX"),
        MarketInstrument("100.DJIA", "道琼斯", "美股", "usDJI"),
        MarketInstrument("100.SPX", "标普500", "美股", "usINX"),
        MarketInstrument("100.N225", "日经225", "日股", "usN225", "^N225"),
        MarketInstrument("100.HSI", "恒生指数", "港股", "hkHSI"),
        MarketInstrument("1.518880", "华安黄金 ETF", "黄金", "sh518880"),
        MarketInstrument("100.KS11", "韩国综合指数", "韩股", "usKS11", "^KS11"),
        MarketInstrument("105.AAPL", "苹果", "美股", "usAAPL"),
        MarketInstrument("105.NVDA", "英伟达", "美股", "usNVDA"),
        MarketInstrument("105.MSFT", "微软", "美股", "usMSFT"),
        MarketInstrument("176.6758", "索尼", "日股", "jp6758", "6758.T"),
        MarketInstrument("177.005930", "三星电子", "韩股", "kr005930", "005930.KS"),
        MarketInstrument("177.000660", "SK 海力士", "韩股", "kr000660", "000660.KS"),
        MarketInstrument("1.512480", "半导体 ETF", "A股", "sh512480"),
        MarketInstrument("0.159869", "游戏 ETF", "A股", "sz159869"),
        MarketInstrument("0.159819", "人工智能 ETF", "A股", "sz159819"),
        MarketInstrument("1.515880", "通信 ETF", "A股", "sh515880"),
        MarketInstrument("1.000012", "上证国债指数", "债券", "sh000012"),
        MarketInstrument("1.000013", "上证企债指数", "债券", "sh000013"),
        MarketInstrument("1.000832", "中证转债", "债券", "sh000832")
    )
    val byId = items.associateBy { it.secid }
    val indices = items.take(11)
    val overview = listOf("1.000001", "0.399001", "0.399006", "1.000688", "100.NDX", "100.DJIA", "100.SPX", "100.N225", "100.KS11", "100.HSI").mapNotNull(byId::get)
    /** ETF stand-ins retained for quote fallback; the full sector universe comes from the board feed. */
    val sectorEtfProxies = listOf(
        SectorEtfProxy("semiconductor", "半导体", "芯片硬件", "1.512480", "半导体 ETF 代表，不等同行业指数"),
        SectorEtfProxy("gaming", "游戏", "数字内容", "0.159869", "游戏 ETF 代表，不等同行业指数"),
        SectorEtfProxy("ai", "人工智能", "AI 算力", "0.159819", "人工智能 ETF 代表，不等同行业指数"),
        SectorEtfProxy("communications", "通信设备", "通信网络", "1.515880", "通信 ETF 代表，不等同行业指数")
    )
}

data class SectorEtfProxy(val id: String, val name: String, val category: String, val instrumentId: String, val description: String)

data class ChartBarsResponse(val source: String, val bars: List<MarketOhlcBar>)

data class MarketProviderFetch(
    val source: String,
    val status: DataFetchStatus,
    val itemCount: Int,
    val expectedCount: Int?,
    val errorCode: String? = null
)

data class MarketQuoteFetchResult(val quotes: List<MarketQuote>, val providerFetches: List<MarketProviderFetch>)

object MarketApi {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
    const val GOLD_SPOT_ID = "GOLD.XAU.USD.OZ"
    const val GOLD_CNY_GRAM_ID = "GOLD.XAU.CNY.GRAM"
    private const val TROY_OUNCE_GRAMS = 31.1034768

    /** Total budget for sequential per-instrument fallbacks so one blocked provider cannot stall a refresh. */
    private const val FALLBACK_BUDGET_MILLIS = 15_000L
    private const val MIN_REQUEST_BUDGET_MILLIS = 5_000L

    private fun hasBudget(deadlineMillis: Long): Boolean =
        deadlineMillis - System.currentTimeMillis() >= MIN_REQUEST_BUDGET_MILLIS

    fun fetchQuotes(): List<MarketQuote> = fetchQuotesWithDiagnostics().quotes

    fun fetchQuotesWithDiagnostics(): MarketQuoteFetchResult {
        val providerFetches = mutableListOf<MarketProviderFetch>()
        fun recordProvider(
            source: String,
            expectedCount: Int?,
            itemCount: Int,
            errorCount: Int = 0,
            errorCode: String? = null
        ) {
            val status = when {
                itemCount == 0 && errorCode != null -> DataFetchStatus.FAILURE
                itemCount == 0 -> DataFetchStatus.FAILURE
                errorCount > 0 || expectedCount != null && itemCount < expectedCount -> DataFetchStatus.PARTIAL
                else -> DataFetchStatus.SUCCESS
            }
            providerFetches += MarketProviderFetch(
                source = source,
                status = status,
                itemCount = itemCount,
                expectedCount = expectedCount,
                errorCode = errorCode
            )
        }

        val eastMoneyResult = runCatching { fetchEastMoneyQuotes() }
        val eastMoneyQuotes = eastMoneyResult.getOrDefault(emptyList())
        recordProvider(
            source = "eastmoney",
            expectedCount = MarketInstrumentCatalog.items.size,
            itemCount = eastMoneyQuotes.size,
            errorCode = eastMoneyResult.exceptionOrNull()?.javaClass?.simpleName
        )
        val tencentResult = runCatching { fetchTencentQuotes() }
        val tencentRows = tencentResult.getOrDefault(emptyList())
        val tencentQuotes = tencentRows.filter { it.source == "tencent" }
        val dailyCloseQuotes = tencentRows.filter { it.source == "daily-close" }
        recordProvider(
            source = "tencent",
            expectedCount = MarketInstrumentCatalog.items.size,
            itemCount = tencentQuotes.size,
            errorCode = tencentResult.exceptionOrNull()?.javaClass?.simpleName
        )
        if (dailyCloseQuotes.isNotEmpty()) {
            recordProvider("daily-close", expectedCount = null, itemCount = dailyCloseQuotes.size)
        }
        val primary = (eastMoneyQuotes + tencentRows).associateBy { it.secid }.toMutableMap()
        val missingYahoo = MarketInstrumentCatalog.items.filter { it.yahooSymbol != null && it.secid !in primary }
        if (missingYahoo.isNotEmpty()) {
            // A blocked provider must not stall the whole refresh: bound the sequential fallback.
            val deadline = System.currentTimeMillis() + FALLBACK_BUDGET_MILLIS
            var yahooFailures = 0
            var yahooSkipped = 0
            missingYahoo.forEach { instrument ->
                if (!hasBudget(deadline)) {
                    yahooSkipped++
                    return@forEach
                }
                val result = runCatching { fetchYahooQuote(instrument) }
                val quote = result.getOrNull()
                if (quote == null) yahooFailures++ else primary[quote.secid] = quote
            }
            recordProvider(
                source = "yahoo",
                expectedCount = missingYahoo.size,
                itemCount = missingYahoo.size - yahooFailures - yahooSkipped,
                errorCount = yahooFailures + yahooSkipped,
                errorCode = when {
                    yahooSkipped > 0 -> "BudgetExceeded"
                    yahooFailures > 0 -> "SourceFailures"
                    else -> null
                }
            )
        }
        val proxyFallbacks = MarketInstrumentCatalog.sectorEtfProxies.mapNotNull { MarketInstrumentCatalog.byId[it.instrumentId] }
            .filter { it.secid !in primary }
        if (proxyFallbacks.isNotEmpty()) {
            val deadline = System.currentTimeMillis() + FALLBACK_BUDGET_MILLIS
            var fallbackFailures = 0
            val fallbackQuotes = mutableListOf<MarketQuote>()
            proxyFallbacks.forEach { instrument ->
                if (!hasBudget(deadline)) {
                    fallbackFailures++
                    return@forEach
                }
                val result = runCatching { fetchQuote(instrument.secid) }
                val quote = result.getOrNull()
                if (quote == null) fallbackFailures++ else {
                    fallbackQuotes += quote
                    primary[quote.secid] = quote
                }
            }
            fallbackQuotes.groupBy { it.source }.forEach { (source, quotes) ->
                recordProvider(source, expectedCount = null, itemCount = quotes.size)
            }
            recordProvider(
                source = "quote-fallback",
                expectedCount = proxyFallbacks.size,
                itemCount = fallbackQuotes.size,
                errorCount = fallbackFailures,
                errorCode = if (fallbackFailures > 0) "SourceFailures" else null
            )
        }

        listOf(
            "gold-api-xau-usd" to ::fetchGoldSpot,
            "gold-api-xau-cny" to ::fetchGoldCnyPerGram,
            "sge-au9999" to ::fetchSgeAu9999Quote
        ).forEach { (source, fetch) ->
            val result = runCatching(fetch)
            val quote = result.getOrNull()
            if (quote == null) {
                recordProvider(source, expectedCount = 1, itemCount = 0, errorCode = result.exceptionOrNull()?.javaClass?.simpleName)
            } else {
                primary[quote.secid] = quote
                recordProvider(source, expectedCount = 1, itemCount = 1)
            }
        }
        return MarketQuoteFetchResult(primary.values.toList(), providerFetches)
    }

    /** International XAU/USD spot, quoted in US dollars per troy ounce. */
    fun fetchGoldSpot(): MarketQuote {
        val json = JSONObject(request("https://api.gold-api.com/price/XAU/USD", "https://gold-api.com/"))
        require(json.optString("symbol") == "XAU" && json.optString("currency") == "USD") { "黄金行情返回了意外的计价单位" }
        val usdPerOunce = json.optDouble("price", Double.NaN)
        require(usdPerOunce.isFinite() && usdPerOunce > 0) { "黄金行情暂无有效价格" }
        val fetchedAt = System.currentTimeMillis() / 1000
        val quoteTime = runCatching { Instant.parse(json.optString("updatedAt")).epochSecond }.getOrDefault(fetchedAt)
        return MarketQuote(GOLD_SPOT_ID, "国际黄金 XAU/USD", usdPerOunce, 0.0, 0.0,
            quoteTime, fetchedAt, "gold-api-xau-usd")
    }

    /** Comparable international reference in CNY per gram for domestic premium calculations. */
    fun fetchGoldCnyPerGram(): MarketQuote {
        val json = JSONObject(request("https://api.gold-api.com/price/XAU/CNY", "https://gold-api.com/"))
        require(json.optString("symbol") == "XAU" && json.optString("currency") == "CNY") { "黄金行情返回了意外的计价单位" }
        val cnyPerOunce = json.optDouble("price", Double.NaN)
        require(cnyPerOunce.isFinite() && cnyPerOunce > 0) { "人民币黄金折算暂无有效价格" }
        val fetchedAt = System.currentTimeMillis() / 1000
        val quoteTime = runCatching { Instant.parse(json.optString("updatedAt")).epochSecond }.getOrDefault(fetchedAt)
        return MarketQuote(GOLD_CNY_GRAM_ID, "国际黄金人民币折算", cnyPerOunce / TROY_OUNCE_GRAMS, 0.0, 0.0,
            quoteTime, fetchedAt, "gold-api-xau-cny")
    }

    /** Official SGE daily closes for Au99.99, used once to backfill the app's own daily series. */
    fun fetchSgeDailyCloses(limit: Int = 250): List<Pair<String, Double>> {
        val referer = "https://www.sge.com.cn/sjzx/mrhq"
        val rows = JSONObject(request("https://www.sge.com.cn/graph/Dailyhq?instid=Au99.99", referer))
            .optJSONArray("time") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                if (row.length() < 3) continue
                val day = row.optString(0)
                val close = row.optString(2).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: continue
                if (!day.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue
                add(day to close)
            }
        }.sortedBy { it.first }.takeLast(limit.coerceIn(2, 2_000))
    }

    /** Shanghai Gold Exchange Au99.99 spot quote. */
    fun fetchSgeAu9999Quote(): MarketQuote {
        val referer = "https://www.sge.com.cn/sjzx/mrhq"
        val intraday = JSONObject(requestPost("https://www.sge.com.cn/graph/quotations", "instid=Au99.99", referer))
        val values = intraday.optJSONArray("data") ?: error("上金所没有返回 Au99.99 行情")
        val price = (0 until values.length()).mapNotNull { values.optString(it).toDoubleOrNull() }
            .lastOrNull { it.isFinite() && it > 0 } ?: error("上金所 Au99.99 暂无有效报价")
        val fetchedAt = System.currentTimeMillis() / 1000
        val timeText = intraday.optString("delaystr")
        val parsedSourceTime = runCatching {
            java.time.LocalDateTime.parse(timeText, java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"))
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond()
        }.getOrDefault(fetchedAt)
        // Some cached SGE responses carry a future trading date; never show a future quote timestamp.
        val sourceTime = if (parsedSourceTime > fetchedAt + 5 * 60) fetchedAt else parsedSourceTime
        val quoteDate = runCatching {
            java.time.LocalDateTime.ofEpochSecond(sourceTime, 0, java.time.ZoneOffset.ofHours(8)).toLocalDate().toString()
        }.getOrDefault("")
        val history = JSONObject(request("https://www.sge.com.cn/graph/Dailyhq?instid=Au99.99", referer))
            .optJSONArray("time")
        val previousClose = (0 until (history?.length() ?: 0)).mapNotNull { index ->
            val row = history?.optJSONArray(index) ?: return@mapNotNull null
            if (row.length() < 3 || row.optString(0) >= quoteDate) return@mapNotNull null
            row.optString(2).toDoubleOrNull()?.takeIf { it > 0 }
        }.lastOrNull()?.takeIf { it > 0 }
        val change = previousClose?.let { price - it } ?: 0.0
        return MarketQuote("GOLD.SGE.AU9999", "上金所 Au99.99", price, change,
            previousClose?.let { change / it * 100.0 } ?: 0.0, sourceTime,
            fetchedAt, "sge-au9999")
    }

    fun fetchQuote(secid: String): MarketQuote {
        val instrument = MarketInstrumentCatalog.byId[secid]
        if (instrument == null) {
            // Bond benchmarks are resolved by exchange prefix instead of a fixed preset list.
            val tencentCode = synthesizedTencentCode(secid)
            if (tencentCode != null) {
                runCatching { fetchTencentQuote(secid, secid, tencentCode) }.getOrNull()?.let { return it }
            }
            return fetchListedFundQuote(secid)
        }
        if (instrument.yahooSymbol != null) runCatching { fetchYahooQuote(instrument) }.getOrNull()?.let { return it }
        return fetchTencentQuote(secid, instrument.name, instrument.tencentCode)
    }

    private fun fetchTencentQuote(secid: String, fallbackName: String, tencentCode: String): MarketQuote {
        val raw = request("https://qt.gtimg.cn/q=${encode(tencentCode)}", "https://gu.qq.com/")
        val matcher = Pattern.compile("v_([^=]+)=\"(.*?)\";", Pattern.DOTALL).matcher(raw)
        require(matcher.find()) { "腾讯行情暂不可用" }
        val fields = (matcher.group(2) ?: "").split('~')
        val price = fields.getOrNull(3)?.toDoubleOrNull()?.takeIf { it > 0 } ?: error("实时价格暂不可用")
        val change = fields.getOrNull(31)?.toDoubleOrNull() ?: 0.0
        val changePct = fields.getOrNull(32)?.toDoubleOrNull() ?: 0.0
        val name = fallbackName
        val fetchedAt = System.currentTimeMillis() / 1000
        val quoteTime = parseTencentQuoteTime(fields.getOrNull(30).orEmpty(), fetchedAt)
            .takeIf { it <= fetchedAt + 5 * 60 } ?: fetchedAt
        return MarketQuote(secid, name, price, change, changePct, quoteTime, fetchedAt, "tencent")
    }

    /** Resolves `1.000012` / `0.399001` style secids to Tencent codes without a preset entry. */
    internal fun synthesizedTencentCode(secid: String): String? {
        val parts = secid.split('.')
        if (parts.size != 2 || !parts[1].matches(Regex("\\d{6}"))) return null
        return when (parts[0]) {
            "1" -> "sh${parts[1]}"
            "0" -> "sz${parts[1]}"
            else -> null
        }
    }
    fun fetchListedFundQuote(secid: String): MarketQuote {
        val parts = secid.split('.')
        require(parts.size == 2 && parts[0] in setOf("0", "1") && parts[1].matches(Regex("\\d{6}"))) {
            "暂不支持此标的"
        }
        val query = listOf(
            "fltt=2",
            "fields=f2,f3,f4,f12,f13,f14,f124",
            "secids=${encode(secid)}"
        ).joinToString("&")
        val raw = request(
            "https://push2.eastmoney.com/api/qt/ulist.np/get?$query",
            "https://quote.eastmoney.com/"
        )
        val root = JSONObject(raw)
        require(root.optInt("rc", -1) == 0) { "东方财富暂未返回场内行情" }
        val rows = root.optJSONObject("data")?.optJSONArray("diff") ?: error("东方财富暂未返回场内行情")
        val row = (0 until rows.length()).asSequence()
            .mapNotNull(rows::optJSONObject)
            .firstOrNull { it.optString("f12") == parts[1] && it.optString("f13") == parts[0] }
            ?: error("没有找到对应的场内基金行情")
        val price = row.optDouble("f2", Double.NaN)
        val changePct = row.optDouble("f3", Double.NaN)
        val quoteTime = row.optLong("f124", 0L)
        require(price.isFinite() && price > 0 && changePct.isFinite() && quoteTime > 0) {
            "场内行情字段无效"
        }
        val fetchedAt = System.currentTimeMillis() / 1000
        return MarketQuote(
            secid = secid,
            name = row.optString("f14").ifBlank { secid },
            price = price,
            change = row.optDouble("f4", 0.0).takeIf(Double::isFinite) ?: 0.0,
            changePct = changePct,
            quoteTime = quoteTime,
            fetchedAt = fetchedAt,
            source = "eastmoney"
        )
    }

    private fun fetchEastMoneyQuotes(): List<MarketQuote> {
        val query = listOf(
            "fltt=2", "ut=fa5fd1943c7b386f172d6893dbfba10b",
            "fields=f2,f3,f4,f12,f13,f14,f124",
            "secids=${encode(MarketInstrumentCatalog.items.joinToString(",") { it.secid })}"
        ).joinToString("&")
        val raw = request("https://push2delay.eastmoney.com/api/qt/ulist.np/get?$query", "https://quote.eastmoney.com/")
        val root = JSONObject(raw)
        require(root.optInt("rc", -1) == 0) { "行情源暂时不可用" }
        val diff = root.optJSONObject("data")?.optJSONArray("diff") ?: error("行情源没有返回指数")
        val fetchedAt = System.currentTimeMillis() / 1000
        return buildList {
            for (i in 0 until diff.length()) {
                val row = diff.getJSONObject(i)
                val secid = "${row.optInt("f13")}.${row.optString("f12")}"
                val instrument = MarketInstrumentCatalog.byId[secid] ?: continue
                val price = row.optDouble("f2", Double.NaN)
                val pct = row.optDouble("f3", Double.NaN)
                if (!price.isFinite() || price <= 0 || !pct.isFinite()) continue
                add(MarketQuote(secid, instrument.name, price, row.optDouble("f4", 0.0), pct,
                    row.optLong("f124", fetchedAt), fetchedAt))
            }
        }
    }

    private fun fetchTencentQuotes(): List<MarketQuote> {
        val instruments = MarketInstrumentCatalog.items.filter {
            it.tencentCode.startsWith("sh") || it.tencentCode.startsWith("sz") || it.tencentCode.startsWith("kr") || it.tencentCode.startsWith("jp") ||
                it.tencentCode in setOf("usNDX", "usDJI", "usINX", "hkHSI")
        }
        val codes = instruments.joinToString(",") { it.tencentCode }
        val raw = request("https://qt.gtimg.cn/q=$codes", "https://gu.qq.com/")
        val byCode = instruments.associateBy { it.tencentCode }
        val matcher = Pattern.compile("v_([^=]+)=\\\"(.*?)\\\";", Pattern.DOTALL).matcher(raw)
        val fetchedAt = System.currentTimeMillis() / 1000
        val live = buildList {
            while (matcher.find()) {
                val code = matcher.group(1) ?: continue
                val row = matcher.group(2) ?: continue
                val instrument = byCode[code] ?: continue
                val fields = row.split('~')
                val price = fields.getOrNull(3)?.toDoubleOrNull() ?: continue
                val change = fields.getOrNull(31)?.toDoubleOrNull() ?: continue
                val pct = fields.getOrNull(32)?.toDoubleOrNull() ?: continue
                if (price <= 0) continue
                val quoteTime = parseTencentQuoteTime(fields.getOrNull(30).orEmpty(), fetchedAt)
                    .takeIf { it <= fetchedAt + 5 * 60 } ?: fetchedAt
                add(MarketQuote(instrument.secid, instrument.name, price, change, pct, quoteTime, fetchedAt, "tencent"))
            }
        }
        val byId = live.associateBy { it.secid }.toMutableMap()
        MarketInstrumentCatalog.overview.forEach { instrument ->
            if (instrument.secid in byId || instrument.yahooSymbol != null) return@forEach
            runCatching { fetchDailyBars(instrument.secid, 2) }.getOrNull()?.takeIf { it.size >= 2 }?.let { bars ->
                val previous = bars[bars.lastIndex - 1]
                val latest = bars.last()
                val pct = (latest.close - previous.close) / previous.close * 100.0
                val dayEpoch = runCatching { java.time.LocalDate.parse(latest.day).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond() }.getOrDefault(fetchedAt)
                byId[instrument.secid] = MarketQuote(instrument.secid, instrument.name, latest.close,
                    latest.close - previous.close, pct, dayEpoch, fetchedAt, "daily-close")
            }
        }
        return byId.values.toList()
    }

    fun fetchDailyBars(secid: String, limit: Int = 180): List<MarketBar> {
        val instrument = MarketInstrumentCatalog.byId[secid] ?: error("暂不支持该标的走势图")
        val param = encode("${instrument.tencentCode},day,,,$limit,qfq")
        val raw = request("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$param", "https://gu.qq.com/")
        val root = JSONObject(raw)
        val node = root.optJSONObject("data")?.optJSONObject(instrument.tencentCode) ?: error("暂时没有日线数据")
        val rows = node.optJSONArray("qfqday") ?: node.optJSONArray("day") ?: error("暂时没有日线数据")
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (row.length() < 3) continue
                val date = row.optString(0)
                val close = row.optString(2).toDoubleOrNull() ?: continue
                if (close > 0) add(MarketBar(date, close))
            }
        }.distinctBy { it.day }.sortedBy { it.day }
    }

    fun fetchChartBars(secid: String, period: ChartPeriod): List<MarketOhlcBar> =
        fetchChartBarsWithSource(secid, period).bars

    fun fetchChartBarsWithSource(secid: String, period: ChartPeriod): ChartBarsResponse {
        val instrument = MarketInstrumentCatalog.byId[secid]
        if (instrument?.yahooSymbol != null) {
            runCatching { fetchYahooChartBars(instrument, period) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return ChartBarsResponse("yahoo", it) }
        }
        if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) {
            return ChartBarsResponse("tencent", fetchTencentChartBars(secid, period))
        }
        return runCatching { ChartBarsResponse("eastmoney", fetchEastMoneyChartBars(secid, period)) }
            .recoverCatching { ChartBarsResponse("tencent", fetchTencentChartBars(secid, period)) }
            .getOrThrow()
    }

    /** Corporate-action-adjusted Tencent A-share history for fund-holding return regression. */
    fun fetchAdjustedStockDailyBars(secid: String): List<MarketOhlcBar> {
        require(secid.matches(Regex("[01]\\.\\d{6}"))) { "股票代码格式无效" }
        val exchangeCode = secid.split('.', limit = 2).let { (market, code) ->
            (if (market == "1") "sh" else "sz") + code
        }
        val param = encode("$exchangeCode,day,,,180,qfq")
        val raw = request(
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/fqkline/get?param=$param",
            "https://gu.qq.com/"
        )
        val root = JSONObject(raw)
        require(root.optInt("code", -1) == 0) { root.optString("msg").ifBlank { "腾讯没有返回复权日线" } }
        val rows = root.optJSONObject("data")?.optJSONObject(exchangeCode)?.optJSONArray("qfqday")
            ?: error("腾讯没有返回复权日线")
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                if (row.length() < 6) continue
                val day = row.optString(0)
                val open = row.optString(1).toDoubleOrNull() ?: continue
                val close = row.optString(2).toDoubleOrNull() ?: continue
                val high = row.optString(3).toDoubleOrNull() ?: continue
                val low = row.optString(4).toDoubleOrNull() ?: continue
                val volume = row.optString(5).toDoubleOrNull() ?: 0.0
                if (listOf(open, close, high, low).all { it.isFinite() && it > 0.0 }) {
                    add(MarketOhlcBar(day, open, close, high, low, volume))
                }
            }
        }.distinctBy { it.day }.sortedBy { it.day }
    }

    /** Fetches only the requested daily-session range; unrelated bars are discarded before persistence. */
    fun fetchChartBarsForDates(secid: String, requestedDays: List<String>): ChartBarsResponse {
        require(requestedDays.isNotEmpty()) { "没有需要回填的交易日" }
        val expectedDays = requestedDays.mapNotNull(::normalizeMarketDay).toSet()
        require(expectedDays.isNotEmpty()) { "回填日期格式无效" }
        val startDay = expectedDays.minOrNull() ?: error("回填日期无效")
        val endDay = expectedDays.maxOrNull() ?: error("回填日期无效")
        val instrument = MarketInstrumentCatalog.byId[secid] ?: error("暂不支持该标的日线回填")
        val eastMoney = runCatching {
            fetchEastMoneyChartBars(secid, ChartPeriod.DAY, startDay, endDay)
        }.getOrNull()?.let { bars -> ChartBarsResponse("eastmoney", bars) }
        val response = eastMoney?.takeIf { it.bars.isNotEmpty() }
            ?: (if (instrument.yahooSymbol != null) runCatching {
                ChartBarsResponse("yahoo", fetchYahooChartBars(instrument, ChartPeriod.DAY, startDay to endDay))
            }.getOrNull()?.takeIf { it.bars.isNotEmpty() } else null)
            ?: eastMoney
            ?: error("日线区间请求失败，暂时没有可用数据源")
        return response.copy(bars = response.bars.filter { normalizeMarketDay(it.day) in expectedDays })
    }

    private fun fetchYahooQuote(instrument: MarketInstrument): MarketQuote {
        val symbol = instrument.yahooSymbol ?: error("没有 Yahoo 行情代码")
        val root = JSONObject(request(yahooUrl(symbol, "5d", "1d"), "https://finance.yahoo.com/"))
        val result = root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
            ?: error(root.optJSONObject("chart")?.optJSONObject("error")?.optString("description") ?: "Yahoo 没有返回报价")
        val meta = result.optJSONObject("meta") ?: error("Yahoo 报价缺少市场信息")
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        val previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN))
        require(price.isFinite() && price > 0 && previous.isFinite() && previous > 0) { "Yahoo 报价不完整" }
        val fetchedAt = System.currentTimeMillis() / 1000
        val quoteTime = meta.optLong("regularMarketTime", fetchedAt)
        val change = price - previous
        return MarketQuote(instrument.secid, instrument.name, price, change, change / previous * 100.0,
            quoteTime, fetchedAt, "yahoo")
    }

    private fun fetchYahooChartBars(
        instrument: MarketInstrument,
        period: ChartPeriod,
        dateRange: Pair<String, String>? = null
    ): List<MarketOhlcBar> {
        val symbol = instrument.yahooSymbol ?: error("没有 Yahoo 行情代码")
        val (range, interval) = when (period) {
            ChartPeriod.INTRADAY -> "1d" to "5m"
            ChartPeriod.FIVE_DAY -> "5d" to "30m"
            ChartPeriod.DAY -> "1y" to "1d"
            ChartPeriod.WEEK -> "5y" to "1wk"
            ChartPeriod.MONTH -> "max" to "1mo"
        }
        val requestUrl = dateRange?.let { yahooDateRangeUrl(symbol, it.first, it.second, interval) }
            ?: yahooUrl(symbol, range, interval)
        val root = JSONObject(request(requestUrl, "https://finance.yahoo.com/"))
        val result = root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
            ?: error(root.optJSONObject("chart")?.optJSONObject("error")?.optString("description") ?: "Yahoo 没有返回 K 线")
        val timestamps = result.optJSONArray("timestamp") ?: error("Yahoo 没有返回 K 线时间")
        val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            ?: error("Yahoo 没有返回 OHLC 数据")
        val opens = quote.optJSONArray("open") ?: error("Yahoo 缺少开盘价")
        val highs = quote.optJSONArray("high") ?: error("Yahoo 缺少最高价")
        val lows = quote.optJSONArray("low") ?: error("Yahoo 缺少最低价")
        val closes = quote.optJSONArray("close") ?: error("Yahoo 缺少收盘价")
        val volumes = quote.optJSONArray("volume")
        val timezone = result.optJSONObject("meta")?.optString("exchangeTimezoneName")?.takeIf { it.isNotBlank() }
            ?: "Asia/Shanghai"
        val zone = java.time.ZoneId.of(timezone)
        val dateFormat = if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY)
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm") else java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        return buildList {
            for (i in 0 until timestamps.length()) {
                val open = opens.optDouble(i, Double.NaN)
                val high = highs.optDouble(i, Double.NaN)
                val low = lows.optDouble(i, Double.NaN)
                val close = closes.optDouble(i, Double.NaN)
                if (!listOf(open, high, low, close).all { it.isFinite() && it > 0 }) continue
                val day = Instant.ofEpochSecond(timestamps.optLong(i)).atZone(zone).format(dateFormat)
                add(MarketOhlcBar(day, open, close, high, low, volumes?.optDouble(i, 0.0) ?: 0.0))
            }
        }.distinctBy { it.day }.sortedBy { it.day }
    }

    private fun yahooUrl(symbol: String, range: String, interval: String): String =
        "https://query1.finance.yahoo.com/v8/finance/chart/${encode(symbol)}?range=$range&interval=$interval&events=history"

    private fun yahooDateRangeUrl(symbol: String, startDay: String, endDay: String, interval: String): String {
        val zone = java.time.ZoneOffset.UTC
        val period1 = java.time.LocalDate.parse(startDay).minusDays(1).atStartOfDay(zone).toEpochSecond()
        val period2 = java.time.LocalDate.parse(endDay).plusDays(2).atStartOfDay(zone).toEpochSecond()
        return "https://query1.finance.yahoo.com/v8/finance/chart/${encode(symbol)}?" +
            "period1=$period1&period2=$period2&interval=$interval&events=history"
    }

    private fun fetchTencentChartBars(secid: String, period: ChartPeriod): List<MarketOhlcBar> {
        val code = MarketInstrumentCatalog.byId[secid]?.tencentCode
            ?: synthesizedTencentCode(secid)
            ?: error("暂不支持该标的走势图")
        val rows = if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) {
            val interval = if (period == ChartPeriod.INTRADAY) "m5" else "m30"
            val param = encode("$code,$interval,,320")
            val raw = request("https://proxy.finance.qq.com/ifzqgtimg/appstock/app/kline/mkline?param=$param", "https://gu.qq.com/")
            val root = JSONObject(raw)
            root.optJSONObject("data")?.optJSONObject(code)?.optJSONArray(interval) ?: error("当前市场暂无分时数据")
        } else {
            val param = encode("$code,${period.cacheKey},,,180,qfq")
            val raw = request("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$param", "https://gu.qq.com/")
            val node = JSONObject(raw).optJSONObject("data")?.optJSONObject(code) ?: error("当前市场暂无 K 线数据")
            node.optJSONArray("qfq${period.cacheKey}") ?: node.optJSONArray(period.cacheKey) ?: error("当前市场暂无 K 线数据")
        }
        val bars = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (row.length() < 5) continue
                val date = row.optString(0)
                val open: Double
                val close: Double
                val high: Double
                val low: Double
                val volume: Double
                if (period == ChartPeriod.INTRADAY || period == ChartPeriod.FIVE_DAY) {
                    open = row.optString(1).toDoubleOrNull() ?: continue
                    close = row.optString(2).toDoubleOrNull() ?: continue
                    high = row.optString(3).toDoubleOrNull() ?: continue
                    low = row.optString(4).toDoubleOrNull() ?: continue
                    volume = row.optString(5).toDoubleOrNull() ?: 0.0
                } else {
                    open = row.optString(1).toDoubleOrNull() ?: continue
                    close = row.optString(2).toDoubleOrNull() ?: continue
                    high = row.optString(3).toDoubleOrNull() ?: continue
                    low = row.optString(4).toDoubleOrNull() ?: continue
                    volume = row.optString(5).toDoubleOrNull() ?: 0.0
                }
                if (listOf(open, close, high, low).all { it.isFinite() && it > 0 }) add(MarketOhlcBar(date, open, close, high, low, volume))
            }
        }.distinctBy { it.day }.sortedBy { it.day }
        return bars
    }

    private fun fetchEastMoneyChartBars(
        secid: String,
        period: ChartPeriod,
        startDay: String? = null,
        endDay: String? = null
    ): List<MarketOhlcBar> {
        require(period == ChartPeriod.DAY || period == ChartPeriod.WEEK || period == ChartPeriod.MONTH)
        require((startDay == null) == (endDay == null)) { "区间日线请求缺少边界日期" }
        val klt = when (period) { ChartPeriod.DAY -> 101; ChartPeriod.WEEK -> 102; else -> 103 }
        val rangeQuery = if (startDay != null && endDay != null) {
            val begin = startDay.replace("-", "")
            val end = endDay.replace("-", "")
            "beg=$begin&end=$end&lmt=180"
        } else "end=20500101&lmt=180"
        val query = listOf(
            "secid=${encode(secid)}", "fields1=f1,f2,f3,f4,f5,f6",
            "fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
            "klt=$klt", "fqt=0", rangeQuery,
            "ut=fa5fd1943c7b386f172d6893dbfba10b"
        ).joinToString("&")
        val raw = request("https://push2his.eastmoney.com/api/qt/stock/kline/get?$query", "https://quote.eastmoney.com/")
        val root = JSONObject(raw)
        require(root.optInt("rc", 0) == 0) { "东方财富没有返回 K 线" }
        val rows = root.optJSONObject("data")?.optJSONArray("klines") ?: error("东方财富没有返回 K 线")
        return buildList {
            for (index in 0 until rows.length()) {
                val fields = rows.optString(index).split(',')
                if (fields.size < 6) continue
                val open = fields[1].toDoubleOrNull() ?: continue
                val close = fields[2].toDoubleOrNull() ?: continue
                val high = fields[3].toDoubleOrNull() ?: continue
                val low = fields[4].toDoubleOrNull() ?: continue
                val volume = fields[5].toDoubleOrNull() ?: 0.0
                if (listOf(open, close, high, low).all { it.isFinite() && it > 0 }) add(MarketOhlcBar(fields[0], open, close, high, low, volume))
            }
        }.distinctBy { it.day }.sortedBy { it.day }
    }

    private fun request(url: String, referer: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            check(code in 200..299) { "行情请求失败（HTTP $code）" }
            body
        } finally { connection.disconnect() }
    }

    private fun requestPost(url: String, form: String, referer: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
        return try {
            connection.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            check(code in 200..299) { "行情请求失败（HTTP $code）" }
            body
        } finally { connection.disconnect() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

internal fun parseTencentQuoteTime(value: String, fallbackEpochSeconds: Long): Long = runCatching {
    java.time.LocalDateTime.parse(
        value.trim(),
        java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
    ).atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond()
}.getOrDefault(fallbackEpochSeconds)

private fun normalizeMarketDay(value: String): String? = runCatching {
    val trimmed = value.trim()
    val date = if (trimmed.length == 8 && trimmed.all(Char::isDigit)) {
        java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
    } else {
        java.time.LocalDate.parse(trimmed.take(10))
    }
    date.toString()
}.getOrNull()

fun MarketQuote.displayTime(): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(quoteTime))
}.getOrDefault("—")
