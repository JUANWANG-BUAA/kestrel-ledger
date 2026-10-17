package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.ChatAttachment
import cn.market.assistant.domain.assistant.ChatMessage
import cn.market.assistant.domain.assistant.ChatModelException
import cn.market.assistant.domain.assistant.ChatRequest
import cn.market.assistant.domain.assistant.ChatRole
import cn.market.assistant.domain.assistant.ChatToolCall
import cn.market.assistant.domain.assistant.ChatToolDefinition
import cn.market.assistant.domain.assistant.LlmModelCapabilities
import cn.market.assistant.domain.assistant.LlmProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmWireTest {
    private val tools = listOf(
        ChatToolDefinition(
            name = "market.quote",
            description = "查询报价",
            parametersJsonSchema = "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"
        )
    )

    @Test
    fun deepSeekChatMapsThinkingEffortAndTemperatureByDocumentedMode() {
        val thinking = JSONObject(
            OpenAiWire.requestBody(
                "deepseek-flash",
                ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    temperature = 0.8,
                    provider = LlmProvider.DEEPSEEK,
                    reasoningEffort = "high"
                )
            )
        )
        assertEquals("enabled", thinking.getJSONObject("thinking").getString("type"))
        assertEquals("high", thinking.getString("reasoning_effort"))
        assertFalse(thinking.has("temperature"))

        val nonThinking = JSONObject(
            OpenAiWire.requestBody(
                "deepseek-flash",
                ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    temperature = 0.8,
                    provider = LlmProvider.DEEPSEEK,
                    reasoningEffort = "none"
                )
            )
        )
        assertEquals("disabled", nonThinking.getJSONObject("thinking").getString("type"))
        assertEquals(0.8, nonThinking.getDouble("temperature"), 0.0)
        assertFalse(nonThinking.has("reasoning_effort"))
    }

    @Test
    fun responsesRequestCarriesConfiguredReasoningEffort() {
        val body = JSONObject(
            OpenAiResponsesWire.requestBody(
                "deepseek-flash",
                ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    provider = LlmProvider.DEEPSEEK,
                    reasoningEffort = "max"
                )
            )
        )
        assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.has("temperature"))
    }

    @Test
    fun anthropicMapsCataloguedEffortToAdaptiveAndManualThinking() {
        val adaptiveBody = JSONObject(
            AnthropicWire.requestBody(
                "claude-sonnet-5-5",
                ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    provider = LlmProvider.ANTHROPIC,
                    reasoningEffort = "high",
                    capabilities = LlmModelCapabilities("claude-sonnet-5-5", reasoningEfforts = listOf("low", "high"))
                ),
                maxTokens = 4_096
            )
        )
        assertEquals("adaptive", adaptiveBody.getJSONObject("thinking").getString("type"))
        assertEquals("high", adaptiveBody.getJSONObject("output_config").getString("effort"))
        assertFalse(adaptiveBody.has("temperature"))

        val manualBody = JSONObject(
            AnthropicWire.requestBody(
                "claude-sonnet-4-5",
                ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    provider = LlmProvider.ANTHROPIC,
                    reasoningEffort = "low",
                    capabilities = LlmModelCapabilities("claude-sonnet-4-5", thinkingTypes = setOf("enabled"))
                ),
                maxTokens = 4_096
            )
        )
        assertEquals("enabled", manualBody.getJSONObject("thinking").getString("type"))
        assertEquals(1_024, manualBody.getJSONObject("thinking").getInt("budget_tokens"))
    }

    @Test
    fun openAiRequestCarriesToolsImagesAndToolResults() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, "系统提示"),
                ChatMessage(ChatRole.USER, "看看这张截图", attachments = listOf(image())),
                ChatMessage(
                    ChatRole.ASSISTANT,
                    "",
                    toolCalls = listOf(ChatToolCall("call-1", "market.quote", "{\"query\":\"沪深300\"}"))
                ),
                ChatMessage(ChatRole.TOOL, "- market.quote.price: 4166.01", toolCallId = "call-1")
            ),
            tools = tools
        )

        val body = JSONObject(OpenAiWire.requestBody("deepseek-chat", request))

        assertEquals("deepseek-chat", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val userContent = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", userContent.getJSONObject(0).getString("type"))
        assertEquals("image_url", userContent.getJSONObject(1).getString("type"))
        assertTrue(userContent.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
        val assistant = messages.getJSONObject(2)
        assertEquals("market.quote", assistant.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("call-1", messages.getJSONObject(3).getString("tool_call_id"))
        assertEquals("market.quote", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun openAiFoldsTextAttachmentsIntoToolResultContent() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    ChatRole.USER,
                    "分析这个流水文件",
                    attachments = listOf(ChatAttachment("trades.csv", "text/csv", text = "日期,金额\n2026-10-01,100"))
                )
            )
        )

        val messages = JSONObject(OpenAiWire.requestBody("m", request)).getJSONArray("messages")
        val content = messages.getJSONObject(0).getString("content")

        assertTrue(content.contains("分析这个流水文件"))
        assertTrue(content.contains("【附件：trades.csv（text/csv）】"))
        assertTrue(content.contains("2026-10-01,100"))
    }

    @Test
    fun openAiParsesToolCallsUsageAndErrors() {
        val response = OpenAiWire.parseResponse(
            """
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"c1","type":"function","function":{"name":"market.quote","arguments":"{\"query\":\"x\"}"}}]}}],
            "usage":{"prompt_tokens":120,"completion_tokens":30,"completion_tokens_details":{"reasoning_tokens":8}}}
            """.trimIndent()
        )

        assertEquals(1, response.message.toolCalls.size)
        assertEquals("market.quote", response.message.toolCalls[0].name)
        assertEquals(120, response.usage.promptTokens)
        assertEquals(30, response.usage.completionTokens)
        assertEquals(8, response.usage.reasoningTokens)
        assertEquals("tool_calls", response.finishReason)

        val error = assertThrows(ChatModelException::class.java) {
            OpenAiWire.parseResponse("{\"error\":{\"message\":\"Invalid API key\"}}")
        }
        assertTrue(error.message!!.contains("Invalid API key"))
    }

    @Test
    fun openAiParsesAndEchoesReasoningContentForToolContinuity() {
        val parsed = OpenAiWire.parseResponse(
            """
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
            "reasoning_content":"需要先查行情","tool_calls":[{"id":"c1","type":"function",
            "function":{"name":"market.quote","arguments":"{\"query\":\"黄金\"}"}}]}}]}
            """.trimIndent()
        )

        assertEquals("需要先查行情", parsed.message.reasoningContent)
        val continuation = ChatRequest(
            messages = listOf(
                parsed.message,
                ChatMessage(ChatRole.TOOL, "现价 2,900", toolCallId = "c1")
            ),
            tools = tools
        )
        val assistant = JSONObject(OpenAiWire.requestBody("deepseek-flash", continuation))
            .getJSONArray("messages").getJSONObject(0)
        assertEquals("需要先查行情", assistant.getString("reasoning_content"))
    }

    @Test
    fun responsesRequestCarriesInstructionsToolsImagesAndStatelessToolHistory() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, "系统提示"),
                ChatMessage(ChatRole.USER, "分析图片", attachments = listOf(image())),
                ChatMessage(
                    ChatRole.ASSISTANT,
                    "",
                    reasoningContent = "先检查图表",
                    toolCalls = listOf(ChatToolCall("call-r1", "market.quote", "{\"query\":\"黄金\"}"))
                ),
                ChatMessage(ChatRole.TOOL, "金价 2,900", toolCallId = "call-r1")
            ),
            tools = tools,
            maxTokens = 128
        )

        val body = JSONObject(OpenAiResponsesWire.requestBody("deepseek-flash", request))

        assertEquals("系统提示", body.getString("instructions"))
        assertEquals(128, body.getInt("max_output_tokens"))
        assertEquals(false, body.getBoolean("stream"))
        val input = body.getJSONArray("input")
        val userContent = input.getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", userContent.getJSONObject(0).getString("type"))
        assertEquals("input_image", userContent.getJSONObject(1).getString("type"))
        assertTrue(userContent.getJSONObject(1).getString("image_url").startsWith("data:image/png;base64,"))
        assertEquals("reasoning", input.getJSONObject(1).getString("type"))
        assertEquals("先检查图表", input.getJSONObject(1).getString("content"))
        assertEquals("function_call", input.getJSONObject(2).getString("type"))
        assertEquals("call-r1", input.getJSONObject(2).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
        assertEquals("call-r1", input.getJSONObject(3).getString("call_id"))
        assertEquals("market.quote", body.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun responsesParsesTextReasoningToolCallsAndUsage() {
        val parsed = OpenAiResponsesWire.parseResponse(
            """
            {"status":"completed","output":[
              {"type":"reasoning","content":[{"type":"reasoning_text","text":"先取证"}]},
              {"type":"message","content":[{"type":"output_text","text":"黄金报价如下"}]},
              {"type":"function_call","call_id":"r1","name":"market.quote","arguments":"{\"query\":\"黄金\"}"}
            ],"usage":{"input_tokens":40,"output_tokens":12}}
            """.trimIndent()
        )

        assertEquals("黄金报价如下", parsed.message.content)
        assertEquals("先取证", parsed.message.reasoningContent)
        assertEquals("r1", parsed.message.toolCalls.single().id)
        assertEquals("market.quote", parsed.message.toolCalls.single().name)
        assertEquals(40, parsed.usage.promptTokens)
        assertEquals(12, parsed.usage.completionTokens)
        assertEquals("completed", parsed.finishReason)
    }

    @Test
    fun anthropicRequestSeparatesSystemAndMergesToolResults() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, "系统提示"),
                ChatMessage(ChatRole.USER, "我的持仓"),
                ChatMessage(
                    ChatRole.ASSISTANT,
                    "先查账本",
                    toolCalls = listOf(ChatToolCall("t1", "portfolio.positions", "{\"scope\":\"detail\"}"))
                ),
                ChatMessage(ChatRole.TOOL, "结果 A", toolCallId = "t1"),
                ChatMessage(ChatRole.TOOL, "结果 B", toolCallId = "t2")
            ),
            tools = tools
        )

        val body = JSONObject(AnthropicWire.requestBody("claude-sonnet-4-5", request, maxTokens = 1_024))

        assertEquals("系统提示", body.getString("system"))
        assertEquals(1_024, body.getInt("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val assistantBlocks = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", assistantBlocks.getJSONObject(0).getString("type"))
        assertEquals("tool_use", assistantBlocks.getJSONObject(1).getString("type"))
        assertEquals("detail", assistantBlocks.getJSONObject(1).getJSONObject("input").getString("scope"))
        val toolResultBlocks = messages.getJSONObject(2).getJSONArray("content")
        assertEquals(2, toolResultBlocks.length())
        assertEquals("t1", toolResultBlocks.getJSONObject(0).getString("tool_use_id"))
        assertEquals("t2", toolResultBlocks.getJSONObject(1).getString("tool_use_id"))
        assertEquals("market.quote", body.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertTrue(body.getJSONArray("tools").getJSONObject(0).has("input_schema"))
    }

    @Test
    fun anthropicEncodesImagesAndParsesContentBlocks() {
        val request = ChatRequest(messages = listOf(ChatMessage(ChatRole.USER, "图", attachments = listOf(image()))))
        val body = JSONObject(AnthropicWire.requestBody("m", request, maxTokens = 128))
        val blocks = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")

        assertEquals("image", blocks.getJSONObject(1).getString("type"))
        assertEquals("image/png", blocks.getJSONObject(1).getJSONObject("source").getString("media_type"))

        val parsed = AnthropicWire.parseResponse(
            """
            {"stop_reason":"tool_use","content":[{"type":"text","text":"查一下"},
            {"type":"tool_use","id":"tu1","name":"market.quote","input":{"query":"黄金"}}],
            "usage":{"input_tokens":80,"output_tokens":20}}
            """.trimIndent()
        )
        assertEquals("查一下", parsed.message.content)
        assertEquals("tu1", parsed.message.toolCalls.single().id)
        assertEquals("{\"query\":\"黄金\"}", parsed.message.toolCalls.single().argumentsJson)
        assertEquals(80, parsed.usage.promptTokens)
        assertEquals(20, parsed.usage.completionTokens)
    }

    @Test
    fun fetchAllowlistOnlyAcceptsPublicHttpsHosts() {
        assertTrue(FetchAllowlist.isAllowed("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=1.000300"))
        assertTrue(FetchAllowlist.isAllowed("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=x"))
        assertTrue(FetchAllowlist.isAllowed("https://www.sge.com.cn/graph/Dailyhq?instid=Au99.99"))
        assertTrue(FetchAllowlist.isAllowed("https://query1.finance.yahoo.com/v8/finance/chart/^N225"))
        assertFalse(FetchAllowlist.isAllowed("http://push2.eastmoney.com/api"))
        assertFalse(FetchAllowlist.isAllowed("https://evil.example.com/steal"))
        assertFalse(FetchAllowlist.isAllowed("https://127.0.0.1:8080/admin"))
        assertFalse(FetchAllowlist.isAllowed("javascript:alert(1)"))
        assertFalse(FetchAllowlist.isAllowed("file:///data/data/cn.market.assistant/databases"))
        assertFalse(FetchAllowlist.isAllowed("https://push2.eastmoney.com.evil.com/api"))
    }

    private fun image() = ChatAttachment(
        fileName = "chart.png",
        mimeType = "image/png",
        base64Image = "iVBORw0KGgoAAAANSUhEUg=="
    )
}
