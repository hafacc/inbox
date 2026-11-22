package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.pgp.DecryptedPreview
import cc.hafa.inbox.core.pgp.OBSCURED_SUBJECT
import cc.hafa.inbox.core.todo.EmailTodo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lists show a split encrypted reply in the row of the thread it answers. */
class ThreadJoinsTest {
    private val original = ThreadSummary(
        id = "a",
        historyId = "10",
        subject = OBSCURED_SUBJECT,
        snippet = "",
        senders = listOf(Person("Ana", "ana@x")),
        messageCount = 1,
        lastDate = NOW - 2 * HOUR,
        labelIds = listOf("INBOX", "Label_1"),
        unread = false,
        encrypted = true,
        messageIds = listOf("<a1@x>"),
        firstDate = NOW - 2 * HOUR,
    )
    private val reply = original.copy(
        id = "b",
        historyId = "12",
        senders = listOf(Person("Bob", "bob@x")),
        lastDate = NOW - HOUR,
        labelIds = listOf("INBOX", "Label_1", "UNREAD"),
        unread = true,
        messageIds = listOf("<b1@x>"),
        answers = listOf("<a1@x>"),
        firstDate = NOW - HOUR,
    )
    private val other = original.copy(id = "c", subject = "Lunch", encrypted = false, messageIds = listOf("<c1@x>"), lastDate = NOW - 3 * HOUR)

    private fun model(view: View = View.INBOX, snoozeDocs: Map<String, SnoozeDoc> = emptyMap(), listed: List<String> = listOf("b", "a", "c")) =
        MailModel(
            email = "me@x",
            now = NOW,
            view = view,
            summaries = listOf(original, reply, other).associateBy { it.id },
            lists = mapOf(view.key to LoadedList(ids = listed, loaded = true)),
            snoozeDocs = snoozeDocs,
        )

    private fun shownIds(model: MailModel): List<String> =
        model.sections(NEW_YORK).flatMap { it.entries }.map { (it as ListEntry.Single).thread.thread.id }

    @Test
    fun `a split encrypted reply shows in its original's row`() {
        val model = model()
        assertEquals(listOf("a", "c"), shownIds(model))
        val row = model.inboxRows.first { it.thread.id == "a" }
        assertEquals(2, row.thread.messageCount)
        assertEquals(NOW - HOUR, row.thread.lastDate)
        assertEquals(true, row.thread.unread)
        assertEquals(listOf("a", "b"), model.threadsOf("a"))
        assertEquals(listOf("a", "b"), model.threadsOf(listOf("a", "b", "a")))
        assertEquals(listOf("c"), model.threadsOf("c"))
        assertEquals("a", model.rowKeyOf("b"))
        assertEquals(row.thread, model.rowSummary("a"))
    }

    @Test
    fun `other lists join their own loaded threads`() {
        val label = View.Label("Label_1")
        assertEquals(listOf("a", "c"), shownIds(model(label)))
        assertEquals(listOf("b", "c"), shownIds(model(label, listed = listOf("b", "c"))))
    }

    @Test
    fun `snoozing every thread hides the row, and a reply split off after it wakes it`() {
        val snoozed = mapOf("a" to SnoozeDoc(NOW + HOUR, "10"), "b" to SnoozeDoc(NOW + HOUR, "12"))
        val asleep = model(snoozeDocs = snoozed)
        assertEquals(listOf("c"), shownIds(asleep))
        assertEquals(listOf("a"), asleep.snoozedRows.map { it.thread.id })
        assertEquals(SnoozeStatus.SNOOZED, asleep.rowStatus("a"))

        val woken = model(snoozeDocs = mapOf("a" to SnoozeDoc(NOW + HOUR, "11")))
        assertEquals(listOf("a", "c"), shownIds(woken))
        assertEquals(SnoozeStatus.WOKEN, woken.rowStatus("a"))
        assertEquals(emptyList<Row>(), woken.snoozedRows)
    }

