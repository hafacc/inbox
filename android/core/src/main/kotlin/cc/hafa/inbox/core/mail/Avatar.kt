package cc.hafa.inbox.core.mail

// The letters an avatar shows, as shared/src/initials.ts gives them; both run
// shared/testdata/initials-fixtures.json.

private val NOT_WORD_RE = Regex("""[^\p{L}\p{N}\p{M}]+""")
private val LETTER_RE = Regex("""[\p{L}\p{N}]\p{M}*""")

/**
 * An avatar's letters: the first letters of a name's first and last words (only the first word's
 * when [single]), else the address's first character, upper-cased; "?" when there is neither.
 *
 * Words are runs of letters, digits and combining marks, so `Mary-Kate O'Neil` gives `MN`, and a
 * letter keeps the accents that follow it.
 */
fun initials(person: Person, single: Boolean = false): String {
    val letters = person.name.replace(NOT_WORD_RE, " ").split(" ").mapNotNull { LETTER_RE.find(it)?.value }
    return when {
        letters.isEmpty() -> {
            val email = person.email.trim()
            if (email.isEmpty()) "?" else String(Character.toChars(email.codePointAt(0))).uppercase()
        }
        letters.size == 1 || single -> letters.first().uppercase()
        else -> (letters.first() + letters.last()).uppercase()
    }
}
