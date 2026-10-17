package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.LlmProvider
import cn.market.assistant.domain.assistant.LlmProtocol
import cn.market.assistant.domain.assistant.LlmSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelCatalogTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deepSeekCatalogueReturnsAvailableModelsAndPublishedCapabilities() = runTest {
        var requestUrl = ""
        var requestHeaders = emptyMap<String, String>()
        val transport = object : HttpTransport() {
            override fun getJson(url: String, headers: Map<String, String>): String {
                requestUrl = url
                requestHeaders = headers
                return """
                    {"data":[
                      {"id":"deepseek-flash","name":"DeepSeek Flash","context_window":1048576,"max_output_tokens":393216,
                       "input_modalities":["text","image"],"effort":{"supported_levels":["low","high","max"]}},
                      {"id":"deepseek-v4-pro","name":"DeepSeek Pro","context_window":1048576,"max_output_tokens":393216,
                       "input_modalities":["text"],"effort":{"supported_levels":["low","high","max"]}}
                    ]}
                """.trimIndent()
            }
        }
        val catalog = LlmModelCatalog(transport, UnconfinedTestDispatcher(testScheduler))
        val models = catalog.readAvailableModels(LlmSettings(), "sk-example")

        assertEquals("https://api.deepseek.com/models", requestUrl)
        assertEquals("Bearer sk-example", requestHeaders["Authorization"])
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.modelId })
        assertEquals(1_048_576, models.first().contextWindow)
        assertEquals(393_216, models.first().maxOutputTokens)
        assertEquals(true, models.first().supportsImageInput)
        assertEquals(false, models.last().supportsImageInput)
        assertEquals(listOf("low", "high", "max"), models.first().reasoningEfforts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anthropicCatalogueUsesKeyHeaderAndReadsThinkingAndImageCapabilities() = runTest {
        var requestUrl = ""
        var requestHeaders = emptyMap<String, String>()
        val transport = object : HttpTransport() {
            override fun getJson(url: String, headers: Map<String, String>): String {
                requestUrl = url
                requestHeaders = headers
                return """
                    {"data":[{"id":"claude-sonnet-4-5","display_name":"Claude Sonnet","max_input_tokens":200000,
                      "max_tokens":64000,"capabilities":{"image_input":{"supported":true},
                      "effort":{"supported":true},"thinking":{"supported":true,"types":{"enabled":{"supported":true}}}}}]}
                """.trimIndent()
            }
        }
        val catalog = LlmModelCatalog(transport, UnconfinedTestDispatcher(testScheduler))
        val settings = LlmSettings().withProvider(LlmProvider.ANTHROPIC)
        val models = catalog.readAvailableModels(settings, "sk-ant-example")

        assertEquals("https://api.anthropic.com/v1/models?limit=100", requestUrl)
        assertEquals("sk-ant-example", requestHeaders["x-api-key"])
        assertEquals("2023-06-01", requestHeaders["anthropic-version"])
        assertNull(models.single().contextWindow)
        assertEquals(200_000, models.single().maxInputTokens)
        assertEquals(64_000, models.single().maxOutputTokens)
        assertEquals(true, models.single().supportsImageInput)
        assertEquals(setOf("enabled"), models.single().thinkingTypes)
        assertNull(models.single().reasoningEfforts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deepSeekAnthropicProtocolStillUsesDeepSeekModelsEndpointAuthentication() = runTest {
        var requestUrl = ""
        var requestHeaders = emptyMap<String, String>()
        val transport = object : HttpTransport() {
            override fun getJson(url: String, headers: Map<String, String>): String {
                requestUrl = url
                requestHeaders = headers
                return """{"data":[{"id":"deepseek-flash"}]}"""
            }
        }
        val catalog = LlmModelCatalog(transport, UnconfinedTestDispatcher(testScheduler))
        val settings = LlmSettings().withProtocol(cn.market.assistant.domain.assistant.LlmProtocol.ANTHROPIC_MESSAGES)
        catalog.readAvailableModels(settings, "sk-example")

        assertEquals("https://api.deepseek.com/models", requestUrl)
        assertEquals("Bearer sk-example", requestHeaders["Authorization"])
        assertNull(requestHeaders["x-api-key"])
        assertNull(requestHeaders["anthropic-version"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun openAiModelListDoesNotInventUnpublishedCapabilityMetadata() = runTest {
        val transport = object : HttpTransport() {
            override fun getJson(url: String, headers: Map<String, String>): String =
                """{"data":[{"id":"gpt-4o-mini","object":"model"}]}"""
        }
        val catalog = LlmModelCatalog(transport, UnconfinedTestDispatcher(testScheduler))
        val models = catalog.readAvailableModels(LlmSettings().withProvider(LlmProvider.OPENAI), "sk-example")

        assertEquals("gpt-4o-mini", models.single().modelId)
        assertNull(models.single().contextWindow)
        assertNull(models.single().maxOutputTokens)
        assertNull(models.single().supportsImageInput)
        assertTrue(models.single().catalogueVerified)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun catalogCanBeReadBeforeChoosingAModel() = runTest {
        val transport = object : HttpTransport() {
            override fun getJson(url: String, headers: Map<String, String>): String =
                """{"data":[{"id":"custom-model"}]}"""
        }
        val catalog = LlmModelCatalog(transport, UnconfinedTestDispatcher(testScheduler))
        val settings = LlmSettings(
            provider = LlmProvider.CUSTOM,
            protocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.example.com/v1",
            model = ""
        )

        assertEquals("custom-model", catalog.readAvailableModels(settings, "sk-example").single().modelId)
    }
}
