package cn.market.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioQuotePresentationTest {
    @Test
    fun labelsAutomaticFundNavQuotes() {
        assertEquals("基金净值", portfolioQuoteSourceLabel("FUND_NAV"))
    }
}
