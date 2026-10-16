package cn.market.assistant.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSettingsTest {
    @Test
    fun normalizationTrimsEndpointsAndClampsSampling() {
        val settings = LlmSettings(
            baseUrl = "  https://api.deepseek.com/v1/  ",
            model = "  deepseek-chat ",
            temperature = 9.0,
            maxTokens = 10
        ).normalized()

        assertEquals("https://api.deepseek.com/v1", settings.baseUrl)
        assertEquals("deepseek-chat", settings.model)
        assertEquals(2.0, settings.temperature, 1e-9)
        assertEquals(128, settings.maxTokens)
        assertTrue(settings.isConfigured)
    }

    @Test
    fun switchingProviderReplacesEndpointAndModelFromThePreset() {
        val settings = LlmSettings().withProvider(LlmProvider.ANTHROPIC)

        assertEquals(LlmProvider.ANTHROPIC, settings.provider)
        assertEquals(LlmProvider.ANTHROPIC.defaultBaseUrl, settings.baseUrl)
        assertEquals(LlmProvider.ANTHROPIC.defaultModel, settings.model)
        assertEquals(LlmProtocol.ANTHROPIC, settings.provider.protocol)
    }

    @Test
    fun endpointValidationRequiresHttpsExceptLocalOllama() {
        val insecure = LlmSettings(provider = LlmProvider.CUSTOM, baseUrl = "http://example.com/v1", model = "m")
        assertTrue(LlmEndpointValidator.validate(insecure) is LlmEndpointCheck.Invalid)

        val local = LlmSettings(provider = LlmProvider.OLLAMA, baseUrl = "http://127.0.0.1:11434/v1", model = "qwen3:8b")
        assertEquals(LlmEndpointCheck.Ok, LlmEndpointValidator.validate(local))

        val empty = LlmSettings(provider = LlmProvider.CUSTOM, baseUrl = "", model = "")
        assertTrue(LlmEndpointValidator.validate(empty) is LlmEndpointCheck.Invalid)
    }

    @Test
    fun chatUrlsFollowTheSelectedProtocol() {
        val openAi = LlmSettings(provider = LlmProvider.DEEPSEEK, baseUrl = "https://api.deepseek.com/v1")
        assertEquals("https://api.deepseek.com/v1/chat/completions", LlmEndpointValidator.openAiChatUrl(openAi))

        val anthropic = LlmSettings(provider = LlmProvider.ANTHROPIC, baseUrl = "https://api.anthropic.com/v1")
        assertEquals("https://api.anthropic.com/v1/messages", LlmEndpointValidator.anthropicMessagesUrl(anthropic))
    }

    @Test
    fun defaultSystemPromptKeepsTheProductRedLines() {
        val prompt = LlmSettings.DEFAULT_SYSTEM_PROMPT

        assertTrue(prompt.contains("不得编造数值"))
        assertTrue(prompt.contains("不提供买卖建议"))
        assertTrue(prompt.contains("估算，非官方净值"))
        assertTrue(prompt.contains("来源与时点"))
    }

    @Test
    fun toolDescriptorBecomesAJsonSchemaWithRequiredFields() {
        val descriptor = AgentToolDescriptor(
            id = "market.quote",
            description = "按名称或代码查询报价",
            parameters = listOf(
                AgentToolParameter("query", "名称或代码", required = true),
                AgentToolParameter("limit", "最多返回条数")
            )
        )

        val definition = descriptor.toChatToolDefinition()

        assertEquals("market.quote", definition.name)
        assertTrue(definition.parametersJsonSchema.contains("\"query\""))
        assertTrue(definition.parametersJsonSchema.contains("\"required\":[\"query\"]"))
        assertTrue(definition.parametersJsonSchema.contains("\"limit\""))
    }
}
