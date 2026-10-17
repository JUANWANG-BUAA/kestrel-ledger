package cn.market.assistant.domain.assistant

enum class LlmProtocol(val label: String, val endpointSuffix: String) {
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions", "/chat/completions"),
    OPENAI_RESPONSES("OpenAI Responses", "/responses"),
    ANTHROPIC_MESSAGES("Anthropic Messages", "/v1/messages")
}

/**
 * Mainstream providers the user can pick from. `defaultBaseUrl` / `defaultModel` are editable, so a
 * provider staying compatible with the chosen protocol keeps working without an app update.
 */
enum class LlmProvider(
    val label: String,
    val defaultProtocol: LlmProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val popularModels: List<String>,
    val consoleHint: String
) {
    DEEPSEEK(
        label = "DeepSeek 深度求索",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        popularModels = listOf("deepseek-flash", "deepseek-v4-pro"),
        consoleHint = "platform.deepseek.com"
    ),
    MOONSHOT(
        label = "Kimi 月之暗面",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "kimi-k2-0905-preview",
        popularModels = listOf("kimi-k2-0905-preview", "kimi-k2-turbo-preview", "moonshot-v1-128k"),
        consoleHint = "platform.moonshot.cn"
    ),
    ZHIPU(
        label = "智谱 GLM",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4.6",
        popularModels = listOf("glm-4.6", "glm-4.5", "glm-4.5-air"),
        consoleHint = "open.bigmodel.cn"
    ),
    DASHSCOPE(
        label = "通义千问 Qwen",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-plus",
        popularModels = listOf("qwen-plus", "qwen-max", "qwen3-max", "qwen-turbo"),
        consoleHint = "bailian.console.aliyun.com"
    ),
    OPENAI(
        label = "OpenAI",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        popularModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini"),
        consoleHint = "platform.openai.com"
    ),
    ANTHROPIC(
        label = "Anthropic Claude",
        defaultProtocol = LlmProtocol.ANTHROPIC_MESSAGES,
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-sonnet-4-5",
        popularModels = listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5"),
        consoleHint = "console.anthropic.com"
    ),
    GEMINI(
        label = "Google Gemini",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.5-flash",
        popularModels = listOf("gemini-2.5-flash", "gemini-2.5-pro"),
        consoleHint = "aistudio.google.com"
    ),
    OPENROUTER(
        label = "OpenRouter 聚合",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        popularModels = listOf("openai/gpt-4o-mini", "anthropic/claude-sonnet-4.5", "deepseek/deepseek-chat"),
        consoleHint = "openrouter.ai/keys"
    ),
    OLLAMA(
        label = "本地 Ollama",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen3:8b",
        popularModels = listOf("qwen3:8b", "qwen3:14b", "llama3.1:8b"),
        consoleHint = "本地运行，无需 API Key"
    ),
    CUSTOM(
        label = "自定义兼容服务",
        defaultProtocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
        defaultBaseUrl = "",
        defaultModel = "",
        popularModels = emptyList(),
        consoleHint = "任何兼容 OpenAI /chat/completions 的服务"
    );

    val isLocal: Boolean get() = this == OLLAMA

    fun baseUrlFor(protocol: LlmProtocol): String = when {
        this == DEEPSEEK && protocol == LlmProtocol.ANTHROPIC_MESSAGES -> "$defaultBaseUrl/anthropic"
        else -> defaultBaseUrl
    }
}

/** User-editable model settings; the API key never enters this object. */
data class LlmSettings(
    val provider: LlmProvider = LlmProvider.DEEPSEEK,
    val baseUrl: String = LlmProvider.DEEPSEEK.defaultBaseUrl,
    val model: String = LlmProvider.DEEPSEEK.defaultModel,
    val protocol: LlmProtocol = provider.defaultProtocol,
    val temperature: Double = 0.3,
    val maxTokens: Int = 1_024,
    val toolsEnabled: Boolean = true,
    val sharePrivateDetails: Boolean = false,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** Explicit capability switch; unknown/custom endpoints default to text-only. */
    val imageInputEnabled: Boolean = ModelImageCapabilities.infer(provider, model)
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun normalized(): LlmSettings = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        temperature = temperature.coerceIn(0.0, 2.0),
        maxTokens = maxTokens.coerceIn(128, 8_192),
        systemPrompt = systemPrompt.take(4_000)
    )

    /** Switching provider resets base URL and model unless the user picked CUSTOM. */
    fun withProvider(next: LlmProvider): LlmSettings = copy(
        provider = next,
        baseUrl = next.baseUrlFor(next.defaultProtocol).ifBlank { baseUrl },
        model = next.defaultModel.ifBlank { model },
        protocol = next.defaultProtocol,
        imageInputEnabled = ModelImageCapabilities.infer(next, next.defaultModel.ifBlank { model })
    )

    fun withProtocol(next: LlmProtocol): LlmSettings {
        val currentSuggestedBaseUrl = provider.baseUrlFor(protocol).trimEnd('/')
        val shouldUpdateBaseUrl = baseUrl.trimEnd('/') == currentSuggestedBaseUrl
        return copy(
            protocol = next,
            baseUrl = if (shouldUpdateBaseUrl) provider.baseUrlFor(next) else baseUrl
        )
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "你是「养基助手」的本地数据分析助手。规则：\n" +
                "1. 只依据用户消息、工具返回的事实和附件内容回答，不得编造数值；缺数据就直说缺什么。\n" +
                "2. 每个数值都要带来源与时点；估算值必须标注「估算，非官方净值」。\n" +
                "3. 不提供买卖建议、不预测涨跌、不承诺收益；可以描述事实、口径、风险与历史表现。\n" +
            "4. 需要行情、基金目录、持仓或报告时可调用工具；一次最多调用两个工具，拿不到就说明原因。\n" +
                "5. 用简体中文回答，先给结论再给依据，控制在 400 字以内；涉及金额保留两位小数。\n" +
            "6. 用户如果问该不该买卖，直接说明本应用不做投资建议，并改为提供相关事实。\n" +
            "7. 用户明确要求记住分析方法时，可调用 portfolio.propose_change 的 save_preference 生成待确认记录；不要声称已经记住。\n" +
            "8. 用户请求修改自选、记录基金流水或调整风险参数时，只能调用 portfolio.propose_change 生成预览；说明这是本机记录，不会提交交易。必须等用户在界面确认后才能说已保存。"
    }
}

