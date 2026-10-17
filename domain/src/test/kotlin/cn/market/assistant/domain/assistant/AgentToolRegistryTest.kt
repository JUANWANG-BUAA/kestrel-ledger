package cn.market.assistant.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class AgentToolRegistryTest {
    private val publicFact = AssistantFact("market.gold.usd_oz", "2300", "USD/oz", "fixture", "2026-10-16T12:00:00Z")
    private val privateFact = AssistantFact(
        "portfolio.asset_count", "3", "assets", "local-ledger", "2026-10-16T12:00:00Z",
        FactSensitivity.PRIVATE_DETAIL
    )

    @Test
    fun privateFactsAreRemovedUnlessTheTurnExplicitlySharesThem() {
        val registry = AgentToolRegistry(listOf(FixtureTool("portfolio.summary", listOf(publicFact, privateFact))))

        val defaultResult = runSuspend {
            registry.execute("portfolio.summary", AgentToolRequest(emptyMap()), AgentToolAuthorization(setOf("portfolio.summary")))
        }
        val sharedResult = runSuspend {
            registry.execute(
                "portfolio.summary", AgentToolRequest(emptyMap()),
                AgentToolAuthorization(setOf("portfolio.summary"), sharePrivateDetails = true)
            )
        }

        assertEquals(listOf(publicFact), defaultResult.facts)
        assertEquals(listOf(publicFact, privateFact), sharedResult.facts)
    }

    @Test
    fun toolMustBeAllowlistedBeforeItCanExecute() {
        var executions = 0
        val registry = AgentToolRegistry(listOf(FixtureTool("portfolio.summary", listOf(publicFact)) { executions++ }))

        val result = runSuspend {
            registry.execute("portfolio.summary", AgentToolRequest(emptyMap()), AgentToolAuthorization())
        }

        assertEquals("TOOL_NOT_ALLOWED", result.errorCode)
        assertTrue(result.facts.isEmpty())
        assertEquals(0, executions)
    }

    @Test
    fun toolExceptionsDoNotLeakDetailsOrPartialFacts() {
        val registry = AgentToolRegistry(listOf(object : AgentTool {
            override val id = "market.quote"
            override suspend fun execute(request: AgentToolRequest): AgentToolResult {
                throw IllegalStateException("sensitive implementation detail")
            }
        }))

        val result = runSuspend {
            registry.execute("market.quote", AgentToolRequest(emptyMap()), AgentToolAuthorization(setOf("market.quote")))
        }

        assertEquals("TOOL_FAILED", result.errorCode)
        assertTrue(result.facts.isEmpty())
    }

    @Test
    fun factsWithoutProvenanceAreRejectedAsAWhole() {
        val registry = AgentToolRegistry(listOf(FixtureTool("market.quote", listOf(publicFact, AssistantFact("price", "12.3")))))

        val result = runSuspend {
            registry.execute("market.quote", AgentToolRequest(emptyMap()), AgentToolAuthorization(setOf("market.quote")))
        }

        assertEquals("INVALID_TOOL_RESULT", result.errorCode)
        assertTrue(result.facts.isEmpty())
    }

    @Test
    fun descriptorsOnlyExposeAllowedKnownToolsInStableOrder() {
        val registry = AgentToolRegistry(listOf(FixtureTool("market.quote", emptyList()), FixtureTool("portfolio.summary", emptyList())))

        val descriptors = registry.descriptors(setOf("portfolio.summary", "unknown", "market.quote"))

        assertEquals(listOf("market.quote", "portfolio.summary"), descriptors.map { it.id })
    }

    @Test
    fun requiredAndUnknownArgumentsAreRejectedBeforeExecution() {
        var executions = 0
        val tool = object : AgentTool {
            override val id = "market.quote"
            override val parameters = listOf(AgentToolParameter("symbol", "Instrument identifier", required = true))
            override suspend fun execute(request: AgentToolRequest): AgentToolResult {
                executions++
                return AgentToolResult(listOf(publicFact))
            }
        }
        val registry = AgentToolRegistry(listOf(tool))
        val authorization = AgentToolAuthorization(setOf(tool.id))

        val missingRequired = runSuspend { registry.execute(tool.id, AgentToolRequest(emptyMap()), authorization) }
        val undeclared = runSuspend {
            registry.execute(tool.id, AgentToolRequest(mapOf("symbol" to "XAU/USD", "account" to "private")), authorization)
        }

        assertEquals("INVALID_TOOL_ARGUMENTS", missingRequired.errorCode)
        assertEquals("INVALID_TOOL_ARGUMENTS", undeclared.errorCode)
        assertEquals(0, executions)
    }

    @Test
    fun duplicateOrMalformedToolIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolRegistry(listOf(FixtureTool("market.quote", emptyList()), FixtureTool("market.quote", emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) { AgentToolRegistry(listOf(FixtureTool("MarketQuote", emptyList()))) }
    }

    private class FixtureTool(
        override val id: String,
        private val resultFacts: List<AssistantFact>,
        private val errorCode: String? = null,
        private val onExecute: () -> Unit = {}
    ) : AgentTool {
        override val description = "Fixture read-only tool"

        override suspend fun execute(request: AgentToolRequest): AgentToolResult {
            onExecute()
            return AgentToolResult(resultFacts, errorCode)
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<T>) { result = outcome }
        })
        assertFalse("Fixture suspend call unexpectedly suspended", result == null)
        return result!!.getOrThrow()
    }

    @Test
    fun keepsOnlyTheSafeProposalErrorFactWhenAToolFails() {
        val publicError = AssistantFact("proposal.error", "金额与份额不一致", "text", "本机提议校验", "2026-10-16T12:00:00Z")
        val secret = AssistantFact("portfolio.secret", "must not leak", "text", "local-ledger", "2026-10-16T12:00:00Z", FactSensitivity.PRIVATE_DETAIL)
        val registry = AgentToolRegistry(
            listOf(FixtureTool("portfolio.propose_change", listOf(publicError, secret), errorCode = "INVALID_CHANGE_PROPOSAL"))
        )
        val result = runSuspend {
            registry.execute(
                "portfolio.propose_change", AgentToolRequest(emptyMap()),
                AgentToolAuthorization(setOf("portfolio.propose_change"))
            )
        }
        assertEquals("INVALID_CHANGE_PROPOSAL", result.errorCode)
        assertEquals(listOf(publicError), result.facts)
    }
}
