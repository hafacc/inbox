package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.todo.Tags
import java.time.Instant
import java.time.ZoneId

/** Operators the field turns into chips, in the order completion lists them. */
val OPERATORS: List<String> = listOf(
    "from", "to", "cc", "bcc", "subject", "in", "is", "has", "label", "filename", "after", "before",
    "older_than", "newer_than", "larger", "smaller", "size", "list", "deliveredto", "category",
)

/** The few operators offered before anything is typed; typing completes every operator. */
val COMMON_OPERATORS: List<String> = listOf("from", "to", "subject", "has", "is")

/** What each operator matches, shown beside it in completion. */
val OPERATOR_DETAILS: Map<String, String> = mapOf(
    "from" to "Sender",
    "to" to "Recipient",
    "cc" to "Copied",
    "bcc" to "Blind-copied",
    "subject" to "Words in the subject",
    "in" to "Mailbox",
    "is" to "State: unread, starred, snoozed, done…",
    "has" to "Attachment, Drive file…",
    "label" to "Label",
    "filename" to "Attachment name or type",
    "after" to "Sent after a date",
    "before" to "Sent before a date",
    "older_than" to "Older than (1d, 2m, 1y)",
    "newer_than" to "Newer than (1d, 2m, 1y)",
    "larger" to "Larger than a size",
    "smaller" to "Smaller than a size",
    "size" to "Larger than a size in bytes",
    "list" to "Mailing list",
    "deliveredto" to "Delivered to an address",
    "category" to "Inbox category",
)

/** One example query per operator, for the search help. */
val OPERATOR_EXAMPLES: Map<String, String> = mapOf(
    "from" to "from:maya@example.com",
    "to" to "to:me",
    "cc" to "cc:team@example.com",
    "bcc" to "bcc:alex",
    "subject" to "subject:invoice",
    "in" to "in:snoozed",
    "is" to "is:unread",
    "has" to "has:attachment",
    "label" to "label:receipts",
    "filename" to "filename:pdf",
    "after" to "after:2026/01/31",
    "before" to "before:2026/06/01",
    "older_than" to "older_than:1y",
    "newer_than" to "newer_than:2d",
    "larger" to "larger:10M",
    "smaller" to "smaller:1M",
    "size" to "size:500000",
    "list" to "list:news@example.com",
    "deliveredto" to "deliveredto:user@hafa.cc",
    "category" to "category:promotions",
)

/** The [Chip.operator] of a chip made from a `#tag`, which searches as the text it is. */
const val TAG_CHIP = "#"

/** A finished operator shown as a chip, or a finished `#tag` ([TAG_CHIP]) in the search field. */
data class Chip(
    /** operator, lower-case; [TAG_CHIP] for a `#tag` */
    val operator: String,
    /** value without quotes; a tag's name without `#` */
    val value: String,
    /** written with a leading `-` */
    val negated: Boolean,
)

/** What the token under the cursor is: an operator name being typed, or the value of one. */
sealed interface CompletionContext {
    /** what is typed so far, without `-` or an opening quote */
    val prefix: String

    /** written with a leading `-` */
    val negated: Boolean

    /** start of the free-text range a completion replaces */
    val start: Int

    /** end of the free-text range a completion replaces */
    val end: Int
}

/** An operator name being typed. */
data class OperatorContext(
    override val prefix: String,
    override val negated: Boolean,
    override val start: Int,
    override val end: Int,
) : CompletionContext

/** The value of an operator being typed. */
data class ValueContext(
    /** operator whose value it is */
    val operator: String,
    override val prefix: String,
    /** the whole value, past the cursor too, without an opening quote */
    val value: String,
    override val negated: Boolean,
    override val start: Int,
    override val end: Int,
) : CompletionContext

/** Search field text split into chips and the text left in the input. */
data class ParsedQuery(
    /** finished operators, in order */
    val chips: List<Chip>,
    /** the text with the chips taken out */
    val free: String,
    /** the cursor's place in [free] */
    val cursor: Int,
    /** what the token under the cursor completes to; null without a cursor or inside a quote or group */
    val context: CompletionContext?,
)

/** A completion offered for a context. */
data class Candidate(
    /** what the list shows */
    val label: String,
    /** what goes into the query: `from:` for an operator, the bare value for a value */
    val value: String,
    /** second line, or null */
    val detail: String?,
)

