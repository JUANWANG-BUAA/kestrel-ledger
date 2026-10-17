package cn.market.assistant.update

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads update metadata/checksum text with a hard cap before UTF-8 decoding. */
internal fun readUpdateText(input: InputStream, maxBytes: Int = 2 * 1024 * 1024): String = input.use { stream ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "更新元数据超过允许大小" }
        output.write(buffer, 0, read)
    }
    output.toByteArray().toString(Charsets.UTF_8)
}
