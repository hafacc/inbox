package cc.hafa.inbox.core.filters

import cc.hafa.inbox.core.label.LabelDelta
import cc.hafa.inbox.core.search.tokenize
import kotlinx.serialization.Serializable
import kotlin.math.floor

/** `criteria` of a Gmail API `Filter` resource. */
@Serializable
data class GmailFilterCriteria(
    /** sender */
    val from: String? = null,
    /** recipient */
    val to: String? = null,
    /** subject words */
    val subject: String? = null,
    /** "has the words": Gmail search text */
    val query: String? = null,
    /** "doesn't have": Gmail search text */
    val negatedQuery: String? = null,
    /** has an attachment */
    val hasAttachment: Boolean? = null,
    /** leave out chats */
    val excludeChats: Boolean? = null,
    /** bytes */
    val size: Int? = null,
    /** `larger`, `smaller` or `unspecified` */
    val sizeComparison: String? = null,
)

/** `action` of a Gmail API `Filter` resource. */
@Serializable
data class GmailFilterAction(
    /** label ids added */
    val addLabelIds: List<String>? = null,
    /** label ids removed */
    val removeLabelIds: List<String>? = null,
    /** a verified forwarding address */
    val forward: String? = null,
)

/** A Gmail API `Filter` resource. */
@Serializable
data class GmailFilter(
    /** filter id; null for one not yet created */
    val id: String? = null,
    /** what it matches */
    val criteria: GmailFilterCriteria? = null,
    /** what it does */
    val action: GmailFilterAction? = null,
)

/** A field a row can match, in the order rows are listed. */
enum class FilterField(
    /** the name shared fixtures and web use */
    val wire: String,
) {
    /** sender */
    FROM("from"),

    /** recipient */
    TO("to"),

    /** subject words */
    SUBJECT("subject"),

    /** Gmail search text */
    HAS_WORDS("hasWords"),

    /** Gmail search text that must not match */
    DOES_NOT_HAVE("doesNotHave"),

    /** message size */
    SIZE("size"),

    /** has an attachment */
    HAS_ATTACHMENT("hasAttachment"),
}

/** How a row compares its field with its value. */
enum class FilterOperator(
    /** the name shared fixtures and web use */
    val wire: String,
) {
    /** text contains the value */
    CONTAINS("contains"),

    /** size above the value */
    LARGER("larger"),

    /** size below the value */
    SMALLER("smaller"),

    /** the field holds (has attachment) */
    IS("is"),
}

/** How a group's rows combine. */
enum class GroupMatch(
    /** the name shared fixtures and web use */
    val wire: String,
) {
    /** every row matches */
    ALL("all"),

    /** any row matches */
    ANY("any"),

    /** no row matches */
    NOT("not"),
}

/** Whether a filter marks mail important. */
enum class Importance(
    /** the name shared fixtures and web use */
    val wire: String,
) {
    /** add `IMPORTANT` */
    ALWAYS("always"),

    /** remove `IMPORTANT` */
    NEVER("never"),
}

/** One condition in the editor. */
@Serializable
data class FilterRow(
    /** the field matched */
    val field: FilterField,
    /** how it is compared */
    val operator: FilterOperator,
    /** text; for size a number with an optional unit; empty for has attachment */
    val value: String,
)

/** Rows combined by [match]. */
@Serializable
data class ConditionGroup(
    /** how the rows combine */
    val match: GroupMatch,
    /** the rows */
    val rows: List<FilterRow>,
)

/** The editor's conditions: groups (all of which must hold), or raw search text when they don't parse. */
@Serializable
sealed interface FilterConditions {
    /** condition groups */
    @Serializable
    data class Rows(
        /** groups, all of which must hold */
        val groups: List<ConditionGroup>,
    ) : FilterConditions

    /** search text */
    @Serializable
    data class Raw(
        /** Gmail search text */
        val query: String,
    ) : FilterConditions
}

