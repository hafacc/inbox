package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.compose.SPACE

// Port of shared/src/web-links.ts; both run shared/testdata/web-link-fixtures.json.

/** A web address in a text, from [start] up to (not including) [end], in UTF-16 units. */
data class WebLink(
    /** first index */
    val start: Int,
    /** index after the last */
    val end: Int,
    /** where it leads: the address as written, `https://` put before a bare domain, unwrapped */
    val href: String,
)

/** A piece of a text: plain, or a web address with where it leads. */
data class WebLinkPiece(
    /** the text */
    val text: String,
    /** where it leads, or null for plain text */
    val href: String?,
)

// The same list as web's, which says why some are left out.
private val TOP_LEVEL_DOMAINS = setOf(
    "com", "net", "org", "edu", "gov", "mil", "int", "info", "biz", "name", "pro", "mobi",
    "app", "dev", "io", "ai", "co", "me", "tv", "cc", "fm", "gg", "ly", "gl", "xyz",
    "online", "site", "tech", "store", "shop", "blog", "cloud", "page", "news", "art",
    "design", "email", "club", "top", "wiki", "link", "space", "website", "today", "eu",
    "asia", "us", "uk", "ca", "au", "nz", "ie", "de", "fr", "es", "it", "nl", "be", "ch",
    "at", "se", "no", "dk", "fi", "pt", "cz", "sk", "hu", "ro", "gr", "tr", "il", "za",
    "jp", "cn", "kr", "tw", "hk", "sg", "in", "br", "mx", "ar", "cl", "ru", "ua",
)

private val SCHEME = Regex("""https?://[^$SPACE<>"]+""", RegexOption.IGNORE_CASE)
private val DOMAIN = Regex("""[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+""", RegexOption.IGNORE_CASE)
private val PORT = Regex(""":\d{1,5}(?![\p{L}\p{N}])""")
private val PATH = Regex("""[/?#][^$SPACE<>"]*""")
private const val NOT_BEFORE = "@.-/+%=&#~\\"
private const val TRAILING_PUNCTUATION = "?!.,:;*_~'\""

// What may follow a bare domain's `_` that closes a style (`_example.com_`): not more of a word.
private val AFTER_CLOSING_MARKER = Regex("""_+(?![\p{L}\p{N}@])""")

// The rest of an email address's local part up to its `@` (`ana.co+news@x.com`).
private val REST_OF_ADDRESS = Regex("""[\w.%+-]*@""")

// The schemes a link may lead to; any other (`javascript:`, `data:`) is never a link.
private val LINK_SCHEMES = setOf("http", "https", "mailto")
private val ANY_SCHEME = Regex("""^([a-z][a-z0-9+.-]*):""", RegexOption.IGNORE_CASE)

/**
 * [href] trimmed and with its scheme in lower case when that scheme is http, https or mailto;
 * null for any other scheme or none, so a `javascript:` address is never linked or opened.
 */
fun safeHref(href: String): String? {
    val trimmed = href.trim()
    val scheme = ANY_SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
    return if (scheme == null || scheme !in LINK_SCHEMES) null else scheme + trimmed.substring(scheme.length)
}

// Where an http(s) [link] leads: unwrapped, its scheme in lower case; itself when what it wraps isn't a web address.
private fun leadsTo(link: String): String = safeHref(unwrapLink(link)) ?: safeHref(link) ?: link

// web's /[\p{L}\p{N}]/u tested on one UTF-16 unit.
private fun isAlphanumeric(char: Char): Boolean = char.isLetterOrDigit() || Character.getType(char).toByte().let {
    it == Character.LETTER_NUMBER || it == Character.OTHER_NUMBER
}

