package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.mail.epochToIso
import cc.hafa.inbox.core.mail.isLocalIso
import cc.hafa.inbox.core.mail.isoToEpoch
import java.time.ZoneId

// latr's todo, the same as shared/src/todos/todo.ts (ported from latr's web utils/todo.ts and the
// edits its app state makes); both run shared/testdata/todo-fixtures.json. Stored at
// users/{uid}/todos/{id} (design.md, "Todos").

/** Whether a todo is done; snoozed is derived, never stored. */
enum class TodoState {
    /** not done (snoozed or not) */
    ACTIVE,

    /** done */
    DONE,
}

/** The todo lists. */
enum class TodoFilter {
    /** not done and not snoozed */
    ACTIVE,

    /** snoozed until a time still ahead */
    SNOOZED,

    /** done */
    DONE,

    /** every live todo */
    ALL,
}

/** A todo as the apps hold it. */
data class Todo(
    /** doc id: a UUID for new todos, latr's id for imported ones */
    val id: String,
    /** text with latr's inline style markers kept */
    val text: String,
    /** done or not */
    val state: TodoState,
    /** when it was made, epoch milliseconds */
    val createdAt: Long,
    /** when it was last changed, epoch milliseconds; orders an unmoved row */
    val modifiedAt: Long,
    /** local `YYYY-MM-DDTHH:mm:ss` it is snoozed until; once past, the time it woke */
    val snoozeUntil: String?,
    /** floats to the top of the active list */
    val pinned: Boolean,
    /** where a move put the row; null orders it by its time */
    val position: String?,
    /** a tombstone: deletes keep the doc so a device's stale copy can't bring it back */
    val deleted: Boolean,
    /** how the todo comes back once done; null for a one-off */
    val repeat: Repeat?,
    /** true once marking this one done has made its next copy */
    val repeatSpawned: Boolean,
    /** the local `YYYY-MM-DDTHH:mm:ss` this copy of a repeating todo stands for */
    val occurrence: String?,
) {
    /** What a write stores, without the id; the store adds `serverModifiedAt`. */
    fun fields(): Map<String, Any?> = linkedMapOf(
        "text" to text,
        "state" to state.name,
        "createdAt" to createdAt,
        "modifiedAt" to modifiedAt,
        "snoozeUntil" to snoozeUntil,
        "pinned" to pinned,
        "position" to position,
        "deleted" to deleted,
        "repeat" to repeat?.toMap(),
        "repeatSpawned" to repeatSpawned,
        "occurrence" to occurrence,
    )

    /** Making and reading todos. */
    companion object {
        /** A new todo with [text], made at [now]. */
        fun new(id: String, text: String, now: Long): Todo = Todo(
            id = id,
            text = text,
            state = TodoState.ACTIVE,
            createdAt = now,
            modifiedAt = now,
            snoozeUntil = null,
            pinned = false,
            position = null,
            deleted = false,
            repeat = null,
            repeatSpawned = false,
            occurrence = null,
        )

        /** A stored state; latr's old "SNOOZED" (and anything else) reads as active. */
        fun readState(value: Any?): TodoState = if (value == TodoState.DONE.name) TodoState.DONE else TodoState.ACTIVE

        private fun readLocalTime(value: Any?): String? = (value as? String)?.takeIf { isLocalIso(it) }

        /** A stored todo; missing or malformed fields read as latr reads them, missing times as [now]. */
        fun read(id: String, data: Map<String, Any?>, now: Long): Todo = Todo(
            id = id,
            text = data["text"] as? String ?: "",
            state = readState(data["state"]),
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: now,
            modifiedAt = (data["modifiedAt"] as? Number)?.toLong() ?: now,
            snoozeUntil = readLocalTime(data["snoozeUntil"]),
            pinned = data["pinned"] == true,
            position = Position.readPosition(data["position"]),
            deleted = data["deleted"] == true,
            repeat = Repeat.read(data["repeat"]),
            repeatSpawned = data["repeatSpawned"] == true,
            occurrence = readLocalTime(data["occurrence"]),
        )
    }
}

/** What marking a todo done writes: the done todo, and the next copy when it repeats. */
data class Completion(
    /** the todo, marked done */
    val done: Todo,
    /** the repeating todo's next copy, or null */
    val spawned: Todo?,
)

