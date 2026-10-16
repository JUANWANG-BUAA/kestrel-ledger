package cn.market.assistant.domain.assistant

import java.time.Instant
import java.time.ZoneId

enum class WorkflowStatus { ACTIVE, COMPLETED, ARCHIVED }

/** One persisted question/answer pair, including which skill and tools produced it. */
data class WorkflowTurn(
    val question: String,
    val answer: String,
    val skillId: String? = null,
    val toolIds: List<String> = emptyList(),
    val atMillis: Long = 0L,
    val failed: Boolean = false
)

/** A durable conversation with a title and a status; resumable after a restart. */
data class AssistantWorkflow(
    val id: String,
    val title: String,
    val status: WorkflowStatus = WorkflowStatus.ACTIVE,
    val turns: List<WorkflowTurn> = emptyList(),
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
) {
    val turnCount: Int get() = turns.size
    val lastTurn: WorkflowTurn? get() = turns.lastOrNull()
}

interface AssistantWorkflowStore {
    suspend fun workflows(limit: Int = 30): List<AssistantWorkflow>
    suspend fun workflow(id: String): AssistantWorkflow?
    suspend fun upsertWorkflow(workflow: AssistantWorkflow)
    suspend fun deleteWorkflow(id: String)
    suspend fun clearWorkflows()
}

/**
 * Line-based workflow codec. Turns are stored one per line and the two free-text fields are
 * Base64-encoded, so a newline or tab inside an answer can never corrupt the stored workflow.
 */
object WorkflowCodec {
    private const val FIELD_SEPARATOR = '\t'

    fun encode(turns: List<WorkflowTurn>): String = turns.joinToString("\n") { turn ->
        listOf(
            base64(turn.question),
            base64(turn.answer),
            turn.skillId.orEmpty(),
            turn.toolIds.joinToString(","),
            turn.atMillis.toString(),
            if (turn.failed) "1" else "0"
        ).joinToString(FIELD_SEPARATOR.toString())
    }

    fun decode(payload: String): List<WorkflowTurn> {
        if (payload.isBlank()) return emptyList()
        return payload.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val fields = line.split(FIELD_SEPARATOR)
            if (fields.size < 6) return@mapNotNull null
            val question = unbase64(fields[0]) ?: return@mapNotNull null
            val answer = unbase64(fields[1]) ?: return@mapNotNull null
            WorkflowTurn(
                question = question,
                answer = answer,
                skillId = fields[2].takeIf { it.isNotBlank() },
                toolIds = fields[3].split(',').map { it.trim() }.filter { it.isNotBlank() },
                atMillis = fields[4].toLongOrNull() ?: 0L,
                failed = fields[5] == "1"
            )
        }.toList()
    }

    private fun base64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unbase64(value: String): String? = runCatching {
        String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }.getOrNull()
}

/** Deterministic Markdown export of one workflow, used by the share sheet. */
object WorkflowExport {
    private val zone = ZoneId.of("Asia/Shanghai")

    fun toMarkdown(workflow: AssistantWorkflow, generatedAtMillis: Long): String = buildString {
        appendLine("# 养基助手 · 会话记录：${workflow.title}")
        appendLine()
        appendLine("- 会话 ID：${workflow.id}")
        appendLine("- 状态：${workflow.status.name} · ${workflow.turnCount} 轮")
        appendLine("- 导出时间：${formatTime(generatedAtMillis)}（Asia/Shanghai）")
        appendLine()
        workflow.turns.forEachIndexed { index, turn ->
            appendLine("## 第 ${index + 1} 轮")
            appendLine("**我**：${turn.question}")
            appendLine()
            val skill = turn.skillId?.takeIf { it.isNotBlank() }?.let { "（技能：$it）" } ?: ""
            val tools = turn.toolIds.takeIf { it.isNotEmpty() }?.let { "（工具：${it.joinToString("、")}）" } ?: ""
            appendLine("**助手**$skill$tools：")
            appendLine()
            appendLine(if (turn.failed) "> 该轮失败：${turn.answer}" else turn.answer)
            appendLine()
        }
        appendLine("---")
        appendLine("本记录由本机生成，仅作复盘；不构成投资建议，估值均为估算。")
    }

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().toString().replace('T', ' ').take(16)
}
