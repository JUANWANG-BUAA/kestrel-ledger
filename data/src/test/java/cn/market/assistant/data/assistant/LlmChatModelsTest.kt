package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.ChatMessage
import cn.market.assistant.domain.assistant.ChatRequest
import cn.market.assistant.domain.assistant.ChatRole
import cn.market.assistant.domain.assistant.LlmProtocol
import cn.market.assistant.domain.assistant.LlmSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.json.JSONObject

class LlmChatModelsTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun factoryUsesEachProtocolEndpointAndItsAuthenticationHeader() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val requests = mutableListOf<CapturedRequest>()
        val transport = object : HttpTransport() {
            override fun postJson(url: String, headers: Map<String, String>, body: String): String {
                requests += CapturedRequest(url, headers, body)
                return when {
                    url.endsWith("/chat/completions") ->
                        """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
                    url.endsWith("/responses") ->
                        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""
                    else -> """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
                }
            }
        }
        val request = ChatRequest(messages = listOf(ChatMessage(ChatRole.USER, "ping")), maxTokens = 16)

        val chat = LlmChatModelFactory.create(LlmSettings(), "sk-test-secret", dispatcher, transport)
        assertNotNull(chat)
        assertEquals("ok", chat!!.complete(request).message.content)

        val responsesSettings = LlmSettings().withProtocol(LlmProtocol.OPENAI_RESPONSES)
        val responses = LlmChatModelFactory.create(responsesSettings, "sk-test-secret", dispatcher, transport)
        assertNotNull(responses)
        assertEquals("ok", responses!!.complete(request).message.content)

        val anthropicSettings = LlmSettings().withProtocol(LlmProtocol.ANTHROPIC_MESSAGES)
        val anthropic = LlmChatModelFactory.create(anthropicSettings, "sk-test-secret", dispatcher, transport)
        assertNotNull(anthropic)
        assertEquals("ok", anthropic!!.complete(request).message.content)

        assertEquals(3, requests.size)
        assertEquals("https://api.deepseek.com/chat/completions", requests[0].url)
        assertEquals("Bearer sk-test-secret", requests[0].headers["Authorization"])
        assertEquals("deepseek-flash", JSONObject(requests[0].body).getString("model"))

        assertEquals("https://api.deepseek.com/responses", requests[1].url)
        assertEquals("Bearer sk-test-secret", requests[1].headers["Authorization"])
        assertEquals("deepseek-flash", JSONObject(requests[1].body).getString("model"))

        assertEquals("https://api.deepseek.com/anthropic/v1/messages", requests[2].url)
        assertEquals("sk-test-secret", requests[2].headers["x-api-key"])
        assertEquals("2023-06-01", requests[2].headers["anthropic-version"])
        assertEquals("deepseek-flash", JSONObject(requests[2].body).getString("model"))
        assertEquals(false, requests[2].headers.containsKey("Authorization"))
    }

    private data class CapturedRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String
    )
}
