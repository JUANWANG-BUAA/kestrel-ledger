package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.AgentTool
import cn.market.assistant.domain.assistant.AgentToolParameter
import cn.market.assistant.domain.assistant.AgentToolRequest
import cn.market.assistant.domain.assistant.AgentToolResult
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.FactSensitivity
import java.time.Instant

private fun workspaceFact(key: String, value: String, unit: String? = null): AssistantFact = AssistantFact(
    key = key,
    value = value,
    unit = unit,
    source = "助手工作区",
    observedAt = Instant.now().toString(),
    sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
)

/** Lists the assistant workspace; read-only and bounded. */
class WorkspaceListTool(private val workspace: LocalAssistantWorkspace) : AgentTool {
    override val id = "workspace.list"
    override val description = "列出助手工作区的文件（reports/*.md 与 data/*.csv|json）。"
    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val files = workspace.list()
        if (files.isEmpty()) return AgentToolResult(listOf(workspaceFact("workspace.empty", "工作区暂无文件")))
        return AgentToolResult(files.take(60).map { path -> workspaceFact("workspace.file", path) })
    }
}

/** Reads one workspace text file with a hard output cap. */
class WorkspaceReadTool(private val workspace: LocalAssistantWorkspace) : AgentTool {
    override val id = "workspace.read"
    override val description = "读取工作区里的文本文件（最多返回 4000 字符）。"
    override val parameters = listOf(AgentToolParameter("path", "reports/x.md 或 data/x.csv", required = true))

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val path = request.arguments["path"]?.trim().orEmpty()
        if (path.isBlank()) return AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        val text = workspace.read(path) ?: return AgentToolResult(emptyList(), "WORKSPACE_FILE_NOT_FOUND")
        return AgentToolResult(
            listOf(
                workspaceFact("workspace.path", path),
                workspaceFact("workspace.content", text.take(4_000)),
                workspaceFact("workspace.truncated", (text.length > 4_000).toString(), "boolean")
            )
        )
    }
}

/** Writes text into the workspace; the app can remove this tool when local changes are disabled. */
class WorkspaceWriteTool(private val workspace: LocalAssistantWorkspace) : AgentTool {
    override val id = "workspace.write"
    override val description = "把 CSV/JSON/Markdown 写入助手工作区（data/*.csv|json、reports/*.md，单文件不超过 256 KB）。"
    override val parameters = listOf(
        AgentToolParameter("path", "目标相对路径", required = true),
        AgentToolParameter("content", "UTF-8 文本内容", required = true)
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val path = request.arguments["path"]?.trim().orEmpty()
        val content = request.arguments["content"].orEmpty()
        if (path.isBlank() || content.isBlank()) return AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        return runCatching { workspace.write(path, content) }.fold(
            onSuccess = { AgentToolResult(listOf(workspaceFact("workspace.written", it))) },
            onFailure = { AgentToolResult(emptyList(), "WORKSPACE_WRITE_FAILED") }
        )
    }
}

/** Applies a declarative filter/select/group/sort/limit pipeline to a CSV in the workspace. */
class WorkspaceQueryTool(private val workspace: LocalAssistantWorkspace) : AgentTool {
    override val id = "workspace.query"
    override val description = "对工作区 CSV 执行声明式流水线：filter / select / group / sort / limit；模型只提供 JSON，不执行代码。"
    override val parameters = listOf(
        AgentToolParameter("path", "data/*.csv", required = true),
        AgentToolParameter("pipeline", "步骤 JSON，例如 {\"steps\":[{\"op\":\"limit\",\"n\":20}],\"limit\":20}", required = true)
    )

    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val path = request.arguments["path"]?.trim().orEmpty()
        val specText = request.arguments["pipeline"]?.trim().orEmpty()
        if (path.isBlank() || specText.isBlank()) return AgentToolResult(emptyList(), "INVALID_TOOL_ARGUMENTS")
        val text = workspace.read(path) ?: return AgentToolResult(emptyList(), "WORKSPACE_FILE_NOT_FOUND")
        val spec = WorkspacePipeline.parse(specText).getOrElse { return AgentToolResult(emptyList(), "INVALID_PIPELINE") }
        val rows = WorkspacePipeline.run(spec, text).getOrElse { return AgentToolResult(emptyList(), "PIPELINE_FAILED") }
        if (rows.isEmpty()) return AgentToolResult(emptyList(), "PIPELINE_EMPTY_RESULT")
        val facts = rows.take(40).mapIndexed { index, row ->
            workspaceFact("row.${index + 1}", row.entries.joinToString(" · ") { "${it.key}=${it.value}" })
        } + workspaceFact("pipeline.rows", rows.size.toString(), "rows")
        return AgentToolResult(facts)
    }
}
