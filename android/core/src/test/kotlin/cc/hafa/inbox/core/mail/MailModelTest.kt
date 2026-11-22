package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.send.OutboxDrafts
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.Todo
import org.junit.Assert.assertEquals
import org.junit.Test

class MailModelTest {
    private fun summary(id: String, labelIds: List<String>, lastDate: Long = NOW - HOUR) =
        ThreadSummary(id, "1", id, "", emptyList(), 1, lastDate, labelIds, unread = false)

    private val base = MailModel(email = "me@x.example.com", now = NOW)

    @Test
    fun `the inbox hides snoozed threads and brings lapsed ones back`() {
        val model = base.copy(
            summaries = mapOf(
                "plain" to summary("plain", listOf("INBOX")),
                "snoozed" to summary("snoozed", listOf("INBOX")),
                "lapsed" to summary("lapsed", listOf("INBOX"), NOW - 48 * HOUR),
                "archived" to summary("archived", emptyList()),
            ),
            lists = mapOf("inbox" to LoadedList(ids = listOf("plain", "snoozed"), loaded = true)),
            snoozeDocs = mapOf(
                "snoozed" to SnoozeDoc(NOW + HOUR, "1"),
                "lapsed" to SnoozeDoc(NOW - HOUR, "1"),
                "archived" to SnoozeDoc(NOW - 2 * HOUR, "1"),
            ),
        )
        assertEquals(listOf("plain", "lapsed"), model.inboxRows.map { it.thread.id })
        assertEquals(NOW - HOUR, model.inboxRows.last().sortDate)
        assertEquals(listOf("snoozed"), model.snoozedRows.map { it.thread.id })
        assertEquals(NOW + HOUR, model.nextWake)
    }

    @Test
    fun `Done leaves out threads back in the inbox or still snoozed`() {
        val model = base.copy(
            view = View.Fixed(Mailbox.DONE),
            summaries = mapOf(
                "archived" to summary("archived", emptyList()),
                "back" to summary("back", listOf("INBOX")),
                "snoozed" to summary("snoozed", emptyList()),
            ),
            lists = mapOf("done" to LoadedList(ids = listOf("archived", "back", "snoozed"), loaded = true)),
            snoozeDocs = mapOf("snoozed" to SnoozeDoc(NOW + HOUR, "1")),
        )
        assertEquals(listOf("archived"), model.sections(NEW_YORK).flatMap { section -> section.entries }.map {
            (it as ListEntry.Single).thread.thread.id
        })
    }

    private fun shownIds(model: MailModel): List<String> = model.sections(NEW_YORK).flatMap { it.entries }.map { entry ->
        when (entry) {
            is ListEntry.Single -> entry.thread.thread.id
            is TodoEntry -> "todo:${entry.todo.id}"
            is ListEntry.Group -> "group:${entry.labelId}"
        }
    }

    @Test
    fun `a label's list keeps a done thread but drops one marked spam or unlabeled`() {
        val model = base.copy(
            view = View.Label("L1"),
            summaries = mapOf(
                "done" to summary("done", listOf("L1")),
                "spam" to summary("spam", listOf("L1", "SPAM")),
                "unlabeled" to summary("unlabeled", emptyList()),
            ),
            lists = mapOf("label:L1" to LoadedList(ids = listOf("done", "spam", "unlabeled"), loaded = true)),
        )
        assertEquals(listOf("done"), shownIds(model))
    }

    @Test
    fun `a search asking for the inbox drops a done thread and one without keeps it`() {
        val summaries = mapOf("done" to summary("done", emptyList()), "inbox" to summary("inbox", listOf("INBOX")))
        fun search(query: String) = base.copy(
            view = View.Search(query),
            summaries = summaries,
            lists = mapOf("search:$query" to LoadedList(ids = listOf("inbox", "done"), loaded = true)),
        )
        assertEquals(listOf("inbox"), shownIds(search("from:ann in:inbox")))
        assertEquals(listOf("inbox", "done"), shownIds(search("from:ann")))
    }

    @Test
    fun `an email's todo lists it in a search by its text, held to the search's state terms`() {
        val summaries = mapOf(
            "inbox" to summary("inbox", listOf("INBOX")),
            "done" to summary("done", emptyList()),
            "other" to summary("other", listOf("INBOX")),
        )
        val emailTodos = listOf(EmailTodo.new("inbox", "call ann", NOW), EmailTodo.new("done", "ann notes", NOW))
        fun search(query: String) = base.copy(
            view = View.Search(query),
            todosOn = true,
            summaries = summaries,
            emailTodos = emailTodos,
            // `is:todo` alone asks Gmail for nothing, so it has no list of its own.
            lists = if (query == "is:todo") emptyMap() else mapOf("search:$query" to LoadedList(ids = listOf("other"), loaded = true)),
        )
        assertEquals(listOf("other", "inbox", "done"), shownIds(search("ann")))
        assertEquals(2, search("ann").searchTodoCount)
        assertEquals(listOf("other", "inbox"), shownIds(search("ann in:inbox")))
        assertEquals(listOf("inbox", "done"), shownIds(search("is:todo")))
        assertEquals(listOf("other"), shownIds(search("ann -is:todo")))
        assertEquals(listOf("other"), shownIds(search("ann").copy(todosOn = false, emailTodos = emptyList())))
    }

