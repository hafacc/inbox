package cc.hafa.inbox.core.mail

/** Most wrappers unwrapped from one link (wrappers can be nested). */
const val MAX_UNWRAP_DEPTH = 5

// Just enough of WHATWG URL parsing for the wrappers: scheme, lower-cased host, path and query.
private class ParsedUrl(val scheme: String, val host: String, val path: String, val query: String) {
    fun param(name: String): String? = query.split("&").firstNotNullOfOrNull { pair ->
        val equals = pair.indexOf('=')
        val key = if (equals < 0) pair else pair.substring(0, equals)
        val value = if (equals < 0) "" else pair.substring(equals + 1)
        if (formDecoded(key) == name) formDecoded(value) else null
    }

    fun firstParam(vararg names: String): String? = names.firstNotNullOfOrNull { param(it)?.ifEmpty { null } }
}

private val SCHEME_RE = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):(.*)$""", RegexOption.DOT_MATCHES_ALL)
private val AUTHORITY_RE = Regex("""^[/\\]{2}([^/\\?#]*)([^?#]*)(?:\?([^#]*))?(?:#.*)?$""", RegexOption.DOT_MATCHES_ALL)
private val PORT_RE = Regex(""":\d*$""")
private val GOOGLE_RE = Regex("""^(www\.)?google\.com$""")
private val FACEBOOK_RE = Regex("""^(l|lm)\.facebook\.com$""")
private val YOUTUBE_RE = Regex("""^(www\.|m\.)?youtube\.com$""")
private val LINKEDIN_RE = Regex("""^(www\.)?linkedin\.com$""")
private val PROOFPOINT_V3_RE = Regex("""^https://urldefense\.com/v3/__(.+?)__;""", RegexOption.IGNORE_CASE)

private fun formDecoded(text: String): String = percentDecoded(text.replace('+', ' '), lossy = true)

private fun parsed(text: String): ParsedUrl? {
    val match = SCHEME_RE.find(text) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val rest = match.groupValues[2]
    if (scheme != "http" && scheme != "https") return ParsedUrl(scheme, "", rest, "")
    val parts = AUTHORITY_RE.find(rest) ?: return null
    val host = parts.groupValues[1].substringAfterLast('@').replace(PORT_RE, "").lowercase()
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    return ParsedUrl(scheme, host, parts.groupValues[2].ifEmpty { "/" }, parts.groupValues[3])
}

private fun proofpointV2(url: ParsedUrl): String? {
    val value = url.param("u")?.ifEmpty { null } ?: return null
    return strictPercentDecoded(value.replace('-', '%').replace('_', '/'))
}

private fun proofpointV3(text: String): String? {
    val target = PROOFPOINT_V3_RE.find(text)?.groupValues?.get(1) ?: return null
    return if (target.contains('*')) null else target
}

private fun wrappedTarget(url: ParsedUrl, text: String): String? = when {
    GOOGLE_RE.matches(url.host) && url.path == "/url" -> url.firstParam("q", "url")
    url.host.endsWith(".safelinks.protection.outlook.com") -> url.firstParam("url")
    url.host == "urldefense.proofpoint.com" && url.path == "/v2/url" -> proofpointV2(url)
    url.host == "urldefense.com" && url.path.startsWith("/v3/__") -> proofpointV3(text)
    FACEBOOK_RE.matches(url.host) && url.path == "/l.php" -> url.firstParam("u")
    YOUTUBE_RE.matches(url.host) && url.path == "/redirect" -> url.firstParam("q")
    url.host == "slack-redir.net" && url.path == "/link" -> url.firstParam("url")
    LINKEDIN_RE.matches(url.host) && url.path == "/redir/redirect" -> url.firstParam("url")
    else -> null
}

/**
 * The link a click-tracking wrapper leads to (Google, Outlook Safe Links, Proofpoint v2/v3,
 * Facebook, YouTube, Slack, LinkedIn), nested up to [MAX_UNWRAP_DEPTH]; anything else unchanged.
 */
fun unwrapLink(link: String): String {
    var current = link.trim()
    repeat(MAX_UNWRAP_DEPTH) {
        val url = parsed(current)
        if (url == null || (url.scheme != "http" && url.scheme != "https")) return current
        val target = wrappedTarget(url, current)?.trim() ?: return current
        val next = parsed(target)
        if (next == null || next.scheme !in setOf("http", "https", "mailto")) return current
        current = target
    }
    return current
}
