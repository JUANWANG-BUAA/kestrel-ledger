package cn.market.assistant.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Persistence boundary for the single local cash ledger. */
interface AccountCashRepository {
    fun accountCashEvents(): List<AccountCashEvent>
    fun accountCashBalance(asOf: LocalDate? = null): AccountCashBalance?
    fun initializeAccountCash(openingBalance: BigDecimal, day: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai")))
    fun addExternalCashMovement(
        type: AccountCashEventType,
        amount: BigDecimal,
        memo: String = "",
        day: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    )
}
