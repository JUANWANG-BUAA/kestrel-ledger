package cn.market.assistant.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardMemberAnalyticsTest {
    private val members = listOf(
        member("1.600519", "600519", "贵州茅台", 2.5, 2_100_000.0),
        member("0.000001", "000001", "平安银行", -1.2, 220_000.0),
        member("1.601318", "601318", "中国平安", 0.8, 950_000.0),
        member("0.300750", "300750", "宁德时代", -0.4, 1_100_000.0)
    )

    @Test
    fun sortsByChangeAndMarketCap() {
        assertEquals(
            listOf("贵州茅台", "中国平安", "宁德时代", "平安银行"),
            BoardMemberAnalytics.sortMembers(members, BoardMemberAnalytics.MemberSort.CHANGE_DESC).map { it.name }
        )
        assertEquals(
            listOf("平安银行", "宁德时代", "中国平安", "贵州茅台"),
            BoardMemberAnalytics.sortMembers(members, BoardMemberAnalytics.MemberSort.CHANGE_ASC).map { it.name }
        )
        assertEquals(
            listOf("贵州茅台", "宁德时代", "中国平安", "平安银行"),
            BoardMemberAnalytics.sortMembers(members, BoardMemberAnalytics.MemberSort.MARKET_CAP_DESC).map { it.name }
        )
    }

    @Test
    fun concentrationUsesTheKnownMarketCapOnly() {
        // 前二市值 3,200,000 / 总 4,370,000 = 73.23%
        assertEquals(73.23, BoardMemberAnalytics.concentrationTopN(members, 2)!!, 0.01)
        assertEquals(100.0, BoardMemberAnalytics.concentrationTopN(members, 10)!!, 1e-9)
        assertNull(BoardMemberAnalytics.concentrationTopN(members, 0))
        assertNull(BoardMemberAnalytics.concentrationTopN(listOf(member("1.1", "1", "甲", 1.0, null))))
    }

    @Test
    fun advanceDeclineIgnoresFlatMembers() {
        val withFlat = members + member("1.2", "2", "乙", 0.0, 10.0)

        assertEquals(2 to 2, BoardMemberAnalytics.advanceDecline(members))
        assertEquals(2 to 2, BoardMemberAnalytics.advanceDecline(withFlat))
    }

    @Test
    fun truncatedSnapshotIsFlagged() {
        val snapshot = MarketBoardMemberSnapshot(
            boardId = "90.BK0475",
            boardName = "半导体",
            members = members,
            totalCount = 120,
            fetchedAt = 1_763_000_000,
            source = "eastmoney-board"
        )

        assertTrue(snapshot.isTruncated)
        assertFalse(snapshot.copy(totalCount = 4).isTruncated)
    }

    private fun member(secid: String, code: String, name: String, changePct: Double, marketCap: Double?) =
        MarketBoardMember(
            secid = secid,
            code = code,
            name = name,
            price = 10.0,
            changePct = changePct,
            turnoverRatePct = 1.0,
            marketCap = marketCap,
            quoteTime = 1_763_000_000,
            fetchedAt = 1_763_000_000,
            source = "eastmoney-board"
        )
}
