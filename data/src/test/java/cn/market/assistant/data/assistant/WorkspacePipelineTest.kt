package cn.market.assistant.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePipelineTest {
    private val csv = """
        kind,name,value
        FUND,a,10
        FUND,b,20
        GOLD,c,5
    """.trimIndent()

    @Test
    fun groupsSortsAndLimitsRows() {
        val spec = WorkspacePipeline.parse(
            """{"steps":[
                {"op":"group","by":["kind"],"sum":["value"]},
                {"op":"sort","field":"value_sum","desc":true},
                {"op":"limit","n":1}
            ],"limit":10}"""
        ).getOrThrow()
        val rows = WorkspacePipeline.run(spec, csv).getOrThrow()
        assertEquals(1, rows.size)
        assertEquals("FUND", rows[0]["kind"])
        assertEquals("30", rows[0]["value_sum"])
        assertEquals("2", rows[0]["count"])
    }

    @Test
    fun filtersAndSelectsWithoutExecutingCode() {
        val spec = WorkspacePipeline.parse(
            """{"steps":[
                {"op":"filter","field":"value","op_":"gt","value":"15"},
                {"op":"select","fields":["name","value"]}
            ]}"""
        ).getOrThrow()
        val rows = WorkspacePipeline.run(spec, csv).getOrThrow()
        assertEquals(listOf("b"), rows.map { it["name"] })
        assertTrue(rows.all { it.keys == setOf("name", "value") })
    }

    @Test
    fun rejectsUnknownStepsAndFields() {
        assertTrue(WorkspacePipeline.parse("""{"steps":[{"op":"shell","cmd":"rm"}]}""").isFailure)
        val spec = WorkspacePipeline.parse("""{"steps":[{"op":"filter","field":"nope","value":"1"}]}""").getOrThrow()
        assertTrue(WorkspacePipeline.run(spec, csv).isFailure)
    }
}