/** What completion needs besides the context. */
data class CompletionOptions(
    /** epoch ms, for date shortcuts */
    val now: Long,
    /** IANA zone the date shortcuts are counted in */
    val timeZone: String,
    /** label names, for `label:` */
    val labels: List<String> = emptyList(),
    /** contacts and recent senders, best first */
    val contacts: List<Person> = emptyList(),
)

/** A run of query text between whitespace; quotes and `()`/`{}` groups may hold whitespace. */
data class Token(
    /** start offset */
    val start: Int,
    /** end offset, exclusive */
    val end: Int,
    /** the token's text */
    val text: String,
    /** false when a quote or group is still open at the end of the text */
    val complete: Boolean,
)

/** Free text and cursor after a change. */
data class Edit(
    /** the free text */
    val text: String,
    /** cursor position in it */
    val cursor: Int,
)

/** Chips, free text and cursor after a chip turns back into text. */
data class Unchipped(
    /** the chips left */
    val chips: List<Chip>,
    /** the free text */
    val free: String,
    /** cursor position in it */
    val cursor: Int,
)

/** Most label or contact candidates returned. */
const val MAX_HOOK_CANDIDATES = 8

/** Chips that are ours, not Gmail's: answered from Firestore and the outbox, not sent to Gmail. */
val LOCAL_CHIPS: List<Pair<String, String>> = listOf("is" to "snoozed", "is" to "todo", "in" to "outbox")

/** Values completed for `in:`. */
val IN_VALUES: List<String> = listOf("inbox", "sent", "drafts", "outbox", "spam", "trash", "anywhere")

/** Values completed for `is:`. */
val IS_VALUES: List<String> = listOf("unread", "read", "starred", "pinned", "snoozed", "done", "todo", "important", "muted")

/** Our own search terms (Gmail has none of them) and what each lists, for the search help. */
val OWN_TERMS: List<Pair<String, String>> = listOf(
    "is:snoozed" to "Snoozed mail and todos",
    "is:pinned" to "Pinned mail and todos, as is:starred",
    "is:done" to "Mail out of the inbox and done todos",
    "is:todo" to "Only todos",
    "in:outbox" to "Mail waiting to be sent",
    "#receipts" to "A label's mail and todos, or todos with that tag",
)

/** Values completed for `has:`. */
val HAS_VALUES: List<String> = listOf(
    "attachment", "drive", "document", "spreadsheet", "presentation", "youtube", "userlabels", "nouserlabels",
)

/** Values completed for `category:`. */
val CATEGORY_VALUES: List<String> =
    listOf("primary", "social", "promotions", "updates", "forums", "reservations", "purchases")

/** Values completed for `larger:`, `smaller:` and `size:`. */
val SIZE_VALUES: List<String> = listOf("1M", "5M", "10M", "25M")

/** Values completed for `older_than:` and `newer_than:`, with their labels. */
val AGE_VALUES: List<Pair<String, String>> =
    listOf("1d" to "1 day", "7d" to "7 days", "1m" to "1 month", "1y" to "1 year")

/** Date shortcuts offered for `after:` and `before:`: label and days back. */
val DATE_SHORTCUTS: List<Pair<String, Int>> = listOf("today" to 0, "yesterday" to 1, "last week" to 7)

private val OPERATOR_SET = OPERATORS.toSet()
private val CONTACT_OPERATORS = setOf("from", "to", "cc", "bcc", "deliveredto")
private val CLOSERS = mapOf('(' to ')', '{' to '}')
private val CHIP_RE = Regex("""^(-?)([A-Za-z_]+):(.+)$""", RegexOption.DOT_MATCHES_ALL)
private val QUOTED_RE = Regex("""^"([^"]*)"$""")
private val BARE_RE = Regex("""^[^"(){}]+$""")
private val NAME_RE = Regex("""^[A-Za-z_]*$""")
private val QUOTE_OR_BRACKET_RE = Regex("""["(){}]""")
private val NEEDS_QUOTES_RE = Regex("""[\s"(){}]""")
private val LABEL_SPLIT_RE = Regex("""[\s/]+""")
private val SPACE_RUN_RE = Regex("""\s+""")

