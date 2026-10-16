package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.ChatModel
import cn.market.assistant.domain.assistant.ChatModelException
import cn.market.assistant.domain.assistant.ChatRequest
import cn.market.assistant.domain.assistant.ChatResponse
import cn.market.assistant.domain.assistant.LlmEndpointValidator
import cn.market.assistant.domain.assistant.LlmProvider
import cn.market.assistant.domain.assistant.LlmSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Blocking HTTP POST kept behind a seam so mapping tests never open a socket. */
class HttpTransport {
    fun postJson(url: String, headers: Map<String, String>, body: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ChatModelException(describeHttpError(code, response))
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun describeHttpError(code: Int, body: String): String {
        val providerMessage = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        val friendly = when (code) {
            401, 403 -> "API Key 无效或无权限（HTTP $code）"
            404 -> "接口地址或模型不存在（HTTP 404）"
            429 -> "请求过于频繁或额度不足（HTTP 429）"
            in 500..599 -> "模型服务暂时不可用（HTTP $code）"
            else -> "模型请求失败（HTTP $code）"
        }
        return providerMessage?.let { "$friendly：${it.take(200)}" } ?: friendly
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 90_000
    }
}

/** OpenAI `/chat/completions` protocol: OpenAI, DeepSeek, Kimi, GLM, Qwen, Gemini, OpenRouter, Ollama. */
class OpenAiCompatibleChatModel(
    private val settings: LlmSettings,
    private val apiKey: String?,
    private val ioDispatcher: CoroutineDispatcher,
    private val transport: HttpTransport = HttpTransport()
) : ChatModel {
    override val displayName: String = "${settings.provider.label} · ${settings.model}"

    override suspend fun complete(request: ChatRequest): ChatResponse = withContext(ioDispatcher) {
        val headers = buildMap {
            apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            if (settings.provider == LlmProvider.OPENROUTER) {
                put("HTTP-Referer", "https://localhost/market-assistant")
                put("X-Title", "Market Assistant")
            }
        }
        val body = OpenAiWire.requestBody(settings.model, request)
        val response = transport.postJson(LlmEndpointValidator.openAiChatUrl(settings), headers, body)
        OpenAiWire.parseResponse(response)
    }
}

/** Anthropic Messages protocol. */
class AnthropicChatModel(
    private val settings: LlmSettings,
    private val apiKey: String?,
    private val ioDispatcher: CoroutineDispatcher,
    private val transport: HttpTransport = HttpTransport()
) : ChatModel {
    override val displayName: String = "${settings.provider.label} · ${settings.model}"

    override suspend fun complete(request: ChatRequest): ChatResponse = withContext(ioDispatcher) {
        val headers = buildMap {
            apiKey?.takeIf { it.isNotBlank() }?.let { put("x-api-key", it) }
            put("anthropic-version", ANTHROPIC_VERSION)
        }
        val body = AnthropicWire.requestBody(settings.model, request, request.maxTokens)
        val response = transport.postJson(LlmEndpointValidator.anthropicMessagesUrl(settings), headers, body)
        AnthropicWire.parseResponse(response)
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

object LlmChatModelFactory {
    /** Returns null when the settings or credentials are incomplete; the UI then stays in offline mode. */
    fun create(
        settings: LlmSettings,
        apiKey: String?,
        ioDispatcher: CoroutineDispatcher,
        transport: HttpTransport = HttpTransport()
    ): ChatModel? {
        val normalized = settings.normalized()
        if (!normalized.isConfigured) return null
        if (normalized.provider != LlmProvider.OLLAMA && apiKey.isNullOrBlank()) return null
        return when (normalized.provider.protocol) {
            cn.market.assistant.domain.assistant.LlmProtocol.OPENAI_COMPATIBLE ->
                OpenAiCompatibleChatModel(normalized, apiKey, ioDispatcher, transport)
            cn.market.assistant.domain.assistant.LlmProtocol.ANTHROPIC ->
                AnthropicChatModel(normalized, apiKey, ioDispatcher, transport)
        }
    }
}
