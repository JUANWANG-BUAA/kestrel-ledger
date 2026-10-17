package cn.market.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    @Test
    fun rendersTableAndNumericChartBlocksFromMarkdown() {
        val blocks = parseMarkdownLite(
            """
            ## 行情摘要

            | 时间 | 金价 |
            | --- | ---: |
            | 09:30 | 560.2 |
            | 10:30 | 562.1 |

            ```chart
            title=黄金盘中变化
            unit=CNY/克
            09:30,560.2
            10:30,562.1
            ```
            """.trimIndent()
        )

        assertTrue(blocks.first() is MarkdownBlock.Heading)
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("时间", "金价"), table.headers)
        assertEquals(2, table.rows.size)
        val chart = blocks.filterIsInstance<MarkdownBlock.Chart>().single()
        assertEquals("黄金盘中变化", chart.title)
        assertEquals("CNY/克", chart.unit)
        assertEquals(listOf(560.2, 562.1), chart.points.map { it.value })
    }

    @Test
    fun malformedOrInsufficientChartDataStaysVisibleAsCode() {
        val blocks = parseMarkdownLite("""
            ```chart
            title=还没有足够数据
            10:00,560.2
            ```
        """.trimIndent())

        assertTrue(blocks.single() is MarkdownBlock.Code)
    }
}
