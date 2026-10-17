package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.ChatAttachment
import cn.market.assistant.domain.assistant.ChatMessage
import cn.market.assistant.domain.assistant.ChatRequest
import cn.market.assistant.domain.assistant.ChatResponse
import cn.market.assistant.domain.assistant.ChatRole
import cn.market.assistant.domain.assistant.ChatToolCall
import cn.market.assistant.domain.assistant.ChatUsage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure wire mapping between the neutral chat contract and the three mainstream protocols. Kept free of
 * Android APIs so the exact request/response shape is unit-testable.
 */
object OpenAiWire {
    fun requestBody(model: String, request: ChatRequest): String {
        val messages = JSONArray()
        request.messages.forEach { message -> messages.put(messageJson(message)) }
        val root = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", request.temperature)
            .put("max_tokens", request.maxTokens)
            .put("stream", false)
        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            request.tools.forEach { tool ->
                tools.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", JSONObject(tool.parametersJsonSchema))
                        )
                )
            }
            root.put("tools", tools)
            root.put("tool_choice", "auto")
        }
        return root.toString()
    }

    fun parseResponse(body: String): ChatResponse {
        val root = JSONObject(body)
        root.optJSONObject("error")?.let { error ->
            throw cn.market.assistant.domain.assistant.ChatModelException(
                error.optString("message").ifBlank { "模型服务返回错误" }
            )
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw cn.market.assistant.domain.assistant.ChatModelException("模型没有返回 choices")
        val message = choice.optJSONObject("message")
            ?: throw cn.market.assistant.domain.assistant.ChatModelException("模型没有返回 message")
        val toolCalls = message.optJSONArray("tool_calls")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val call = array.optJSONObject(index) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name").takeIf { it.isNotBlank() } ?: continue
                    add(
                        ChatToolCall(
                            id = call.optString("id").ifBlank { "call-$index" },
                            name = name,
                            argumentsJson = function.optString("arguments").ifBlank { "{}" }
                        )
                    )
                }
            }
        }.orEmpty()
        val usage = root.optJSONObject("usage")
        return ChatResponse(
            message = ChatMessage(
                role = ChatRole.ASSISTANT,
                content = message.optString("content"),
                toolCalls = toolCalls,
                reasoningContent = message.optString("reasoning_content").takeIf { it.isNotBlank() }
            ),
            usage = ChatUsage(
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
            ),
            finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() }
        )
    }

    private fun messageJson(message: ChatMessage): JSONObject = when (message.role) {
        ChatRole.ASSISTANT -> JSONObject()
            .put("role", "assistant")
            .put("content", message.content.ifBlank { JSONObject.NULL })
            .apply {
                message.reasoningContent?.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
                if (message.toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        JSONArray().apply {
                            message.toolCalls.forEach { call ->
                                put(
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.name)
                                                .put("arguments", call.argumentsJson)
                                        )
                                )
                            }
                        }
                    )
                }
            }
        ChatRole.TOOL -> JSONObject()
            .put("role", "tool")
            .put("tool_call_id", message.toolCallId.orEmpty())
            .put("content", message.content)
        else -> JSONObject()
            .put("role", message.role.name.lowercase())
            .put("content", contentJson(message))
    }

    private fun contentJson(message: ChatMessage): Any {
        val images = message.attachments.filter { it.isImage }
        val text = messageText(message)
        if (images.isEmpty()) return text
        return JSONArray().apply {
            if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
            images.forEach { image ->
                put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:${image.mimeType};base64,${image.base64Image}")
                        )
                )
            }
        }
    }

    /** File attachments travel as labelled text blocks so any text-capable model can read them. */
    internal fun messageText(message: ChatMessage): String {
        val documents = message.attachments.filter { it.isText }
        if (documents.isEmpty()) return message.content
        return buildString {
            append(message.content)
            documents.forEach { document ->
                append("\n\n【附件：${document.fileName}（${document.mimeType}）】\n")
                append(document.text)
            }
        }.trim()
    }
}

