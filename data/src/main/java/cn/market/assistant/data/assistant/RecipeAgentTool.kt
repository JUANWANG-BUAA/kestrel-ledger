package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRegistry
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.AssistantRecipeStore
import cn.market.assistant.domain.assistant.FactSensitivity
import cn.market.assistant.domain.assistant.RecipeExecutor
import java.time.Instant

/** Registry handle for recipes, which need to call back into the registry that contains them. */
class RecipeRegistryHandle {
    var registry: AgentToolRegistry? = null
}

/**
 * Runs a saved recipe by id. Steps only call already-authorized tools (re-checked by the registry),
 * cannot recurse into another recipe, and writes go to the bounded assistant workspace.
 */
class RecipeAgentTool(
    private val store: AssistantRecipeStore,
    private val workspace: LocalAssistantWorkspace,
    private val handle: RecipeRegistryHandle
) : AgentTool {
    override val id = "recipe.run"
    override val description = "运行本机保存的配方（按顺序调用已授权工具，可写一份 Markdown 报告到助手工作区）。"
    override val parameters = listOf(AgentToolParameter("recipe", "配方 ID，例如 gold-weekly", required = true))

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val id = request.arguments["recipe"]?.trim().orEmpty()
        if (id.isBlank()) return AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        val recipe = store.recipes().firstOrNull { it.id == id }
            ?: return AgentToolResult(emptyList(), "RECIPE_NOT_FOUND")
        val registry = handle.registry ?: return AgentToolResult(emptyList(), "TOOL_REGISTRY_UNAVAILABLE")
        val observedAt = Instant.now().toString()
        val result = RecipeExecutor(registry).run(recipe, request.authorization) { path, content ->
            workspace.write(path, content)
        }
        if (!result.succeeded) return AgentToolResult(emptyList(), result.errorCode)
        val facts = result.facts.toMutableList()
        result.writtenFiles.forEach { path ->
            facts += AssistantFact(
                key = "recipe.file",
                value = path,
                unit = "relative path",
                source = "助手工作区",
                observedAt = observedAt,
                sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
            )
        }
        if (facts.isEmpty() && result.charts.isEmpty()) {
            return AgentToolResult(emptyList(), "EMPTY_RECIPE_RESULT")
        }
        facts += AssistantFact(
            key = "recipe.executed",
            value = "${recipe.id}（${recipe.steps.size} 步，${result.charts.size} 张图）",
            source = "本机配方",
            observedAt = observedAt,
            sensitivity = FactSensitivity.PUBLIC_MARKET
        )
        return AgentToolResult(facts, charts = result.charts)
    }
}
