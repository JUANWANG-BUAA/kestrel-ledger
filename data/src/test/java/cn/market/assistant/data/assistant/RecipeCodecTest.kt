package cn.market.assistant.data.assistant

import cn.market.assistant.domain.assistant.RecipeOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeCodecTest {
    @Test
    fun roundTripsToolAndWriteSteps() {
        val json = """
            {"version":1,"id":"gold-weekly","name":"黄金周复盘","summary":"每周一次",
             "steps":[
               {"op":"tool","tool":"market.chart","args":{"symbol":"gold.domestic","days":"30"}},
               {"op":"write","path":"reports/gold.md"}
             ]}
        """.trimIndent()
        val recipe = requireNotNull(RecipeCodec.decode(json))
        assertEquals("gold-weekly", recipe.id)
        assertEquals(2, recipe.steps.size)
        assertEquals(RecipeOp.TOOL, recipe.steps[0].op)
        assertEquals("gold.domestic", recipe.steps[0].args["symbol"])
        assertEquals("reports/gold.md", recipe.steps[1].path)
        val again = RecipeCodec.decode(RecipeCodec.encode(recipe))
        assertEquals(recipe, again)
    }

    @Test
    fun rejectsUnsafeOrUnknownSpecs() {
        assertNull(RecipeCodec.decode("""{"id":"x","name":"y","steps":[{"op":"write","path":"../x.md"}]}"""))
        assertNull(RecipeCodec.decode("""{"id":"x","name":"y","steps":[{"op":"tool","tool":"recipe.run"}]}"""))
        assertNull(RecipeCodec.decode("""{"id":"x","name":"y","steps":[{"op":"shell","cmd":"rm"}]}"""))
        assertNull(RecipeCodec.decode("""{"version":2,"id":"x","name":"y","steps":[]}"""))
        assertNull(RecipeCodec.decode("not json"))
    }
}
