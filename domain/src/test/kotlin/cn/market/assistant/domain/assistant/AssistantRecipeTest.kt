package cn.market.assistant.domain.assistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRecipeTest {
    private fun fact(key: String) = AssistantFact(
        key = key, value = "1.0", unit = null, source = "test",
        observedAt = "2026-10-17T10:00:00Z", sensitivity = FactSensitivity.PUBLIC_MARKET
    )

    private class Tool(
        override val id: String,
        private val result: AgentToolResult
    ) : AgentTool {
        override val description = id
        override suspend fun execute(request: AgentToolRequest): AgentToolResult = result
    }

    private fun chart(title: String) = AssistantChart(
        title = title,
        series = listOf(
            AssistantChartSeries(
                "s",
                listOf(AssistantChartPoint("10-15", 1.0), AssistantChartPoint("10-16", 2.0))
            )
        ),
        source = "test",
        observedAt = "2026-10-17T10:00:00Z"
    )

    private fun recipe(vararg steps: RecipeStep, id: String = "gold-weekly") =
        AssistantRecipe(id = id, name = "黄金周复盘", steps = steps.toList())

    @Test
    fun validatesRecipeStructure() {
        assertNull(RecipeRules.validate(recipe(RecipeStep(RecipeOp.TOOL, toolId = "market.chart"))))
        assertNotNull(RecipeRules.validate(recipe(RecipeStep(RecipeOp.TOOL, toolId = "recipe.run"))))
        assertNotNull(RecipeRules.validate(recipe(RecipeStep(RecipeOp.WRITE, path = "../secret.md"))))
        assertNull(RecipeRules.validate(recipe(RecipeStep(RecipeOp.WRITE, path = "reports/gold.md"))))
        assertNotNull(RecipeRules.validate(AssistantRecipe("BAD", "x", steps = emptyList())))
    }

    @Test
    fun runsToolsAndWritesAConsistentReport() = runTest {
        val registry = AgentToolRegistry(
            listOf(
                Tool("market.chart", AgentToolResult(listOf(fact("chart.latest")), charts = listOf(chart("国内金价")))),
                Tool("portfolio.period_report", AgentToolResult(listOf(fact("report.week"))))
            )
        )
        var written: Pair<String, String>? = null
        val result = RecipeExecutor(registry).run(
            recipe(
                RecipeStep(RecipeOp.TOOL, toolId = "market.chart"),
                RecipeStep(RecipeOp.TOOL, toolId = "portfolio.period_report"),
                RecipeStep(RecipeOp.WRITE, path = "reports/gold.md")
            ),
            AgentToolAuthorization(allowedToolIds = setOf("market.chart", "portfolio.period_report", "workspace.write"))
        ) { path, content -> written = path to content }

        assertTrue(result.succeeded)
        assertEquals(1, result.charts.size)
        assertEquals(2, result.facts.size)
        assertEquals("reports/gold.md", result.writtenFiles.single())
        val (path, content) = requireNotNull(written)
        assertEquals("reports/gold.md", path)
        assertTrue(content.contains("chart.latest"))
        assertTrue(content.contains("国内金价"))
        assertTrue(content.contains("不构成投资建议"))
    }

    @Test
    fun refusesWorkspaceWritesWithoutTheWriteTool() = runTest {
        val result = RecipeExecutor(AgentToolRegistry(emptyList())).run(
            recipe(RecipeStep(RecipeOp.WRITE, path = "reports/gold.md")),
            AgentToolAuthorization(allowedToolIds = emptySet())
        ) { _, _ -> }
        assertEquals("RECIPE_WRITE_NOT_ALLOWED", result.errorCode)
    }

    @Test
    fun refusesToolsOutsideTheTurnAllowlist() = runTest {
        val registry = AgentToolRegistry(listOf(Tool("market.chart", AgentToolResult(listOf(fact("x"))))))
        val result = RecipeExecutor(registry).run(
            recipe(RecipeStep(RecipeOp.TOOL, toolId = "market.chart")),
            AgentToolAuthorization(allowedToolIds = emptySet())
        ) { _, _ -> }
        assertEquals("RECIPE_TOOL_NOT_ALLOWED", result.errorCode)
    }

    @Test
    fun stopsWhenAStepFails() = runTest {
        val registry = AgentToolRegistry(
            listOf(Tool("market.chart", AgentToolResult(emptyList(), errorCode = "UPSTREAM_DOWN")))
        )
        val result = RecipeExecutor(registry).run(
            recipe(RecipeStep(RecipeOp.TOOL, toolId = "market.chart")),
            AgentToolAuthorization(allowedToolIds = setOf("market.chart"))
        ) { _, _ -> }
        assertEquals("RECIPE_STEP_1_FAILED", result.errorCode)
        assertTrue(result.facts.isEmpty())
    }
}
