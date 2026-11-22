package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.Person

// RFC 5322 "specials" plus characters that would end or confuse a bare phrase.
private val PHRASE_SPECIALS = Regex("""[()<>\[\]:;@\\,."]""")
private val SPACE_RUN = Regex("""\s{2,}""")
/** Characters escaped with a backslash inside a quoted string. */
internal val QUOTE_ESCAPES = Regex("""[\\"]""")

/** An address that can't be written into a header as it is. */
class InvalidAddressError(
    /** the address as given */
    val address: String,
) : IllegalArgumentException("Not a valid email address: \"$address\"")

/** What the user is told about an address that can't be sent to, or null when [error] isn't about one. */
fun addressProblem(error: Throwable): String? = (error as? InvalidAddressError)?.let { addressProblemText(it.address) }

/** The first of [people] whose address can't be sent to, said for the user; null when all can. */
fun addressProblem(people: List<Person>): String? =
    people.firstOrNull { !isPlainAddress(it.email.trim(::isJsWhitespace)) }?.let { addressProblemText(it.email) }

private fun addressProblemText(address: String): String {
    val trimmed = address.trim(::isJsWhitespace)
    return if (trimmed.isEmpty()) "One of the addresses is empty. Remove it and try again." else "$trimmed isn't a valid email address. Fix or remove it."
}

/** Whether a character is whitespace as JavaScript's `\s` sees it. */
internal fun isJsWhitespace(character: Char): Boolean = character.isWhitespace() || character == '\uFEFF'

/**
 * Whether text is one plain address (`local@domain`), safe to write into a header or a `mailto:`:
 * exactly one `@` with something on each side, and no whitespace, control characters, `,`, `;`,
 * `<` or `>`.
 */
fun isPlainAddress(email: String): Boolean {
    val at = email.indexOf('@')
    return at > 0 &&
        at == email.lastIndexOf('@') &&
        at < email.length - 1 &&
        email.none { isJsWhitespace(it) || it in ",;<>" || it.code < 0x20 || it.code == 0x7f }
}

/**
 * One mailbox as it goes in an address header.
 *
 * - no name: `ana@example.com`
 * - printable ASCII name of plain words: `Ana Lopez <ana@example.com>`
 * - ASCII name with specials: a quoted string, `"Lopez, Ana" <ana@example.com>`
 * - any other name: RFC 2047 encoded words, `=?UTF-8?B?…?= <ana@example.com>`
 *
 * The address is trimmed, then must be [isPlainAddress], else this throws [InvalidAddressError].
 */
fun formatMailbox(mailbox: Person): String {
    val email = mailbox.email.trim(::isJsWhitespace)
    if (!isPlainAddress(email)) throw InvalidAddressError(mailbox.email)
    val name = mailbox.name.trim()
    return when {
        name.isEmpty() -> email
        !isPrintableAscii(name) || name.contains("=?") -> "${encodedWords(name).joinToString(" ")} <$email>"
        PHRASE_SPECIALS.containsMatchIn(name) || SPACE_RUN.containsMatchIn(name) ->
            "\"${name.replace(QUOTE_ESCAPES) { "\\" + it.value }}\" <$email>"
        else -> "$name <$email>"
    }
}

/** Mailboxes as an address header value, joined by `, `. */
fun formatAddressList(mailboxes: List<Person>): String = mailboxes.joinToString(", ", transform = ::formatMailbox)

/** An address in the form used to compare two: trimmed and lower-cased. */
fun addressKey(email: String): String = email.trim().lowercase()

private val EMAIL_RE = Regex("""^[^@\s<>(),;:"]+@[^@\s<>(),;:"]+\.[^@\s<>(),;:".]+$""")

/** Whether text looks like a deliverable address (`local@domain.tld`). */
fun isEmailAddress(text: String): Boolean = EMAIL_RE.matches(text.trim())