/** Conservative UI defaults for known vision model families; custom models remain opt-in. */
object ModelImageCapabilities {
    fun infer(provider: LlmProvider, model: String): Boolean {
        val id = model.trim().lowercase()
        return when (provider) {
            LlmProvider.OPENAI -> listOf("gpt-4o", "gpt-4.1", "gpt-4.5", "gpt-5", "o1", "o3", "o4").any(id::startsWith)
            LlmProvider.ANTHROPIC -> listOf("claude-3", "claude-4", "claude-haiku-4", "claude-sonnet-4", "claude-opus-4").any(id::startsWith)
            LlmProvider.GEMINI -> id.startsWith("gemini-")
            LlmProvider.DASHSCOPE -> listOf("qwen-vl", "qwen2.5-vl", "qwen3-vl", "qwen-omni").any(id::startsWith)
            LlmProvider.ZHIPU -> listOf("glm-4v", "glm-4.5v", "glm-4.6v").any(id::startsWith)
            LlmProvider.MOONSHOT -> id.startsWith("kimi-vl") || id.startsWith("kimi-k2.5")
            LlmProvider.OPENROUTER -> listOf(
                "gpt-4o", "gpt-4.1", "gpt-5", "claude-3", "claude-sonnet-4", "gemini-",
                "qwen-vl", "qwen2.5-vl", "qwen3-vl", "glm-4v", "llava", "pixtral", "internvl",
                "deepseek-flash"
            ).any(id::contains)
            LlmProvider.OLLAMA -> listOf("llava", "qwen2-vl", "qwen2.5vl", "qwen3-vl", "gemma3", "minicpm-v", "moondream").any(id::startsWith)
            LlmProvider.DEEPSEEK -> id.startsWith("deepseek-flash")
            LlmProvider.CUSTOM -> false
        }
    }
}

/** Result of validating a user-supplied endpoint before saving it. */
sealed interface LlmEndpointCheck {
    data object Ok : LlmEndpointCheck
    data class Invalid(val reason: String) : LlmEndpointCheck
}

object LlmEndpointValidator {
    fun validate(settings: LlmSettings): LlmEndpointCheck = when {
        !settings.isConfigured -> LlmEndpointCheck.Invalid("请先填写接口地址和模型名称")
        settings.provider != LlmProvider.OLLAMA && !settings.baseUrl.startsWith("https://") &&
            !settings.baseUrl.startsWith("http://127.0.0.1") && !settings.baseUrl.startsWith("http://localhost") ->
            LlmEndpointCheck.Invalid("接口地址必须是 https://（本机 Ollama 可用 http://127.0.0.1）")
        settings.model.length > 200 -> LlmEndpointCheck.Invalid("模型名称过长")
        else -> LlmEndpointCheck.Ok
    }

    fun openAiChatUrl(settings: LlmSettings): String = settings.baseUrl.trimEnd('/') + "/chat/completions"

    fun openAiResponsesUrl(settings: LlmSettings): String = settings.baseUrl.trimEnd('/') + "/responses"

    fun anthropicMessagesUrl(settings: LlmSettings): String {
        val baseUrl = settings.baseUrl.trimEnd('/')
        return if (baseUrl.endsWith("/v1")) "$baseUrl/messages" else "$baseUrl/v1/messages"
    }

    fun endpointUrl(settings: LlmSettings): String = when (settings.protocol) {
        LlmProtocol.OPENAI_CHAT_COMPLETIONS -> openAiChatUrl(settings)
        LlmProtocol.OPENAI_RESPONSES -> openAiResponsesUrl(settings)
        LlmProtocol.ANTHROPIC_MESSAGES -> anthropicMessagesUrl(settings)
    }
}
