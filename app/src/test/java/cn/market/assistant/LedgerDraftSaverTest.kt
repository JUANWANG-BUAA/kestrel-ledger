package cn.market.assistant

import androidx.compose.runtime.saveable.SaverScope
import cn.market.assistant.domain.portfolio.AccountCashEventType
import cn.market.assistant.domain.portfolio.AssetInfo
import cn.market.assistant.domain.portfolio.AssetKind
import cn.market.assistant.domain.portfolio.TradeSide
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerDraftSaverTest {
    @Test
    fun roundTripsHalfFilledFormFields() {
        val draft = LedgerDraft()
        draft.selected.value = AssetInfo("005827", "易方达蓝筹", AssetKind.FUND, "混合型")
        draft.side.value = TradeSide.SELL
        draft.amount.value = "100"
        draft.units.value = "12.5"
        draft.fee.value = "0.5"
        draft.note.value = "备注 测试"
        draft.cashEventType.value = AccountCashEventType.WITHDRAWAL
        draft.tradeDay.value = LocalDate.parse("2026-10-10")
        draft.message.value = "待填写净值"

        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(LedgerDraft.Saver) { with(scope) { save(draft) } }
        val restored = requireNotNull(LedgerDraft.Saver.restore(requireNotNull(saved)))

        assertEquals("005827", restored.selected.value?.symbol)
        assertEquals("易方达蓝筹", restored.selected.value?.name)
        assertEquals(TradeSide.SELL, restored.side.value)
        assertEquals("100", restored.amount.value)
        assertEquals("12.5", restored.units.value)
        assertEquals("0.5", restored.fee.value)
        assertEquals("备注 测试", restored.note.value)
        assertEquals(AccountCashEventType.WITHDRAWAL, restored.cashEventType.value)
        assertEquals(LocalDate.parse("2026-10-10"), restored.tradeDay.value)
        assertEquals("待填写净值", restored.message.value)
        assertTrue(!restored.saving.value)
    }
}
