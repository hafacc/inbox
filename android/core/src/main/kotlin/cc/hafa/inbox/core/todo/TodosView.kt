// What the Todos view lists (design.md, "Lists"): the active todos of their own and the todos on
// emails whose email is active, under latr's headings. The same as shared/src/todos/todos-view.ts;
// both run shared/testdata/todos-view-fixtures.json.
package cc.hafa.inbox.core.todo

import java.time.Instant
import java.time.ZoneId

/** An email with a todo, as the Todos view reads it: its own state, which is its todo's. */
data class TodosViewEmail<Email>(
    /** The email (a list row), handed back as it is. */
    val email: Email,
    /** Its todo. */
    val todo: EmailTodo,
    /** Whether it is in Gmail's inbox. */
    val inbox: Boolean,
    /** Whether its snooze is still ahead. */
    val snoozed: Boolean,
    /** Whether it is pinned (starred). */
    val pinned: Boolean,
    /** Its date in a list, epoch milliseconds: its latest received message, or a woken snooze's wake time. */
    val sortDate: Long,
    /** Where it sat when it opened, while it is open (or its close still plays): it stays there. */
    val held: TodosViewPlace? = null,
)

/** Where an open email's todo sat in the Todos view when the email opened. */
data class TodosViewPlace(
    /** The date it sorted by, epoch milliseconds. */
    val date: Long,
    /** Whether it sat under Pinned. */
    val pinned: Boolean,
)

/** Where [email] sits in the Todos view now, to hold while it is open; null when it isn't listed. */
fun <Email> todosViewPlace(email: TodosViewEmail<Email>): TodosViewPlace? = when {
    email.held != null -> email.held
    email.inbox && !email.snoozed -> TodosViewPlace(email.sortDate, email.pinned)
    else -> null
}

/** One row of the Todos view: a todo of its own, or an email's todo. */
sealed interface TodosViewEntry<out Email> {
    /** A todo of its own. */
    data class Own(
        /** The todo. */
        val todo: Todo,
    ) : TodosViewEntry<Nothing>

    /** An email's todo, its row the email's. */
    data class OnEmail<Email>(
        /** The email's row. */
        val email: Email,
        /** Its todo. */
        val todo: EmailTodo,
    ) : TodosViewEntry<Email>
}

/** One heading of the Todos view and its rows, in list order. */
data class TodosViewBucket<Email>(
    /** The heading. */
    val label: String,
    /** Whether it is the Pinned heading. */
    val pinned: Boolean,
    /** Its rows. */
    val entries: List<TodosViewEntry<Email>>,
)

private data class Keyed<Email>(val entry: TodosViewEntry<Email>, val key: String, val id: String, val pinned: Boolean)

private val HEADINGS = listOf("Pinned", "Today", "Yesterday", "This week", "Earlier")
private const val WEEK_DAYS = 6L

// latr's date heading for a row dated [at], as [groupActive] heads them.
private fun dateHeading(at: Long, now: Long, zone: ZoneId): String {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = { date: java.time.LocalDate -> date.atStartOfDay(zone).toInstant().toEpochMilli() }
    return when {
        at >= day(today) -> "Today"
        at >= day(today.minusDays(1)) -> "Yesterday"
        at >= day(today.minusDays(WEEK_DAYS)) -> "This week"
        else -> "Earlier"
    }
}

/**
 * The Todos view's rows under their headings: each active todo of its own (as [groupActive] lists
 * them), and each email's todo whose email is in the inbox and not snoozed, pinned when its email
 * is, dated by its email; an open one ([TodosViewEmail.held]) stays where it sat when it opened. Pinned first, then Today, Yesterday, This week and Earlier; newest first
 * under each, ties by id.
 */
fun <Email> todosView(
    todos: List<Todo>,
    emails: List<TodosViewEmail<Email>>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TodosViewBucket<Email>> {
    val keyed = todos.filter { it.matches(TodoFilter.ACTIVE, now, zone) }
        .map { Keyed<Email>(TodosViewEntry.Own(it), it.activeKey(zone), it.id, it.pinned) } +
        emails.filter { it.held != null || (it.inbox && !it.snoozed) }.map {
            Keyed(
                TodosViewEntry.OnEmail(it.email, it.todo),
                Position.timeKey(it.held?.date ?: it.sortDate) + Position.idKey(it.todo.threadId),
                it.todo.id,
                it.held?.pinned ?: it.pinned,
            )
        }
    val sorted = keyed.sortedWith { left, right ->
        if (left.key == right.key) left.id.compareTo(right.id) else right.key.compareTo(left.key)
    }
    val byHeading = sorted.groupBy(
        { if (it.pinned) "Pinned" else dateHeading(Position.keyTime(it.key), now, zone) },
        { it.entry },
    )
    return HEADINGS.mapNotNull { label ->
        byHeading[label]?.let { TodosViewBucket(label, label == "Pinned", it) }
    }
}

/** The Todos view's headings and rows as far as [limit] rows reach. */
fun <Email> limitTodosView(buckets: List<TodosViewBucket<Email>>, limit: Int): List<TodosViewBucket<Email>> {
    val shown = mutableListOf<TodosViewBucket<Email>>()
    var left = limit
    for (bucket in buckets) {
        if (left <= 0) break
        shown += if (bucket.entries.size <= left) bucket else bucket.copy(entries = bucket.entries.take(left))
        left -= bucket.entries.size
    }
    return shown
}

/** How many rows the Todos view has under all its headings. */
fun <Email> todosViewCount(buckets: List<TodosViewBucket<Email>>): Int = buckets.sumOf { it.entries.size }