/** Split query text into tokens at whitespace outside quotes and groups. */
fun tokenize(text: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var index = 0
    while (index < text.length) {
        if (text[index].isWhitespace()) {
            index++
            continue
        }
        val start = index
        val open = ArrayDeque<Char>()
        var complete = true
        while (index < text.length) {
            val character = text[index]
            if (open.isEmpty() && character.isWhitespace()) break
            if (character == '"') {
                val close = text.indexOf('"', index + 1)
                if (close < 0) {
                    index = text.length
                    complete = false
                    break
                }
                index = close + 1
                continue
            }
            val closer = CLOSERS[character]
            if (closer != null) {
                open.addLast(closer)
            } else if (character == open.lastOrNull()) {
                open.removeLast()
            }
            index++
        }
        if (open.isNotEmpty()) complete = false
        tokens += Token(start, index, text.substring(start, index), complete)
    }
    return tokens
}

/**
 * The chip a token makes when finished, or null for free text. With [tags], a token that is one
 * whole `#tag` (as todos have them, optionally after `-`) makes a [TAG_CHIP] chip.
 */
fun tokenChip(token: Token, tags: Boolean = false): Chip? {
    if (!token.complete) return null
    if (tags) {
        val negated = token.text.startsWith("-")
        val body = if (negated) token.text.substring(1) else token.text
        val span = Tags.parse(body).singleOrNull()
        if (span != null && span.start == 0 && span.end == body.length) return Chip(TAG_CHIP, body.substring(1), negated)
    }
    val match = CHIP_RE.find(token.text) ?: return null
    val (minus, name, raw) = match.destructured
    val operator = name.lowercase()
    if (operator !in OPERATOR_SET) return null
    val quoted = QUOTED_RE.find(raw)
    val value = when {
        quoted != null -> quoted.groupValues[1].trim()
        BARE_RE.matches(raw) -> raw
        else -> return null
    }
    return if (value.isEmpty()) null else Chip(operator, value, minus == "-")
}

/** A chip as query text: `-from:"Maya Chen"`; quoted when the value has spaces, quotes or brackets. A tag chip is its `#tag`. */
fun formatChip(chip: Chip): String {
    if (chip.operator == TAG_CHIP) return "${if (chip.negated) "-" else ""}#${chip.value}"
    val value = if (NEEDS_QUOTES_RE.containsMatchIn(chip.value)) "\"${chip.value.replace("\"", "")}\"" else chip.value
    return "${if (chip.negated) "-" else ""}${chip.operator}:$value"
}

/** Whether a chip is one of ours (`is:snoozed`, `is:todo`, `in:outbox`), to be answered locally. */
fun isLocalChip(chip: Chip): Boolean =
    LOCAL_CHIPS.any { (operator, value) -> operator == chip.operator && value == chip.value.lowercase() }

/** The whole query: chips, then the free text. */
fun queryText(chips: List<Chip>, free: String): String =
    (chips.map(::formatChip) + free.trim()).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * A chip as Gmail is asked for it: our `is:pinned` is Gmail's `is:starred`, and `is:done` (out of
 * the inbox) is `-in:inbox`, so `-is:done` is `in:inbox`. Any other chip is as it is.
 */
fun gmailChip(chip: Chip): Chip {
    val value = chip.value.lowercase()
    return when {
        chip.operator == "is" && value == "pinned" -> chip.copy(value = "starred")
        chip.operator == "is" && value == "done" -> Chip("in", "inbox", !chip.negated)
        else -> chip
    }
}

/** Gmail's `q` for a query's chips and free text: our local chips left out, the rest as [gmailChip] has them. */
fun gmailQueryText(chips: List<Chip>, free: String): String = queryText(chips.filterNot(::isLocalChip).map(::gmailChip), free)

/**
 * Split search field text into chips and free text, aware of the cursor.
 *
 * An operator token becomes a chip once it has a value, any quote is closed, and the cursor is not
 * inside or touching it. Groups, `OR` and unknown operators stay text, and so does an operator right
 * before or after a top-level `OR` (a chip would move it out of the `OR`). With [cursor] null every
 * finished operator becomes a chip and there is no context. With [tags] (the search field), a
 * finished `#tag` becomes a chip too ([tokenChip]); a query read for searching leaves it in the
 * free text.
 */