/** OpenAI Responses API wire format, including stateless tool-call and image round trips. */
object OpenAiResponsesWire {
    fun requestBody(model: String, request: ChatRequest): String {
        val instructions = request.messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
        val input = JSONArray()
        request.messages.filter { it.role != ChatRole.SYSTEM }.forEach { message ->
            when (message.role) {
                ChatRole.USER -> input.put(userMessage(message))
                ChatRole.ASSISTANT -> {
                    message.reasoningContent?.takeIf { it.isNotBlank() }?.let { reasoning ->
                        input.put(JSONObject().put("type", "reasoning").put("content", reasoning))
                    }
                    if (message.content.isNotBlank()) {
                        input.put(JSONObject().put("role", "assistant").put("content", message.content))
                    }
                    message.toolCalls.forEach { call ->
                        input.put(
                            JSONObject()
                                .put("type", "function_call")
                                .put("call_id", call.id)
                                .put("name", call.name)
                                .put("arguments", call.argumentsJson)
                        )
                    }
                }
                ChatRole.TOOL -> input.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", message.toolCallId.orEmpty())
                        .put("output", message.content)
                )
                ChatRole.SYSTEM -> Unit
            }
        }
        val root = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("max_output_tokens", request.maxTokens)
            .put("temperature", request.temperature)
            .put("stream", false)
        instructions?.let { root.put("instructions", it) }
        if (request.tools.isNotEmpty()) {
            root.put(
                "tools",
                JSONArray().apply {
                    request.tools.forEach { tool ->
                        put(
                            JSONObject()
                                .put("type", "function")
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", JSONObject(tool.parametersJsonSchema))
                        )
                    }
                }
            )
            root.put("tool_choice", "auto")
        }
        return root.toString()
    }

    fun parseResponse(body: String): ChatResponse {
        val root = JSONObject(body)
        root.optJSONObject("error")?.let { error ->
            throw cn.market.assistant.domain.assistant.ChatModelException(
                error.optString("message").ifBlank { "模型服务返回错误" }
            )
        }
        val output = root.optJSONArray("output")
            ?: throw cn.market.assistant.domain.assistant.ChatModelException("Responses API 没有返回 output")
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ChatToolCall>()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (partIndex in 0 until content.length()) {
                        val part = content.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") == "output_text") text.append(part.optString("text"))
                    }
                }
                "reasoning" -> {
                    val content = item.optJSONArray("content")
                    if (content == null) {
                        reasoning.append(item.optString("content"))
                    } else {
                        for (partIndex in 0 until content.length()) {
                            val part = content.optJSONObject(partIndex) ?: continue
                            if (part.optString("type") == "reasoning_text") reasoning.append(part.optString("text"))
                        }
                    }
                }
                "function_call" -> {
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                    toolCalls += ChatToolCall(
                        id = item.optString("call_id").ifBlank { item.optString("id").ifBlank { "call-$index" } },
                        name = name,
                        argumentsJson = item.optString("arguments").ifBlank { "{}" }
                    )
                }
            }
        }
        val usage = root.optJSONObject("usage")
        val status = root.optString("status")
        if (status == "failed") {
            throw cn.market.assistant.domain.assistant.ChatModelException(
                root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: "Responses API 请求失败"
            )
        }
        return ChatResponse(
            message = ChatMessage(
                role = ChatRole.ASSISTANT,
                content = text.toString().trim(),
                toolCalls = toolCalls,
                reasoningContent = reasoning.toString().takeIf { it.isNotBlank() }
            ),
            usage = ChatUsage(
                promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("output_tokens", 0) ?: 0
            ),
            finishReason = status.takeIf { it.isNotBlank() }
        )
    }

    private fun userMessage(message: ChatMessage): JSONObject {
        val images = message.attachments.filter { it.isImage }
        val text = OpenAiWire.messageText(message)
        val content: Any = if (images.isEmpty()) {
            text
        } else {
            JSONArray().apply {
                if (text.isNotBlank()) put(JSONObject().put("type", "input_text").put("text", text))
                images.forEach { image ->
                    put(
                        JSONObject()
                            .put("type", "input_image")
                            .put("image_url", "data:${image.mimeType};base64,${image.base64Image}")
                    )
                }
            }
        }
        return JSONObject().put("role", "user").put("content", content)
    }
}

