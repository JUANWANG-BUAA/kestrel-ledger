package cn.market.assistant.domain.assistant

/**
 * A saved recipe: an ordered list of existing tools plus an optional Markdown report. Recipes are
 * data, never code: they can only call tools that the current turn already allows, cannot recurse
 * into other recipes, and any write goes through the shared assistant workspace.
 */
enum class RecipeOp { TOOL, WRITE }

data class RecipeStep(
    val op: RecipeOp,
    val toolId: String? = null,
    val args: Map<String, String> = emptyMap(),
    val path: String? = null
)

data class AssistantRecipe(
    val id: String,
    val name: String,
    val summary: String = "",
    val steps: List<RecipeStep>,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
) {
    val toolIds: List<String> get() = steps.mapNotNull { it.toolId }.distinct()
}

interface AssistantRecipeStore {
    suspend fun recipes(): List<AssistantRecipe>
    suspend fun upsertRecipe(recipe: AssistantRecipe): AssistantRecipe
    suspend fun deleteRecipe(id: String)
}

data class RecipeRunResult(
    val facts: List<AssistantFact> = emptyList(),
    val charts: List<AssistantChart> = emptyList(),
    val writtenFiles: List<String> = emptyList(),
    val errorCode: String? = null
) {
    val succeeded: Boolean get() = errorCode == null
}

object RecipeRules {
    val ID = Regex("[a-z][a-z0-9_-]{1,40}")
    const val MAX_STEPS = 8
    const val MAX_ARGS = 8

    /** Returns a human-readable problem, or null when the recipe may be stored and executed. */
    fun validate(recipe: AssistantRecipe): String? {
        if (!ID.matches(recipe.id)) return "配方 ID 需为小写字母开头的 2–41 位标识"
        if (recipe.name.length !in 2..40) return "配方名称需为 2–40 个字符"
        if (recipe.summary.length > 200) return "配方说明过长"
        if (recipe.steps.isEmpty() || recipe.steps.size > MAX_STEPS) return "配方步骤需为 1–$MAX_STEPS 步"
        recipe.steps.forEachIndexed { index, step ->
            when (step.op) {
                RecipeOp.TOOL -> {
                    val toolId = step.toolId ?: return "第 ${index + 1} 步缺少工具 ID"
                    if (toolId == "recipe.run") return "配方不能调用自身"
                    if (!toolId.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) return "第 ${index + 1} 步工具 ID 无效"
                    if (step.args.size > MAX_ARGS) return "第 ${index + 1} 步参数过多"
                    if (step.args.any { it.key.length > 40 || it.value.length > 500 }) return "第 ${index + 1} 步参数过长"
                }
                RecipeOp.WRITE -> {
                    val path = step.path ?: return "第 ${index + 1} 步缺少写入路径"
                    if (!path.matches(Regex("reports/[a-z0-9][a-z0-9_./-]{0,60}\\.md"))) {
                        return "第 ${index + 1} 步只能写入 reports/*.md"
                    }
                }
            }
        }
        return null
    }
}

/**
 * Executes recipes through the normal tool registry. Authentication/authorization comes from the
 * caller: every nested tool call is re-checked against the same allowlist and privacy consent.
 */
class RecipeExecutor(private val registry: AgentToolRegistry) {
    suspend fun run(
        recipe: AssistantRecipe,
        authorization: AgentToolAuthorization,
        writeReport: suspend (path: String, content: String) -> Unit
    ): RecipeRunResult {
        RecipeRules.validate(recipe)?.let { return RecipeRunResult(errorCode = "INVALID_RECIPE") }
        if (recipe.toolIds.any { it !in authorization.allowedToolIds }) {
            return RecipeRunResult(errorCode = "RECIPE_TOOL_NOT_ALLOWED")
        }
        val facts = mutableListOf<AssistantFact>()
        val charts = mutableListOf<AssistantChart>()
        val files = mutableListOf<String>()
        recipe.steps.forEachIndexed { index, step ->
            when (step.op) {
                RecipeOp.TOOL -> {
                    val toolId = step.toolId ?: return RecipeRunResult(errorCode = "INVALID_RECIPE")
                    if (toolId == "recipe.run") return RecipeRunResult(errorCode = "RECIPE_RECURSION")
                    val result = registry.execute(
                        toolId,
                        AgentToolRequest(step.args, authorization),
                        authorization
                    )
                    if (result.errorCode != null) {
                        return RecipeRunResult(errorCode = "RECIPE_STEP_${index + 1}_FAILED")
                    }
                    if (result.actionProposal != null) {
                        return RecipeRunResult(errorCode = "RECIPE_CANNOT_PROPOSE_CHANGES")
                    }
                    facts += result.facts
                    result.charts.forEach { chart -> if (charts.size < 4) charts += chart }
                }
                RecipeOp.WRITE -> {
                    val path = step.path ?: return RecipeRunResult(errorCode = "INVALID_RECIPE")
                    runCatching { writeReport(path, buildReport(recipe, facts, charts)) }
                        .onSuccess { files += path }
                        .onFailure { return RecipeRunResult(errorCode = "RECIPE_WRITE_FAILED") }
                }
            }
        }
        return RecipeRunResult(facts = facts, charts = charts, writtenFiles = files)
    }

    private fun buildReport(
        recipe: AssistantRecipe,
        facts: List<AssistantFact>,
        charts: List<AssistantChart>
    ): String = buildString {
        appendLine("# ${recipe.name}")
        appendLine()
        if (recipe.summary.isNotBlank()) appendLine(recipe.summary).also { appendLine() }
        appendLine("> 本报告由本机配方「${recipe.id}」生成；数据来自下列事实，不构成投资建议。")
        appendLine()
        facts.forEach { fact ->
            append("- **${fact.key}**：${fact.value}")
            fact.unit?.let { append(" $it") }
            append("（来源：${fact.source.orEmpty()}，时点：${fact.observedAt.orEmpty()}）")
            appendLine()
        }
        if (charts.isNotEmpty()) {
            appendLine()
            appendLine("## 图表数据")
            charts.forEach { chart ->
                appendLine()
                appendLine("### ${chart.title}")
                chart.series.forEach { series ->
                    appendLine("- ${series.name}：${series.points.size} 点（${series.points.firstOrNull()?.label} → ${series.points.lastOrNull()?.label}）")
                }
                appendLine("- 来源：${chart.source} · ${chart.observedAt}")
            }
        }
    }
}
