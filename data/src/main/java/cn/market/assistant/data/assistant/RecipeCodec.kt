package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.AssistantRecipe
import cn.market.assistant.domain.assistant.RecipeOp
import cn.market.assistant.domain.assistant.RecipeRules
import cn.market.assistant.domain.assistant.RecipeStep
import org.json.JSONArray
import org.json.JSONObject

/** JSON form of a recipe; the model writes this, the app validates and stores it. */
object RecipeCodec {
    fun encode(recipe: AssistantRecipe): String = JSONObject().apply {
        put("version", 1)
        put("id", recipe.id)
        put("name", recipe.name)
        put("summary", recipe.summary)
        put("steps", JSONArray().apply {
            recipe.steps.forEach { step ->
                put(JSONObject().apply {
                    put("op", step.op.name.lowercase())
                    step.toolId?.let { put("tool", it) }
                    if (step.args.isNotEmpty()) put("args", JSONObject(step.args as Map<*, *>))
                    step.path?.let { put("path", it) }
                })
            }
        })
    }.toString()

    /** Strict decode with a reason so the model can correct its own spec instead of guessing. */
    fun decodeDetailed(raw: String): RecipeDecode {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return RecipeDecode(null, "not a JSON object")
        if (json.optInt("version", 1) != 1) return RecipeDecode(null, "unsupported version")
        val stepsJson = json.optJSONArray("steps") ?: return RecipeDecode(null, "steps must be an array")
        if (stepsJson.length() == 0) return RecipeDecode(null, "steps is empty")
        val steps = mutableListOf<RecipeStep>()
        for (index in 0 until stepsJson.length()) {
            val step = stepsJson.optJSONObject(index)
                ?: return RecipeDecode(null, "step ${index + 1} is not an object")
            val op = step.optString("op").ifBlank { step.optString("type") }.lowercase()
            when {
                op == "tool" || (op.isBlank() && step.optString("tool").isNotBlank()) -> {
                    val rawTool = listOf("tool", "tool_id", "id")
                        .map { step.optString(it) }
                        .firstOrNull { it.isNotBlank() }
                        ?: return RecipeDecode(null, "step ${index + 1} has no tool")
                    // Models often return provider-safe names such as market_chart; map the first
                    // underscore back to the dotted local id the registry uses.
                    val tool = if (rawTool.contains('.') || !rawTool.contains('_')) rawTool
                    else rawTool.replaceFirst('_', '.')
                    val args = when (val rawArgs = step.opt("args") ?: step.opt("params")) {
                        null -> emptyMap()
                        is JSONObject -> buildMap { rawArgs.keys().forEach { key -> put(key, rawArgs.optString(key)) } }
                        is String -> runCatching {
                            val parsed = JSONObject(rawArgs)
                            buildMap { parsed.keys().forEach { key -> put(key, parsed.optString(key)) } }
                        }.getOrElse { return RecipeDecode(null, "step ${index + 1} args is not an object") }
                        else -> return RecipeDecode(null, "step ${index + 1} args is not an object")
                    }
                    steps += RecipeStep(RecipeOp.TOOL, toolId = tool, args = args)
                }
                op == "write" || op == "report" || (op.isBlank() && step.optString("path").isNotBlank()) -> {
                    val path = step.optString("path").takeIf { it.isNotBlank() }
                        ?: return RecipeDecode(null, "step ${index + 1} has no path")
                    steps += RecipeStep(RecipeOp.WRITE, path = path)
                }
                else -> return RecipeDecode(null, "step ${index + 1} has unknown op '$op'")
            }
        }
        val recipe = AssistantRecipe(
            id = json.optString("id"),
            name = json.optString("name"),
            summary = json.optString("summary"),
            steps = steps
        )
        RecipeRules.validate(recipe)?.let { return RecipeDecode(null, it) }
        return RecipeDecode(recipe, null)
    }

    fun decode(raw: String): AssistantRecipe? = decodeDetailed(raw).recipe
}

data class RecipeDecode(val recipe: AssistantRecipe?, val error: String?)
