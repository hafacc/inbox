package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.todo.Position
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.core.todo.ownKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListModelTest {
    private fun row(id: String, hoursAgo: Long, labelIds: List<String> = listOf("INBOX")): Row {
        val thread = ThreadSummary(
            id = id,
            historyId = "1",
            subject = id,
            snippet = "",
            senders = emptyList(),
            messageCount = 1,
            lastDate = NOW - hoursAgo * HOUR,
            labelIds = labelIds,
            unread = false,
        )
        return Row(thread, SnoozeStatus.NONE, null, thread.lastDate)
    }

    @Test
    fun `pinned threads sit on top, then time sections with label groups`() {
        val rows = listOf(
            row("today-1", 1),
            row("pinned-old", 400, listOf("INBOX", "STARRED")),
            row("gh-1", 2, listOf("INBOX", "Label_gh")),
            row("gh-2", 3, listOf("INBOX", "Label_gh")),
            row("gh-3", 4, listOf("INBOX", "Label_gh")),
            row("yesterday", 20),
        )
        val sections = inboxSections(rows, NOW, listOf("Label_gh"), NEW_YORK)
        assertEquals(listOf("pinned", "today", "yesterday"), sections.map { it.key })
        assertFalse(sections[0].canDoneAll)
        assertEquals(
            listOf(ListEntry.Single::class, ListEntry.Group::class),
            sections[1].entries.map { it::class },
        )
        assertEquals(listOf("today-1", "gh-1", "gh-2", "gh-3"), doneAllIds(sections[1]))
    }

    @Test
    fun `a thread listed twice shows once, at its newest date`() {
        val rows = listOf(row("a", 30), row("b", 2), row("a", 1))
        val inbox = inboxSections(rows, NOW, emptyList(), NEW_YORK)
        assertEquals(listOf("today"), inbox.map { it.key })
        assertEquals(listOf("a", "b"), inbox.single().entries.map { (it as ListEntry.Single).thread.thread.id })
        val plain = plainSections(rows, NOW, NEW_YORK)
        assertEquals(listOf("a", "b"), plain.flatMap { section -> section.entries.map { (it as ListEntry.Single).thread.thread.id } })
    }

    @Test
    fun `Done all leaves pinned threads alone`() {
        val section = SectionModel(
            "today",
            "Today",
            listOf(ListEntry.Single(row("a", 1)), ListEntry.Single(row("b", 1, listOf("INBOX", "STARRED")))),
            canDoneAll = true,
        )
        assertEquals(listOf("a"), doneAllIds(section))
    }

    @Test
    fun `Snoozed lists the soonest wake first`() {
        val later = row("later", 1).copy(status = SnoozeStatus.SNOOZED, snoozeUntil = NOW + 48 * HOUR)
        val soon = row("soon", 1).copy(status = SnoozeStatus.SNOOZED, snoozeUntil = NOW + 2 * HOUR)
        val sections = snoozedSections(listOf(later, soon), NOW, NEW_YORK)
        assertEquals(listOf("Today", "This Wednesday"), sections.map { it.title })
    }

    @Test
    fun `the open thread and bundle are held where the list shows them`() {
        val rows = listOf(
            row("a", 1),
            row("p", 2, listOf("INBOX", "STARRED")),
            row("g1", 3, listOf("INBOX", "L")),
            row("g2", 4, listOf("INBOX", "L")),
            row("g3", 30, listOf("INBOX", "L")),
        )
        val sections = inboxSections(rows, NOW, listOf("L"), NEW_YORK)
        assertEquals(
            HeldPlaces(mapOf("p" to ThreadPlace(NOW - 2 * HOUR, pinned = true, single = true))),
            openPlaces(sections, emptySet(), "p"),
        )
        assertEquals(
            HeldPlaces(mapOf("g2" to ThreadPlace(NOW - 4 * HOUR, pinned = false, single = false)), mapOf("L" to NOW - 3 * HOUR)),
            openPlaces(sections, setOf("L"), "g2"),
        )
    }

    @Test
    fun `the focused thread not open holds only whether it is pinned now`() {
        val rows = listOf(row("a", 1), row("p", 2, listOf("INBOX", "STARRED")), row("g1", 3, listOf("INBOX", "L")), row("g2", 4, listOf("INBOX", "L")))
        val sections = inboxSections(rows, NOW, listOf("L"), NEW_YORK)
        assertEquals(HeldPlaces(pins = mapOf("p" to true)), openPlaces(sections, emptySet(), null, "p"))
        assertEquals(HeldPlaces(bundles = mapOf("L" to NOW - 3 * HOUR), pins = mapOf("g2" to false)), openPlaces(sections, setOf("L"), null, "g2"))
        assertEquals(setOf("a"), openPlaces(sections, emptySet(), "a", "a").let { it.threads.keys + it.pins.keys })
    }

    private fun todo(id: String, hoursAgo: Long) = Todo.new(id, id, NOW - hoursAgo * HOUR)

    private fun entryIds(section: SectionModel): List<String> = section.entries.map { entry ->
        when (entry) {
            is ListEntry.Single -> entry.thread.thread.id
            is ListEntry.Group -> entry.labelId
            is TodoEntry -> "todo:${entry.todo.id}"
        }
    }

    @Test
    fun `todos sit among inbox threads by time, pinned ones in Pinned`() {
        val rows = listOf(row("new", 1), row("pinned", 30, listOf("INBOX", "STARRED")), row("old", 20))
        val todos = listOf(todo("between", 2), todo("pinned-todo", 5).copy(pinned = true), todo("yesterday-todo", 21))
        val sections = inboxSections(rows, NOW, emptyList(), NEW_YORK, todos = todos)
        assertEquals(
            listOf(listOf("todo:pinned-todo", "pinned"), listOf("new", "todo:between"), listOf("old", "todo:yesterday-todo")),
            sections.map(::entryIds),
        )
        assertEquals(listOf("between"), doneAllTodoIds(sections[1]))
        assertEquals(listOf("new"), doneAllIds(sections[1]))
        assertEquals(1, openPlaces(sections, emptySet(), "new").threads.size)
    }

    @Test
    fun `todos with the same time keep their keys' order among threads, whatever order they come in`() {
        val first = todo("first", 2)
        val next = todo("next", 0).copy(position = Position.positionAbove(first.ownKey(), "next"))
        val later = todo("later", 1)
        val rows = listOf(row("mail", 3))
        for (todos in listOf(listOf(next, first, later), listOf(first, later, next))) {
            val expected = listOf(listOf("todo:later", "todo:next", "todo:first", "mail"))
            assertEquals(expected, inboxSections(rows, NOW, emptyList(), NEW_YORK, todos = todos).map(::entryIds))
            assertEquals(expected, plainSections(rows, NOW, NEW_YORK, todos = todos).map(::entryIds))
        }
    }

    @Test
    fun `snoozed todos wake in order with threads, done ones by when they were done`() {
        val snoozedRow = row("later", 1).copy(snoozeUntil = NOW + 3 * HOUR)
        val soon = todo("soon", 1).copy(snoozeUntil = epochToIso(NOW + HOUR, NEW_YORK))
        assertEquals(listOf(listOf("todo:soon", "later")), snoozedSections(listOf(snoozedRow), NOW, NEW_YORK, listOf(soon)).map(::entryIds))
        val done = todo("done", 2).copy(state = TodoState.DONE)
        assertEquals(
            listOf(listOf("mail", "todo:done", "older")),
            plainSections(listOf(row("mail", 1), row("older", 3)), NOW, NEW_YORK, todos = listOf(done)).map(::entryIds),
        )
    }

    @Test
    fun `an email with a todo keeps a row of its own, outside its bundle, and Done all sweeps only todo rows`() {
        val rows = listOf(
            row("mail", 1),
            row("gh-1", 2, listOf("INBOX", "Label_gh")),
            row("gh-2", 3, listOf("INBOX", "Label_gh")),
            row("gh-3", 4, listOf("INBOX", "Label_gh")),
        )
        val sections = inboxSections(rows, NOW, listOf("Label_gh"), NEW_YORK, todos = listOf(todo("own", 7))) { it.thread.id == "gh-2" }
        assertEquals(listOf(listOf("mail", "Label_gh", "gh-2", "todo:own")), sections.map(::entryIds))
        assertEquals(listOf("own"), doneAllTodoIds(sections[0]))
    }
}
