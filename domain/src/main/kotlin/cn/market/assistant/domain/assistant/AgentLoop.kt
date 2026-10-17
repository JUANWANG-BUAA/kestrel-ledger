package cn.market.assistant.domain.assistant

import java.util.concurrent.CancellationException

/** Live progress events so the UI can render tool steps while the turn is still running. */
sealed interface AgentEvent {
    data class ModelCallStarted(val round: Int) : AgentEvent
    data class ToolCallStarted(val toolId: String, val argumentSummary: String) : AgentEvent
    data class ToolCallFinished(val toolId: String, val succeeded: Boolean, val factCount: Int) : AgentEvent
    data class Answer(val text: String, val toolCallIds: List<String>) : AgentEvent
    data class ChartReady(val chart: AssistantChart) : AgentEvent
    data class Failed(val reason: String) : AgentEvent
}

data class AgentRunResult(
    val reply: AssistantReply,
    val rounds: Int,
    val toolCallIds: List<String>,
    val usage: ChatUsage,
    /** Non-null when the live model failed; the app then falls back to the offline fact engine. */
    val failureReason: String? = null,
    /** The UI must obtain confirmation before applying this proposed local change. */
    val pendingAction: AssistantActionProposal? = null,
    /** Charts a tool produced this turn, rendered inline under the answer. */
    val charts: List<AssistantChart> = emptyList()
) {
    val usedLiveModel: Boolean get() = failureReason == null
}

/**
 * Multi-turn tool-calling loop over one user-configured chat model.
 *
 * Safety boundaries: the advice gate runs before any provider call, tools execute through
 * [AgentToolRegistry] with a per-turn allowlist, private facts require an explicit opt-in, tool calls
 * per round and total rounds are capped, and every failure returns a reason instead of throwing so the
 * app can fall back to the offline engine.
 */
