package cn.market.assistant.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One installable release fetched from GitHub or a proxy mirror. */
data class UpdateRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val pageUrl: String,
    val mirrorLabel: String
)

sealed interface UpdateFailure {
    data class Network(val message: String) : UpdateFailure
    data object NoRelease : UpdateFailure
    data class Unverified(val message: String) : UpdateFailure
    data class Corrupt(val message: String) : UpdateFailure
}

/**
 * Talks to GitHub Releases and its proxy mirrors. It never installs anything and never touches app
 * data: it only returns metadata and a verified APK file inside the cache directory.
 */
class UpdateClient(
    private val userAgent: String = "MarketUpdater/1.0",
    private val mirrors: List<Mirror> = DEFAULT_MIRRORS,
    private val timeoutMs: Int = 20_000
) {
    data class Mirror(val label: String, val prefix: String)

    /** Latest published release, or [UpdateFailure] when the network/format is unusable. */
    suspend fun latestRelease(repository: String): Result<UpdateRelease> = withContext(Dispatchers.IO) {
        val repo = repository.trim().trim('/')
        if (!REPO.matches(repo)) return@withContext Result.failure(IOException("仓库格式应为 owner/repo"))
        val attempts = buildList {
            // Prefer mirrors when enabled: on mainland networks the official API can hang
            // before the first byte, and the fallback never gets a chance to run.
            mirrors.forEach { mirror ->
                add(mirror to "${mirror.prefix}https://api.github.com/repos/$repo/releases/latest")
            }
            add(Mirror("GitHub 官方", "") to "https://api.github.com/repos/$repo/releases/latest")
        }
        var lastError = "无法连接更新源"
        attempts.forEach { (mirror, url) ->
            try {
                val body = httpGet(url)
                val release = parseRelease(body, mirror.label)
                return@withContext Result.success(release)
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        }
        Result.failure(IOException(lastError))
    }

    /** Downloads to [destinationDir] and verifies the size and SHA-256 before returning the file. */
    suspend fun download(
        release: UpdateRelease,
        destinationDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!isAllowedApkSize(release.sizeBytes)) {
            return@withContext Result.failure(IOException("APK 大小异常，已阻止下载"))
        }
        destinationDir.mkdirs()
        val target = File(destinationDir, "market-${release.version}.apk")
        if (target.exists() && target.length() == release.sizeBytes && sha256(target) == release.sha256) {
            return@withContext Result.success(target)
        }
        val sources = downloadOrder(release.apkUrl, mirrors)
        var lastError = "下载失败"
        sources.forEach { (label, url) ->
            try {
                onProgress(0L, release.sizeBytes)
                downloadTo(url, target, release.sizeBytes, onProgress)
                if (target.length() != release.sizeBytes) {
                    lastError = "下载大小与发布信息不一致"
                    target.delete()
                } else if (!release.sha256.equals(sha256(target), ignoreCase = true)) {
                    lastError = "SHA-256 校验失败，文件已删除"
                    target.delete()
                } else {
                    return@withContext Result.success(target)
                }
            } catch (error: Exception) {
                lastError = "$label：${error.message ?: error.javaClass.simpleName}"
                target.delete()
            }
        }
        Result.failure(IOException(lastError))
    }

    private fun parseRelease(body: String, mirrorLabel: String): UpdateRelease {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { error("发布信息缺少版本号") }
        val assets = json.optJSONArray("assets") ?: error("该版本没有发布文件")
        val apk = (0 until assets.length())
            .map { assets.optJSONObject(it) }
            .firstOrNull { it?.optString("name")?.endsWith(".apk", ignoreCase = true) == true }
            ?: error("该版本没有 APK")
        val checksumAsset = (0 until assets.length())
            .map { assets.optJSONObject(it) }
            .firstOrNull { it?.optString("name")?.endsWith(".sha256", ignoreCase = true) == true }
        val declaredDigest = apk.optString("digest")
            .removePrefix("sha256:")
            .takeIf { it.length == 64 }
        val sha = declaredDigest
            ?: checksumAsset?.optString("browser_download_url")?.takeIf { it.isNotBlank() }?.let { url ->
                httpGet(url).trim().split(Regex("\\s+")).firstOrNull { it.length == 64 }
            }
            ?: error("发布文件没有 SHA-256 校验值，已阻止更新")
        return UpdateRelease(
            version = tag.removePrefix("v").removePrefix("V"),
            notes = json.optString("body").take(4_000),
            apkUrl = apk.optString("browser_download_url"),
            sha256 = sha.lowercase(),
            sizeBytes = apk.optLong("size"),
            publishedAt = json.optString("published_at"),
            pageUrl = json.optString("html_url"),
            mirrorLabel = mirrorLabel
        )
    }

    private fun downloadTo(url: String, target: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        val connection = openFollowingRedirects(url)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    val startedAt = System.currentTimeMillis()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        require(System.currentTimeMillis() - startedAt <= MAX_SOURCE_DURATION_MS) {
                            "下载源超时，尝试下一个源"
                        }
                        require(downloaded + read <= maxOf(expectedSize, 1L)) { "APK 实际大小超过发布信息" }
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val connection = openFollowingRedirects(url)
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { readUpdateText(it) } ?: ""
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = open(current)
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val next = resolveTrustedRedirect(current, connection.getHeaderField("Location"))
            connection.disconnect()
            require(next != null) { "更新重定向到了不受信任的地址" }
            current = next
        }
        error("更新重定向次数过多")
    }

    private fun open(url: String): HttpURLConnection {
        require(isTrustedUpdateUrl(url)) { "更新源不受信任或不是 HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection
    }

    companion object {
        const val MAX_APK_BYTES = 100L * 1024 * 1024

        fun isAllowedApkSize(sizeBytes: Long): Boolean = sizeBytes in 1..MAX_APK_BYTES

        private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

        /** Official first, then public accelerators; every failure falls through to the next one. */
        val DEFAULT_MIRRORS = listOf(
            Mirror("gh-proxy", "https://gh-proxy.com/"),
            Mirror("ghproxy.net", "https://ghproxy.net/"),
            Mirror("ghfast", "https://ghfast.top/")
        )

        fun mirrorDownloadUrls(apkUrl: String, mirrors: List<Mirror>): List<Pair<String, String>> =
            mirrors.map { it.label to "${it.prefix}$apkUrl" }

        /** Mirrors first keeps a mainland download from waiting for a stalled official socket. */
        fun downloadOrder(apkUrl: String, mirrors: List<Mirror>): List<Pair<String, String>> =
            mirrorDownloadUrls(apkUrl, mirrors) + ("GitHub 官方" to apkUrl)

        const val MAX_SOURCE_DURATION_MS = 30_000L

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
