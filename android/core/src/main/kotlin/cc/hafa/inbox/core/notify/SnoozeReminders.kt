package cc.hafa.inbox.core.notify

import cc.hafa.inbox.core.compose.InlineStyle
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.epochToIso
import cc.hafa.inbox.core.mail.isoToEpoch
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoState
import java.time.ZoneId

private const val TODO_ITEM_PREFIX = "todo:"

/** The snooze item id of a todo: `todo:` and its id, apart from Gmail's thread ids. */
fun todoItemId(todoId: String): String = TODO_ITEM_PREFIX + todoId

/** The todo a snooze item id names, or null when it names a thread. */
fun todoIdOf(itemId: String): String? = itemId.removePrefix(TODO_ITEM_PREFIX).takeIf { itemId.startsWith(TODO_ITEM_PREFIX) }

/** An item's snooze end as stored: a local `YYYY-MM-DDTHH:mm:ss`, read in the device's zone. */
data class SnoozeEnd(
    /** the snoozed item: a Gmail thread id, or a todo's [todoItemId] */
    val itemId: String,
    /** when the snooze ends, local wall time */
    val until: String,
) {
    /** When the snooze ends in [zone], epoch milliseconds. */
    fun endsAt(zone: ZoneId): Long = isoToEpoch(until, zone)
}

/** What a snooze check found: the snoozes to notify about, and the one to wait on next. */
data class SnoozeCheck(
    /** snoozes that ended after the last check and by now, earliest first */
    val ended: List<SnoozeEnd>,
    /** the earliest snooze still ahead, or null when none is */
    val next: SnoozeEnd?,
)

/** When the last snooze check ran, and the zone the phone was in then. */
data class LastCheck(
    /** when it ran, epoch milliseconds */
    val at: Long,
    /** the phone's zone at the time */
    val zone: ZoneId,
)

/**
 * Sort [ends] for a check at [now]: those that ended after the previous check [since] are due a
 * notification (none on the first check, when [since] is null), and the earliest still ahead is
 * the one to wait on. After a zone change, "ended since" goes by wall time: a snooze ended when
 * its wall time lies after the old check's wall time (in the old zone) and by now's, so flying east
 * skips none and flying west repeats none.
 */
fun checkSnoozes(ends: List<SnoozeEnd>, since: LastCheck?, now: Long, zone: ZoneId): SnoozeCheck {
    val order = compareBy<SnoozeEnd>({ it.endsAt(zone) }, { it.itemId })
    val ended = when {
        since == null -> emptyList()
        since.zone == zone -> ends.filter { it.endsAt(zone) in (since.at + 1)..now }
        else -> {
            val from = epochToIso(since.at, since.zone)
            val until = epochToIso(now, zone)
            ends.filter { it.until > from && it.until <= until }
        }
    }
    return SnoozeCheck(ended.sortedWith(order), ends.filter { it.endsAt(zone) > now }.minWithOrNull(order))
}

/** The phone's notification for a snooze that ended, from the thread as last seen ([thread], null when unknown). */
fun snoozeEndedPayload(snooze: SnoozeEnd, thread: ThreadSummary?, zone: ZoneId): PushPayload {
    val encrypted = thread?.encrypted == true
    val sender = thread?.senders?.lastOrNull()
    return PushPayload(
        kind = PushKind.SNOOZE_ENDED,
        threadId = snooze.itemId,
        messageId = "",
        sender = sender?.name.orEmpty(),
        senderAddress = sender?.email.orEmpty(),
        subject = if (encrypted) "" else thread?.subject.orEmpty(),
        snippet = "",
        account = "",
        actionToken = "",
        sentAt = snooze.endsAt(zone).toString(),
        encrypted = encrypted,
    )
}

/**
 * A stored todo's snooze end, or null when it has none to wait on: deleted, done, or never
 * snoozed. One whose snooze already passed still has one (the time it woke), as threads do.
 */
fun todoSnoozeEnd(todoId: String, data: Map<String, Any?>): SnoozeEnd? = todoSnoozeEnd(Todo.read(todoId, data, 0L))

/** A todo's snooze end, or null when it has none to wait on; see the stored todo's [todoSnoozeEnd]. */
fun todoSnoozeEnd(todo: Todo): SnoozeEnd? {
    val until = todo.snoozeUntil
    return if (todo.deleted || todo.state == TodoState.DONE || until == null) null else SnoozeEnd(todoItemId(todo.id), until)
}

/** The phone's notification for a todo whose snooze ended: its text, markers removed, with Done. */
fun todoSnoozeEndedContent(snooze: SnoozeEnd, text: String, zone: ZoneId): NotificationContent =
    NotificationContent(
        title = InlineStyle.plain(text).trim().ifEmpty { "Todo" },
        body = "Back from snooze",
        tag = snooze.itemId,
        timestamp = snooze.endsAt(zone),
        canDone = true,
    )
