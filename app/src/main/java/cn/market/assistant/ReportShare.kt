package cn.market.assistant

import android.content.Context
import android.content.Intent
import cn.market.assistant.domain.report.ReportProvenance

/** Shares generated local text through the system chooser; nothing is uploaded by the app itself. */
internal fun shareLocalText(context: Context, subject: String, text: String, mimeType: String = "text/plain") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, subject))
}

internal fun reportProvenance(
    context: Context,
    dataSources: List<String>,
    extraNote: String? = null,
    nowMillis: Long = System.currentTimeMillis()
): ReportProvenance {
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.1.0"
    return ReportProvenance(
        generatedAtMillis = nowMillis,
        dataSources = dataSources.ifEmpty { listOf("本机账本") },
        appVersion = version,
        extraNote = extraNote
    )
}
