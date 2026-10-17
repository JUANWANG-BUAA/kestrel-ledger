package cn.market.assistant.data.fund

import org.junit.Assert.assertEquals
import org.junit.Test

class FundCatalogSearchTest {
    private val entries = listOf(
        FundCatalogEntry("161725", "ZSZZBJZSLOFA", "招商中证白酒指数(LOF)A", "指数型-股票", "ZHAOSHANGZHONGZHENGBAIJIUZHISHULOFA"),
        FundCatalogEntry("110011", "YFDLC", "易方达蓝筹精选混合", "混合型-偏股", "YIFANGDALANCHUJINGXUANHUNHE"),
        FundCatalogEntry("012345", "YFDLC2", "易方达蓝筹成长混合", "混合型-偏股", "YIFANGDALANCHUCHENGZHANGHUNHE"),
        FundCatalogEntry("400001", "DFLHH", "东方龙混合", "混合型-偏股", "DONGFANGLONGHUNHE"),
        FundCatalogEntry("400003", "DFJXHH", "东方精选混合", "混合型-偏股", "DONGFANGJINGXUANHUNHE"),
        FundCatalogEntry("400005", "DFJZB", "东方金账簿货币", "货币型", "DONGFANGJINZHANGBUHUOBI")
    )

    @Test
    fun exactCodeRanksBeforePrefixMatches() {
        val results = FundCatalogSearch.search(entries, "161725", fetchedAt = 10, source = "catalog")

        assertEquals(listOf("161725"), results.map { it.code })
    }

    @Test
    fun searchesChineseNamesPinyinAbbreviationAndFullPinyin() {
        assertEquals(listOf("012345", "110011"), FundCatalogSearch.search(entries, "易方达", 10, "catalog").map { it.code })
        assertEquals(listOf("110011", "012345"), FundCatalogSearch.search(entries, "yfdlc", 10, "catalog").map { it.code })
        assertEquals(listOf("161725"), FundCatalogSearch.search(entries, "zhaoshang", 10, "catalog").map { it.code })
    }

    @Test
    fun chineseNameSearchReturnsEveryMatchingCatalogEntry() {
        val results = FundCatalogSearch.search(entries, "东方", 10, "catalog")

        assertEquals(listOf("400001", "400003", "400005"), results.map { it.code })
    }

    @Test
    fun keepsTheResultSetBoundedAndPreservesSourceMetadata() {
        val results = FundCatalogSearch.search(
            entries = entries,
            query = "混合",
            fetchedAt = 42,
            source = "eastmoney-fund-catalog",
            maxResults = 1
        )

        assertEquals(1, results.size)
        assertEquals("eastmoney-fund-catalog", results.single().source)
        assertEquals(42L, results.single().fetchedAt)
        assertEquals("混合型-偏股", results.single().category)
    }
}