    @Test
    fun `an email's todo tagged for a label lists it there, fetched when missing`() {
        val model = base.copy(
            view = View.Label("L1"),
            todosOn = true,
            labels = listOf(GmailLabel("L1", "GitHub")),
            summaries = mapOf("tagged" to summary("tagged", listOf("INBOX"))),
            emailTodos = listOf(EmailTodo.new("tagged", "review #github", NOW), EmailTodo.new("unseen", "fix #github", NOW)),
            lists = mapOf("label:L1" to LoadedList(ids = emptyList(), loaded = true)),
        )
        assertEquals(listOf("tagged"), shownIds(model))
        assertEquals(listOf("unseen"), model.missingTaggedThreads)
    }

    @Test
    fun `a search's todos sit among its threads, newest first`() {
        val newer = Todo.new("newer", "ann call", NOW - 30 * 60_000L)
        val older = Todo.new("older", "ann notes", NOW - 3 * HOUR)
        val model = base.copy(
            view = View.Search("ann"),
            todosOn = true,
            todos = listOf(older, newer),
            summaries = mapOf("mail" to summary("mail", emptyList(), NOW - HOUR)),
            lists = mapOf("search:ann" to LoadedList(ids = listOf("mail"), loaded = true)),
        )
        assertEquals(listOf("todo:newer", "mail", "todo:older"), shownIds(model))
    }

    @Test
    fun `a search shows its todos a page at a time, counted afresh for another list`() {
        val found = List(150) { Todo.new("t$it", "ann $it", NOW - it * 60_000L) }
        val model = base.copy(
            view = View.Search("ann is:todo"),
            todosOn = true,
            todos = found,
        )
        assertEquals(100, shownIds(model).size)
        assertEquals(50, model.todosHidden)
        assertEquals(150, model.searchTodoCount)
        val more = model.copy(todoPages = TodoPageCount(model.todoListKey, 2))
        assertEquals(150, shownIds(more).size)
        assertEquals(0, more.todosHidden)
        assertEquals(100, more.copy(view = View.Search("ann")).todoLimit)
    }

    @Test
    fun `menu labels follow gmail's visibility until set here`() {
        val model = base.copy(
            labels = listOf(
                GmailLabel("INBOX", "INBOX", "system"),
                GmailLabel("L2", "receipts"),
                GmailLabel("L1", "GitHub"),
                GmailLabel("L3", "hidden", labelListVisibility = "labelHide"),
                GmailLabel("L4", "shown", labelListVisibility = "labelHide"),
            ),
            labelPrefDocs = mapOf("L2" to mapOf("sidebar" to false), "L4" to mapOf("sidebar" to true)),
        )
        assertEquals(listOf("GitHub", "hidden", "receipts", "shown"), model.userLabels.map { it.name })
        assertEquals(listOf("GitHub", "shown"), model.drawerLabels.map { it.name })
    }

    @Test
    fun `the open thread stays listed though it no longer matches the view`() {
        val search = View.Search("is:unread")
        val read = base.copy(
            view = search,
            summaries = mapOf("a" to summary("a", listOf("INBOX")), "b" to summary("b", listOf("INBOX", "UNREAD")).copy(unread = true)),
            lists = mapOf(search.key to LoadedList(ids = listOf("a", "b"), loaded = true)),
        )
        assertEquals(listOf("b"), shownIds(read))
        assertEquals(listOf("b", "a"), shownIds(read.copy(list = ListState(open = "a"))))
        // Opened from a notification, never in the list.
        val elsewhere = read.copy(view = View.Fixed(Mailbox.SENT), list = ListState(open = "a"))
        assertEquals(listOf("a"), shownIds(elsewhere))
    }

    @Test
    fun `a thread holding only a scheduled send shows only in the Outbox`() {
        val draft = summary("s", listOf("DRAFT", "INBOX", "Label_1")).copy(messageCount = 0)
        val outbox = OutboxDrafts(messageIds = setOf("m1"), threadIds = setOf("s"))
        val searchView = View.Search("lunch")
        val model = base.copy(
            summaries = mapOf("s" to draft, "a" to summary("a", listOf("INBOX", "Label_1"))),
            lists = listOf(View.INBOX, View.Label("Label_1"), searchView, View.Fixed(Mailbox.DRAFTS))
                .associate { it.key to LoadedList(ids = listOf("s", "a"), loaded = true) },
            outboxDrafts = outbox,
        )
        for (view in listOf(View.INBOX, View.Label("Label_1"), searchView)) assertEquals(listOf("a"), shownIds(model.copy(view = view)))
        assertEquals(emptyList<String>(), shownIds(model.copy(view = View.Fixed(Mailbox.DRAFTS))))
        assertEquals(listOf("s", "a"), shownIds(model.copy(view = searchView, outboxDrafts = OutboxDrafts())))
    }
}
