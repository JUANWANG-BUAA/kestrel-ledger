package cn.market.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.market.assistant.domain.update.AppVersion
import cn.market.assistant.update.UpdateClient
import cn.market.assistant.update.UpdateRelease
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Update preferences live beside the theme, never inside the ledger or its backup. */
internal data class UpdateSettings(
    val repository: String = DEFAULT_REPOSITORY,
    val autoCheck: Boolean = true,
    val useMirrors: Boolean = true
) {
    companion object {
        const val DEFAULT_REPOSITORY = "JUANWANG-BUAA/kestrel-ledger"
        val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}

internal class UpdateSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("market_update_settings", Context.MODE_PRIVATE)

    fun load(): UpdateSettings = UpdateSettings(
        repository = preferences.getString(KEY_REPO, null)?.takeIf { UpdateSettings.REPO.matches(it) }
            ?: UpdateSettings.DEFAULT_REPOSITORY,
        autoCheck = preferences.getBoolean(KEY_AUTO, true),
        useMirrors = preferences.getBoolean(KEY_MIRRORS, true)
    )

    fun save(settings: UpdateSettings) {
        preferences.edit()
            .putString(KEY_REPO, settings.repository.trim())
            .putBoolean(KEY_AUTO, settings.autoCheck)
            .putBoolean(KEY_MIRRORS, settings.useMirrors)
            .apply()
    }

    private companion object {
        const val KEY_REPO = "repository"
        const val KEY_AUTO = "auto_check"
        const val KEY_MIRRORS = "use_mirrors"
    }
}

internal enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

/**
 * Update flow: check metadata, download to cache, verify size + SHA-256 and the APK signing
 * certificate, then hand the file to the system installer. It never deletes app data; a signature
 * mismatch is refused here because the package manager would otherwise require an uninstall
 * (which would wipe the encrypted ledger).
 */
@Stable
internal class UpdateFeatureState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val settingsStore: UpdateSettingsStore = UpdateSettingsStore(context),
    private val client: UpdateClient = UpdateClient()
) {
    var settings by mutableStateOf(settingsStore.load())
        private set
    var phase by mutableStateOf(UpdatePhase.IDLE)
        private set
    var release by mutableStateOf<UpdateRelease?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var signatureMatches by mutableStateOf<Boolean?>(null)
        private set
    var log by mutableStateOf<List<String>>(emptyList())
        private set

    val currentVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "未知"

    private var pendingFile: File? = null
    private var lastCheckMillis: Long = 0L

    fun updateSettings(next: UpdateSettings) {
        settings = next.copy(repository = next.repository.trim())
        settingsStore.save(settings)
    }

    fun note(text: String) {
        log = (listOf(text) + log).take(8)
    }

    fun check(auto: Boolean = false) {
        if (phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING) return
        val repo = settings.repository.trim()
        if (!UpdateSettings.REPO.matches(repo)) {
            phase = UpdatePhase.ERROR
            message = "更新源格式应为 owner/repo"
            return
        }
        lastCheckMillis = System.currentTimeMillis()
        phase = UpdatePhase.CHECKING
        message = if (auto) "正在后台检查更新…" else null
        scope.launch {
            val mirrors = if (settings.useMirrors) UpdateClient.DEFAULT_MIRRORS else emptyList()
            val result = UpdateClient(mirrors = mirrors).latestRelease(repo)
            result.onSuccess { found ->
                val newer = AppVersion.compare(found.version, currentVersion) > 0
                release = found
                signatureMatches = null
                note("检查更新：${found.version}（来源 ${found.mirrorLabel}）")
                if (newer) {
                    phase = UpdatePhase.AVAILABLE
                    message = "发现新版本 ${found.version}"
                } else {
                    phase = UpdatePhase.UP_TO_DATE
                    message = "已是最新版本 ${currentVersion}"
                }
            }.onFailure { error ->
                phase = UpdatePhase.ERROR
                val text = error.message ?: "检查更新失败"
                message = text
                note("检查更新失败：$text")
            }
        }
    }

    fun download() {
        val target = release ?: return
        if (phase == UpdatePhase.DOWNLOADING) return
        phase = UpdatePhase.DOWNLOADING
        progress = 0f
        message = "正在下载 ${target.version}…"
        scope.launch {
            val dir = File(context.cacheDir, "updates")
            val mirrors = if (settings.useMirrors) UpdateClient.DEFAULT_MIRRORS else emptyList()
            val result = UpdateClient(mirrors = mirrors).download(target, dir) { downloaded, total ->
                progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
            }
            result.onSuccess { file ->
                val matches = withContext(ioDispatcher) { sameSignature(file) }
                signatureMatches = matches
                if (matches == false) {
                    phase = UpdatePhase.ERROR
                    pendingFile = null
                    file.delete()
                    message = "安装包签名与本机不一致，已删除下载。覆盖安装会被系统拒绝，卸载重装会丢失本机账本。"
                    note("签名校验失败，已阻止安装")
                } else {
                    pendingFile = file
                    phase = UpdatePhase.READY
                    progress = 1f
                    message = "已下载并通过 SHA-256 校验"
                    note("下载完成并通过校验（${file.length() / 1024 / 1024} MB）")
                }
            }.onFailure { error ->
                phase = UpdatePhase.ERROR
                message = error.message ?: "下载失败"
                note("下载失败：${message}")
            }
        }
    }

    fun install() {
        val file = pendingFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            message = "请先允许本应用安装未知来源应用，再回来点安装"
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                message = "无法打开系统安装器：${it.message}"
            }
        note("已交给系统安装器；安装只替换程序，本机账本与缓存会保留")
    }

    fun resetMessage() {
        message = null
        if (phase == UpdatePhase.ERROR) phase = UpdatePhase.IDLE
    }

    /** Reads the downloaded APK's signing certificate and compares it with the installed app. */
    private fun sameSignature(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: return@runCatching false
        val self = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val archiveCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            archive.signatures?.map { it.toByteArray() }
        }.orEmpty()
        val selfCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            self.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            self.signatures?.map { it.toByteArray() }
        }.orEmpty()
        val certSet = selfCerts.map(::sha256).toSet()
        archiveCerts.isNotEmpty() && archiveCerts.all { sha256(it) in certSet }
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

}

@Composable
internal fun rememberUpdateFeatureState(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher): UpdateFeatureState {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        UpdateFeatureState(context, scope, ioDispatcher).also { state ->
            if (state.settings.autoCheck) {
                scope.launch {
                    // Let the ledger open and the first frame settle before touching the network.
                    kotlinx.coroutines.delay(6_000)
                    state.check(auto = true)
                }
            }
        }
    }
}
