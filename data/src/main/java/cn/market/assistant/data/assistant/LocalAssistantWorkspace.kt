package cn.market.assistant.data.assistant

import android.content.Context
import java.io.File

/**
 * The assistant's tiny private workspace: Markdown reports only, inside app storage, bounded in size
 * and path. It is deliberately not a general file API; the ledger and cache stay untouched.
 */
class LocalAssistantWorkspace(context: Context) {
    private val root = File(context.applicationContext.filesDir, ROOT)

    /** Writes one report and returns the relative path. Rejects traversal, symlinks and oversize text. */
    fun write(relativePath: String, content: String): String {
        require(PATH.matches(relativePath)) { "报告路径必须是 reports/*.md" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "报告超过 ${MAX_BYTES / 1024} KB" }
        val target = File(root, relativePath)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) { "报告路径越界" }
        require(!java.nio.file.Files.isSymbolicLink(target.toPath())) { "不支持符号链接" }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return relativePath
    }

    fun read(relativePath: String): String? = runCatching {
        require(PATH.matches(relativePath))
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator))
        if (file.isFile) file.readText() else null
    }.getOrNull()

    fun list(): List<String> = runCatching {
        root.walkTopDown().filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .take(200)
            .toList()
    }.getOrDefault(emptyList())

    fun usageBytes(): Long = runCatching {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private companion object {
        const val ROOT = "assistant-workspace"
        const val MAX_BYTES = 256 * 1024
        val PATH = Regex(
            "(reports/[a-z0-9][a-z0-9_./-]{0,60}\\.md)|(data/[a-z0-9][a-z0-9_.-]{0,60}\\.(csv|json|md))"
        )
    }
}
