package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.parseAddressList
import cc.hafa.inbox.core.mail.percentDecoded

/** What another app asks a new message to start with: a `mailto:` link's fields, or a share's. */
data class ComposePrefill(
    /** `To` */
    val to: List<Person> = emptyList(),
    /** `Cc` */
    val cc: List<Person> = emptyList(),
    /** `Bcc` */
    val bcc: List<Person> = emptyList(),
    /** subject, on one line */
    val subject: String = "",
    /** body text */
    val body: String = "",
)

private val MAILTO_PREFIX_RE = Regex("""^mailto:""", RegexOption.IGNORE_CASE)
private val LINE_BREAKS_RE = Regex("""[\r\n]+""")

/** The deliverable addresses in [lists] (each an address header value), once each, in order. */
fun prefillAddresses(lists: List<String>): List<Person> =
    lists.flatMap(::parseAddressList)
        .filter { isPlainAddress(it.email) && isEmailAddress(it.email) }
        .distinctBy { addressKey(it.email) }

/**
 * A `mailto:` URI (RFC 6068) as a new message's start, or null when [uri] isn't one. The addresses
 * before `?` and in `to=` go to To, `cc=` and `bcc=` to Cc and Bcc; `subject=` and `body=` fill
 * those. Addresses that aren't deliverable are dropped, and the subject is kept to one line.
 */
fun parseMailtoPrefill(uri: String): ComposePrefill? {
    val trimmed = uri.trim()
    if (!MAILTO_PREFIX_RE.containsMatchIn(trimmed)) return null
    val rest = trimmed.replaceFirst(MAILTO_PREFIX_RE, "")
    val query = rest.indexOf('?')
    val path = if (query < 0) rest else rest.substring(0, query)
    val to = mutableListOf(percentDecoded(path, lossy = true))
    val cc = mutableListOf<String>()
    val bcc = mutableListOf<String>()
    var subject = ""
    var body = ""
    val pairs = if (query < 0) emptyList() else rest.substring(query + 1).split("&")
    for (pair in pairs) {
        val equals = pair.indexOf('=')
        if (equals < 0) continue
        val value = percentDecoded(pair.substring(equals + 1), lossy = true)
        when (pair.substring(0, equals).lowercase()) {
            "to" -> to += value
            "cc" -> cc += value
            "bcc" -> bcc += value
            "subject" -> subject = value
            "body" -> body = value
        }
    }
    return ComposePrefill(
        to = prefillAddresses(to),
        cc = prefillAddresses(cc),
        bcc = prefillAddresses(bcc),
        subject = oneLine(subject),
        body = body,
    )
}

/** [text] with its line breaks turned to spaces, trimmed. */
private fun oneLine(text: String): String = text.replace(LINE_BREAKS_RE, " ").trim()

/**
 * A share's fields added to a `mailto:` link's ([link], when the share had one): addresses joined
 * once each, the share's subject over the link's, and the two bodies one after the other.
 */
fun mergePrefill(link: ComposePrefill?, share: ComposePrefill): ComposePrefill {
    val base = link ?: ComposePrefill()
    return ComposePrefill(
        to = (base.to + share.to).distinctBy { addressKey(it.email) },
        cc = (base.cc + share.cc).distinctBy { addressKey(it.email) },
        bcc = (base.bcc + share.bcc).distinctBy { addressKey(it.email) },
        subject = oneLine(share.subject).ifEmpty { base.subject },
        body = listOf(base.body, share.body).filter { it.isNotBlank() }.joinToString("\n\n"),
    )
}
