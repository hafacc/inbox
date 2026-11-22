package cc.hafa.inbox.core.todo

import java.time.ZoneId

// Typing not yet saved, undo, and the end of an edit, over the todos Firestore holds; the same as
// web's shared/src/todos/typing.ts. Both run shared/testdata/todo-typing-fixtures.json.

/**
 * These stored todos with each one's unsaved [typing] laid over it: only the text changes, as
 * [withText] makes it, so a change another device made since (done, pinned, snoozed) holds. A
 * todo no longer stored stays gone.
 */
fun List<Todo>.withTyping(typing: Map<String, String>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Todo> =
    if (typing.isEmpty()) this else map { todo -> typing[todo.id]?.let { todo.withText(it, now, zone) } ?: todo }

/**
 * What undoing an action writes back for this todo, as it was before: the text typed into
 * [current] since is kept; a todo no longer live (deleted) comes back as it was.
 */
fun Todo.undone(current: Todo?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo =
    if (current == null || current.text == text) this else withText(current.text, now, zone)

/**
 * This todo as editing it ends at [now], its text [startText] when editing began (null for one new
 * in this edit). Changed text stamps `modifiedAt` and drops a place set by a move, so the row takes
 * its new place once, as editing ends; a row that woke from a snooze leaves the snooze behind.
 * Saves made while typing change only the text ([withText]), so the row holds its place.
 */
fun Todo.editEnded(startText: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo =
    if (startText == null || text == startText) {
        this
    } else {
        val woke = matches(TodoFilter.ACTIVE, now, zone) && snoozeUntil != null
        copy(modifiedAt = now, position = null, snoozeUntil = if (woke) null else snoozeUntil)
    }
