package cn.market.assistant.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class AccountCashLedgerTest {
    @Test fun `cash balance includes internal trades but XIRR flows only include outside money`() {
        val events = listOf(
            event(1, "2026-01-01", AccountCashEventType.OPENING_BALANCE, "1000"),
            event(2, "2026-01-03", AccountCashEventType.DEPOSIT, "500"),
            event(3, "2026-01-04", AccountCashEventType.TRADE_BUY, "800", transactionId = 11),
            event(4, "2026-01-06", AccountCashEventType.TRADE_SELL, "200", transactionId = 12),
            event(5, "2026-01-08", AccountCashEventType.WITHDRAWAL, "100")
        )

        val balance = AccountCashLedger.balance(events)
        assertEquals(BigDecimal("800"), balance?.amount)
        assertEquals(LocalDate.parse("2026-01-01"), balance?.trackingStart)
        assertEquals(listOf("-1000", "-500", "100"), AccountCashLedger.externalXirrFlows(events).map { it.amount.toPlainString() })
    }

    @Test fun `cash balance stays unavailable until opening balance is explicit`() {
        assertNull(AccountCashLedger.balance(listOf(event(1, "2026-01-01", AccountCashEventType.DEPOSIT, "20"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative balance is rejected`() {
        AccountCashLedger.balance(listOf(
            event(1, "2026-01-01", AccountCashEventType.OPENING_BALANCE, "50"),
            event(2, "2026-01-02", AccountCashEventType.WITHDRAWAL, "60")
        ))
    }

    private fun event(
        id: Long,
        day: String,
        type: AccountCashEventType,
        amount: String,
        transactionId: Long? = null
    ) = AccountCashEvent(id, LocalDate.parse(day), type, BigDecimal(amount), transactionId = transactionId)
}
