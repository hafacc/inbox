package cc.hafa.inbox.core.todo

// Todos on an email (design.md, "Todos on an email"): an email's todo holds only its text. Whether it is done,
// snoozed, pinned or in the inbox is its email's, read from the email, never stored on the todo. Its doc id is
// its thread id, so an email has at most one. The same as shared/src/todos/linked.ts; both run
// shared/testdata/linked-todo-fixtures.json.

/** A todo on an email: its text and bookkeeping; its state is its email's. */
data class EmailTodo(
    /** doc id: the Gmail thread id, so an email has at most one todo */
    val id: String,
    /** the Gmail thread it is on, the same as [id] */
    val threadId: String,
    /** text with latr's inline style markers kept */
    val text: String,
    /** when it was made, epoch milliseconds */
    val createdAt: Long,
    /** when it was last changed, epoch milliseconds */
    val modifiedAt: Long,
    /** a tombstone: deletes keep the doc so a device's stale copy can't bring it back */
    val deleted: Boolean,
) {
    /** What a write stores, without the id; the store adds `serverModifiedAt`. */
    fun fields(): Map<String, Any?> = linkedMapOf(
        "threadId" to threadId,
        "text" to text,
        "createdAt" to createdAt,
        "modifiedAt" to modifiedAt,
        "deleted" to deleted,
    )

    /** This todo with new [text]; `modifiedAt` waits for the edit to end ([editEnded]). */
    fun withText(text: String): EmailTodo = if (text == this.text) this else copy(text = text)

    /**
     * This todo as editing it ends at [now], its text [startText] when editing began (null for one
     * new in this edit): changed text stamps `modifiedAt`, once.
     */
    fun editEnded(startText: String?, now: Long): EmailTodo =
        if (startText == null || text == startText) this else copy(modifiedAt = now)

    /** This todo deleted at [now]: a tombstone with its text blanked. */
    fun withDeleted(now: Long): EmailTodo = copy(deleted = true, text = "", modifiedAt = now)

    /** Making and reading email todos. */
    companion object {
        /** Whether a stored todo doc is one on an email: it carries a `threadId`. */
        fun isDoc(data: Map<String, Any?>): Boolean = (data["threadId"] as? String)?.isNotEmpty() == true

        /** A stored todo on an email, its doc id its thread; missing times read as [now]. */
        fun read(id: String, data: Map<String, Any?>, now: Long): EmailTodo = EmailTodo(
            id = id,
            threadId = id,
            text = data["text"] as? String ?: "",
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: now,
            modifiedAt = (data["modifiedAt"] as? Number)?.toLong() ?: now,
            deleted = data["deleted"] == true,
        )

        /** A new todo on the email [threadId]. */
        fun new(threadId: String, text: String, now: Long): EmailTodo =
            EmailTodo(id = threadId, threadId = threadId, text = text, createdAt = now, modifiedAt = now, deleted = false)
    }
}

/** The todo Add todo opens on a row, and whether it is a new one, saved once it has text. */
data class EmailTodoEdit(
    /** the todo to edit */
    val todo: EmailTodo,
    /** true for a new, empty one on the row's lead */
    val fresh: Boolean,
)

/** Per Gmail thread id, the live todo of that email. */
fun emailTodosByThread(todos: List<EmailTodo>): Map<String, EmailTodo> {
    val byThread = linkedMapOf<String, EmailTodo>()
    for (todo in todos) {
        if (!todo.deleted) byThread[todo.threadId] = todo
    }
    return byThread
}

/** The todo a row shows: that of the first of its Gmail threads ([threadIds], lead first) that has one, or null. */
fun rowEmailTodo(byThread: Map<String, EmailTodo>, threadIds: List<String>): EmailTodo? =
    threadIds.firstNotNullOfOrNull { byThread[it] }

/** The todo Add todo opens on a row ([threadIds], lead first): the one it has, else a new, empty one on its lead. */
fun emailTodoToEdit(byThread: Map<String, EmailTodo>, threadIds: List<String>, now: Long): EmailTodoEdit {
    val existing = rowEmailTodo(byThread, threadIds)
    return if (existing != null) {
        EmailTodoEdit(existing, fresh = false)
    } else {
        EmailTodoEdit(EmailTodo.new(threadIds.firstOrNull().orEmpty(), "", now), fresh = true)
    }
}
