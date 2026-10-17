package cn.market.assistant.update

import java.net.URI

private val TRUSTED_UPDATE_HOSTS = setOf(
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "raw.githubusercontent.com",
    "gh-proxy.com",
    "ghproxy.net",
    "ghfast.top"
)

/** Update traffic may only start at, or redirect to, this small set of HTTPS release hosts. */
internal fun isTrustedUpdateUrl(raw: String): Boolean = runCatching {
    val uri = URI(raw)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.host?.lowercase() in TRUSTED_UPDATE_HOSTS
}.getOrDefault(false)

internal fun resolveTrustedRedirect(baseUrl: String, location: String?): String? {
    if (location.isNullOrBlank()) return null
    return runCatching {
        URI(baseUrl).resolve(location).toString().takeIf(::isTrustedUpdateUrl)
    }.getOrNull()
}
