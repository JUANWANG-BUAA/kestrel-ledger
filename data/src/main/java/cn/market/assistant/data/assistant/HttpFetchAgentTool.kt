package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Only these public data hosts may be fetched by the model; everything else is refused. */
object FetchAllowlist {
    private val HOSTS = setOf(
        "push2.eastmoney.com",
        "push2delay.eastmoney.com",
        "push2his.eastmoney.com",
        "fundmobapi.eastmoney.com",
        "searchapi.eastmoney.com",
        "qt.gtimg.cn",
        "web.ifzq.gtimg.cn",
        "proxy.finance.qq.com",
        "hq.sinajs.cn",
        "www.sge.com.cn",
        "query1.finance.yahoo.com"
    )

    const val MAX_RESPONSE_CHARS = 6_000
    const val TIMEOUT_MILLIS = 10_000

    fun hostOf(url: String): String? = runCatching { URL(url).host?.lowercase() }.getOrNull()

    fun isAllowed(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = hostOf(url) ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }
}

/**
 * Read-only web fetch restricted to the public market-data hosts the app itself uses. It never
 * accepts an arbitrary URL, never sends credentials, and caps the returned body before it reaches
 * the model.
 */
class HttpFetchAgentTool(
    private val fetcher: (String) -> String = ::defaultFetch
) : AgentTool {
    override val id = "web.fetch"
    override val description = "抓取公开行情数据（仅限东方财富、腾讯、上金所、新浪、Yahoo 的 https 地址），返回文本片段。"
    override val parameters = listOf(
        AgentToolParameter("url", "以 https:// 开头的公开数据地址", required = true)
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val url = request.arguments["url"]?.trim().orEmpty()
        if (url.isBlank()) return AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        if (!FetchAllowlist.isAllowed(url)) {
            return AgentToolResult(emptyList(), "URL_NOT_ALLOWED")
        }
        val body = try {
            fetcher(url)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentToolResult(emptyList(), "FETCH_FAILED")
        }
        val trimmed = body.take(FetchAllowlist.MAX_RESPONSE_CHARS)
        if (trimmed.isBlank()) return AgentToolResult(emptyList(), "EMPTY_RESPONSE")
        val host = FetchAllowlist.hostOf(url) ?: "unknown"
        return AgentToolResult(
            listOf(
                AssistantFact(
                    key = "web.fetch.$host",
                    value = trimmed,
                    unit = null,
                    source = "https://$host",
                    observedAt = Instant.now().toString(),
                    sensitivity = FactSensitivity.PUBLIC_MARKET
                )
            )
        )
    }

    private companion object {
        fun defaultFetch(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            return try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                check(code in 200..299) { "HTTP $code" }
                body
            } finally {
                connection.disconnect()
            }
        }
    }
}
