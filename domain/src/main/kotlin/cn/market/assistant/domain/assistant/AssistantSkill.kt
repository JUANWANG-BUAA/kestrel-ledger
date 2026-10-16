package cn.market.assistant.domain.assistant

/** A user-defined skill: reusable instructions plus an optional tool allowlist. */
data class AssistantSkill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val toolIds: List<String> = emptyList(),
    val triggerWords: List<String> = emptyList(),
    val enabled: Boolean = true,
    val usageCount: Int = 0,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
) {
    val hasCustomTools: Boolean get() = toolIds.isNotEmpty()
}

interface AssistantSkillStore {
    suspend fun skills(): List<AssistantSkill>
    suspend fun upsertSkill(skill: AssistantSkill): AssistantSkill
    suspend fun deleteSkill(id: String)
    suspend fun recordSkillUse(id: String, atMillis: Long)
}

/** Matches a question to a skill, either by explicit `/id` or by trigger words and name overlap. */
object AssistantSkillMatcher {
    fun explicitId(question: String, skills: List<AssistantSkill>): String? {
        val trimmed = question.trim()
        if (!trimmed.startsWith("/")) return null
        val token = trimmed.substringBefore(' ').removePrefix("/").trim()
        if (token.isEmpty()) return null
        return skills.firstOrNull { it.id.equals(token, ignoreCase = true) || it.name == token }?.id
    }

    fun match(question: String, skills: List<AssistantSkill>): AssistantSkill? {
        val enabled = skills.filter { it.enabled }
        if (enabled.isEmpty()) return null
        explicitId(question, enabled)?.let { id -> return enabled.first { it.id == id } }
        val normalized = question.lowercase()
        val scored = enabled.map { skill ->
            val triggerHits = skill.triggerWords.count { word -> word.isNotBlank() && normalized.contains(word.lowercase()) }
            val nameHit = if (skill.name.length > 1 && normalized.contains(skill.name.lowercase())) 2 else 0
            val descriptionHit = if (skill.description.length > 3 && normalized.contains(skill.description.lowercase())) 1 else 0
            skill to (triggerHits * 3 + nameHit + descriptionHit)
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second > 0 }
    }

    /** Appends the skill's instructions and tool scope to the base system prompt. */
    fun composeSystemPrompt(base: String, skill: AssistantSkill?): String {
        if (skill == null) return base
        val toolLine = if (skill.toolIds.isEmpty()) {
            ""
        } else {
            "\n- 本技能只允许调用这些工具：${skill.toolIds.joinToString("、")}"
        }
        return buildString {
            append(base)
            append("\n\n【当前技能：${skill.name}】")
            if (skill.description.isNotBlank()) append("\n适用场景：${skill.description}")
            append("\n执行要求：\n")
            append(skill.instructions.trim())
            if (toolLine.isNotEmpty()) append(toolLine)
        }
    }
}
