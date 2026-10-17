package cn.market.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotWritePolicyTest {
    @Test
    fun newerObservedSnapshotWins() {
        assertTrue(shouldReplaceSnapshot("2026-10-18T01:00:00Z", true, "2026-10-18T02:00:00Z"))
    }

    @Test
    fun olderObservedSnapshotCannotOverwriteNewer() {
        assertFalse(shouldReplaceSnapshot("2026-10-18T02:00:00Z", true, "2026-10-18T01:00:00Z"))
    }

    @Test
    fun invalidSnapshotMayBeRepaired() {
        assertTrue(shouldReplaceSnapshot("2026-10-18T02:00:00Z", false, "2026-10-18T01:00:00Z"))
    }

    @Test
    fun olderQuoteCannotOverwriteNewer() {
        assertFalse(shouldReplaceQuote(2_000L, 1_000L))
        assertTrue(shouldReplaceQuote(1_000L, 2_000L))
        assertTrue(shouldReplaceQuote(null, 1_000L))
    }
}
