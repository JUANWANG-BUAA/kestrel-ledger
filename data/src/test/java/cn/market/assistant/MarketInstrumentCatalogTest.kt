package cn.market.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketInstrumentCatalogTest {
    @Test
    fun onlyCatalogInstrumentsCanBeAddedToWatchlist() {
        assertTrue(MarketInstrumentCatalog.supportsWatchlist("1.000300"))
        assertFalse(MarketInstrumentCatalog.supportsWatchlist("0.000001"))
    }
}
