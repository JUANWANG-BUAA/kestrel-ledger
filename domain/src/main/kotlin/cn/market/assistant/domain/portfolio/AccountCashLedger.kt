package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate

enum class AccountCashEventType {
    OPENING_BALANCE,
    DEPOSIT,
    WITHDRAWAL,
    TRADE_BUY,
    TRADE_SELL
}

/** A single cash journal entry. Amounts are always stored as positive values. */
data class AccountCashEvent(
    val id: Long,
    val day: LocalDate,
    val type: AccountCashEventType,
    val amount: BigDecimal,
    val memo: String = "",
    val transactionId: Long? = null,
    val occurredAt: String? = null,
    val portfolioValueBefore: BigDecimal? = null,
    val valuationComplete: Boolean = false
) {
    init {
        require(amount >= BigDecimal.ZERO) { "现金流水金额不能为负" }
        require(type == AccountCashEventType.OPENING_BALANCE || amount > BigDecimal.ZERO) { "现金流水金额必须大于零" }
        require((type == AccountCashEventType.TRADE_BUY || type == AccountCashEventType.TRADE_SELL) == (transactionId != null)) {
            "交易现金流水必须关联一笔交易"
        }
        require(portfolioValueBefore?.let { it >= BigDecimal.ZERO } != false) { "资金流前估值不能小于零" }
        require(!valuationComplete || (occurredAt != null && portfolioValueBefore != null)) { "完整资金流估值必须有时间和金额" }
    }

    /** Change to the account's cash balance. */
    val balanceDelta: BigDecimal get() = when (type) {
        AccountCashEventType.OPENING_BALANCE, AccountCashEventType.DEPOSIT, AccountCashEventType.TRADE_SELL -> amount
        AccountCashEventType.WITHDRAWAL, AccountCashEventType.TRADE_BUY -> amount.negate()
    }

    /** Investor-perspective flow for XIRR. Internal trades never count as external flows. */
    val investorCashFlow: BigDecimal? get() = when (type) {
        AccountCashEventType.OPENING_BALANCE, AccountCashEventType.DEPOSIT -> amount.negate()
        AccountCashEventType.WITHDRAWAL -> amount
        AccountCashEventType.TRADE_BUY, AccountCashEventType.TRADE_SELL -> null
    }
}

data class AccountCashBalance(val amount: BigDecimal, val trackingStart: LocalDate)

object AccountCashLedger {
    /** Returns null until a user establishes an explicit opening cash balance. */
    fun balance(events: List<AccountCashEvent>, asOf: LocalDate? = null): AccountCashBalance? {
        val opening = events.filter { it.type == AccountCashEventType.OPENING_BALANCE }
        require(opening.size <= 1) { "现金账本只能有一笔期初余额" }
        val start = opening.singleOrNull() ?: return null
        if (asOf != null && asOf.isBefore(start.day)) return null
        var balance = start.amount
        events.asSequence().filter { it.id != start.id && !it.day.isBefore(start.day) && (asOf == null || !it.day.isAfter(asOf)) }
            .sortedWith(compareBy<AccountCashEvent> { it.day }.thenBy { it.id })
            .forEach { event ->
                balance += event.balanceDelta
                require(balance >= BigDecimal.ZERO) { "现金流水导致余额为负" }
            }
        return AccountCashBalance(balance, start.day)
    }

    fun externalXirrFlows(events: List<AccountCashEvent>): List<CashFlow> = events.mapNotNull { event ->
        event.investorCashFlow?.let { CashFlow(event.day, it) }
    }
}
