package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.mail.Labels
import cc.hafa.inbox.core.mail.ThreadDetail
import cc.hafa.inbox.core.mail.ThreadSummary

/** How a search runs: Gmail's `q` without our own chips, and what those chips ask for. */
data class SearchPlan(
    /** the Gmail query, local chips left out; empty when only local chips were given */
    val gmailQuery: String,
    /** `is:snoozed`: keep only threads snoozed now */
    val snoozedOnly: Boolean,
    /** `is:todo`: todos only, no mail */
    val todosOnly: Boolean,
    /** `-is:todo`: no todos */
    val noTodos: Boolean,
    /** `in:outbox`: show the outbox */
    val outbox: Boolean,
    /** every chip of the query */
    val chips: List<Chip>,
    /** the free text */
    val free: String,
) {
    /** the free text's terms, for matching on the device */
    val terms: FreeTerms by lazy { freeTerms(free) }

    internal val deviceChips: List<Chip> by lazy { chips.filterNot(::isLocalChip) }
}

/** Split a whole search into what goes to Gmail and what is answered on the device. */
fun searchPlan(query: String): SearchPlan {
    val parsed = parseQuery(query, null)
    val local = parsed.chips.filter(::isLocalChip)
    fun has(operator: String, value: String, negated: Boolean = false) =
        local.any { it.operator == operator && it.value.lowercase() == value && it.negated == negated }
    return SearchPlan(
        gmailQuery = gmailQueryText(parsed.chips, parsed.free),
        snoozedOnly = has("is", "snoozed"),
        todosOnly = has("is", "todo"),
        noTodos = has("is", "todo", negated = true),
        outbox = has("in", "outbox"),
        chips = parsed.chips,
        free = parsed.free,
    )
}

/** The words of free text a search looks for, lower-cased: phrases kept whole. */
data class FreeTerms(
    /** words and phrases that must appear */
    val include: List<String>,
    /** words and phrases (`-word`, `-"a phrase"`) that must not */
    val exclude: List<String>,
)

private val TERM = Regex("""(-?)"([^"]*)"|(-?)([^\s"(){}]+)""")
private val SPACES = Regex("""\s+""")

/** Words of free text: `-word` excluded, quotes kept as phrases, `OR`/`AND` and brackets dropped. */
fun freeTerms(free: String): FreeTerms {
    val include = mutableListOf<String>()
    val exclude = mutableListOf<String>()
    for (match in TERM.findAll(free)) {
        val quoted = match.groups[2]
        val negated = (if (quoted != null) match.groups[1] else match.groups[3])?.value == "-"
        val term = (quoted ?: match.groups[4])?.value.orEmpty().lowercase().replace(SPACES, " ").trim()
        if (term.isEmpty() || term == "or" || term == "and") continue
        (if (negated) exclude else include) += term
    }
    return FreeTerms(include, exclude)
}

/** Text for offline search: lower-cased, with runs of spaces made one. */
fun savedSearchText(parts: List<String>): String = parts.joinToString("\n").replace(SPACES, " ").lowercase()

/** What offline search reads of an opened thread's messages besides their text. */
data class SavedText(
    /** every message's To, Cc and Bcc names and addresses, as [savedSearchText] */
    val recipients: String,
    /** whether any message has an attachment */
    val attachment: Boolean,
)

/** What offline search reads of a thread opened on this device. */
fun savedText(detail: ThreadDetail): SavedText = SavedText(
    recipients = savedSearchText(detail.messages.flatMap { it.to + it.cc + it.bcc }.map { "${it.name} ${it.email}" }),
    attachment = detail.messages.any { it.body.attachments.isNotEmpty() },
)

/**
 * Whether a cached thread matches a search, for searching offline: each included term appears in
 * its subject, snippet or senders (or [savedHolds] finds it in its saved messages), no excluded
 * one does, and every chip the device can answer holds. [saved] reads the thread's saved
 * messages, null when it wasn't opened here, only when a chip needs them (`to:`, `has:attachment`).
 * Chips it can't answer (dates, sizes…) are skipped, so results may be broader than Gmail's.
 */
suspend fun matchesOffline(
    thread: ThreadSummary,
    plan: SearchPlan,
    labelNames: Map<String, String>,
    savedHolds: suspend (term: String) -> Boolean = { false },
    saved: suspend () -> SavedText? = { null },
): Boolean {
    val own = savedSearchText(listOf(thread.subject, thread.snippet) + thread.senders.map { "${it.name} ${it.email}" })
    val holds: suspend (String) -> Boolean = { term -> term in own || savedHolds(term) }
    val terms = plan.terms
    if (!terms.include.all { holds(it) } || terms.exclude.any { holds(it) }) return false
    var read = false
    var savedText: SavedText? = null
    val savedOnce: suspend () -> SavedText? = {
        if (!read) {
            savedText = saved()
            read = true
        }
        savedText
    }
    return plan.deviceChips.all { chip ->
        val answer = chipHolds(thread, chip, labelNames, savedOnce)
        answer == null || answer != chip.negated
    }
}

private val IN_LABELS = mapOf(
    "inbox" to Labels.INBOX,
    "sent" to Labels.SENT,
    "drafts" to Labels.DRAFT,
    "spam" to Labels.SPAM,
    "trash" to Labels.TRASH,
    "starred" to Labels.STARRED,
)

// Whether one chip holds for a thread; null when the device can't tell.
private suspend fun chipHolds(
    thread: ThreadSummary,
    chip: Chip,
    labelNames: Map<String, String>,
    saved: suspend () -> SavedText?,
): Boolean? {
    val value = chip.value.lowercase()
    val labels = thread.labelIds
    return when (chip.operator) {
        "from" -> thread.senders.any { value in it.email.lowercase() || value in it.name.lowercase() }
        "to", "cc", "bcc", "deliveredto" -> saved()?.let { value in it.recipients }
        "subject" -> value in thread.subject.lowercase()
        "label" -> labelNames.entries.firstOrNull { labelQueryValue(it.value) == value }?.let { it.key in labels } ?: false
        "in" -> if (value == "anywhere") true else IN_LABELS[value]?.let { it in labels }
        "is" -> when (value) {
            "unread" -> thread.unread
            "read" -> !thread.unread
            "starred", "pinned" -> Labels.STARRED in labels
            "done" -> Labels.INBOX !in labels
            "important" -> "IMPORTANT" in labels
            else -> null
        }
        "has" -> if (value == "attachment") saved()?.attachment else null
        else -> null
    }
}
