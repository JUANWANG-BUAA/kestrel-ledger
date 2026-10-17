package cn.market.assistant.data.assistant

/** Bounded assistant workspace so a model cannot fill the app's private storage with reports. */
internal object WorkspaceQuota {
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024
    const val MAX_FILES = 100

    fun canWrite(
        totalBytes: Long,
        fileCount: Int,
        incomingBytes: Long,
        replacingBytes: Long = 0L
    ): Boolean {
        if (incomingBytes < 0 || replacingBytes < 0 || totalBytes < 0) return false
        val nextBytes = totalBytes - replacingBytes + incomingBytes
        val nextFiles = fileCount + if (replacingBytes == 0L) 1 else 0
        return nextBytes in 0..MAX_TOTAL_BYTES && nextFiles <= MAX_FILES
    }
}