/** What a filter does. One label at most; ids Gmail uses but the editor doesn't show are kept. */
@Serializable
data class FilterActions(
    /** user label id (`Label_…`) to add, or null */
    val labelId: String? = null,
    /** skip the inbox (remove `INBOX`) */
    val archive: Boolean = false,
    /** remove `UNREAD` */
    val markRead: Boolean = false,
    /** add `STARRED` */
    val star: Boolean = false,
    /** never send to spam (remove `SPAM`) */
    val neverSpam: Boolean = false,
    /** whether to mark important, or null to leave to Gmail */
    val important: Importance? = null,
    /** delete (add `TRASH`) */
    val trash: Boolean = false,
    /** forwarding address, or null */
    val forward: String? = null,
    /** other label ids added */
    val otherAddLabelIds: List<String> = emptyList(),
    /** other label ids removed */
    val otherRemoveLabelIds: List<String> = emptyList(),
)

/** The whole editor. */
@Serializable
data class FilterEditor(
    /** what it matches */
    val conditions: FilterConditions,
    /** what it does */
    val actions: FilterActions,
)

/** A filter to create: criteria and action. */
data class NewFilter(
    /** what it matches */
    val criteria: GmailFilterCriteria,
    /** what it does */
    val action: GmailFilterAction,
)

/** Actions with nothing chosen. */
val NO_ACTIONS: FilterActions = FilterActions()

private const val KB = 1024
private const val MB = 1024 * 1024
private val SIZE_RE = Regex("""^\s*(\d+(?:\.\d+)?)\s*(b|bytes?|k|kb|m|mb)?\s*$""", RegexOption.IGNORE_CASE)
private val SEARCH_SIZE_RE = Regex("""^(\d+)([kKmM]?)$""")
private val QUOTED_RE = Regex("""^"[^"]*"$""")
private val OPERATOR_TERM_RE = Regex("""^([A-Za-z_]+):(.+)$""", RegexOption.DOT_MATCHES_ALL)

/** A size row's value in bytes (`5MB`, `5 mb`, `500KB`, `1200`), or null when it isn't one. */
fun parseSize(value: String): Int? {
    val match = SIZE_RE.find(value) ?: return null
    val unit = match.groupValues[2].lowercase()
    val scale = when {
        unit.startsWith("m") -> MB
        unit.startsWith("k") -> KB
        else -> 1
    }
    return floor(match.groupValues[1].toDouble() * scale + 0.5).toInt()
}

/** Bytes as a size row's value: `5MB`, `500KB`, else bytes. */
fun formatSize(bytes: Int): String = when {
    bytes > 0 && bytes % MB == 0 -> "${bytes / MB}MB"
    bytes > 0 && bytes % KB == 0 -> "${bytes / KB}KB"
    else -> bytes.toString()
}

private fun searchSize(bytes: Int): String = if (bytes > 0 && bytes % MB == 0) "${bytes / MB}M" else bytes.toString()

/** What's wrong with a row, or null when it can be saved. */
fun rowError(row: FilterRow): String? = when {
    row.field == FilterField.HAS_ATTACHMENT -> null
    row.value.isBlank() -> "Enter a value"
    row.field == FilterField.SIZE && parseSize(row.value) == null -> "Enter a size like 5MB"
    else -> null
}

private fun hasSpace(text: String): Boolean = text.trim().any { it.isWhitespace() }

private fun grouped(value: String): String {
    val trimmed = value.trim()
    return if (hasSpace(trimmed) && !QUOTED_RE.matches(trimmed)) "($trimmed)" else trimmed
}

private fun without(value: String): String {
    val trimmed = value.trim()
    return if (tokenize(trimmed).size > 1) "-{$trimmed}" else "-$trimmed"
}

private fun sizeComparison(operator: FilterOperator): String = if (operator == FilterOperator.SMALLER) "smaller" else "larger"

private fun rowTerm(row: FilterRow): String = when (row.field) {
    FilterField.FROM, FilterField.TO, FilterField.SUBJECT -> "${row.field.wire}:${grouped(row.value)}"
    FilterField.HAS_WORDS -> grouped(row.value)
    FilterField.DOES_NOT_HAVE -> without(row.value)
    FilterField.SIZE -> "${sizeComparison(row.operator)}:${searchSize(parseSize(row.value) ?: 0)}"
    FilterField.HAS_ATTACHMENT -> "has:attachment"
}

private fun negatedTerm(term: String): String = if (term.startsWith("-")) term.substring(1) else "-$term"