// Where [link] ends once its trailing punctuation and unmatched closing parentheses are left out.
private fun trimmedLength(link: String, least: Int): Int {
    var length = link.length
    while (length > least) {
        val last = link[length - 1]
        if (last in TRAILING_PUNCTUATION) {
            length--
        } else if (last == ')') {
            val kept = link.substring(0, length)
            if (kept.count { it == ')' } > kept.count { it == '(' }) length-- else return length
        } else {
            return length
        }
    }
    return length
}

// The link starting at [index] of [text], or null.
private fun linkAt(text: String, index: Int): WebLink? {
    val scheme = SCHEME.matchAt(text, index)
    if (scheme != null) {
        val prefix = scheme.value.indexOf("//") + 2
        val length = trimmedLength(scheme.value, prefix)
        return if (length == prefix) null else WebLink(index, index + length, leadsTo(scheme.value.substring(0, length)))
    }
    val host = DOMAIN.matchAt(text, index)?.value ?: return null
    if (host.substringAfterLast('.').lowercase() !in TOP_LEVEL_DOMAINS) return null
    var end = index + host.length
    val next = text.getOrNull(end)
    if (REST_OF_ADDRESS.matchAt(text, end) != null || (next != null && isAlphanumeric(next))) return null
    if (next == '_' && AFTER_CLOSING_MARKER.matchAt(text, end) == null) return null
    PORT.matchAt(text, end)?.let { end += it.value.length }
    PATH.matchAt(text, end)?.let { path -> end = index + trimmedLength(text.substring(index, end + path.value.length), end - index) }
    return WebLink(index, end, leadsTo("https://${text.substring(index, end)}"))
}

/**
 * The web addresses in [text], in order: `http://` and `https://` URLs, and bare domains ending in
 * a known top-level domain with an optional port and path (`example.com/x`, `www.example.com`).
 * One never starts inside a word, an email address or a `#tag`, and its trailing punctuation and
 * unmatched closing parentheses are left out (`see example.com.` links `example.com`).
 */
fun findWebLinks(text: String): List<WebLink> {
    val links = mutableListOf<WebLink>()
    var index = 0
    while (index < text.length) {
        val before = text.getOrNull(index - 1)
        val free = before == null || (!isAlphanumeric(before) && before !in NOT_BEFORE)
        val link = if (free && isAlphanumeric(text[index])) linkAt(text, index) else null
        if (link == null) {
            index++
        } else {
            links += link
            index = link.end
        }
    }
    return links
}

/** [text] cut into plain pieces and the web addresses between them, in order. */
fun webLinkPieces(text: String): List<WebLinkPiece> {
    val pieces = mutableListOf<WebLinkPiece>()
    var at = 0
    for (link in findWebLinks(text)) {
        if (link.start > at) pieces += WebLinkPiece(text.substring(at, link.start), null)
        pieces += WebLinkPiece(text.substring(link.start, link.end), link.href)
        at = link.end
    }
    if (at < text.length) pieces += WebLinkPiece(text.substring(at), null)
    return pieces
}

/**
 * A link's address as typed: trimmed, with `https://` put before one without a scheme
 * (`example.com`); null for any scheme but http, https and mailto (see [safeHref]).
 */
fun linkAddress(typed: String): String? {
    val address = typed.trim()
    return if (ANY_SCHEME.containsMatchIn(address)) safeHref(address) else "https://$address"
}

private val WHITESPACE = Regex("[$SPACE]")

/**
 * The web address that ends the last word of [before] (the text up to a space or line break just
 * typed), trailing punctuation aside, with where it leads as written (`https://` put before a bare
 * domain, not unwrapped); null when that word holds none or it follows an unclosed backtick.
 */
fun finishedWebLink(before: String): WebLink? {
    val link = findWebLinks(before).lastOrNull() ?: return null
    if (WHITESPACE.containsMatchIn(before.substring(link.end))) return null
    // After an unclosed backtick it is inline code being typed.
    if (before.substring(0, link.start).count { it == '`' } % 2 == 1) return null
    return linkAddress(before.substring(link.start, link.end))?.let { WebLink(link.start, link.end, it) }
}