class AgentLoop(
    private val model: ChatModel,
    private val registry: AgentToolRegistry,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun run(
        question: String,
        history: List<ChatMessage>,
        attachments: List<ChatAttachment>,
        authorization: AgentToolAuthorization,
        settings: LlmSettings,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): AgentRunResult {
        if (AssistantEngine.findAdviceRequest(question) != null) {
            return AgentRunResult(
                reply = AssistantReply(AssistantEngine.ADVICE_REFUSAL),
                rounds = 0,
                toolCallIds = emptyList(),
                usage = ChatUsage()
            )
        }
        val toolDefinitions = if (settings.toolsEnabled) {
            val usedFunctionNames = mutableSetOf<String>()
            registry.descriptors(authorization.allowedToolIds).map { descriptor ->
                val baseName = descriptor.id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
                var functionName = baseName
                var suffix = 2
                while (!usedFunctionNames.add(functionName)) {
                    val suffixText = "_$suffix"
                    functionName = baseName.take(64 - suffixText.length) + suffixText
                    suffix++
                }
                descriptor.toChatToolDefinition(functionName)
            }
        } else {
            emptyList()
        }
        val localToolIdsByFunctionName = toolDefinitions.associate { it.name to it.localToolId }
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage(ChatRole.SYSTEM, settings.systemPrompt)
        messages += history
        messages += ChatMessage(ChatRole.USER, question, attachments = attachments)

        val collectedFacts = mutableListOf<AssistantFact>()
        val collectedCharts = mutableListOf<AssistantChart>()
        val usedToolIds = mutableListOf<String>()
        var usage = ChatUsage()
        var rounds = 0
        while (rounds < maxToolRounds) {
            rounds++
            onEvent(AgentEvent.ModelCallStarted(rounds))
            val response = try {
                model.complete(
                    ChatRequest(
                        messages = messages.toList(),
                        tools = toolDefinitions,
                        temperature = settings.temperature,
                        maxTokens = settings.maxTokens,
                        provider = settings.provider,
                        reasoningEffort = settings.reasoningEffort,
                        capabilities = settings.modelCapabilities
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onEvent(AgentEvent.Failed(error.message ?: "模型调用失败"))
                return AgentRunResult(
                    reply = AssistantReply(error.message ?: "模型调用失败"),
                    rounds = rounds,
                    toolCallIds = usedToolIds,
                    usage = usage,
                    failureReason = "模型调用失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
            usage = ChatUsage(
                promptTokens = usage.promptTokens + response.usage.promptTokens,
                completionTokens = usage.completionTokens + response.usage.completionTokens,
                reasoningTokens = listOfNotNull(usage.reasoningTokens, response.usage.reasoningTokens)
                    .takeIf { it.isNotEmpty() }?.sum()
            )
            val toolCalls = response.message.toolCalls
            if (toolCalls.isEmpty()) {
                val text = response.message.content.trim()
                if (text.isEmpty()) {
                    onEvent(AgentEvent.Failed("模型没有返回内容"))
                    return failure("模型没有返回内容", rounds, usedToolIds, usage)
                }
                onEvent(AgentEvent.Answer(text, usedToolIds.distinct()))
                return AgentRunResult(
                    reply = AssistantReply(
                        text = text,
                        citations = collectedFacts.map { AssistantCitation(it.key, it.source, it.observedAt, it.value) }
                            .distinctBy { it.factKey },
                        usedToolIds = usedToolIds.distinct()
                    ),
                    rounds = rounds,
                    toolCallIds = usedToolIds.distinct(),
                    usage = usage,
                    charts = collectedCharts.toList()
                )
            }
            messages += ChatMessage(
                role = ChatRole.ASSISTANT,
                content = response.message.content,
                toolCalls = toolCalls,
                reasoningContent = response.message.reasoningContent
            )
            toolCalls.take(MAX_TOOL_CALLS_PER_ROUND).forEach { call ->
                val localToolId = localToolIdsByFunctionName[call.name] ?: call.name
                val arguments = parseStringArguments(call.argumentsJson)
                onEvent(
                    AgentEvent.ToolCallStarted(
                        localToolId,
                        arguments.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    )
                )
                val result = registry.execute(localToolId, AgentToolRequest(arguments), authorization)
                onEvent(AgentEvent.ToolCallFinished(localToolId, result.errorCode == null, result.facts.size))
                result.actionProposal?.let { proposal ->
                    if (localToolId !in usedToolIds) usedToolIds += localToolId
                    return AgentRunResult(
                        reply = AssistantReply(
                            text = "我整理好了这项本地更改，请核对内容后确认。",
                            usedToolIds = usedToolIds.distinct()
                        ),
                        rounds = rounds,
                        toolCallIds = usedToolIds.distinct(),
                        usage = usage,
                        pendingAction = proposal
                    )
                }
                if (result.errorCode == null && (result.facts.isNotEmpty() || result.charts.isNotEmpty())) {
                    collectedFacts += result.facts
                    result.charts.forEach { chart ->
                        if (collectedCharts.size < 4) {
                            collectedCharts += chart
                            onEvent(AgentEvent.ChartReady(chart))
                        }
                    }
                    if (localToolId !in usedToolIds) usedToolIds += localToolId
                    val chartNote = result.charts.takeIf { it.isNotEmpty() }
                        ?.joinToString("；") { "已生成图表「${it.title}」（${it.series.size} 条序列）" }
                        .orEmpty()
                    messages += ChatMessage(
                        ChatRole.TOOL,
                        listOfNotNull(result.facts.toToolResultText().takeIf { result.facts.isNotEmpty() }, chartNote.takeIf { it.isNotBlank() })
                            .joinToString("\n"),
                        toolCallId = call.id
                    )
                } else {
                    val reason = result.errorCode ?: "EMPTY_RESULT"
                    messages += ChatMessage(
                        ChatRole.TOOL,
                        "工具 $localToolId 返回错误：$reason。请改用其他依据或在回答中说明该数据暂不可得。",
                        toolCallId = call.id
                    )
                }
            }
            if (toolCalls.size > MAX_TOOL_CALLS_PER_ROUND) {
                messages += ChatMessage(
                    ChatRole.TOOL,
                    "本轮工具调用超过上限，仅执行了前 $MAX_TOOL_CALLS_PER_ROUND 个。",
                    toolCallId = toolCalls.first().id
                )
            }
        }
        onEvent(AgentEvent.Failed("工具调用超过 $maxToolRounds 轮仍未给出结论"))
        return failure("工具调用超过 $maxToolRounds 轮仍未给出结论", rounds, usedToolIds, usage)
    }

    private fun failure(reason: String, rounds: Int, toolIds: List<String>, usage: ChatUsage) = AgentRunResult(
        reply = AssistantReply(reason),
        rounds = rounds,
        toolCallIds = toolIds.distinct(),
        usage = usage,
        failureReason = reason
    )

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 4
        const val MAX_TOOL_CALLS_PER_ROUND = 2
    }
}

/**
 * Minimal parser for flat JSON objects whose values are strings, numbers or booleans. Tool arguments
 * are declared as strings by [AgentToolParameter], so the assistant never needs a full JSON parser.
 */
fun parseStringArguments(json: String): Map<String, String> {
    val trimmed = json.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return emptyMap()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
    val result = linkedMapOf<String, String>()
    var index = 1
    val end = trimmed.length - 1
    while (index < end) {
        while (index < end && (trimmed[index].isWhitespace() || trimmed[index] == ',')) index++
        if (index >= end || trimmed[index] != '"') break
        val key = readJsonString(trimmed, index) ?: break
        index = key.second
        while (index < end && (trimmed[index].isWhitespace() || trimmed[index] == ':')) index++
        if (index >= end) break
        val value: String
        if (trimmed[index] == '"') {
            val parsed = readJsonString(trimmed, index) ?: break
            value = parsed.first
            index = parsed.second
        } else {
            val start = index
            var depth = 0
            while (index < end && (depth > 0 || (trimmed[index] != ',' && trimmed[index] != '}'))) {
                when (trimmed[index]) {
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                }
                index++
            }
            value = trimmed.substring(start, index).trim().trim('"')
        }
        result[key.first] = value
        while (index < end && trimmed[index].isWhitespace()) index++
    }
    return result
}

private fun readJsonString(text: String, start: Int): Pair<String, Int>? {
    if (start >= text.length || text[start] != '"') return null
    val value = StringBuilder()
    var index = start + 1
    while (index < text.length) {
        when (val char = text[index]) {
            '"' -> return value.toString() to index + 1
            '\\' -> {
                if (index + 1 >= text.length) return null
                when (val escaped = text[index + 1]) {
                    '"', '\\', '/' -> value.append(escaped)
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000C')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'u' -> {
                        if (index + 5 >= text.length) return null
                        val code = text.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                        value.append(code.toChar())
                        index += 4
                    }
                    else -> value.append(escaped)
                }
                index += 2
            }
            else -> {
                value.append(char)
                index += 1
            }
        }
    }
    return null
}
