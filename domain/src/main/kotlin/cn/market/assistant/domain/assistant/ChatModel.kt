package cn.market.assistant.domain.assistant

/**
 * Provider-neutral chat contract for the assistant. Implementations translate it into the wire format
 * of one provider (OpenAI-compatible `/chat/completions`, Anthropic Messages, local Ollama, ...).
 */
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/** One file or image the user attached. Content stays in memory for the session only. */
data class ChatAttachment(
    val fileName: String,
    val mimeType: String,
    val text: String? = null,
    val base64Image: String? = null
) {
    val isImage: Boolean get() = !base64Image.isNullOrBlank()
    val isText: Boolean get() = !text.isNullOrBlank()
}

data class ChatToolCall(val id: String, val name: String, val argumentsJson: String)

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList()
)

data class ChatToolDefinition(val name: String, val description: String, val parametersJsonSchema: String)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ChatToolDefinition> = emptyList(),
    val temperature: Double = 0.3,
    val maxTokens: Int = 1_024
)

data class ChatUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

data class ChatResponse(
    val message: ChatMessage,
    val usage: ChatUsage = ChatUsage(),
    val finishReason: String? = null
)

/** Raised by adapters with a user-readable reason; the UI falls back to the offline engine. */
class ChatModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ChatModel {
    val displayName: String
    suspend fun complete(request: ChatRequest): ChatResponse
}

/** Turns declared tool parameters into the JSON schema mainstream providers expect. */
fun AgentToolDescriptor.toChatToolDefinition(): ChatToolDefinition {
    val properties = parameters.joinToString(",") { parameter ->
        "\"${parameter.name}\":{\"type\":\"string\",\"description\":\"${parameter.description.jsonEscaped()}\"}"
    }
    val required = parameters.filter { it.required }.joinToString(",") { "\"${it.name}\"" }
    return ChatToolDefinition(
        name = id,
        description = description,
        parametersJsonSchema = "{\"type\":\"object\",\"properties\":{$properties},\"required\":[$required]}"
    )
}

/** Renders tool facts as the plain text a model reads back, keeping source and time visible. */
fun List<AssistantFact>.toToolResultText(): String = if (isEmpty()) {
    "（工具没有返回可用事实）"
} else {
    joinToString("\n") { fact ->
        val unit = fact.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val source = fact.source?.takeIf { it.isNotBlank() }?.let { " · 来源 $it" } ?: ""
        val time = fact.observedAt?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        "- ${fact.key}: ${fact.value}$unit$source$time"
    }
}

internal fun String.jsonEscaped(): String = buildString(length + 8) {
    this@jsonEscaped.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
}