fun parseQuery(text: String, cursor: Int?, tags: Boolean = false): ParsedQuery {
    val tokens = tokenize(text)
    val chips = mutableListOf<Chip>()
    val removed = mutableListOf<Pair<Int, Int>>()
    tokens.forEachIndexed { index, token ->
        val chip = tokenChip(token, tags)
        val touching = cursor != null && cursor >= token.start && cursor <= token.end
        val besideOr = tokens.getOrNull(index - 1)?.text == "OR" || tokens.getOrNull(index + 1)?.text == "OR"
        if (chip == null || touching || besideOr) return@forEachIndexed
        chips += chip
        val next = tokens.getOrNull(index + 1)
        var start = token.start
        var end = token.end
        if (next != null) {
            end = next.start
        } else if (end < text.length) {
            end = text.length
        } else {
            start = maxOf(tokens.getOrNull(index - 1)?.end ?: 0, removed.lastOrNull()?.second ?: 0)
        }
        removed += start to end
    }
    val free = StringBuilder()
    var at = 0
    var freeCursor = cursor ?: 0
    for ((start, end) in removed) {
        free.append(text, at, start)
        at = end
        if (cursor != null && cursor >= end) {
            freeCursor -= end - start
        } else if (cursor != null && cursor > start) {
            freeCursor -= cursor - start
        }
    }
    free.append(text, at, text.length)
    val freeText = free.toString()
    return ParsedQuery(
        chips = chips,
        free = freeText,
        cursor = if (cursor == null) freeText.length else freeCursor,
        context = if (cursor == null) null else completionContext(freeText, freeCursor),
    )
}

/** What the token under the cursor completes to (see [parseQuery]). */
fun completionContext(text: String, cursor: Int): CompletionContext? {
    val token = tokenize(text).find { cursor >= it.start && cursor <= it.end }
        ?: return OperatorContext("", false, cursor, cursor)
    val typed = token.text.substring(0, cursor - token.start)
    val negated = typed.startsWith("-")
    val body = if (negated) typed.substring(1) else typed
    val colon = body.indexOf(':')
    if (colon < 0) {
        if (!NAME_RE.matches(body)) return null
        val tokenColon = token.text.indexOf(':')
        return OperatorContext(
            prefix = body,
            negated = negated,
            start = token.start,
            end = if (tokenColon < 0) token.end else token.start + tokenColon + 1,
        )
    }
    val operator = body.substring(0, colon).lowercase()
    if (operator !in OPERATOR_SET) return null
    val prefix = body.substring(colon + 1).removePrefix("\"")
    if (QUOTE_OR_BRACKET_RE.containsMatchIn(prefix)) return null
    val value = token.text.substring(token.text.indexOf(':') + 1).removePrefix("\"")
    return ValueContext(operator, prefix, value, negated, token.start, token.end)
}


/**
 * Whether the token under the cursor is an operator with nothing after its colon (`in:`, `-from:`,
 * `subject:"`), so the query is unfinished: the search action completes a suggestion there instead
 * of searching.
 */
fun awaitsValue(context: CompletionContext?): Boolean = context is ValueContext && context.value.isEmpty()

/** The free text with the operator [context] stands on taken out, for searching an unfinished query. */
fun withoutOperator(free: String, context: CompletionContext): String {
    val before = free.substring(0, context.start).trimEnd()
    val after = free.substring(context.end).trimStart()
    return if (before.isEmpty() || after.isEmpty()) before + after else "$before $after"
}

private fun fixed(values: List<String>, prefix: String): List<Candidate> =
    values.filter { it.startsWith(prefix, ignoreCase = true) }.map { Candidate(it, it, null) }

/** A label name as Gmail's `label:` takes it: lower-case, spaces and `/` as `-`. */
fun labelQueryValue(name: String): String = name.trim().lowercase().replace(LABEL_SPLIT_RE, "-")

private fun labelCandidates(labels: List<String>, prefix: String): List<Candidate> {
    val folded = prefix.lowercase()
    return labels
        .filter { name ->
            labelQueryValue(name).startsWith(folded, ignoreCase = true) ||
                name.lowercase().split(LABEL_SPLIT_RE).any { it.startsWith(folded) }
        }
        .take(MAX_HOOK_CANDIDATES)
        .map { Candidate(it, labelQueryValue(it), null) }
}

