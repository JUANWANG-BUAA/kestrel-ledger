package cn.market.assistant.domain.assistant

import java.math.BigDecimal

/** Facts are explicit, sourced and timestamped before they may enter an assistant request. */
enum class FactSensitivity { PUBLIC_MARKET, AGGREGATE_PORTFOLIO, PRIVATE_DETAIL }

data class AssistantFact(
    val key: String,
    val value: String,
    val unit: String? = null,
    val source: String? = null,
    val observedAt: String? = null,
    val sensitivity: FactSensitivity = FactSensitivity.PUBLIC_MARKET
)

data class AssistantRequest(
    val conversationId: String,
    val question: String,
    val facts: List<AssistantFact>,
    val allowedToolIds: Set<String> = emptySet(),
    val answerLimitCharacters: Int = 1_000,
    val dataSharingConsent: AssistantDataSharingConsent = AssistantDataSharingConsent.PUBLIC_AND_AGGREGATE
)

enum class AssistantDataSharingConsent { PUBLIC_AND_AGGREGATE, INCLUDE_PRIVATE_DETAILS }

data class AssistantCitation(
    val factKey: String,
    val source: String?,
    val observedAt: String?,
    val value: String? = null
)

data class AssistantReply(
    val text: String,
    val citations: List<AssistantCitation> = emptyList(),
    val usedToolIds: List<String> = emptyList()
)

data class AgentToolRequest(val arguments: Map<String, String>)

data class AgentToolResult(
    val facts: List<AssistantFact>,
    val errorCode: String? = null,
    /** A local change request for the user to review; tools never apply it themselves. */
    val actionProposal: AssistantActionProposal? = null
)

enum class AssistantActionKind {
    ADD_FUND_WATCHLIST,
    REMOVE_FUND_WATCHLIST,
    RECORD_FUND_BUY,
    RECORD_FUND_SELL,
    UPDATE_RISK_PARAMETERS,
    SAVE_PREFERENCE
}

/** A typed preview of a local change. The app applies it only after an explicit user confirmation. */
data class AssistantActionProposal(
    val id: String,
    val kind: AssistantActionKind,
    val summary: String,
    val fundCode: String? = null,
    val fundName: String? = null,
    val fundCategory: String = "公募基金",
    val amount: BigDecimal? = null,
    val units: BigDecimal? = null,
    val unitPrice: BigDecimal? = null,
    val fee: BigDecimal? = null,
    val riskFreeAnnualRate: BigDecimal? = null,
    val minimumAcceptableAnnualReturn: BigDecimal? = null,
    val preference: String? = null
)

data class AgentToolParameter(
    val name: String,
    val description: String,
    val required: Boolean = false
)

data class AgentToolDescriptor(
    val id: String,
    val description: String,
    val parameters: List<AgentToolParameter> = emptyList()
)

/** Tool access is granted per turn; private facts require a separate explicit opt-in. */
data class AgentToolAuthorization(
    val allowedToolIds: Set<String> = emptySet(),
    val sharePrivateDetails: Boolean = false
)

/** A tool returns typed facts or a proposed local change; it cannot directly alter data. */
interface AgentTool {
    val id: String
    val description: String get() = id
    val parameters: List<AgentToolParameter> get() = emptyList()
    suspend fun execute(request: AgentToolRequest): AgentToolResult
}

/** Validates tool calls and enforces per-turn allowlists and fact privacy at one boundary. */
class AgentToolRegistry(tools: Collection<AgentTool>) {
    private val toolsById: Map<String, AgentTool>

    init {
        require(tools.all { TOOL_ID.matches(it.id) }) { "Agent tool IDs must be stable lowercase identifiers" }
        require(tools.map { it.id }.distinct().size == tools.size) { "Agent tool IDs must be unique" }
        require(tools.all { tool ->
            tool.parameters.all { PARAMETER_NAME.matches(it.name) && it.description.isNotBlank() } &&
                tool.parameters.map { it.name }.distinct().size == tool.parameters.size
        }) { "Agent tool parameters must have unique names and descriptions" }
        toolsById = tools.associateBy { it.id }
    }

    /** Every registered tool id, used by the settings surface to build the allowlist UI. */
    val toolIds: Set<String> get() = toolsById.keys

    fun descriptors(allowedToolIds: Set<String>): List<AgentToolDescriptor> = allowedToolIds
        .mapNotNull(toolsById::get)
        .sortedBy { it.id }
        .map { AgentToolDescriptor(it.id, it.description, it.parameters) }

    suspend fun execute(
        toolId: String,
        request: AgentToolRequest,
        authorization: AgentToolAuthorization
    ): AgentToolResult {
        if (toolId !in authorization.allowedToolIds) return AgentToolResult(emptyList(), TOOL_NOT_ALLOWED)
        val tool = toolsById[toolId] ?: return AgentToolResult(emptyList(), TOOL_NOT_FOUND)
        val declaredParameters = tool.parameters.associateBy { it.name }
        if (request.arguments.keys.any { it !in declaredParameters } || declaredParameters.values.any { parameter ->
                parameter.required && request.arguments[parameter.name].isNullOrBlank()
            }) {
            return AgentToolResult(emptyList(), INVALID_TOOL_ARGUMENTS)
        }
        val result = try {
            tool.execute(request)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentToolResult(emptyList(), TOOL_FAILED)
        }
        if (result.errorCode != null) return result.copy(facts = emptyList())
        if (result.facts.size > MAX_FACTS_PER_TOOL || result.facts.any {
                it.key.isBlank() || it.value.isBlank() || it.source.isNullOrBlank() || it.observedAt.isNullOrBlank()
            }) {
            return AgentToolResult(emptyList(), INVALID_TOOL_RESULT)
        }
        val permittedFacts = if (authorization.sharePrivateDetails) result.facts else {
            result.facts.filter { it.sensitivity != FactSensitivity.PRIVATE_DETAIL }
        }
        return result.copy(facts = permittedFacts)
    }

    private companion object {
        val TOOL_ID = Regex("[a-z][a-z0-9_.-]{0,63}")
        val PARAMETER_NAME = Regex("[a-z][a-zA-Z0-9_]{0,63}")
        const val MAX_FACTS_PER_TOOL = 100
        const val TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED"
        const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
        const val TOOL_FAILED = "TOOL_FAILED"
        const val INVALID_TOOL_ARGUMENTS = "INVALID_TOOL_ARGUMENTS"
        const val INVALID_TOOL_RESULT = "INVALID_TOOL_RESULT"
    }
}

/** Provider-neutral boundary for a future local or user-configured AI provider. */
interface AssistantAgent {
    suspend fun respond(request: AssistantRequest): AssistantReply
}

/** Decorator to make private fact filtering the default at the provider boundary. */
class PrivacyGuardedAssistantAgent(private val delegate: AssistantAgent) : AssistantAgent {
    override suspend fun respond(request: AssistantRequest): AssistantReply {
        val safeRequest = when (request.dataSharingConsent) {
            AssistantDataSharingConsent.PUBLIC_AND_AGGREGATE -> request.withoutPrivateDetails()
            AssistantDataSharingConsent.INCLUDE_PRIVATE_DETAILS -> request
        }
        return delegate.respond(safeRequest)
    }
}

/** The caller must opt in before private position-level facts are sent to an agent. */
fun AssistantRequest.withoutPrivateDetails(): AssistantRequest = copy(
    facts = facts.filter { it.sensitivity != FactSensitivity.PRIVATE_DETAIL }
)
