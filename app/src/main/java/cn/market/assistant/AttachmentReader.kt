package cn.market.assistant

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads UTF-8 text with a hard byte cap.
 *
 * `InputStream.readNBytes` is API 33; the app supports API 26, so the attachment path must not use it.
 * Returning null when the stream exceeds the cap keeps the caller's "attachment rejected" behavior
 * deterministic without first materializing an unbounded string.
 */
internal fun readUtf8Limited(input: InputStream, maxBytes: Int): String? {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) {
            val single = input.read()
            if (single < 0) break
            if (total >= maxBytes) return null
            output.write(single)
            total++
            continue
        }
        if (total > maxBytes - read) return null
        output.write(buffer, 0, read)
        total += read
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}
