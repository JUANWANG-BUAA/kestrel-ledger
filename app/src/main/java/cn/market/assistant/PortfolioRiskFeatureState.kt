package cn.market.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.math.BigDecimal

internal data class PortfolioRiskParameters(
    val riskFreeAnnualRate: BigDecimal = BigDecimal.ZERO,
    val minimumAcceptableAnnualReturn: BigDecimal = BigDecimal.ZERO,
    val updatedAtMillis: Long = 0L
)

@Stable
internal class PortfolioRiskFeatureState(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var parameters by mutableStateOf(readParameters())
        private set

    fun update(riskFreeAnnualRate: BigDecimal, minimumAcceptableAnnualReturn: BigDecimal) {
        require(riskFreeAnnualRate > BigDecimal.ONE.negate() && riskFreeAnnualRate <= BigDecimal.ONE) {
            "无风险年利率需大于 -100% 且不超过 100%"
        }
        require(minimumAcceptableAnnualReturn > BigDecimal.ONE.negate() &&
            minimumAcceptableAnnualReturn <= BigDecimal.ONE) {
            "最低可接受年收益需大于 -100% 且不超过 100%"
        }
        val updated = PortfolioRiskParameters(
            riskFreeAnnualRate.stripTrailingZeros(),
            minimumAcceptableAnnualReturn.stripTrailingZeros(),
            System.currentTimeMillis()
        )
        check(preferences.edit()
            .putString(KEY_RISK_FREE, updated.riskFreeAnnualRate.toPlainString())
            .putString(KEY_MINIMUM_RETURN, updated.minimumAcceptableAnnualReturn.toPlainString())
            .putLong(KEY_UPDATED_AT, updated.updatedAtMillis)
            .commit()) { "风险参数保存失败，请重试" }
        parameters = updated
    }

    private fun readParameters(): PortfolioRiskParameters {
        val riskFree = preferences.getString(KEY_RISK_FREE, null)
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?.takeIf { it > BigDecimal.ONE.negate() && it <= BigDecimal.ONE }
            ?: BigDecimal.ZERO
        val minimumReturn = preferences.getString(KEY_MINIMUM_RETURN, null)
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?.takeIf { it > BigDecimal.ONE.negate() && it <= BigDecimal.ONE }
            ?: BigDecimal.ZERO
        return PortfolioRiskParameters(riskFree, minimumReturn, preferences.getLong(KEY_UPDATED_AT, 0L))
    }

    private companion object {
        const val PREFERENCES_NAME = "market_ui_preferences"
        const val KEY_RISK_FREE = "portfolio_risk_free_annual_rate"
        const val KEY_MINIMUM_RETURN = "portfolio_risk_minimum_acceptable_return"
        const val KEY_UPDATED_AT = "portfolio_risk_parameters_updated_at"
    }
}

@Composable
internal fun rememberPortfolioRiskFeatureState(context: Context): PortfolioRiskFeatureState =
    remember(context) { PortfolioRiskFeatureState(context) }