object AnthropicWire {
    fun requestBody(model: String, request: ChatRequest, maxTokens: Int): String {
        val system = request.messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
        val messages = JSONArray()
        request.messages.filter { it.role != ChatRole.SYSTEM }.forEach { message -> appendMessage(messages, message) }
        val root = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", request.temperature)
            .put("messages", messages)
        system?.let { root.put("system", it) }
        if (request.tools.isNotEmpty()) {
            root.put(
                "tools",
                JSONArray().apply {
                    request.tools.forEach { tool ->
                        put(
                            JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("input_schema", JSONObject(tool.parametersJsonSchema))
                        )
                    }
                }
            )
        }
        return root.toString()
    }

    fun parseResponse(body: String): ChatResponse {
        val root = JSONObject(body)
        root.optJSONObject("error")?.let { error ->
            throw cn.market.assistant.domain.assistant.ChatModelException(
                error.optString("message").ifBlank { "模型服务返回错误" }
            )
        }
        val blocks = root.optJSONArray("content")
            ?: throw cn.market.assistant.domain.assistant.ChatModelException("模型没有返回 content")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ChatToolCall>()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> {
                    val name = block.optString("name").takeIf { it.isNotBlank() } ?: continue
                    toolCalls += ChatToolCall(
                        id = block.optString("id").ifBlank { "tool-$index" },
                        name = name,
                        argumentsJson = block.optJSONObject("input")?.toString() ?: "{}"
                    )
                }
            }
        }
        val usage = root.optJSONObject("usage")
        return ChatResponse(
            message = ChatMessage(ChatRole.ASSISTANT, text.toString().trim(), toolCalls = toolCalls),
            usage = ChatUsage(
                promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("output_tokens", 0) ?: 0
            ),
            finishReason = root.optString("stop_reason").takeIf { it.isNotBlank() }
        )
    }

    private fun appendMessage(messages: JSONArray, message: ChatMessage) {
        when (message.role) {
            ChatRole.USER -> messages.put(JSONObject().put("role", "user").put("content", userBlocks(message)))
            ChatRole.ASSISTANT -> messages.put(JSONObject().put("role", "assistant").put("content", assistantBlocks(message)))
            ChatRole.TOOL -> {
                val block = JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", message.toolCallId.orEmpty())
                    .put("content", message.content)
                val previous = if (messages.length() > 0) messages.optJSONObject(messages.length() - 1) else null
                val previousBlocks = previous?.optJSONArray("content")
                if (previous != null && previous.optString("role") == "user" &&
                    previousBlocks?.optJSONObject(0)?.optString("type") == "tool_result"
                ) {
                    previousBlocks.put(block)
                } else {
                    messages.put(JSONObject().put("role", "user").put("content", JSONArray().put(block)))
                }
            }
            ChatRole.SYSTEM -> Unit
        }
    }

    private fun userBlocks(message: ChatMessage): JSONArray = JSONArray().apply {
        val text = OpenAiWire.messageText(message)
        if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
        message.attachments.filter { it.isImage }.forEach { image ->
            put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", image.mimeType)
                            .put("data", image.base64Image)
                    )
            )
        }
        if (length() == 0) put(JSONObject().put("type", "text").put("text", "（空消息）"))
    }

    private fun assistantBlocks(message: ChatMessage): JSONArray = JSONArray().apply {
        if (message.content.isNotBlank()) put(JSONObject().put("type", "text").put("text", message.content))
        message.toolCalls.forEach { call ->
            put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", call.id)
                    .put("name", call.name)
                    .put("input", runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject()))
            )
        }
        if (length() == 0) put(JSONObject().put("type", "text").put("text", "（空回复）"))
    }
}

internal fun ChatAttachment.imageLabel(): String = "@image:$fileName"