/**
 * Criteria for the editor's conditions, as Gmail's own editor writes them where it can.
 *
 * The first row of each field in "all" groups fills the structured fields; further rows, "any"
 * groups (`{a b}`) and "not" groups (`-a -b`) are added to `query`. Rows with a [rowError] are left out.
 */
fun conditionsToCriteria(conditions: FilterConditions): GmailFilterCriteria {
    if (conditions is FilterConditions.Raw) {
        val query = conditions.query.trim()
        return if (query.isEmpty()) GmailFilterCriteria() else GmailFilterCriteria(query = query)
    }
    var criteria = GmailFilterCriteria()
    val extra = mutableListOf<String>()
    val seen = mutableSetOf<FilterField>()
    var hasWords: String? = null
    for (group in (conditions as FilterConditions.Rows).groups) {
        val rows = group.rows.filter { rowError(it) == null }
        if (group.match != GroupMatch.ALL) {
            val terms = rows.map(::rowTerm)
            when {
                group.match == GroupMatch.NOT -> extra += terms.map(::negatedTerm)
                terms.size == 1 -> extra += terms[0]
                terms.size > 1 -> extra += "{${terms.joinToString(" ")}}"
            }
            continue
        }
        for (row in rows) {
            if (!seen.add(row.field)) {
                extra += rowTerm(row)
                continue
            }
            val value = row.value.trim()
            criteria = when (row.field) {
                FilterField.FROM -> criteria.copy(from = value)
                FilterField.TO -> criteria.copy(to = value)
                FilterField.SUBJECT -> criteria.copy(subject = value)
                FilterField.HAS_WORDS -> criteria.also { hasWords = value }
                FilterField.DOES_NOT_HAVE -> criteria.copy(negatedQuery = value)
                FilterField.SIZE -> criteria.copy(size = parseSize(value) ?: 0, sizeComparison = sizeComparison(row.operator))
                FilterField.HAS_ATTACHMENT -> criteria.copy(hasAttachment = true)
            }
        }
    }
    val query = (listOfNotNull(hasWords) + extra).joinToString(" ")
    return if (query.isEmpty()) criteria else criteria.copy(query = query)
}

/** Search text that finds what the criteria match, for the live preview and the raw editor. */
fun criteriaToSearch(criteria: GmailFilterCriteria): String {
    val parts = mutableListOf<String>()
    for ((field, raw) in listOf("from" to criteria.from, "to" to criteria.to, "subject" to criteria.subject)) {
        val value = raw?.trim()
        if (!value.isNullOrEmpty()) parts += "$field:${grouped(value)}"
    }
    val query = criteria.query?.trim()
    if (!query.isNullOrEmpty()) {
        val tokens = tokenize(query)
        parts += if (tokens.size > 1 && tokens.any { it.text == "OR" }) "($query)" else query
    }
    val negated = criteria.negatedQuery?.trim()
    if (!negated.isNullOrEmpty()) parts += without(negated)
    if (criteria.hasAttachment == true) parts += "has:attachment"
    val size = criteria.size
    if (size != null && size > 0) {
        val comparison = if (criteria.sizeComparison == "smaller") "smaller" else "larger"
        parts += "$comparison:${searchSize(size)}"
    }
    return parts.joinToString(" ")
}

/** Search text for the editor's conditions (the live preview). */
fun conditionsToSearch(conditions: FilterConditions): String = criteriaToSearch(conditionsToCriteria(conditions))

private fun unwrapped(text: String, open: Char, close: Char): String? {
    if (!text.startsWith(open) || !text.endsWith(close) || text.length < 2) return null
    val inner = text.substring(1, text.length - 1)
    return if (tokenize(inner).all { it.complete }) inner.trim() else null
}

private fun searchBytes(value: String): Int? {
    val match = SEARCH_SIZE_RE.find(value) ?: return null
    val unit = match.groupValues[2].lowercase()
    val scale = when (unit) {
        "m" -> MB
        "k" -> KB
        else -> 1
    }
    return match.groupValues[1].toInt() * scale
}

