package cn.market.assistant.data.assistant

/**
 * Declarative table pipeline for the assistant workspace. The model writes JSON steps, never code:
 * filter/select/group/sort/limit over CSV rows, with hard bounds on steps, rows and fields.
 */
data class PipelineSpec(
    val steps: List<PipelineStep>,
    val limit: Int = 50
)

sealed interface PipelineStep {
    data class Filter(val field: String, val op: String, val value: String) : PipelineStep
    data class Select(val fields: List<String>) : PipelineStep
    data class Group(val by: List<String>, val sum: List<String>) : PipelineStep
    data class Sort(val field: String, val descending: Boolean) : PipelineStep
    data class Limit(val count: Int) : PipelineStep
}

object WorkspacePipeline {
    private const val MAX_STEPS = 8
    private const val MAX_ROWS = 5_000
    private const val MAX_RESULT_ROWS = 200

    fun parse(raw: String): Result<PipelineSpec> = runCatching {
        val json = org.json.JSONObject(raw)
        val stepsJson = json.optJSONArray("steps") ?: error("steps 必须是数组")
        require(stepsJson.length() in 1..MAX_STEPS) { "步骤需为 1–$MAX_STEPS 个" }
        val steps = (0 until stepsJson.length()).map { index ->
            val step = stepsJson.optJSONObject(index) ?: error("第 ${index + 1} 步不是对象")
            when (step.optString("op").lowercase()) {
                "filter" -> PipelineStep.Filter(
                    field = step.optString("field").takeIf { it.isNotBlank() } ?: error("filter 缺少 field"),
                    op = step.optString("op_").ifBlank { step.optString("cmp", "eq") },
                    value = step.optString("value")
                )
                "select" -> {
                    val fields = step.optJSONArray("fields") ?: error("select 缺少 fields")
                    PipelineStep.Select((0 until fields.length()).map { fields.optString(it) }.filter { it.isNotBlank() })
                }
                "group" -> {
                    val by = step.optJSONArray("by") ?: error("group 缺少 by")
                    val sum = step.optJSONArray("sum")
                    PipelineStep.Group(
                        by = (0 until by.length()).map { by.optString(it) }.filter { it.isNotBlank() },
                        sum = (0 until (sum?.length() ?: 0)).map { sum?.optString(it).orEmpty() }.filter { it.isNotBlank() }
                    )
                }
                "sort" -> PipelineStep.Sort(
                    field = step.optString("field").takeIf { it.isNotBlank() } ?: error("sort 缺少 field"),
                    descending = step.optBoolean("desc", false)
                )
                "limit" -> PipelineStep.Limit(step.optInt("n", 50).coerceIn(1, MAX_RESULT_ROWS))
                else -> error("不支持的步骤：${step.optString("op")}")
            }
        }
        PipelineSpec(steps, json.optInt("limit", 50).coerceIn(1, MAX_RESULT_ROWS))
    }

    /** Runs the pipeline over CSV text; returns up to [MAX_RESULT_ROWS] rows as string maps. */
    fun run(spec: PipelineSpec, csv: String): Result<List<Map<String, String>>> = runCatching {
        val table = parseCsv(csv)
        require(table.rows.isNotEmpty()) { "CSV 没有数据行" }
        require(table.rows.size <= MAX_ROWS) { "CSV 行数超过 $MAX_ROWS" }
        var rows = table.rows
        var fields = table.header
        spec.steps.forEach { step ->
            when (step) {
                is PipelineStep.Filter -> {
                    require(step.field in fields) { "filter 字段不存在：${step.field}" }
                    rows = rows.filter { row -> matches(row[step.field].orEmpty(), step.op, step.value) }
                }
                is PipelineStep.Select -> {
                    require(step.fields.isNotEmpty() && step.fields.all { it in fields }) { "select 字段不存在" }
                    fields = step.fields
                    rows = rows.map { row -> fields.associateWith { row[it].orEmpty() } }
                }
                is PipelineStep.Group -> {
                    require(step.by.isNotEmpty() && step.by.all { it in fields }) { "group 字段不存在" }
                    val sumFields = step.sum.filter { it in fields }
                    val grouped = linkedMapOf<List<String>, MutableList<Map<String, String>>>()
                    rows.forEach { row ->
                        val key = step.by.map { row[it].orEmpty() }
                        grouped.getOrPut(key) { mutableListOf() }.add(row)
                    }
                    val outFields = step.by + sumFields.map { "${it}_sum" } + "count"
                    fields = outFields
                    rows = grouped.map { (key, group) ->
                        buildMap {
                            step.by.forEachIndexed { index, field -> put(field, key[index]) }
                            sumFields.forEach { field ->
                                val total = group.sumOf { it[field]?.toDoubleOrNull() ?: 0.0 }
                                put("${field}_sum", trimNumber(total))
                            }
                            put("count", group.size.toString())
                        }
                    }
                }
                is PipelineStep.Sort -> {
                    require(step.field in fields) { "sort 字段不存在：${step.field}" }
                    rows = rows.sortedWith(
                        compareBy<Map<String, String>> { row ->
                            row[step.field].orEmpty().toDoubleOrNull() ?: Double.NaN
                        }.thenBy { row -> row[step.field].orEmpty() }
                    )
                    if (step.descending) rows = rows.reversed()
                }
                is PipelineStep.Limit -> rows = rows.take(step.count)
            }
        }
        rows.take(minOf(spec.limit, MAX_RESULT_ROWS))
    }

    fun toCsv(rows: List<Map<String, String>>, fields: List<String>): String = buildString {
        appendLine(fields.joinToString(",") { escape(it) })
        rows.forEach { row -> appendLine(fields.joinToString(",") { escape(row[it].orEmpty()) }) }
    }

    private fun matches(value: String, op: String, expected: String): Boolean {
        val left = value.toDoubleOrNull()
        val right = expected.toDoubleOrNull()
        return when (op.lowercase()) {
            "eq" -> value == expected
            "ne" -> value != expected
            "contains" -> value.contains(expected, ignoreCase = true)
            "gt" -> left != null && right != null && left > right
            "gte" -> left != null && right != null && left >= right
            "lt" -> left != null && right != null && left < right
            "lte" -> left != null && right != null && left <= right
            else -> false
        }
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.4f", value).trimEnd('0').trimEnd('.')

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private data class Table(val header: List<String>, val rows: List<Map<String, String>>)

    /** Small CSV reader with quoted fields; enough for exported tables, no external dependency. */
    private fun parseCsv(text: String): Table {
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += field.toString(); field = StringBuilder()
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += field.toString(); field = StringBuilder()
                    if (row.any { it.isNotBlank() }) records += row
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotBlank() }) records += row
        }
        require(records.size >= 2) { "CSV 需要表头和至少一行数据" }
        val header = records.first().map { it.trim() }
        require(header.all { it.isNotBlank() } && header.distinct().size == header.size) { "CSV 表头无效" }
        val rows = records.drop(1).map { values ->
            header.mapIndexed { i, name -> name to values.getOrElse(i) { "" } }.toMap()
        }
        return Table(header, rows)
    }
}
