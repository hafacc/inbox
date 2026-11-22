package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.compose.isPlainAddress

/** A `mailto:` unsubscribe: send this message. */
data class UnsubscribeMail(
    /** recipient */
    val address: String,
    /** subject, empty when none is given */
    val subject: String,
    /** body, empty when none is given */
    val body: String,
)

/** A way to unsubscribe. */
enum class UnsubscribeMethod(
    /** the name shared fixtures and web use */
    val wire: String,
) {
    /** POST to a URL (RFC 8058) */
    ONE_CLICK("oneClick"),

    /** send a mail */
    MAILTO("mailto"),

    /** open a page in a browser */
    URL("url"),
}

/** The ways a message offers to unsubscribe, and which to use. */
data class Unsubscribe(
    /** https URL to POST [ONE_CLICK_BODY] to (RFC 8058), or null */
    val oneClickUrl: String?,
    /** mail to send, or null */
    val mailto: UnsubscribeMail?,
    /** http(s) URL to open in a browser, or null */
    val url: String?,
    /** the one to use; null when there is none */
    val preferred: UnsubscribeMethod?,
)

/** Body of the one-click POST, sent as `application/x-www-form-urlencoded`. */
const val ONE_CLICK_BODY = "List-Unsubscribe=One-Click"

private val FOLD_RE = Regex("""\r?\n[ \t]*""")
private val BRACKETED_RE = Regex("""<([^>]*)>""")
private val COMMENT_RE = Regex("""\([^)]*\)""")
private val WHITESPACE_RE = Regex("""\s+""")
private val URI_RE = Regex("""^(https?|mailto):\S+$""", RegexOption.IGNORE_CASE)
private val MAILTO_RE = Regex("""^mailto:([^?]*)(?:\?(.*))?$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** The URIs a `List-Unsubscribe` header lists, in order: `<…>` entries, else bare ones. */
fun listUnsubscribeUris(header: String): List<String> {
    val unfolded = header.replace(FOLD_RE, " ")
    val bracketed = BRACKETED_RE.findAll(unfolded).map { it.groupValues[1].replace(WHITESPACE_RE, "") }.toList()
    val uris = bracketed.ifEmpty { unfolded.replace(COMMENT_RE, " ").split(",").map { it.trim() } }
    return uris.filter { URI_RE.matches(it) }
}

/** Addresses in a decoded `mailto:` address list, trimmed, empty ones left out. */
private fun addressList(text: String): List<String> = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * A `mailto:` URI (RFC 6068) as a message to send, or null. Only the first address is used (from
 * before `?`, else from `to=`), and only when it is one plain address ([isPlainAddress]), so an
 * encoded `,` or line break can't add recipients or headers.
 */
fun parseMailto(uri: String): UnsubscribeMail? {
    val match = MAILTO_RE.find(uri.trim()) ?: return null
    val addresses = addressList(percentDecoded(match.groupValues[1])).toMutableList()
    var subject = ""
    var body = ""
    for (pair in match.groupValues[2].split("&")) {
        val equals = pair.indexOf('=')
        if (equals < 0) continue
        val name = pair.substring(0, equals).lowercase()
        val value = percentDecoded(pair.substring(equals + 1))
        when (name) {
            "subject" -> subject = value
            "body" -> body = value
            "to" -> addresses += addressList(value)
        }
    }
    val address = addresses.firstOrNull()
    return if (address == null || !isPlainAddress(address)) null else UnsubscribeMail(address, subject, body)
}

private fun isOneClick(post: String?): Boolean =
    post != null && post.replace(WHITESPACE_RE, "").lowercase() == ONE_CLICK_BODY.lowercase()

/**
 * Read `List-Unsubscribe` and `List-Unsubscribe-Post` (either may be null): one-click (the first
 * https URI, only with the exact POST header), the first http(s) URL, the first `mailto:` with an
 * address, and which to prefer, in that order.
 */
fun parseUnsubscribe(listUnsubscribe: String?, listUnsubscribePost: String?): Unsubscribe {
    val uris = if (listUnsubscribe == null) emptyList() else listUnsubscribeUris(listUnsubscribe)
    val url = uris.find { it.startsWith("http:", ignoreCase = true) || it.startsWith("https:", ignoreCase = true) }
    val https = uris.find { it.startsWith("https:", ignoreCase = true) }
    val oneClickUrl = if (isOneClick(listUnsubscribePost)) https else null
    val mailto = uris.asSequence().filter { it.startsWith("mailto:", ignoreCase = true) }.mapNotNull(::parseMailto).firstOrNull()
    val preferred = when {
        oneClickUrl != null -> UnsubscribeMethod.ONE_CLICK
        mailto != null -> UnsubscribeMethod.MAILTO
        url != null -> UnsubscribeMethod.URL
        else -> null
    }
    return Unsubscribe(oneClickUrl, mailto, url, preferred)
}
