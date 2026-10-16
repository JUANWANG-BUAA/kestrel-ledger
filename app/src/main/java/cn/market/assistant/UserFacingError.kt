package cn.market.assistant

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * Data sources, the local database and the LLM client all throw platform exceptions whose messages
 * carry class names, host addresses and stream internals
 * (for example "unexpected end of stream on com.android.okhttp.Address@7ece453b"). None of that
 * belongs on screen, so failures are classified into short, actionable sentences here and callers pass
 * a domain-specific fallback for the cases we cannot classify.
 *
 * Local ledger validation deliberately does *not* go through this helper: those exceptions already
 * carry curated Chinese explanations that the user needs verbatim.
 */
internal fun Throwable.userFacingMessage(fallback: String): String = when (this) {
    is CancellationException -> fallback
    is UnknownHostException -> "网络不可用，请检查连接后重试"
    is SocketTimeoutException -> "请求超时，请稍后重试"
    is SSLException -> "安全连接失败，请稍后重试"
    is IOException -> "网络连接中断，请稍后重试"
    else -> fallback
}
