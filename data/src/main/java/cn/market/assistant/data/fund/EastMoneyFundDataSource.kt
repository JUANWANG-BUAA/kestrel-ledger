package cn.market.assistant.data.fund

import cn.market.assistant.domain.fund.FundHolding
import cn.market.assistant.domain.fund.FundHoldingQuote
import cn.market.assistant.domain.fund.FundNavPoint
import cn.market.assistant.domain.fund.FundProfile
import cn.market.assistant.domain.fund.FundSearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

internal data class FundCatalogEntry(
    val code: String,
    val pinyinAbbreviation: String,
    val name: String,
    val category: String,
    val pinyin: String
)

internal object FundCatalogSearch {
    const val MAX_RESULTS = 500

    fun search(
        entries: List<FundCatalogEntry>,
        query: String,
        fetchedAt: Long,
        source: String,
        maxResults: Int = MAX_RESULTS
    ): List<FundSearchResult> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return emptyList()
        val pinyinQuery = normalized.uppercase(Locale.ROOT)
        return entries.mapNotNull { entry ->
            val rank = when {
                entry.code == normalized -> 0
                entry.code.startsWith(normalized) -> 1
                entry.name.equals(normalized, ignoreCase = true) -> 2
                entry.name.startsWith(normalized, ignoreCase = true) -> 3
                entry.name.contains(normalized, ignoreCase = true) -> 4
                entry.pinyinAbbreviation == pinyinQuery -> 5
                entry.pinyinAbbreviation.startsWith(pinyinQuery) -> 6
                entry.pinyinAbbreviation.contains(pinyinQuery) -> 7
                entry.pinyin.startsWith(pinyinQuery) -> 8
                entry.pinyin.contains(pinyinQuery) -> 9
                else -> null
            }
            rank?.let { it to entry }
        }.sortedWith(compareBy<Pair<Int, FundCatalogEntry>> { it.first }.thenBy { it.second.code })
            .take(maxResults.coerceAtLeast(0))
            .map { (_, entry) ->
                FundSearchResult(
                    code = entry.code,
                    name = entry.name,
                    category = entry.category.ifBlank { "公募基金" },
                    source = source,
                    fetchedAt = fetchedAt
                )
            }
    }
}

internal object EastMoneyFundDataSource {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
    private const val SEARCH_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8"
    private const val SEARCH_SOURCE = "eastmoney-fund-suggest"
    private const val FUND_SEARCH_SOURCE = "eastmoney-fund-search"
    private const val FUND_CATALOG_SOURCE = "eastmoney-fund-catalog"
    private const val FUND_SOURCE = "tiantianfund"
    private const val NAV_INCREMENTAL_PAGE_SIZE = 120
    private const val NAV_FULL_PAGE_SIZE = 1000
    private const val MAX_NAV_PAGES = 40
    private const val DEVICE_ID = "WapDeviceId"
    private const val FUND_CATALOG_CACHE_AGE_MILLIS = 12 * 60 * 60 * 1000L
    private const val MAX_FUND_CATALOG_CACHE_BYTES = 12L * 1_024 * 1_024
    private const val FUND_CATALOG_URL = "https://fund.eastmoney.com/js/fundcode_search.js"

    private data class FundCatalogSnapshot(val entries: List<FundCatalogEntry>, val fetchedAt: Long)

    private val fundCatalogLock = Any()

    @Volatile
    private var fundCatalogSnapshot: FundCatalogSnapshot? = null
    private var fundCatalogRefreshInProgress = false
    private var fundCatalogInitialLoad: CompletableFuture<FundCatalogSnapshot>? = null