private fun operatorRow(term: String): FilterRow? {
    val match = OPERATOR_TERM_RE.find(term) ?: return null
    val operator = match.groupValues[1].lowercase()
    val value = match.groupValues[2]
    return when (operator) {
        "from" -> FilterRow(FilterField.FROM, FilterOperator.CONTAINS, unwrapped(value, '(', ')') ?: value)
        "to" -> FilterRow(FilterField.TO, FilterOperator.CONTAINS, unwrapped(value, '(', ')') ?: value)
        "subject" -> FilterRow(FilterField.SUBJECT, FilterOperator.CONTAINS, unwrapped(value, '(', ')') ?: value)
        "larger", "smaller", "size" -> searchBytes(value)?.let {
            FilterRow(FilterField.SIZE, if (operator == "smaller") FilterOperator.SMALLER else FilterOperator.LARGER, formatSize(it))
        }
        "has" -> if (value.lowercase() == "attachment") FilterRow(FilterField.HAS_ATTACHMENT, FilterOperator.IS, "") else null
        else -> null
    }
}

private fun termRows(texts: List<String>): List<FilterRow>? {
    val rows = mutableListOf<FilterRow>()
    for (text in texts) {
        if (text == "OR" || text.startsWith("-") || text.startsWith("{")) return null
        rows += operatorRow(text) ?: FilterRow(FilterField.HAS_WORDS, FilterOperator.CONTAINS, unwrapped(text, '(', ')') ?: text)
    }
    return rows
}

private class QueryRows(val all: List<FilterRow>, val others: List<ConditionGroup>)

private fun queryRows(query: String): QueryRows? {
    val tokens = tokenize(query)
    if (tokens.any { !it.complete }) return null
    if (tokens.any { it.text == "OR" }) {
        return QueryRows(listOf(FilterRow(FilterField.HAS_WORDS, FilterOperator.CONTAINS, query.trim())), emptyList())
    }
    val all = mutableListOf<FilterRow>()
    val others = mutableListOf<ConditionGroup>()
    val words = mutableListOf<String>()
    for (token in tokens) {
        val text = token.text
        if (text.startsWith("-") && text.length > 1) {
            val rest = text.substring(1)
            val inner = unwrapped(rest, '{', '}')
            val rows = termRows(if (inner == null) listOf(rest) else tokenize(inner).map { it.text }) ?: return null
            val last = others.lastOrNull()
            if (last?.match == GroupMatch.NOT && inner == null) {
                others[others.size - 1] = last.copy(rows = last.rows + rows)
            } else {
                others += ConditionGroup(GroupMatch.NOT, rows)
            }
            continue
        }
        val inner = unwrapped(text, '{', '}')
        if (inner != null) {
            val rows = termRows(tokenize(inner).map { it.text }) ?: return null
            others += ConditionGroup(GroupMatch.ANY, rows)
            continue
        }
        val row = operatorRow(text)
        if (row != null) all += row else words += text
    }
    if (words.isNotEmpty()) all += FilterRow(FilterField.HAS_WORDS, FilterOperator.CONTAINS, words.joinToString(" "))
    return QueryRows(all, others)
}

/**
 * The editor's conditions for a filter's criteria: rows in an "all" group, then "any" and "not"
 * groups from the query, or raw search text when the criteria don't parse into rows.
 */
fun criteriaToConditions(criteria: GmailFilterCriteria): FilterConditions {
    val raw = FilterConditions.Raw(criteriaToSearch(criteria))
    if (criteria.size != null && criteria.sizeComparison == "unspecified") return raw
    val all = mutableListOf<FilterRow>()
    for ((field, value) in listOf(FilterField.FROM to criteria.from, FilterField.TO to criteria.to, FilterField.SUBJECT to criteria.subject)) {
        val trimmed = value?.trim()
        if (!trimmed.isNullOrEmpty()) all += FilterRow(field, FilterOperator.CONTAINS, trimmed)
    }
    val negated = criteria.negatedQuery?.trim()
    if (!negated.isNullOrEmpty()) {
        if (tokenize(negated).any { !it.complete }) return raw
        all += FilterRow(FilterField.DOES_NOT_HAVE, FilterOperator.CONTAINS, negated)
    }
    val size = criteria.size
    if (size != null && size > 0) {
        val operator = if (criteria.sizeComparison == "smaller") FilterOperator.SMALLER else FilterOperator.LARGER
        all += FilterRow(FilterField.SIZE, operator, formatSize(size))
    }
    if (criteria.hasAttachment == true) all += FilterRow(FilterField.HAS_ATTACHMENT, FilterOperator.IS, "")
    var others: List<ConditionGroup> = emptyList()
    val query = criteria.query?.trim()
    if (!query.isNullOrEmpty()) {
        val parsed = queryRows(query) ?: return raw
        all += parsed.all
        others = parsed.others
    }
    val sorted = all.sortedBy { it.field.ordinal }
    val groups = if (sorted.isNotEmpty()) listOf(ConditionGroup(GroupMatch.ALL, sorted)) + others else others
    return FilterConditions.Rows(groups)
}