    @Test
    fun `an email's todo on any thread of the row shows in it, its lead's first`() {
        val onReply = EmailTodo.new("b", "Book flights", NOW - HOUR)
        val model = model().copy(todosOn = true, emailTodos = listOf(onReply))
        assertEquals("b", model.rowTodo("a")?.id)
        assertEquals(emptyList<TodoEntry>(), model.sections(NEW_YORK).flatMap { it.entries }.filterIsInstance<TodoEntry>())
        val onLead = EmailTodo.new("a", "Pack", NOW - HOUR)
        assertEquals("a", model.copy(emailTodos = listOf(onReply, onLead)).rowTodo("a")?.id)
    }

    @Test
    fun `the open row stays joined when its lead leaves the list`() {
        val archived = model().let { it.copy(summaries = it.summaries + ("a" to original.copy(labelIds = emptyList()))) }
        assertEquals(listOf("b", "c"), shownIds(archived))
        val held = archived.copy(heldJoin = listOf("a", "b"))
        assertEquals(listOf("a", "c"), shownIds(held))
        assertEquals(listOf("a", "b"), held.threadsOf("b"))
    }

    @Test
    fun `a split reply opened before it was listed shows in its original's row`() {
        val opened = model(listed = listOf("a", "c")).copy(list = ListState(open = "b"))
        assertEquals("a", opened.rowKeyOf("b"))
        assertEquals(listOf("a", "b"), opened.threadsOf("a"))
        assertEquals(listOf("a", "c"), shownIds(opened))
        val leadOpen = opened.copy(list = ListState(open = "a"), heldJoin = listOf("a", "b"))
        assertEquals(listOf("a", "c"), shownIds(leadOpen))
        assertEquals(2, leadOpen.inboxRows.first { it.thread.id == "a" }.thread.messageCount)
    }

    @Test
    fun `Snoozed marks a row when any of its threads skipped the inbox`() {
        val snoozed = mapOf("a" to SnoozeDoc(NOW + HOUR, "10"), "b" to SnoozeDoc(NOW + HOUR, "12"))
        val model = model(View.Fixed(Mailbox.SNOOZED), snoozed).copy(skippedInbox = setOf("b"))
        assertEquals(setOf("a", "b"), model.rowSkippedInbox)
    }

    @Test
    fun `the row shows the lead's decrypted subject, else any thread's`() {
        val previews = mapOf("b" to DecryptedPreview("Plans", "see you", NOW - HOUR))
        assertEquals("Plans", model().copy(previews = previews).rowPreviews.getValue("a").subject)
        val both = previews + ("a" to DecryptedPreview("Trip", "first", NOW - 2 * HOUR))
        val shown = model().copy(previews = both).rowPreviews.getValue("a")
        assertEquals("Trip", shown.subject)
        assertEquals("see you", shown.snippet)
    }

    private fun message(id: String, threadId: String, date: Long) =
        MessageView(id, Person("", "ana@x"), emptyList(), date, "", unread = false, body = MessageBody(null, null, emptyList(), emptyList()), threadId = threadId)

    @Test
    fun `an open joined row merges messages by date, and replies go to the newest message's thread`() {
        val lead = ThreadDetail("a", "10", OBSCURED_SUBJECT, listOf(message("a1", "a", 100), message("a2", "a", 300)))
        val split = ThreadDetail("b", "12", OBSCURED_SUBJECT, listOf(message("b1", "", 200), message("b2", "", 400)))
        val merged = mergeDetails(listOf(lead, split))
        assertEquals("a", merged.id)
        assertEquals("12", merged.historyId)
        assertEquals(listOf("a1", "b1", "a2", "b2"), merged.messages.map { it.id })
        assertEquals(setOf("a", "b"), merged.threadIds)
        assertEquals("b", merged.threadOf(merged.messages.last()))
        assertEquals(lead, mergeDetails(listOf(lead)))
    }
}
