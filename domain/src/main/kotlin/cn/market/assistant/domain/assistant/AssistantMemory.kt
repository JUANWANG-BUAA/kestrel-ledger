package cn.market.assistant.domain.assistant

enum class MemoryKind { QUESTION, TOOL_USE, SKILL_USE, INSTRUMENT, MODEL }

data class MemoryEvent(
    val kind: MemoryKind,
    val key: String,
    val atMillis: Long,
    val weight: Double = 1.0
)

data class MemoryCount(val key: String, val count: Int, val lastAtMillis: Long)

data class HabitSummary(
    val totalEvents: Int = 0,
    val topQuestions: List<MemoryCount> = emptyList(),
    val topTools: List<MemoryCount> = emptyList(),
    val topSkills: List<MemoryCount> = emptyList(),
    val topInstruments: List<MemoryCount> = emptyList(),
    val lastActiveAtMillis: Long? = null
) {
    val isEmpty: Boolean get() = totalEvents == 0
}

interface AssistantMemoryStore {
    suspend fun recordMemory(event: MemoryEvent)
    suspend fun memoryEvents(limit: Int = 800): List<MemoryEvent>
    suspend fun clearMemory()
}

/**
 * Retrieval-lite over locally recorded usage: no embeddings and no network. A question is tokenized
 * into Chinese bi-grams and latin words, memories are scored by term overlap, recency decay and
 * frequency, and the best matches are injected into the system prompt as user-habit context.
 */
object AssistantMemoryRanker {
    private const val RECENCY_HALF_LIFE_MILLIS = 14L * 24 * 60 * 60 * 1_000
    private val CHINESE = Regex("[\\u4e00-\\u9fff]+")
    private val LATIN = Regex("[a-z0-9]{2,}")

    fun summarize(events: List<MemoryEvent>): HabitSummary {
        if (events.isEmpty()) return HabitSummary()
        fun top(kind: MemoryKind, limit: Int): List<MemoryCount> = events.asSequence()
            .filter { it.kind == kind }
            .groupBy { it.key }
            .map { (key, sameKey) -> MemoryCount(key, sameKey.size, sameKey.maxOf { it.atMillis }) }
            .sortedWith(compareByDescending<MemoryCount> { it.count }.thenByDescending { it.lastAtMillis })
            .take(limit)
            .toList()
        return HabitSummary(
            totalEvents = events.size,
            topQuestions = top(MemoryKind.QUESTION, 6),
            topTools = top(MemoryKind.TOOL_USE, 6),
            topSkills = top(MemoryKind.SKILL_USE, 6),
            topInstruments = top(MemoryKind.INSTRUMENT, 6),
            lastActiveAtMillis = events.maxOfOrNull { it.atMillis }
        )
    }

    /** Memories related to the question, most relevant first. */
    fun retrieve(
        question: String,
        events: List<MemoryEvent>,
        limit: Int = 5,
        nowMillis: Long = System.currentTimeMillis()
    ): List<MemoryCount> {
        if (question.isBlank() || events.isEmpty() || limit <= 0) return emptyList()
        val tokens = tokensOf(question)
        if (tokens.isEmpty()) return emptyList()
        val candidates = events.groupBy { it.kind to it.key }
        return candidates.map { (kindAndKey, sameKey) ->
            val (kind, key) = kindAndKey
            val keyTokens = tokensOf(key)
            val overlap = tokens.count { token -> keyTokens.any { it == token || it.contains(token) || token.contains(it) } }
            if (overlap == 0) return@map null
            val recency = sameKey.maxOf { it.atMillis }.let { last ->
                val age = (nowMillis - last).coerceAtLeast(0)
                Math.pow(0.5, age.toDouble() / RECENCY_HALF_LIFE_MILLIS)
            }
            val frequency = Math.log10(sameKey.size.toDouble() + 1.0)
            val kindBoost = if (kind == MemoryKind.INSTRUMENT) 0.15 else 0.0
            val score = overlap * 1.0 + recency * 0.8 + frequency * 0.5 + kindBoost
            Triple(MemoryCount(key, sameKey.size, sameKey.maxOf { it.atMillis }), score, kind)
        }.filterNotNull()
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** One compact system-prompt section; null when there is nothing useful to say. */
    fun habitPrompt(summary: HabitSummary, retrieved: List<MemoryCount> = emptyList()): String? {
        if (summary.isEmpty) return null
        val lines = mutableListOf<String>()
        if (summary.topTools.isNotEmpty()) {
            lines += "- 常用工具：" + summary.topTools.take(3).joinToString("、") { "${it.key}(${it.count}次)" }
        }
        if (summary.topSkills.isNotEmpty()) {
            lines += "- 常用技能：" + summary.topSkills.take(3).joinToString("、") { "${it.key}(${it.count}次)" }
        }
        if (summary.topInstruments.isNotEmpty()) {
            lines += "- 常看标的：" + summary.topInstruments.take(4).joinToString("、") { it.key }
        }
        if (retrieved.isNotEmpty()) {
            lines += "- 与本次问题相关的历史：" + retrieved.joinToString("；") { "${it.key}（${it.count}次，最近 " +
                java.time.Instant.ofEpochMilli(it.lastAtMillis).toString().take(10) + "）" }
        }
        if (lines.isEmpty()) return null
        return "【用户使用习惯（仅本机记录，用于理解偏好，不代表事实依据）】\n" + lines.joinToString("\n")
    }

    fun tokensOf(text: String): List<String> {
        val tokens = mutableListOf<String>()
        CHINESE.findAll(text.lowercase()).forEach { match ->
            val word = match.value
            if (word.length == 1) {
                tokens += word
            } else {
                for (index in 0 until word.length - 1) tokens += word.substring(index, index + 2)
            }
        }
        LATIN.findAll(text.lowercase()).forEach { tokens += it.value }
        return tokens.distinct().take(40)
    }
}
