package cn.market.assistant.domain.assistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private fun fact(key: String, value: String, sensitivity: FactSensitivity) = AssistantFact(
        key = key,
        value = value,
        unit = null,
        source = "test",
        observedAt = "2026-10-16T08:00:00Z",
        sensitivity = sensitivity
    )

    private class FakeTool(
        override val id: String,
        private val facts: List<AssistantFact>,
        private val errorCode: String? = null
    ) : AgentTool {
        override val description = "fake tool $id"
        override val parameters = listOf(AgentToolParameter("query", "query text", required = true))

        override suspend fun execute(request: AgentToolRequest): AgentToolResult =
            AgentToolResult(facts, errorCode)
    }

    private class FakeModel(
        private val responses: List<ChatResponse>,
        private val failWith: String? = null
    ) : ChatModel {
        override val displayName = "fake-model"
        var calls = 0
        var lastRequest: ChatRequest? = null

        override suspend fun complete(request: ChatRequest): ChatResponse {
            calls++
            lastRequest = request
            failWith?.let { throw ChatModelException(it) }
            return responses.getOrElse(calls - 1) { responses.last() }
        }
    }

    private val registry = AgentToolRegistry(
        listOf(
            FakeTool("market.quote", listOf(fact("market.quote.price", "5.12", FactSensitivity.PUBLIC_MARKET))),
            FakeTool("portfolio.positions", listOf(fact("portfolio.position.a", "1000", FactSensitivity.PRIVATE_DETAIL))),
            FakeTool("broken.tool", emptyList(), errorCode = "TOOL_FAILED")
        )
    )
    private val authorization = AgentToolAuthorization(
        allowedToolIds = setOf("market.quote", "portfolio.positions", "broken.tool"),
        sharePrivateDetails = false
    )
    private val settings = LlmSettings(toolsEnabled = true)

    @Test
    fun answersDirectlyWithoutCallingAnyTool() = runTest {
        val model = FakeModel(listOf(ChatResponse(ChatMessage(ChatRole.ASSISTANT, "直接回答"))))

        val result = AgentLoop(model, registry).run("你好", emptyList(), emptyList(), authorization, settings)

        assertNull(result.failureReason)
        assertEquals("直接回答", result.reply.text)
        assertTrue(result.reply.citations.isEmpty())
        assertEquals(1, model.calls)
        assertEquals(3, model.lastRequest!!.tools.size)
    }

    @Test
    fun executesToolAndFeedsFactsBackBeforeTheFinalAnswer() = runTest {
        val model = FakeModel(
            listOf(
                ChatResponse(
                    ChatMessage(
                        ChatRole.ASSISTANT,
                        "",
                        toolCalls = listOf(ChatToolCall("call-1", "market.quote", "{\"query\":\"沪深300\"}"))
                    )
                ),
                ChatResponse(ChatMessage(ChatRole.ASSISTANT, "沪深300 现价 5.12"))
            )
        )
        val events = mutableListOf<AgentEvent>()

        val result = AgentLoop(model, registry).run(
            "沪深300 多少钱", emptyList(), emptyList(), authorization, settings
        ) { events += it }

        assertNull(result.failureReason)
        assertEquals("沪深300 现价 5.12", result.reply.text)
        assertEquals(listOf("market.quote"), result.reply.usedToolIds)
        assertEquals("market.quote.price", result.reply.citations.single().factKey)
        assertEquals(2, result.rounds)
        assertTrue(events.any { it is AgentEvent.ToolCallStarted && it.toolId == "market.quote" })
        assertTrue(events.any { it is AgentEvent.ToolCallFinished && it.succeeded })
        assertTrue(events.any { it is AgentEvent.Answer })
    }

    @Test
    fun adviceRequestsAreRefusedBeforeAnyProviderCall() = runTest {
        val model = FakeModel(listOf(ChatResponse(ChatMessage(ChatRole.ASSISTANT, "买入"))))

        val result = AgentLoop(model, registry).run(
            "现在该买入哪只基金", emptyList(), emptyList(), authorization, settings
        )

        assertEquals(0, model.calls)
        assertEquals(AssistantEngine.ADVICE_REFUSAL, result.reply.text)
        assertNull(result.failureReason)
    }

    @Test
    fun toolFailuresAreReportedBackWithoutCrashingTheTurn() = runTest {
        val model = FakeModel(
            listOf(
                ChatResponse(
                    ChatMessage(
                        ChatRole.ASSISTANT,
                        "",
                        toolCalls = listOf(ChatToolCall("call-1", "broken.tool", "{}"))
                    )
                ),
                ChatResponse(ChatMessage(ChatRole.ASSISTANT, "该数据暂不可得"))
            )
        )
        val events = mutableListOf<AgentEvent>()

        val result = AgentLoop(model, registry).run("查一下", emptyList(), emptyList(), authorization, settings) { events += it }

        assertNull(result.failureReason)
        assertTrue(events.any { it is AgentEvent.ToolCallFinished && !it.succeeded })
    }

    @Test
    fun privateFactsAreFilteredByTheRegistryBeforeReachingTheModel() = runTest {
        val model = FakeModel(
            listOf(
                ChatResponse(
                    ChatMessage(
                        ChatRole.ASSISTANT,
                        "",
                        toolCalls = listOf(ChatToolCall("call-1", "portfolio.positions", "{}"))
                    )
                ),
                ChatResponse(ChatMessage(ChatRole.ASSISTANT, "没有授权明细"))
            )
        )

        val result = AgentLoop(model, registry).run("我的持仓", emptyList(), emptyList(), authorization, settings)

        assertTrue(result.reply.citations.isEmpty())
        val toolMessage = model.lastRequest!!.messages.first { it.role == ChatRole.TOOL }
        assertFalse(toolMessage.content.contains("portfolio.position.a"))
    }

    @Test
    fun modelFailureReturnsAReasonInsteadOfThrowing() = runTest {
        val result = AgentLoop(FakeModel(emptyList(), failWith = "HTTP 401"), registry)
            .run("你好", emptyList(), emptyList(), authorization, settings)

        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("401"))
        val events = mutableListOf<AgentEvent>()
        AgentLoop(FakeModel(emptyList(), failWith = "超时"), registry)
            .run("你好", emptyList(), emptyList(), authorization, settings) { events += it }
        assertTrue(events.any { it is AgentEvent.Failed })
    }

    @Test
    fun endlessToolCallingStopsAtTheRoundLimit() = runTest {
        val model = FakeModel(
            List(6) {
                ChatResponse(
                    ChatMessage(
                        ChatRole.ASSISTANT,
                        "",
                        toolCalls = listOf(ChatToolCall("call-$it", "market.quote", "{\"query\":\"x\"}"))
                    )
                )
            }
        )

        val result = AgentLoop(model, registry, maxToolRounds = 3)
            .run("查", emptyList(), emptyList(), authorization, settings)

        assertNotNull(result.failureReason)
        assertEquals(3, result.rounds)
        assertTrue(result.failureReason!!.contains("3"))
    }

    @Test
    fun toolArgumentsParseStringsNumbersBooleansAndEscapes() {
        assertEquals(
            mapOf("query" to "沪深300", "limit" to "5", "strict" to "true"),
            parseStringArguments("{\"query\":\"沪深300\",\"limit\":5,\"strict\":true}")
        )
        assertEquals(mapOf("path" to "a\"b\nc"), parseStringArguments("{\"path\":\"a\\\"b\\nc\"}"))
        assertEquals(emptyMap<String, String>(), parseStringArguments("not json"))
        assertEquals(emptyMap<String, String>(), parseStringArguments("{}"))
    }
}
