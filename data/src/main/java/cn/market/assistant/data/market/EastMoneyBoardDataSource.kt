package cn.market.assistant.data.market

import cn.market.assistant.domain.market.MarketBoard
import cn.market.assistant.domain.market.MarketBoardType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Eastmoney industry/concept board feed. A snapshot is returned only after every page validates. */
internal object EastMoneyBoardDataSource {
    private const val PAGE_SIZE = 100
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
    private const val SOURCE = "eastmoney-board"

    fun fetch(type: MarketBoardType): List<MarketBoard> {
        val boards = linkedMapOf<String, MarketBoard>()
        var expectedCount = 0
        var page = 1
        do {
            val root = readPage(url(type, page))
            require(root.optInt("rc", -1) == 0) { "东方财富板块列表暂不可用" }
            val data = root.optJSONObject("data") ?: error("东方财富没有返回板块数据")
            if (page == 1) {
                expectedCount = data.optInt("total", 0)
                require(expectedCount > 0) { "东方财富板块列表为空" }
            }
            val rows = data.optJSONArray("diff") ?: error("东方财富板块列表缺少当前页数据")
            require(rows.length() > 0) { "东方财富板块列表分页不完整" }
            val fetchedAt = System.currentTimeMillis() / 1000
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val code = row.optString("f12").takeIf { it.isNotBlank() } ?: continue
                val name = row.optString("f14").takeIf { it.isNotBlank() } ?: continue
                val price = row.number("f2") ?: continue
                val changePct = row.number("f3") ?: continue
                if (price <= 0.0 || changePct <= -100.0) continue
                val change = row.number("f4") ?: 0.0
                val sourceTime = row.number("f124")?.toLong()
                    ?.takeIf { it > 0 && it <= fetchedAt + 5 * 60 }
                    ?: fetchedAt
                boards["90.$code"] = MarketBoard(
                    id = "90.$code",
                    type = type,
                    name = name,
                    price = price,
                    change = change,
                    changePct = changePct,
                    turnoverAmount = row.number("f6"),
                    turnoverRatePct = row.number("f8"),
                    marketCap = row.number("f20"),
                    advancingCount = row.optInt("f104", 0).coerceAtLeast(0),
                    decliningCount = row.optInt("f105", 0).coerceAtLeast(0),
                    quoteTime = sourceTime,
                    fetchedAt = fetchedAt,
                    source = SOURCE
                )
            }
            require(boards.size >= minOf(expectedCount, page * PAGE_SIZE)) { "东方财富板块分页出现重复或缺失数据" }
            page++
            if (boards.size < expectedCount) Thread.sleep(150)
        } while (boards.size < expectedCount && page <= (expectedCount / PAGE_SIZE) + 2)

        require(boards.size >= expectedCount) { "东方财富板块数据不完整：${boards.size}/$expectedCount" }
        return boards.values.toList()
    }

    private fun url(type: MarketBoardType, page: Int): String {
        val eastMoneyType = when (type) {
            MarketBoardType.INDUSTRY -> 2
            MarketBoardType.CONCEPT -> 3
        }
        val query = listOf(
            "pn=$page",
            "pz=$PAGE_SIZE",
            "po=1",
            "np=1",
            "fltt=2",
            "invt=2",
            // A stable order prevents live percentage changes from shifting rows between pages.
            "fid=f12",
            "fs=${encode("m:90+t:$eastMoneyType+f:!50")}",
            "fields=f2,f3,f4,f6,f8,f12,f14,f20,f104,f105,f124"
        ).joinToString("&")
        return "https://push2.eastmoney.com/api/qt/clist/get?$query"
    }

    private fun JSONObject.number(field: String): Double? =
        optDouble(field, Double.NaN).takeIf { it.isFinite() }

    private fun readPage(url: String): JSONObject {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val response = runCatching { JSONObject(request(url)) }
            val result = response.getOrNull()
            if (result?.optInt("rc", -1) == 0 &&
                result.optJSONObject("data")?.optJSONArray("diff") != null) return result
            lastError = response.exceptionOrNull() ?: IllegalStateException("行情源没有返回有效板块页")
            if (attempt == 0) Thread.sleep(350)
        }
        throw IllegalStateException("东方财富板块分页读取失败", lastError)
    }

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", "https://quote.eastmoney.com/")
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "东方财富请求失败（HTTP $code）" }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
