package cn.market.assistant.domain.assistant

import cn.market.assistant.domain.portfolio.AccountCashBalance

/** Converts ledger values into sourced facts for future read-only assistant tools. */
object AccountCashFactMapper {
    fun facts(balance: AccountCashBalance, observedAt: String, source: String = "本地现金账本"): List<AssistantFact> = listOf(
        AssistantFact(
            key = "account_cash_balance",
            value = balance.amount.toPlainString(),
            unit = "CNY",
            source = source,
            observedAt = observedAt,
            sensitivity = FactSensitivity.PRIVATE_DETAIL
        ),
        AssistantFact(
            key = "account_cash_tracking_start",
            value = balance.trackingStart.toString(),
            source = source,
            observedAt = observedAt,
            sensitivity = FactSensitivity.PRIVATE_DETAIL
        )
    )
}
