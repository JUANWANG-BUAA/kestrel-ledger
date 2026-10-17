package cn.market.assistant

import android.content.Context
import cn.market.assistant.domain.assistant.LlmProvider
import cn.market.assistant.domain.assistant.LlmProtocol
import cn.market.assistant.domain.assistant.LlmSettings

/**
 * Non-secret assistant preferences: provider, endpoint, model, sampling, prompt and the tool
 * allowlist. The API key lives in [cn.market.assistant.data.assistant.LlmSecretStore] instead.
 */
class AssistantSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): LlmSettings {
        val provider = runCatching {
            LlmProvider.valueOf(preferences.getString(KEY_PROVIDER, null) ?: LlmProvider.DEEPSEEK.name)
        }.getOrDefault(LlmProvider.DEEPSEEK)
        val storedModel = preferences.getString(KEY_MODEL, null) ?: provider.defaultModel
        val protocol = runCatching {
            LlmProtocol.valueOf(preferences.getString(KEY_PROTOCOL, null) ?: provider.defaultProtocol.name)
        }.getOrDefault(provider.defaultProtocol)
        val migrateLegacyDeepSeek = provider == LlmProvider.DEEPSEEK &&
            storedModel in setOf("deepseek-chat", "deepseek-reasoner")
        val model = if (migrateLegacyDeepSeek) provider.defaultModel else storedModel
        val storedBaseUrl = preferences.getString(KEY_BASE_URL, null) ?: provider.defaultBaseUrl
        val baseUrl = if (migrateLegacyDeepSeek && storedBaseUrl == "https://api.deepseek.com/v1") {
            provider.defaultBaseUrl
        } else {
            storedBaseUrl
        }
        val settings = LlmSettings(
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            protocol = protocol,
            temperature = preferences.getFloat(KEY_TEMPERATURE, 0.3f).toDouble(),
            maxTokens = preferences.getInt(KEY_MAX_TOKENS, 1_024),
            toolsEnabled = preferences.getBoolean(KEY_TOOLS_ENABLED, true),
            sharePrivateDetails = preferences.getBoolean(KEY_SHARE_PRIVATE, false),
            systemPrompt = preferences.getString(KEY_SYSTEM_PROMPT, null) ?: LlmSettings.DEFAULT_SYSTEM_PROMPT,
            imageInputEnabled = if (migrateLegacyDeepSeek) {
                cn.market.assistant.domain.assistant.ModelImageCapabilities.infer(provider, model)
            } else {
                preferences.getBoolean(
                    KEY_IMAGE_INPUT_ENABLED,
                    cn.market.assistant.domain.assistant.ModelImageCapabilities.infer(provider, model)
                )
            }
        ).normalized()
        if (migrateLegacyDeepSeek) save(settings)
        return settings
    }

    fun save(settings: LlmSettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putString(KEY_PROVIDER, normalized.provider.name)
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_MODEL, normalized.model)
            .putString(KEY_PROTOCOL, normalized.protocol.name)
            .putFloat(KEY_TEMPERATURE, normalized.temperature.toFloat())
            .putInt(KEY_MAX_TOKENS, normalized.maxTokens)
            .putBoolean(KEY_TOOLS_ENABLED, normalized.toolsEnabled)
            .putBoolean(KEY_SHARE_PRIVATE, normalized.sharePrivateDetails)
            .putString(KEY_SYSTEM_PROMPT, normalized.systemPrompt)
            .putBoolean(KEY_IMAGE_INPUT_ENABLED, normalized.imageInputEnabled)
            .apply()
    }

    fun enabledToolIds(): Set<String>? {
        val raw = preferences.getStringSet(KEY_ENABLED_TOOLS, null) ?: return null
        return raw.toSet()
    }

    fun saveEnabledToolIds(ids: Set<String>) {
        preferences.edit().putStringSet(KEY_ENABLED_TOOLS, ids).apply()
    }

    /** Resets the prompt to a preset without touching provider credentials. */
    fun saveSystemPrompt(prompt: String) {
        preferences.edit().putString(KEY_SYSTEM_PROMPT, prompt.take(4_000)).apply()
    }

    private companion object {
        const val PREFERENCES = "assistant_settings"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TOOLS_ENABLED = "tools_enabled"
        const val KEY_SHARE_PRIVATE = "share_private"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_ENABLED_TOOLS = "enabled_tools"
        const val KEY_IMAGE_INPUT_ENABLED = "image_input_enabled"
    }
}

/** System-prompt presets users can apply in one tap and then keep editing. */
object AssistantPromptPresets {
    data class Preset(val id: String, val label: String, val prompt: String)

    val all: List<Preset> = listOf(
        Preset("general", "通用复盘", LlmSettings.DEFAULT_SYSTEM_PROMPT),
        Preset(
            "valuation",
            "估值解读",
            "只解释基金盘中估值：方法、训练/验证样本、样本外 MAE、覆盖率和降级原因。\n" +
                "所有数字标为「估算，非官方净值」并给出误差区间是否可用；不预测涨跌、不给操作建议。\n" +
                "数据不足时直接说明还缺多少交易日。"
        ),
        Preset(
            "risk",
            "风险审查",
            "审查本机持仓的集中度、波动、回撤、相关性与尾部风险，说明每个指标需要的样本量。\n" +
                "只描述历史事实与口径，不做预测、不推荐买卖；样本不足就说不可计算。"
        ),
        Preset(
            "ledger",
            "账本核对",
            "用本地账本事实核对份额、成本、已实现与持有收益，指出可能的缺口（缺报价、未确认流水、" +
                "未启用现金跟踪）。不改动任何账本数据；发现异常时给出需要用户核实的具体字段。"
        ),
        Preset(
            "gold",
            "黄金跟踪",
            "汇总国内外金价、黄金 ETF 与银行积存金报价，逐项标注币种、单位、来源与时点。\n" +
                "银行报价只能引用用户录入或标定值：不得用国际金价反推，也不得推算点差。"
        )
    )
}
