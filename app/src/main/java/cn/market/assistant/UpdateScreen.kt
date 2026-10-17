package cn.market.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.update.UpdateRelease

/**
 * About + update screen. The copy is explicit that installing a new APK replaces only the program:
 * the encrypted ledger, preferences and cache stay in app storage, and a mismatched signing key is
 * refused before the system installer is opened.
 */
@Composable
internal fun UpdateScreen(
    state: UpdateFeatureState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = MarketNavBarClearance),
        verticalArrangement = Arrangement.spacedBy(geometry.md)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            MarketBackButton(label = "总览", onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("关于与更新", color = palette.ink, style = MarketType.title)
                Text("版本、更新源与安装校验", color = palette.muted, style = MarketType.caption)
            }
            Box(
                Modifier.clip(RoundedCornerShape(geometry.chip))
                    .background(palette.accentSoft)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("v${state.currentVersion}", color = palette.accent, style = MarketType.label)
            }
        }

        MarketCard {
            MarketSectionHeader("当前版本", caption = "数据库 v25 · 加密备份 v7 · 全部数据在本机")
            Text(
                "更新只会替换程序文件，本机账本、行情缓存与偏好都会保留。安装包必须使用与本机相同的签名，" +
                    "否则系统会要求卸载重装——那会清空数据，因此应用会在下载后先校验签名并拒绝不一致的包。",
                color = palette.muted, style = MarketType.caption, lineHeight = 16.sp
            )
        }

        MarketCard {
            MarketSectionHeader("更新源", caption = "GitHub Releases；官方不通时自动尝试加速镜像")
            var repository = state.settings.repository
            OutlinedTextField(
                value = repository,
                onValueChange = {
                    repository = it.trim().take(80)
                    if (UpdateSettings.REPO.matches(repository)) {
                        state.updateSettings(state.settings.copy(repository = repository))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("仓库 owner/repo", style = MarketType.caption) },
                placeholder = { Text("例如 owner/market", style = MarketType.caption) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(geometry.card),
                colors = fieldColors()
            )
            if (!UpdateSettings.REPO.matches(state.settings.repository)) {
                Text("仓库格式应为 owner/repo，修改后才会用于检查。", color = palette.warn, style = MarketType.caption)
            }
            UpdateSwitch(
                title = "启动时检查更新",
                detail = "只读发布元数据，不自动下载",
                checked = state.settings.autoCheck
            ) { state.updateSettings(state.settings.copy(autoCheck = it)) }
            UpdateSwitch(
                title = "使用代理镜像",
                detail = "官方源失败时按 gh-proxy → ghproxy.net → ghfast 依次重试",
                checked = state.settings.useMirrors
            ) { state.updateSettings(state.settings.copy(useMirrors = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { state.check() },
                    enabled = state.phase != UpdatePhase.CHECKING && state.phase != UpdatePhase.DOWNLOADING,
                    shape = RoundedCornerShape(geometry.chip),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(
                        when (state.phase) {
                            UpdatePhase.CHECKING -> "检查中…"
                            else -> "检查更新"
                        },
                        style = MarketType.label, fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = { state.resetMessage() }) { Text("清除提示", color = palette.muted, style = MarketType.label) }
            }
        }

        state.release?.let { release -> ReleaseCard(state, release) }

        if (state.log.isNotEmpty()) {
            MarketCard {
                MarketSectionHeader("更新记录", caption = "最近 ${state.log.size} 条")
                state.log.forEach { line ->
                    Text("· $line", color = palette.muted, style = MarketType.caption, lineHeight = 15.sp)
                }
            }
        }

        MarketCard {
            MarketSectionHeader("关于", caption = "养基助手 · 本机优先")
            Text(
                "不提供投资建议、不做交易通道、不上传持仓。行情来自公开接口，可能延迟或缺失；" +
                    "估值均为估算并标注来源与时点。",
                color = palette.muted, style = MarketType.caption, lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun ReleaseCard(state: UpdateFeatureState, release: UpdateRelease) {
    val palette = MarketTheme.colors
    val geometry = MarketTheme.geometry
    MarketCard {
        MarketSectionHeader(
            "v${release.version}",
            caption = listOfNotNull(
                release.publishedAt.take(10).takeIf { it.isNotBlank() },
                "来源 ${release.mirrorLabel}",
                release.sizeBytes.takeIf { it > 0 }?.let { "${it / 1024 / 1024} MB" }
            ).joinToString(" · ")
        )
        if (release.notes.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(geometry.chip))
                    .background(palette.surfaceAlt)
                    .padding(geometry.md)
            ) {
                Text(
                    release.notes,
                    color = palette.inkSoft,
                    style = MarketType.caption,
                    lineHeight = 16.sp,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when (state.phase) {
            UpdatePhase.DOWNLOADING -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = palette.accent,
                    trackColor = palette.surfaceAlt
                )
                Text(
                    "下载 ${(state.progress * 100).toInt()}%",
                    color = palette.muted, style = MarketType.caption
                )
            }
            UpdatePhase.READY -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(palette.down))
                    Text(
                        when (state.signatureMatches) {
                            true -> "SHA-256 与签名校验通过"
                            false -> "签名不一致"
                            null -> "待校验"
                        },
                        color = palette.down, style = MarketType.caption, fontWeight = FontWeight.Medium
                    )
                }
                Button(
                    onClick = { state.install() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(geometry.chip),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) { Text("安装 v${release.version}", style = MarketType.label, fontWeight = FontWeight.SemiBold) }
                Text(
                    "系统安装器会要求确认；安装不会清除本机账本与缓存，但请先保留一份加密备份。",
                    color = palette.muted, style = MarketType.caption, lineHeight = 15.sp
                )
            }
            else -> {
                Button(
                    onClick = { state.download() },
                    enabled = state.phase == UpdatePhase.AVAILABLE || state.phase == UpdatePhase.ERROR,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(geometry.chip),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) { Text("下载并校验", style = MarketType.label, fontWeight = FontWeight.SemiBold) }
            }
        }
        state.message?.let { text ->
            Text(
                text,
                color = if (state.phase == UpdatePhase.ERROR) palette.up else palette.muted,
                style = MarketType.caption,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun UpdateSwitch(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MarketTheme.geometry.chip))
            .background(MarketTheme.colors.surfaceAlt)
            .clickable { onCheckedChange(!checked) }
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MarketTheme.colors.ink, style = MarketType.label, fontWeight = FontWeight.Medium)
            Text(detail, color = MarketTheme.colors.muted, style = MarketType.caption, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