private fun contactCandidates(contacts: List<Person>, prefix: String): List<Candidate> {
    val folded = prefix.lowercase().trim()
    return contacts
        .filter { contact ->
            contact.email.startsWith(folded, ignoreCase = true) ||
                contact.name.startsWith(folded, ignoreCase = true) ||
                contact.name.lowercase().split(SPACE_RUN_RE).any { it.startsWith(folded) }
        }
        .take(MAX_HOOK_CANDIDATES)
        .map { Candidate(it.name.ifEmpty { it.email }, it.email, if (it.name.isNotEmpty()) it.email else null) }
}

/** Calendar date [days] before today in [timeZone], as Gmail's `yyyy/mm/dd`. */
fun dateDaysAgo(now: Long, timeZone: String, days: Int): String {
    val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(timeZone)).toLocalDate().minusDays(days.toLong())
    return "%04d/%02d/%02d".format(date.year, date.monthValue, date.dayOfMonth)
}

/**
 * Completions for a context: operators by name, fixed values for `in:`, `is:`, `has:`,
 * `category:`, sizes and ages, date shortcuts for `after:`/`before:`, and the caller's labels and
 * contacts (at most [MAX_HOOK_CANDIDATES]). Matching is by case-insensitive prefix.
 */
fun completions(context: CompletionContext, options: CompletionOptions): List<Candidate> {
    val prefix = context.prefix
    if (context is OperatorContext) {
        val offered = if (prefix.isEmpty()) COMMON_OPERATORS else OPERATORS
        return offered.filter { it.startsWith(prefix, ignoreCase = true) }.map { Candidate("$it:", "$it:", OPERATOR_DETAILS[it]) }
    }
    return when (val operator = (context as ValueContext).operator) {
        "in" -> fixed(IN_VALUES, prefix)
        "is" -> fixed(IS_VALUES, prefix)
        "has" -> fixed(HAS_VALUES, prefix)
        "category" -> fixed(CATEGORY_VALUES, prefix)
        "larger", "smaller", "size" -> fixed(SIZE_VALUES, prefix)
        "older_than", "newer_than" -> AGE_VALUES
            .filter { (value, label) -> value.startsWith(prefix, ignoreCase = true) || label.startsWith(prefix, ignoreCase = true) }
            .map { (value, label) -> Candidate(label, value, null) }
        "after", "before" -> DATE_SHORTCUTS
            .map { (label, days) -> Candidate(label, dateDaysAgo(options.now, options.timeZone, days), null) }
            .filter { it.label.startsWith(prefix, ignoreCase = true) || it.value.startsWith(prefix, ignoreCase = true) }
            .map { it.copy(detail = it.value) }
        "label" -> labelCandidates(options.labels, prefix)
        else -> if (operator in CONTACT_OPERATORS) contactCandidates(options.contacts, prefix) else emptyList()
    }
}

/**
 * Put a candidate into the free text: an operator replaces the name being typed (cursor after the
 * colon); a value replaces the token with the finished chip text and a space, cursor after it.
 */
fun applyCompletion(text: String, context: CompletionContext, candidate: Candidate): Edit {
    val before = text.substring(0, context.start)
    val after = text.substring(context.end)
    val minus = if (context.negated) "-" else ""
    return when (context) {
        is OperatorContext -> {
            val insert = minus + candidate.value
            Edit(before + insert + after, before.length + insert.length)
        }
        is ValueContext -> {
            val chip = formatChip(Chip(context.operator, candidate.value, context.negated))
            val space = if (after.firstOrNull()?.isWhitespace() == true) "" else " "
            Edit(before + chip + space + after, before.length + chip.length + 1)
        }
    }
}

/** Backspace on a chip: its text goes to the start of the free text, cursor at its end. */
fun unchip(chips: List<Chip>, index: Int, free: String): Unchipped {
    val chip = chips.getOrNull(index) ?: return Unchipped(chips.toList(), free, 0)
    val text = formatChip(chip)
    val rest = free.trimStart()
    return Unchipped(
        chips = chips.filterIndexed { other, _ -> other != index },
        free = if (rest.isEmpty()) text else "$text $rest",
        cursor = text.length,
    )
}
