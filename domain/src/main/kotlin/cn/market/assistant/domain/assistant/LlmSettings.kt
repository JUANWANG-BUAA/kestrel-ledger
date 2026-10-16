package cn.market.assistant.domain.assistant

enum class LlmProtocol { OPENAI_COMPATIBLE, ANTHROPIC }

/**
 * Mainstream providers the user can pick from. `defaultBaseUrl` / `defaultModel` are editable, so a
 * provider staying compatible with the chosen protocol keeps working without an app update.
 */
enum class LlmProvider(
    val label: String,
    val protocol: LlmProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val popularModels: List<String>,
    val consoleHint: String
) {
    DEEPSEEK(
        label = "DeepSeek 深度求索",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        popularModels = listOf("deepseek-chat", "deepseek-reasoner"),
        consoleHint = "platform.deepseek.com"
    ),
    MOONSHOT(
        label = "Kimi 月之暗面",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "kimi-k2-0905-preview",
        popularModels = listOf("kimi-k2-0905-preview", "kimi-k2-turbo-preview", "moonshot-v1-128k"),
        consoleHint = "platform.moonshot.cn"
    ),
    ZHIPU(
        label = "智谱 GLM",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4.6",
        popularModels = listOf("glm-4.6", "glm-4.5", "glm-4.5-air"),
        consoleHint = "open.bigmodel.cn"
    ),
    DASHSCOPE(
        label = "通义千问 Qwen",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-plus",
        popularModels = listOf("qwen-plus", "qwen-max", "qwen3-max", "qwen-turbo"),
        consoleHint = "bailian.console.aliyun.com"
    ),
    OPENAI(
        label = "OpenAI",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        popularModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini"),
        consoleHint = "platform.openai.com"
    ),
    ANTHROPIC(
        label = "Anthropic Claude",
        protocol = LlmProtocol.ANTHROPIC,
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-sonnet-4-5",
        popularModels = listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5"),
        consoleHint = "console.anthropic.com"
    ),
    GEMINI(
        label = "Google Gemini",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.5-flash",
        popularModels = listOf("gemini-2.5-flash", "gemini-2.5-pro"),
        consoleHint = "aistudio.google.com"
    ),
    OPENROUTER(
        label = "OpenRouter 聚合",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        popularModels = listOf("openai/gpt-4o-mini", "anthropic/claude-sonnet-4.5", "deepseek/deepseek-chat"),
        consoleHint = "openrouter.ai/keys"
    ),
    OLLAMA(
        label = "本地 Ollama",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen3:8b",
        popularModels = listOf("qwen3:8b", "qwen3:14b", "llama3.1:8b"),
        consoleHint = "本地运行，无需 API Key"
    ),
    CUSTOM(
        label = "自定义兼容服务",
        protocol = LlmProtocol.OPENAI_COMPATIBLE,
        defaultBaseUrl = "",
        defaultModel = "",
        popularModels = emptyList(),
        consoleHint = "任何兼容 OpenAI /chat/completions 的服务"
    );

    val isLocal: Boolean get() = this == OLLAMA
}

/** User-editable model settings; the API key never enters this object. */
data class LlmSettings(
    val provider: LlmProvider = LlmProvider.DEEPSEEK,
    val baseUrl: String = LlmProvider.DEEPSEEK.defaultBaseUrl,
    val model: String = LlmProvider.DEEPSEEK.defaultModel,
    val temperature: Double = 0.3,
    val maxTokens: Int = 1_024,
    val toolsEnabled: Boolean = true,
    val sharePrivateDetails: Boolean = false,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
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
        baseUrl = next.defaultBaseUrl.ifBlank { baseUrl },
        model = next.defaultModel.ifBlank { model }
    )

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "你是「养基助手」的本地数据分析助手。规则：\n" +
                "1. 只依据用户消息、工具返回的事实和附件内容回答，不得编造数值；缺数据就直说缺什么。\n" +
                "2. 每个数值都要带来源与时点；估算值必须标注「估算，非官方净值」。\n" +
                "3. 不提供买卖建议、不预测涨跌、不承诺收益；可以描述事实、口径、风险与历史表现。\n" +
                "4. 需要行情、持仓或报告时可调用工具；一次最多调用两个工具，拿不到就说明原因。\n" +
                "5. 用简体中文回答，先给结论再给依据，控制在 400 字以内；涉及金额保留两位小数。\n" +
                "6. 用户如果问该不该买卖，直接说明本应用不做投资建议，并改为提供相关事实。"
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

    /** Chat completions path for the OpenAI-compatible protocol. */
    fun openAiChatUrl(settings: LlmSettings): String = settings.baseUrl.trimEnd('/') + "/chat/completions"

    fun anthropicMessagesUrl(settings: LlmSettings): String = settings.baseUrl.trimEnd('/') + "/messages"
}
