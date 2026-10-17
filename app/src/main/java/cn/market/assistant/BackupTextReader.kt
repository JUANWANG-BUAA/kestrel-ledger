package cn.market.assistant

import cn.market.assistant.backup.EncryptedBackup
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads an imported backup with a hard cap before UTF-8 decoding, so a huge file cannot exhaust memory. */
internal fun readBackupText(
    inputStream: InputStream,
    maxBytes: Int = EncryptedBackup.MAX_FILE_BYTES
): String = inputStream.use { input ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) break
            require(totalBytes < maxBytes) { "备份文件超过允许大小" }
            output.write(byte)
            totalBytes++
            continue
        }
        require(count <= maxBytes - totalBytes) { "备份文件超过允许大小" }
        output.write(buffer, 0, count)
        totalBytes += count
    }
    String(output.toByteArray(), Charsets.UTF_8)
}