/** Snoozed-ness is derived, not stored: a todo not done whose snooze time is still ahead. */
fun Todo.isSnoozed(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    state != TodoState.DONE && snoozeUntil?.let { isoToEpoch(it, zone) > now } == true

/** Whether this todo shows in the [filter] list at [now]. */
fun Todo.matches(filter: TodoFilter, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean = when (filter) {
    TodoFilter.ALL -> true
    TodoFilter.DONE -> state == TodoState.DONE
    TodoFilter.SNOOZED -> isSnoozed(now, zone)
    TodoFilter.ACTIVE -> state != TodoState.DONE && !isSnoozed(now, zone)
}

/** A row's own place: where a move put it, else its modify time. */
fun Todo.ownKey(): String = position ?: (Position.timeKey(modifiedAt) + Position.idKey(id))

/** A row's place in Active: a row that was snoozed sits at its unsnooze time. */
fun Todo.activeKey(zone: ZoneId = ZoneId.systemDefault()): String =
    snoozeUntil?.let { Position.timeKey(isoToEpoch(it, zone)) + ownKey() } ?: ownKey()

/**
 * The key a todo sorts by among threads in any list, newest first: a done one's own place, any
 * other's place in Active (a snoozed or woken one at its wake time).
 */
fun Todo.listDateKey(zone: ZoneId = ZoneId.systemDefault()): String =
    if (state == TodoState.DONE) ownKey() else activeKey(zone)

/** When a todo sorts among threads in any list: the time [listDateKey] starts with. */
fun Todo.listDate(zone: ZoneId = ZoneId.systemDefault()): Long = Position.keyTime(listDateKey(zone))

/**
 * These todos in the order they keep among threads, newest first by [listDateKey], then id; two with
 * the same time go by the rest of their keys, so a todo started just above another stays above it.
 */
fun List<Todo>.newestFirst(zone: ZoneId = ZoneId.systemDefault()): List<Todo> =
    map { it to it.listDateKey(zone) }
        .sortedWith(compareByDescending<Pair<Todo, String>> { (_, key) -> key }.thenBy { (todo, _) -> todo.id })
        .map { (todo, _) -> todo }

/** The key a list orders by, newest first (Snoozed orders by snooze time before it). */
fun Todo.listKey(filter: TodoFilter, zone: ZoneId = ZoneId.systemDefault()): String =
    if (filter == TodoFilter.ACTIVE) activeKey(zone) else ownKey()

/**
 * These todos in the order the [filter] list shows them: Snoozed by snooze time, then every list
 * by [listKey], newest first, then id. Pinned rows aren't moved here; the Active list puts them in
 * their own group.
 */
fun List<Todo>.sortedFor(filter: TodoFilter, zone: ZoneId = ZoneId.systemDefault()): List<Todo> =
    map { todo ->
        val snoozeAt = if (filter == TodoFilter.SNOOZED) todo.snoozeUntil?.let { isoToEpoch(it, zone) } ?: 0L else 0L
        Triple(todo, todo.listKey(filter, zone), snoozeAt)
    }
        .sortedWith(
            compareBy<Triple<Todo, String, Long>> { (_, _, snoozeAt) -> snoozeAt }
                .thenByDescending { (_, key, _) -> key }
                .thenBy { (todo, _, _) -> todo.id },
        )
        .map { (todo, _, _) -> todo }

/** This todo with new [text]; the first edit of a row that woke from a snooze keeps it where it sits. */
fun Todo.withText(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo {
    val woke = snoozeUntil
    return if (matches(TodoFilter.ACTIVE, now, zone) && woke != null) {
        copy(text = text, snoozeUntil = null, position = null, modifiedAt = isoToEpoch(woke, zone))
    } else {
        copy(text = text)
    }
}

/** This todo snoozed at [now] until [until] (epoch milliseconds). */
fun Todo.withSnoozed(until: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo =
    copy(snoozeUntil = epochToIso(until, zone), position = null, modifiedAt = now)

/** Flips [Todo.pinned]; only an active row also drops its was-snoozed marker. */
fun Todo.withPinToggled(now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo =
    if (matches(TodoFilter.ACTIVE, now, zone)) {
        copy(pinned = !pinned, position = null, snoozeUntil = null, modifiedAt = now)
    } else {
        copy(pinned = !pinned, position = null, modifiedAt = now)
    }

/** This todo marked done at [now], and the next copy it makes if it repeats and hasn't made one yet. */
fun Todo.withDone(now: Long, newId: String, zone: ZoneId = ZoneId.systemDefault()): Completion {
    val done = copy(state = TodoState.DONE, snoozeUntil = null, position = null, modifiedAt = now)
    val rule = repeat
    return if (rule == null || repeatSpawned) {
        Completion(done, null)
    } else {
        val nowIso = epochToIso(now, zone)
        val after = occurrence?.takeIf { it > nowIso } ?: nowIso
        val next = rule.nextOccurrence(after)
        Completion(
            done.copy(repeatSpawned = true),
            Todo.new(newId, text, now).copy(snoozeUntil = next, pinned = pinned, repeat = rule, occurrence = next),
        )
    }
}

/** This todo given the rule [draft] at [now]; its snooze, place and copies are left alone. */
fun Todo.withRepeat(draft: RepeatDraft, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo =
    copy(repeat = draft.anchored(epochToIso(now, zone)))

/** A done or snoozed todo made active at [now]; unsnoozing one also stops it repeating. */
fun Todo.withReactivated(now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo {
    val reactivated = copy(state = TodoState.ACTIVE, snoozeUntil = null, position = null, modifiedAt = now)
    return if (isSnoozed(now, zone)) {
        reactivated.copy(repeat = null, repeatSpawned = false, occurrence = null)
    } else {
        reactivated
    }
}

/** This todo deleted at [now]: a tombstone with its text blanked. */
fun Todo.withDeleted(now: Long): Todo = copy(deleted = true, text = "", modifiedAt = now)
