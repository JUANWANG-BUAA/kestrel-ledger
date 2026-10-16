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

internal object EastMoneyFundDataSource {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
    private const val SEARCH_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8"
    private const val SEARCH_SOURCE = "eastmoney-fund-search"
    private const val FUND_SOURCE = "tiantianfund"
    private const val NAV_INCREMENTAL_PAGE_SIZE = 120
    private const val NAV_FULL_PAGE_SIZE = 1000
    private const val MAX_NAV_PAGES = 40
    private const val DEVICE_ID = "WapDeviceId"

    fun search(query: String): List<FundSearchResult> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.length >= 2) { "至少输入 2 个字或代码" }
        val url = "https://searchapi.eastmoney.com/api/suggest/get?" + listOf(
            "input=${encode(normalizedQuery)}",
            "type=14",
            "token=$SEARCH_TOKEN",
            "count=20"
        ).joinToString("&")
        val root = JSONObject(request(url, "https://www.eastmoney.com/"))
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
        }.distinctBy { it.code }
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

    private fun fetchBasic(code: String): JSONObject {
        val query = mobileQuery("FCODE=${encode(code)}")
        val root = JSONObject(request("https://fundmobapi.eastmoney.com/FundMNewApi/FundMNNBasicInformation?$query", "https://fund.eastmoney.com/"))
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
