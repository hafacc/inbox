package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.LabelTags
import cc.hafa.inbox.core.todo.Tags
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoFilter
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.core.todo.isSnoozed
import cc.hafa.inbox.core.todo.rankBySearch
import cc.hafa.inbox.core.todo.sortedFor
import java.time.ZoneId

// What a search lists: the mail Gmail is asked for and the todos the device adds. A port of
// shared/src/search-scope.ts; both run shared/testdata/search-scope-fixtures.json.

/** Which todos a search lists. */
enum class SearchTodos {
    /** no todos */
    NONE,

    /** every todo */
    ALL,

    /** only those still snoozed */
    SNOOZED,
}

/** What a search lists. */
data class SearchScope(
    /** Gmail's `q` for the mail listed, our own chips left out; null when Gmail lists no mail for it */
    val gmailQuery: String?,
    /** whether Gmail is asked for spam and trash too */
    val includeSpamTrash: Boolean,
    /** which todos are listed */
    val todos: SearchTodos,
    /** `is:snoozed`: only threads still snoozed, from the device's snoozes when there is no Gmail query */
    val snoozedOnly: Boolean,
)

private val SPAM_OR_TRASH = setOf("spam", "trash", "anywhere")

// The chips a todo can be held to (each also negated), by what it must be for each.
private val TODO_CHIPS: Map<String, (Todo, Long, ZoneId) -> Boolean> = mapOf(
    "is:todo" to { _, _, _ -> true },
    "is:snoozed" to { todo, now, zone -> todo.isSnoozed(now, zone) },
    "is:pinned" to { todo, _, _ -> todo.pinned },
    "is:starred" to { todo, _, _ -> todo.pinned },
    "is:done" to { todo, _, _ -> todo.state == TodoState.DONE },
    "in:inbox" to { todo, _, _ -> todo.state != TodoState.DONE },
)

private fun todoChipTest(chip: Chip) = TODO_CHIPS["${chip.operator}:${chip.value.lowercase()}"]

/**
 * What a whole search query lists. `is:todo` lists todos and no mail; `is:snoozed` or `in:outbox`
 * with nothing else lists no mail from Gmail (the device has those); `-is:todo` lists no todos, so
 * on its own it lists all mail. Todos show only while every chip is one a todo can be held to:
 * `is:todo`, `is:snoozed`, `is:pinned` (or `is:starred`), `is:done`, `in:inbox` (any of them
 * negated) or `label:` (which [searchTodos] reads against the todos' tags). `is:pinned` and
 * `is:done` go to Gmail as `is:starred` and `-in:inbox` ([gmailQueryText]).
 */
fun searchScope(query: String): SearchScope {
    val (chips, free) = parseQuery(query, null)
    fun has(operator: String, value: String, negated: Boolean) =
        chips.any { isLocalChip(it) && it.operator == operator && it.value.lowercase() == value && it.negated == negated }
    val todosOnly = has("is", "todo", negated = false)
    val snoozedOnly = has("is", "snoozed", negated = false)
    val outbox = has("in", "outbox", negated = false)
    val mailQuery = gmailQueryText(chips, free)
    val deviceOnly = mailQuery.isEmpty() && (snoozedOnly || outbox)
    val mailChips = chips.any { it.operator != "label" && todoChipTest(it) == null }
    val todos = when {
        has("is", "todo", negated = true) || (mailChips && !todosOnly) -> SearchTodos.NONE
        snoozedOnly -> SearchTodos.SNOOZED
        else -> SearchTodos.ALL
    }
    return SearchScope(
        gmailQuery = if (todosOnly || deviceOnly) null else mailQuery,
        includeSpamTrash = chips.any { it.operator == "in" && !it.negated && it.value.lowercase() in SPAM_OR_TRASH },
        todos = todos,
        snoozedOnly = snoozedOnly,
    )
}

/**
 * The todos a search lists at [now], best match first: for `is:snoozed` the snoozed ones, soonest
 * to wake first; otherwise every todo in the All list's order. Ties keep that order. A `label:`
 * keeps the todos with a tag naming that label among [labels], `-label:` those without.
 * `is:pinned` (or `is:starred`) keeps the pinned ones, `is:done` the done ones, `in:inbox` those
 * not done (snoozed included, as a snoozed email stays in Gmail's inbox), and `-is:snoozed` those
 * not snoozed; each negated keeps the others. A `-#tag` word keeps the todos without that tag; a
 * `#tag` word ranks like any other word.
 */
fun searchTodos(
    todos: List<Todo>,
    query: String,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    labels: LabelTags = LabelTags(emptyList()),
): List<Todo> {
    val scope = searchScope(query).todos
    if (scope == SearchTodos.NONE) return emptyList()
    val kept = matchingText(todos, query, labels, { it.text }) { chip, todo -> todoChipTest(chip)?.invoke(todo, now, zone) ?: true }
    val shown = kept.sortedFor(if (scope == SearchTodos.SNOOZED) TodoFilter.SNOOZED else TodoFilter.ALL, zone)
    return rankBySearch(shown, freeWords(query))
}

/**
 * The emails a search lists for their todos, by thread id, in the order given: those whose todo's
 * text matches as [searchTodos] reads text (words, `#tag` and `-#tag` words, `label:` and
 * `-label:`). Whether the email is in the inbox, snoozed, pinned or done is the email's own, so
 * the list's filter holds those terms to it as to any thread. None when the search lists no todos.
 */
fun searchEmailTodos(todos: List<EmailTodo>, query: String, labels: LabelTags = LabelTags(emptyList())): List<String> {
    if (searchScope(query).todos == SearchTodos.NONE) return emptyList()
    val kept = matchingText(todos, query, labels, { it.text }) { _, _ -> null }
    return rankBySearch(kept, freeWords(query)) { it.text }.map { it.threadId }
}

// The query's free text with its `-#tag` words left out, as todos rank by it.
private fun freeWords(query: String): String {
    val tagged = parseQuery(parseQuery(query, null).free, null, tags = true)
    return queryText(tagged.chips.filter { it.operator == TAG_CHIP && !it.negated }, tagged.free)
}

// The items whose text lacks every `-#tag` and holds to every chip: `label:` read against the tags,
// any other by [holds], which answers null for a chip it leaves to something else.
private fun <Item> matchingText(
    items: List<Item>,
    query: String,
    labels: LabelTags,
    textOf: (Item) -> String,
    holds: (Chip, Item) -> Boolean?,
): List<Item> {
    val (chips, free) = parseQuery(query, null)
    val left = parseQuery(free, null, tags = true).chips
        .filter { it.operator == TAG_CHIP && it.negated }
        .map { Tags.key(it.value) }
        .toSet()
    fun lacksLeftTags(text: String): Boolean = Tags.parse(text).none { Tags.key(text.substring(it.start + 1, it.end)) in left }
    fun holdsChip(chip: Chip, item: Item): Boolean {
        val held = if (chip.operator == "label") labels.namesQuery(textOf(item), chip.value) else holds(chip, item)
        return held == null || held != chip.negated
    }
    return items.filter { item -> lacksLeftTags(textOf(item)) && chips.all { holdsChip(it, item) } }
}
