package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.ChatModelException
import cn.market.assistant.domain.assistant.LlmModelCapabilities
import cn.market.assistant.domain.assistant.LlmEndpointCheck
import cn.market.assistant.domain.assistant.LlmEndpointValidator
import cn.market.assistant.domain.assistant.LlmProvider
import cn.market.assistant.domain.assistant.LlmProtocol
import cn.market.assistant.domain.assistant.LlmSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Reads model identifiers and capability fields the provider actually publishes. */
class LlmModelCatalog(
    private val transport: HttpTransport = HttpTransport(),
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun readAvailableModels(settings: LlmSettings, apiKey: String?): List<LlmModelCapabilities> = withContext(ioDispatcher) {
        val normalized = settings.normalized()
        val endpointCheck = LlmEndpointValidator.validateModelCatalog(normalized)
        if (endpointCheck is LlmEndpointCheck.Invalid) throw ChatModelException(endpointCheck.reason)
        if (normalized.provider != LlmProvider.OLLAMA && apiKey.isNullOrBlank()) {
            throw ChatModelException("请先填写 API Key")
        }
        val body = transport.getJson(modelsUrl(normalized), headers(normalized, apiKey))
        val root = JSONObject(body)
        val rows = root.optJSONArray("data") ?: throw ChatModelException("模型目录没有返回 data 列表")
        val models = (0 until rows.length())
            .mapNotNull(rows::optJSONObject)
            .mapNotNull { model ->
                model.optString("id").takeIf(String::isNotBlank)?.let {
                    when (normalized.provider) {
                        LlmProvider.DEEPSEEK -> deepSeek(model)
                        LlmProvider.ANTHROPIC -> anthropic(model)
                        else -> LlmModelCapabilities(
                            modelId = it,
                            displayName = model.optString("name").takeIf(String::isNotBlank) ?: it,
                            modelFound = true
                        )
                    }
                }
            }
        if (models.isEmpty()) throw ChatModelException("模型目录没有可用模型")
        models
    }

    suspend fun readSelectedModel(settings: LlmSettings, apiKey: String?): LlmModelCapabilities =
        readAvailableModels(settings, apiKey).firstOrNull { it.modelId == settings.model }
            ?: throw ChatModelException("API Key 可用的模型目录中没有「${settings.model}」")

    private fun deepSeek(model: JSONObject): LlmModelCapabilities {
        val effort = model.optJSONObject("effort")
        return LlmModelCapabilities(
            modelId = model.optString("id"),
            displayName = model.optString("name").takeIf { it.isNotBlank() },
            contextWindow = model.optInt("context_window", 0).takeIf { it > 0 },
            maxOutputTokens = model.optInt("max_output_tokens", 0).takeIf { it > 0 },
            inputModalities = model.optJSONArray("input_modalities")?.let { values ->
                (0 until values.length()).mapNotNull { index -> values.optString(index).takeIf(String::isNotBlank) }
            },
            reasoningEfforts = effort?.optJSONArray("supported_levels")?.let { values ->
                (0 until values.length()).mapNotNull { index -> values.optString(index).takeIf(String::isNotBlank) }
            },
            modelFound = true
        )
    }

    private fun anthropic(model: JSONObject): LlmModelCapabilities {
        val capabilities = model.optJSONObject("capabilities")
        val effort = capabilities?.optJSONObject("effort")
        val types = capabilities?.optJSONObject("thinking")?.optJSONObject("types")
        return LlmModelCapabilities(
            modelId = model.optString("id"),
            displayName = model.optString("display_name").takeIf { it.isNotBlank() },
            maxInputTokens = model.optInt("max_input_tokens", 0).takeIf { it > 0 },
            maxOutputTokens = model.optInt("max_tokens", 0).takeIf { it > 0 },
            inputModalities = buildList {
                add("text")
                if (capabilities?.optJSONObject("image_input")?.optBoolean("supported") == true) add("image")
            },
            reasoningEfforts = effort?.let { objectKeysWithSupportedTrue(it, excluding = setOf("supported")) }
                ?.takeIf { it.isNotEmpty() }?.toList(),
            thinkingTypes = types?.let { objectKeysWithSupportedTrue(it) } ?: emptySet(),
            modelFound = true
        )
    }

    private fun objectKeysWithSupportedTrue(value: JSONObject, excluding: Set<String> = emptySet()): Set<String> =
        value.keys().asSequence()
            .filter { it !in excluding && value.optJSONObject(it)?.optBoolean("supported") == true }
            .toSet()

    private fun modelsUrl(settings: LlmSettings): String {
        var base = settings.baseUrl.trimEnd('/')
        if (settings.provider == LlmProvider.DEEPSEEK && base.endsWith("/anthropic")) {
            base = base.removeSuffix("/anthropic")
        }
        val suffix = if (settings.provider == LlmProvider.ANTHROPIC) "?limit=100" else ""
        return "$base/models$suffix"
    }

    private fun headers(settings: LlmSettings, apiKey: String?): Map<String, String> = buildMap {
        // DeepSeek exposes /models on its regular API surface, even when chat uses
        // the Anthropic-compatible /anthropic/messages route.
        if (settings.provider == LlmProvider.DEEPSEEK) {
            apiKey?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
        } else if (settings.protocol == LlmProtocol.ANTHROPIC_MESSAGES) {
            apiKey?.takeIf(String::isNotBlank)?.let { put("x-api-key", it) }
            put("anthropic-version", "2023-06-01")
        } else {
            apiKey?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
        }
    }
}
