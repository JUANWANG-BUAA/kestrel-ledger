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
        val requests = mutableListOf<ChatRequest>()

        override suspend fun complete(request: ChatRequest): ChatResponse {
            calls++
            lastRequest = request
            requests += request
            failWith?.let { throw ChatModelException(it) }
            return responses.getOrElse(calls - 1) { responses.last() }
        }
    }

    private class ProposalTool(private val proposal: AssistantActionProposal) : AgentTool {
        override val id = "portfolio.propose_change"
        override val description = "propose a local change"
        override suspend fun execute(request: AgentToolRequest): AgentToolResult =
            AgentToolResult(emptyList(), actionProposal = proposal)
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
                        toolCalls = listOf(ChatToolCall("call-1", "market_quote", "{\"query\":\"沪深300\"}"))
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
        assertEquals(
            "market_quote",
            model.requests.first().tools.first { it.localToolId == "market.quote" }.name
        )
        assertEquals(
            "market_quote",
            model.requests[1].messages.first { it.role == ChatRole.ASSISTANT }.toolCalls.single().name
        )
        assertTrue(events.any { it is AgentEvent.ToolCallStarted && it.toolId == "market.quote" })
        assertTrue(events.any { it is AgentEvent.ToolCallFinished && it.succeeded })
        assertTrue(events.any { it is AgentEvent.Answer })
    }

    @Test
    fun providerSafeToolNamesStayUniqueAndMapBackToLocalIds() = runTest {
        val localRegistry = AgentToolRegistry(
            listOf(
                FakeTool(
                    "price.search",
                    listOf(fact("price.search.result", "first", FactSensitivity.PUBLIC_MARKET))
                ),
                FakeTool(
                    "price_search",
                    listOf(fact("price_search.result", "second", FactSensitivity.PUBLIC_MARKET))
                )
            )
        )
        val model = FakeModel(
            listOf(
                ChatResponse(
                    ChatMessage(
                        ChatRole.ASSISTANT,
                        "",
                        toolCalls = listOf(ChatToolCall("call-1", "price_search_2", "{\"query\":\"gold\"}"))
                    )
                ),
                ChatResponse(ChatMessage(ChatRole.ASSISTANT, "已查询"))
            )
        )

        val result = AgentLoop(model, localRegistry).run(
            "查询报价",
            emptyList(),
            emptyList(),
            AgentToolAuthorization(setOf("price.search", "price_search")),
            settings
        )

        val providerNames = model.requests.first().tools.map { it.name }
        assertEquals(listOf("price_search", "price_search_2"), providerNames)
        assertTrue(providerNames.all { it.matches(Regex("^[A-Za-z0-9_-]+$")) })
        assertEquals(listOf("price_search"), result.toolCallIds)
        assertEquals("price_search.result", result.reply.citations.single().factKey)
    }

    @Test
    fun localChangeToolReturnsAProposalWithoutExecutingItOrCallingTheModelAgain() = runTest {
        val proposal = AssistantActionProposal(
            id = "proposal-1",
            kind = AssistantActionKind.ADD_FUND_WATCHLIST,
            summary = "添加基金到自选",
            fundCode = "000001",
            fundName = "测试基金"
        )
        val model = FakeModel(
            listOf(
                ChatResponse(
                    ChatMessage(
                        ChatRole.ASSISTANT,
                        "",
                        toolCalls = listOf(ChatToolCall("call-1", "portfolio_propose_change", "{}"))
                    )
                )
            )
        )
        val actionRegistry = AgentToolRegistry(listOf(ProposalTool(proposal)))

        val result = AgentLoop(model, actionRegistry).run(
            "帮我加入自选", emptyList(), emptyList(),
            AgentToolAuthorization(allowedToolIds = setOf("portfolio.propose_change")), settings
        )

        assertEquals(proposal, result.pendingAction)
        assertEquals("portfolio.propose_change", result.toolCallIds.single())
        assertEquals(1, model.calls)
        assertTrue(result.reply.text.contains("核对"))
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
                        toolCalls = listOf(ChatToolCall("call-1", "broken_tool", "{}"))
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
                        toolCalls = listOf(ChatToolCall("call-1", "portfolio_positions", "{}"))
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
                        toolCalls = listOf(ChatToolCall("call-$it", "market_quote", "{\"query\":\"x\"}"))
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
