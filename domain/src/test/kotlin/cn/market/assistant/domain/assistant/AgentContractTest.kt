package cn.market.assistant.domain.assistant

import cn.market.assistant.domain.portfolio.AccountCashBalance
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class AgentContractTest {
    @Test
    fun requestDropsPrivateDetailsByDefault() {
        val request = AssistantRequest(
            conversationId = "local-preview",
            question = "总结行情",
            facts = listOf(
                AssistantFact("semi", "上涨 1%"),
                AssistantFact("portfolio_value", "10000", "CNY", sensitivity = FactSensitivity.PRIVATE_DETAIL)
            )
        )

        assertEquals(listOf("semi"), request.withoutPrivateDetails().facts.map { it.key })
    }

    @Test
    fun cashFactsCarrySourceAndStayPrivateByDefault() {
        val facts = AccountCashFactMapper.facts(
            AccountCashBalance(BigDecimal("1234.50"), LocalDate.parse("2026-10-16")),
            observedAt = "2026-10-16T01:00:00Z"
        )
        assertEquals("CNY", facts.first().unit)
        assertEquals("本地现金账本", facts.first().source)
        assertEquals("2026-10-16T01:00:00Z", facts.first().observedAt)
        assertEquals(emptyList<String>(), AssistantRequest("c", "summary", facts).withoutPrivateDetails().facts.map { it.key })
    }

    @Test
    fun privacyGuardFiltersProviderRequestsUnlessExplicitlyConsented() {
        val privateFact = AssistantFact("holding.units", "12", sensitivity = FactSensitivity.PRIVATE_DETAIL)
        var received: AssistantRequest? = null
        val guarded = PrivacyGuardedAssistantAgent(object : AssistantAgent {
            override suspend fun respond(request: AssistantRequest): AssistantReply {
                received = request
                return AssistantReply("ok")
            }
        })
        val baseRequest = AssistantRequest("c", "analyze", listOf(privateFact))

        runSuspend { guarded.respond(baseRequest) }
        assertEquals(emptyList<String>(), received?.facts?.map { it.key })
        runSuspend {
            guarded.respond(baseRequest.copy(dataSharingConsent = AssistantDataSharingConsent.INCLUDE_PRIVATE_DETAILS))
        }
        assertEquals(listOf(privateFact.key), received?.facts?.map { it.key })
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<T>) { result = outcome }
        })
        return checkNotNull(result).getOrThrow()
    }
}
