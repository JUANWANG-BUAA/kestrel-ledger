package cn.market.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.portfolio.PortfolioRiskSummary
import java.math.BigDecimal
import java.math.RoundingMode

private val RiskPanel = Color(0xFFF3F1EB)

@Composable
internal fun PortfolioRiskCard(
    summary: PortfolioRiskSummary,
    parameters: PortfolioRiskParameters,
    onSaveParameters: (BigDecimal, BigDecimal) -> Unit
) {
    var riskFreeDraft by remember(parameters.riskFreeAnnualRate, parameters.updatedAtMillis) {
        mutableStateOf(parameters.riskFreeAnnualRate.asPercentInput())
    }
    var minimumReturnDraft by remember(parameters.minimumAcceptableAnnualReturn, parameters.updatedAtMillis) {
        mutableStateOf(parameters.minimumAcceptableAnnualReturn.asPercentInput())
    }
    var parameterError by remember(parameters.updatedAtMillis) { mutableStateOf<String?>(null) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, Color(0xFFD8D8D2))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("账户风险观察", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("历史 · 252 交易日窗口", color = Muted, fontSize = 9.sp)
            }
            Text("风险参数 · ${if (parameters.updatedAtMillis == 0L) "默认" else portfolioRiskParameterTime(parameters.updatedAtMillis)}", color = Muted, fontSize = 9.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(
                    value = riskFreeDraft,
                    onValueChange = { riskFreeDraft = it.filter { char -> char.isDigit() || char == '.' || char == '-' }.take(10) },
                    modifier = Modifier.weight(1f),
                    label = { Text("无风险年利率 (%)", fontSize = 10.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
                OutlinedTextField(
                    value = minimumReturnDraft,
                    onValueChange = { minimumReturnDraft = it.filter { char -> char.isDigit() || char == '.' || char == '-' }.take(10) },
                    modifier = Modifier.weight(1f),
                    label = { Text("最低年收益 (%)", fontSize = 10.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            val riskFree = BigDecimal(riskFreeDraft).movePointLeft(2)
                            val minimumReturn = BigDecimal(minimumReturnDraft).movePointLeft(2)
                            onSaveParameters(riskFree, minimumReturn)
                        }.onSuccess { parameterError = null }
                            .onFailure { parameterError = it.message ?: "风险参数无效" }
                    },
                    shape = RoundedCornerShape(11.dp)
                ) { Text("保存并重算", fontSize = 11.sp) }
                TextButton(onClick = {
                    riskFreeDraft = "0"
                    minimumReturnDraft = "0"
                    runCatching { onSaveParameters(BigDecimal.ZERO, BigDecimal.ZERO) }
                        .onSuccess { parameterError = null }
                        .onFailure { parameterError = it.message ?: "参数重置失败" }
                }) { Text("恢复 0%", color = Muted, fontSize = 11.sp) }
            }
            parameterError?.let { Text(it, color = ErrorColor, fontSize = 10.sp, lineHeight = 14.sp) }
            summary.missingReason?.let { reason ->
                Text(
                    if (reason.startsWith("风险统计计算中")) "正在计算" else "暂不可计算",
                    color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
                Text(reason, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            } ?: run {
                Text(
                    "${summary.startDay} — ${summary.endDay} · ${summary.observationCount} 个连续有效日收益",
                    color = Muted, fontSize = 10.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    RiskMetricTile("年化波动", summary.annualizedVolatilityPct?.let { "${it.toPlainString()}%" } ?: "—", Modifier.weight(1f))
                    RiskMetricTile("Sharpe", summary.sharpeRatio?.toPlainString() ?: "—", Modifier.weight(1f))
                    RiskMetricTile("Sortino", summary.sortinoRatio?.toPlainString() ?: "—", Modifier.weight(1f))
                }
                val ratioReasons = listOfNotNull(summary.sharpeMissingReason, summary.sortinoMissingReason).distinct()
                if (ratioReasons.isNotEmpty()) Text(ratioReasons.joinToString("；"), color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
                val tailMissingReason = summary.tailMissingReason
                if (tailMissingReason != null) {
                    Text(tailMissingReason, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        RiskMetricTile(
                            "1 日 VaR 95%",
                            riskLossLabel(summary.historicalVar95Amount, summary.historicalVar95Pct),
                            Modifier.weight(1f)
                        )
                        RiskMetricTile(
                            "1 日 CVaR 95%",
                            riskLossLabel(summary.historicalCvar95Amount, summary.historicalCvar95Pct),
                            Modifier.weight(1f)
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("持仓收益相关性", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    if (summary.correlations.isEmpty()) {
                        Text(summary.correlationMissingReason ?: "共同历史样本不足", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    } else {
                        summary.correlations.forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "${pair.firstAssetName} · ${pair.secondAssetName}",
                                    Modifier.weight(1f), color = Ink, fontSize = 10.sp, maxLines = 1
                                )
                                Text("${pair.correlation.toPlainString()} · ${pair.observationCount} 日", color = Muted, fontSize = 10.sp)
                            }
                        }
                        summary.correlationMissingReason?.let {
                            Text(it, color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                        }
                    }
                }
                Text(
                    "按本地工作日取样并以 252 日年化，未接入各市场节假日日历；Sharpe 无风险率 ${parameters.riskFreeAnnualRate.asPercentInput()}%，Sortino 目标收益 ${parameters.minimumAcceptableAnnualReturn.asPercentInput()}%。相关性基于持仓单位估值收益，分红、拆分和转移日不参与。1 日 VaR/CVaR 是历史尾部损失统计，不代表最大可能损失。",
                    color = Muted, fontSize = 9.sp, lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun RiskMetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(RiskPanel, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun riskLossLabel(amount: BigDecimal?, percentage: BigDecimal?): String = when {
    amount != null && percentage != null -> "¥${amount.riskMoney()} · ${percentage.toPlainString()}%"
    percentage != null -> "${percentage.toPlainString()}%"
    else -> "—"
}

private fun BigDecimal.riskMoney(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

private fun BigDecimal.asPercentInput(): String = multiply(BigDecimal("100"))
    .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private fun portfolioRiskParameterTime(value: Long): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .format(java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.of("Asia/Shanghai")))
}.getOrDefault("时间未知")