/** The editor's actions for a filter's action. */
fun actionFromGmail(action: GmailFilterAction): FilterActions {
    var actions = NO_ACTIONS
    val otherAdd = mutableListOf<String>()
    val otherRemove = mutableListOf<String>()
    for (id in action.addLabelIds.orEmpty()) {
        actions = when {
            id == "STARRED" -> actions.copy(star = true)
            id == "IMPORTANT" -> actions.copy(important = Importance.ALWAYS)
            id == "TRASH" -> actions.copy(trash = true)
            id.startsWith("Label_") && actions.labelId == null -> actions.copy(labelId = id)
            else -> actions.also { otherAdd += id }
        }
    }
    for (id in action.removeLabelIds.orEmpty()) {
        actions = when (id) {
            "INBOX" -> actions.copy(archive = true)
            "UNREAD" -> actions.copy(markRead = true)
            "SPAM" -> actions.copy(neverSpam = true)
            "IMPORTANT" -> actions.copy(important = Importance.NEVER)
            else -> actions.also { otherRemove += id }
        }
    }
    return actions.copy(
        forward = action.forward?.ifEmpty { null },
        otherAddLabelIds = otherAdd,
        otherRemoveLabelIds = otherRemove,
    )
}

/** A filter's action for the editor's actions; empty lists and a null forward are left out. */
fun actionToGmail(actions: FilterActions): GmailFilterAction {
    val add = buildList {
        actions.labelId?.let(::add)
        if (actions.star) add("STARRED")
        if (actions.important == Importance.ALWAYS) add("IMPORTANT")
        if (actions.trash) add("TRASH")
        addAll(actions.otherAddLabelIds)
    }
    val remove = buildList {
        if (actions.archive) add("INBOX")
        if (actions.markRead) add("UNREAD")
        if (actions.neverSpam) add("SPAM")
        if (actions.important == Importance.NEVER) add("IMPORTANT")
        addAll(actions.otherRemoveLabelIds)
    }
    return GmailFilterAction(
        addLabelIds = add.ifEmpty { null },
        removeLabelIds = remove.ifEmpty { null },
        forward = actions.forward?.ifEmpty { null },
    )
}

/** Label ids a filter adds and removes, for applying it to mail already there. */
fun labelChanges(actions: FilterActions): LabelDelta = LabelDelta(
    add = buildList {
        actions.labelId?.takeIf { it.isNotEmpty() }?.let(::add)
        if (actions.star) add("STARRED")
        if (actions.important == Importance.ALWAYS) add("IMPORTANT")
        if (actions.trash) add("TRASH")
    },
    remove = buildList {
        if (actions.archive) add("INBOX")
        if (actions.markRead) add("UNREAD")
        if (actions.important == Importance.NEVER) add("IMPORTANT")
    },
)

/** Load a Gmail filter into the editor. */
fun filterToEditor(filter: GmailFilter): FilterEditor = FilterEditor(
    conditions = criteriaToConditions(filter.criteria ?: GmailFilterCriteria()),
    actions = actionFromGmail(filter.action ?: GmailFilterAction()),
)

/** The Gmail filter to create for the editor's state (an edit is delete + create). */
fun editorToFilter(editor: FilterEditor): NewFilter =
    NewFilter(conditionsToCriteria(editor.conditions), actionToGmail(editor.actions))

/** Whether the editor's filter can be saved: it matches something and does something; leaving the editor keeps a change only then. */
fun filterIsWhole(editor: FilterEditor): Boolean {
    val (criteria, action) = editorToFilter(editor)
    return criteria != GmailFilterCriteria() &&
        (!action.addLabelIds.isNullOrEmpty() || !action.removeLabelIds.isNullOrEmpty() || action.forward != null)
}