    fun search(query: String, catalogCacheFile: File? = null): List<FundSearchResult> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.length >= 2) { "至少输入 2 个字或代码" }

        if (normalizedQuery.matches(Regex("\\d{6}"))) {
            val cached = fundCatalogSnapshot
            val cachedCode = cached?.takeIf {
                System.currentTimeMillis() - it.fetchedAt * 1000L < FUND_CATALOG_CACHE_AGE_MILLIS
            }?.let {
                FundCatalogSearch.search(
                    entries = it.entries,
                    query = normalizedQuery,
                    fetchedAt = it.fetchedAt,
                    source = FUND_CATALOG_SOURCE,
                    maxResults = 1
                ).firstOrNull()?.takeIf { result -> result.code == normalizedQuery }
            }
            if (cachedCode != null) return listOf(cachedCode)
            val codeAttempt = runCatching { searchByCode(normalizedQuery) }
            codeAttempt.getOrNull()?.let { return listOf(it) }
            val fallbackAttempt = runCatching { searchFallback(normalizedQuery) }
            fallbackAttempt.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (fallbackAttempt.isFailure) throw fallbackAttempt.exceptionOrNull()!!
            val codeError = codeAttempt.exceptionOrNull()
            if (codeError?.message?.contains("没有返回基金画像") == true ||
                codeError?.message?.contains("没有返回基金名称") == true
            ) return emptyList()
            if (codeError != null) throw codeError
            return emptyList()
        }

        val catalogAttempt = runCatching { loadFundCatalog(catalogCacheFile) }
        val snapshot = catalogAttempt.getOrNull()
        if (snapshot != null) {
            val fresh = System.currentTimeMillis() - snapshot.fetchedAt * 1000L <= FUND_CATALOG_CACHE_AGE_MILLIS
            val source = if (fresh) FUND_CATALOG_SOURCE else "$FUND_CATALOG_SOURCE-cache"
            val catalogResults = FundCatalogSearch.search(
                entries = snapshot.entries,
                query = normalizedQuery,
                fetchedAt = snapshot.fetchedAt,
                source = source
            )
            if (catalogResults.isNotEmpty() || fresh) return catalogResults
        }

        val fallbackResults = runCatching { searchFallback(normalizedQuery) }.getOrNull().orEmpty()
        if (fallbackResults.isNotEmpty()) return fallbackResults
        if (catalogAttempt.isFailure) throw catalogAttempt.exceptionOrNull()!!
        return fallbackResults
    }

    private fun searchByCode(code: String): FundSearchResult {
        val basic = fetchBasic(code, connectTimeoutMillis = 3_500, readTimeoutMillis = 5_000)
        val name = basic.optString("SHORTNAME").takeIf(String::isNotBlank)
            ?: error("没有返回基金名称")
        val fetchedAt = System.currentTimeMillis() / 1000
        return FundSearchResult(
            code = code,
            name = name,
            category = basic.optString("FTYPE").takeIf(String::isNotBlank) ?: "公募基金",
            source = "tiantianfund-basic-info",
            fetchedAt = fetchedAt
        )
    }

    private fun searchFallback(query: String): List<FundSearchResult> {
        val errors = mutableListOf<Exception>()
        val byFundName = runCatching { searchFundNames(query) }
            .onFailure { if (it is Exception) errors += it }
            .getOrDefault(emptyList())
        val bySuggestion = runCatching { searchMarketSuggestions(query) }
            .onFailure { if (it is Exception) errors += it }
            .getOrDefault(emptyList())
        val merged = (byFundName + bySuggestion)
            .distinctBy { it.code }
            .take(FundCatalogSearch.MAX_RESULTS)
        if (merged.isNotEmpty()) return merged
        if (errors.isNotEmpty()) throw errors.first()
        return emptyList()
    }

    private fun searchMarketSuggestions(query: String): List<FundSearchResult> {
        val url = "https://searchapi.eastmoney.com/api/suggest/get?" + listOf(
            "input=${encode(query)}",
            "type=14",
            "token=$SEARCH_TOKEN",
            "count=50"
        ).joinToString("&")
        val root = JSONObject(
            request(url, "https://www.eastmoney.com/", connectTimeoutMillis = 2_500, readTimeoutMillis = 3_500)
        )
        val rows = root.optJSONObject("QuotationCodeTable")?.optJSONArray("Data") ?: JSONArray()
        val fetchedAt = System.currentTimeMillis() / 1000
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val code = row.optString("Code").trim()
                val name = row.optString("Name").trim()
                val isFund = row.optString("Classify").equals("Fund", ignoreCase = true) ||
                    row.optString("SecurityTypeName").contains("基金") || row.optString("SecurityType") == "8"
                if (!isFund || !code.matches(Regex("\\d{6}") ) || name.isBlank()) continue
                add(FundSearchResult(code, name, "公募基金", SEARCH_SOURCE, fetchedAt))
            }
        }.distinctBy { it.code }.take(FundCatalogSearch.MAX_RESULTS)
    }

    private fun searchFundNames(query: String): List<FundSearchResult> {
        val url = "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?" + listOf(
            "m=1",
            "key=${encode(query)}"
        ).joinToString("&")
        val root = JSONObject(
            request(url, "https://fund.eastmoney.com/", connectTimeoutMillis = 2_500, readTimeoutMillis = 3_500)
        )
        val rows = root.optJSONArray("Datas") ?: JSONArray()
        val fetchedAt = System.currentTimeMillis() / 1000
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val code = row.optString("CODE").trim()
                val name = row.optString("NAME").trim()
                if (!code.matches(Regex("\\d{6}") ) || name.isBlank()) continue
                val basic = row.optJSONObject("FundBaseInfo")
                val category = basic?.optString("FTYPE")?.takeIf(String::isNotBlank) ?: "公募基金"
                add(FundSearchResult(code, name, category, FUND_SEARCH_SOURCE, fetchedAt))
            }
        }.distinctBy { it.code }
    }

    private fun loadFundCatalog(cacheFile: File?): FundCatalogSnapshot {
        var cached: FundCatalogSnapshot? = null
        var startRefresh = false
        var initialLoad: CompletableFuture<FundCatalogSnapshot>? = null
        var ownsInitialLoad = false

        synchronized(fundCatalogLock) {
            val memory = fundCatalogSnapshot
            if (memory != null && isFundCatalogFresh(memory)) return memory

            cached = memory ?: readFundCatalogCache(cacheFile)?.also { fundCatalogSnapshot = it }
            if (cached != null) {
                if (!isFundCatalogFresh(cached) && !fundCatalogRefreshInProgress) {
                    fundCatalogRefreshInProgress = true
                    startRefresh = true
                }
            } else {
                initialLoad = fundCatalogInitialLoad
                if (initialLoad == null) {
                    initialLoad = CompletableFuture()
                    fundCatalogInitialLoad = initialLoad
                    ownsInitialLoad = true
                }
            }
        }

        if (cached != null) {
            if (startRefresh) {
                Thread({
                    try {
                        val current = fundCatalogSnapshot
                        if (current == null || !isFundCatalogFresh(current)) {
                            runCatching { downloadFundCatalog(cacheFile) }
                        }
                    } finally {
                        synchronized(fundCatalogLock) { fundCatalogRefreshInProgress = false }
                    }
                }, "fund-catalog-refresh").apply { isDaemon = true }.start()
            }
            return cached
        }

        val pendingLoad = requireNotNull(initialLoad)
        if (!ownsInitialLoad) {
            return try {
                pendingLoad.get()
            } catch (error: ExecutionException) {
                throw (error.cause as? Exception ?: error)
            }
        }
        return try {
            downloadFundCatalog(cacheFile).also(pendingLoad::complete)
        } catch (error: Exception) {
            pendingLoad.completeExceptionally(error)
            throw error
        } finally {
            synchronized(fundCatalogLock) {
                if (fundCatalogInitialLoad === pendingLoad) fundCatalogInitialLoad = null
            }
        }
    }

    private fun isFundCatalogFresh(snapshot: FundCatalogSnapshot): Boolean =
        System.currentTimeMillis() - snapshot.fetchedAt * 1000L < FUND_CATALOG_CACHE_AGE_MILLIS

    private fun downloadFundCatalog(cacheFile: File?): FundCatalogSnapshot {
        val body = request(
            FUND_CATALOG_URL,
            "https://fund.eastmoney.com/",
            connectTimeoutMillis = 3_500,
            readTimeoutMillis = 7_000
        )
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        check(start >= 0 && end > start) { "基金目录格式无效" }
        val rows = JSONArray(body.substring(start, end + 1))
        val entries = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                val code = row.optString(0).trim()
                val name = row.optString(2).trim()
                if (!code.matches(Regex("\\d{6}") ) || name.isBlank()) continue
                add(
                    FundCatalogEntry(
                        code = code,
                        pinyinAbbreviation = row.optString(1).trim().uppercase(Locale.ROOT),
                        name = name,
                        category = row.optString(3).trim(),
                        pinyin = row.optString(4).trim().uppercase(Locale.ROOT)
                    )
                )
            }
        }.distinctBy { it.code }
        check(entries.isNotEmpty()) { "基金目录没有有效条目" }
        val snapshot = FundCatalogSnapshot(entries, System.currentTimeMillis() / 1000)
        fundCatalogSnapshot = snapshot
        writeFundCatalogCache(cacheFile, snapshot.fetchedAt, body)
        return snapshot
    }

    private fun readFundCatalogCache(file: File?): FundCatalogSnapshot? {
        return runCatching {
            if (file == null || !file.isFile || file.length() > MAX_FUND_CATALOG_CACHE_BYTES) return@runCatching null
            val text = file.readText(Charsets.UTF_8)
            val separator = text.indexOf('\n')
            if (separator <= 0) return@runCatching null
            val fetchedAt = text.substring(0, separator).toLongOrNull() ?: return@runCatching null
            val body = text.substring(separator + 1)
            val start = body.indexOf('[')
            val end = body.lastIndexOf(']')
            if (start < 0 || end <= start) return@runCatching null
            val rows = JSONArray(body.substring(start, end + 1))
            val entries = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONArray(index) ?: continue
                    val code = row.optString(0).trim()
                    val name = row.optString(2).trim()
                    if (!code.matches(Regex("\\d{6}") ) || name.isBlank()) continue
                    add(FundCatalogEntry(
                        code,
                        row.optString(1).trim().uppercase(Locale.ROOT),
                        name,
                        row.optString(3).trim(),
                        row.optString(4).trim().uppercase(Locale.ROOT)
                    ))
                }
            }.distinctBy { it.code }
            entries.takeIf { it.isNotEmpty() }?.let { FundCatalogSnapshot(it, fetchedAt) }
        }.getOrNull()
    }

    private fun writeFundCatalogCache(file: File?, fetchedAt: Long, body: String) {
        if (file == null) return
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText("$fetchedAt\n$body", Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                file.writeText("$fetchedAt\n$body", Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    fun profile(code: String): FundProfile {
        require(code.matches(Regex("\\d{6}"))) { "基金代码格式无效" }
        val basic = fetchBasic(code)
        val navResult = runCatching { fetchNavHistory(code) }
        val holdingsResult = runCatching { fetchHoldings(code) }
        val navHistory = navResult.getOrDefault(emptyList())
        val holdingsData = holdingsResult.getOrDefault(FundHoldingsData())
        val latestNav = navHistory.lastOrNull()
        val fetchedAt = System.currentTimeMillis() / 1000
        return FundProfile(
            code = code,
            name = basic.optString("SHORTNAME").takeIf(String::isNotBlank) ?: code,
            fundType = basic.optString("FTYPE").cleanFundField(),
            managementCompany = basic.optString("JJGS").cleanFundField(),
            unitNav = latestNav?.unitNav ?: basic.optString("DWJZ").toDoubleOrNull(),
            accumulatedNav = latestNav?.accumulatedNav ?: basic.optString("LJJZ").toDoubleOrNull(),
            changePct = latestNav?.changePct ?: basic.optString("RZDF").toDoubleOrNull(),
            navDate = latestNav?.day ?: basic.optString("FSRQ").cleanFundField(),
            subscriptionStatus = basic.optString("SGZT").cleanFundField(),
            redemptionStatus = basic.optString("SHZT").cleanFundField(),
            riskLevel = basic.optString("RISKLEVEL").cleanFundField(),
            navHistory = navHistory,
            holdings = holdingsData.holdings,
            holdingsReportDate = basic.optString("FEGMRQ").cleanFundField(),
            source = FUND_SOURCE,
            fetchedAt = fetchedAt,
            navError = navResult.exceptionOrNull()?.simpleMessage(),
            holdingsError = holdingsResult.exceptionOrNull()?.simpleMessage(),
            trackingIndexSecid = trackingIndexSecid(basic),
            trackingIndexName = basic.optString("INDEXNAME").cleanFundField(),
            linkedEtfCode = holdingsData.linkedEtfCode,
            linkedEtfName = holdingsData.linkedEtfName
        )
    }

    private fun trackingIndexSecid(basic: JSONObject): String? {
        val code = basic.optString("INDEXCODE").trim()
        val exchange = basic.optString("INDEXTEXCH").trim()
        return if (code.matches(Regex("\\d{6}")) && exchange in setOf("0", "1")) "$exchange.$code" else null
    }

    private fun fetchBasic(
        code: String,
        connectTimeoutMillis: Int = 8_000,
        readTimeoutMillis: Int = 10_000
    ): JSONObject {
        val query = mobileQuery("FCODE=${encode(code)}")
        val root = JSONObject(
            request(
                "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNNBasicInformation?$query",
                "https://fund.eastmoney.com/",
                connectTimeoutMillis,
                readTimeoutMillis
            )
        )
        return root.optJSONObject("Datas") ?: error(root.optString("ErrMsg").takeIf(String::isNotBlank) ?: "没有返回基金画像")
    }

    private fun fetchNavHistory(code: String): List<FundNavPoint> {
        return fetchNavPage(code, page = 1, pageSize = NAV_INCREMENTAL_PAGE_SIZE).points
    }

    fun fetchLatestNav(code: String): FundNavPoint? =
        fetchNavPage(code, page = 1, pageSize = 5).points.lastOrNull()

    fun fetchAllNavHistory(code: String): List<FundNavPoint> {
        require(code.matches(Regex("\\d{6}"))) { "基金代码格式无效" }
        val pointsByDay = linkedMapOf<String, FundNavPoint>()
        var expectedTotal: Int? = null
        var complete = false
        for (page in 1..MAX_NAV_PAGES) {
            val result = fetchNavPage(code, page, NAV_FULL_PAGE_SIZE)
            if (page == 1) expectedTotal = result.totalCount
            result.points.forEach { pointsByDay[it.day] = it }
            val requestedThrough = page * NAV_FULL_PAGE_SIZE
            if (result.rowCount < NAV_FULL_PAGE_SIZE || result.rowCount == 0 ||
                expectedTotal?.let { requestedThrough >= it } == true) {
                complete = true
                break
            }
        }
        check(complete) { "基金净值历史超过当前分页上限，请稍后继续补齐" }
        return pointsByDay.values.sortedBy { it.day }
    }

    private fun fetchNavPage(code: String, page: Int, pageSize: Int): NavPage {
        val query = mobileQuery("FCODE=${encode(code)}&pageIndex=$page&pageSize=$pageSize")
        val root = JSONObject(
            request(
                "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNHisNetList?$query",
                "https://fund.eastmoney.com/"
            )
        )
        val rows = root.optJSONArray("Datas") ?: JSONArray()
        val totalCount = root.optInt("TotalCount", -1).takeIf { it >= 0 }
        val points = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val day = row.optString("FSRQ").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: continue
                val unitNav = row.optString("DWJZ").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: continue
                add(FundNavPoint(
                    day = day,
                    unitNav = unitNav,
                    accumulatedNav = row.optString("LJJZ").toDoubleOrNull()?.takeIf(Double::isFinite),
                    changePct = row.optString("JZZZL").toDoubleOrNull()?.takeIf(Double::isFinite)
                ))
            }
        }.distinctBy { it.day }.sortedBy { it.day }
        return NavPage(points, rows.length(), totalCount)
    }

    private data class NavPage(val points: List<FundNavPoint>, val rowCount: Int, val totalCount: Int?)

    private fun fetchHoldings(code: String): FundHoldingsData {
        val query = mobileQuery("FCODE=${encode(code)}")
        val root = JSONObject(request("https://fundmobapi.eastmoney.com/FundMNewApi/FundMNInverstPosition?$query", "https://fund.eastmoney.com/"))
        val data = root.optJSONObject("Datas") ?: return FundHoldingsData()
        val rows = data.optJSONArray("fundStocks") ?: JSONArray()
        val holdings = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val stockCode = row.optString("GPDM").cleanFundField() ?: continue
                val name = row.optString("GPJC").cleanFundField() ?: continue
                val weight = row.optString("JZBL").replace("%", "").toDoubleOrNull() ?: continue
                if (!weight.isFinite() || weight <= 0) continue
                add(FundHolding(stockCode, name, weight, eastMoneyAShareId(stockCode)))
            }
        }.distinctBy { it.code }.sortedByDescending { it.weightPct }
        return FundHoldingsData(
            holdings = holdings,
            linkedEtfCode = data.optString("ETFCODE").trim().takeIf { it.matches(Regex("\\d{6}")) },
            linkedEtfName = data.optString("ETFSHORTNAME").cleanFundField()
        )
    }

    private data class FundHoldingsData(
        val holdings: List<FundHolding> = emptyList(),
        val linkedEtfCode: String? = null,
        val linkedEtfName: String? = null
    )

    fun fetchHoldingQuotes(holdings: List<FundHolding>): List<FundHoldingQuote> {
        val supported = holdings.mapNotNull { holding ->
            holding.code.trim().takeIf { it.matches(Regex("\\d{6}")) }
                ?.let { code -> eastMoneyAShareId(code)?.let { id -> code to id } }
        }
        if (supported.isEmpty()) return emptyList()
        val securityIds = supported.joinToString(",") { it.second }
        val url = "https://push2.eastmoney.com/api/qt/ulist.np/get?" + listOf(
            "fltt=2",
            "fields=f3,f12,f13,f14,f124",
            "secids=${encode(securityIds)}"
        ).joinToString("&")
        val root = JSONObject(request(url, "https://quote.eastmoney.com/"))
        val rows = root.optJSONObject("data")?.optJSONArray("diff") ?: JSONArray()
        val names = holdings.associateBy { it.code }
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val code = row.optString("f12").trim()
                val name = row.optString("f14").trim().ifBlank { names[code]?.name ?: code }
                val changePct = row.optDouble("f3", Double.NaN)
                val quoteTime = row.optLong("f124", 0L)
                if (code !in names || !changePct.isFinite() || quoteTime <= 0) continue
                add(FundHoldingQuote(code, name, changePct, quoteTime, "eastmoney"))
            }
        }.distinctBy { it.code }
    }

    private fun eastMoneyAShareId(code: String): String? = when {
        code.startsWith("600") || code.startsWith("601") || code.startsWith("603") ||
            code.startsWith("605") || code.startsWith("688") || code.startsWith("689") -> "1.$code"
        code.startsWith("000") || code.startsWith("001") || code.startsWith("002") ||
            code.startsWith("003") || code.startsWith("300") || code.startsWith("301") -> "0.$code"
        else -> null
    }

    private fun mobileQuery(prefix: String): String =
        "$prefix&deviceid=$DEVICE_ID&plat=Android&product=EFund&version=6.2.8"

    private fun request(
        url: String,
        referer: String,
        connectTimeoutMillis: Int = 8_000,
        readTimeoutMillis: Int = 10_000
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            check(code in 200..299) { "基金数据请求失败（HTTP $code）" }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun String.cleanFundField(): String? = trim().takeIf { it.isNotEmpty() && it != "--" && it != "null" }

    private fun Throwable.simpleMessage(): String = message?.take(80) ?: javaClass.simpleName
}
