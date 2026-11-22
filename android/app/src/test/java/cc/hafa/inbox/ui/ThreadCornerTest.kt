package cc.hafa.inbox.ui

import cc.hafa.inbox.core.mail.ThreadAction
import cc.hafa.inbox.core.mail.ThreadTodoAction
import cc.hafa.inbox.core.mail.threadActionPlaces
import cc.hafa.inbox.core.mail.threadActions
import org.junit.Assert.assertEquals
import org.junit.Test

/** The open thread's corner button: names and tones of its wedges, as the approved design draws them. */
class ThreadCornerTest {
    @Test
    fun inboxThreadHasDoneSnoozeAndPinNextToTheButton() {
        val actions = threadActions(listOf("INBOX"), snoozed = false, unread = false, todo = null, hasSender = true)
        val places = threadActionPlaces(actions)
        assertEquals(listOf("Done", "Snooze", "Pin"), places.inner.map { threadTool(it, snoozed = false).label })
        assertEquals(listOf("Label", "Report spam", "Delete"), places.outer.map { threadTool(it, snoozed = false).label })
    }

    @Test
    fun unreadThreadHasNoMarkRead() {
        val actions = threadActions(listOf("INBOX", "UNREAD"), snoozed = false, unread = true, todo = ThreadTodoAction.ADD, hasSender = true)
        val outer = threadActionPlaces(actions).outer.map { threadTool(it, snoozed = false).label }
        assertEquals(listOf("Label", "Add todo", "Report spam", "Delete"), outer)
    }

    @Test
    fun tones() {
        assertEquals(ToolTone.DONE, threadTool(ThreadAction.DONE, snoozed = false).tone)
        assertEquals(ToolTone.DANGER, threadTool(ThreadAction.SPAM, snoozed = false).tone)
        assertEquals(ToolTone.DANGER, threadTool(ThreadAction.DELETE, snoozed = false).tone)
        assertEquals(ToolTone.ON, threadTool(ThreadAction.UNPIN, snoozed = false).tone)
        assertEquals(ToolTone.ON, threadTool(ThreadAction.READ, snoozed = false).tone)
        assertEquals(ToolTone.PLAIN, threadTool(ThreadAction.PIN, snoozed = false).tone)
    }

    @Test
    fun snoozedThreadChangesItsSnoozeTime() {
        assertEquals("Change snooze time", threadTool(ThreadAction.SNOOZE, snoozed = true).label)
        assertEquals("Snooze", threadTool(ThreadAction.SNOOZE, snoozed = false).label)
    }

    @Test
    fun everyActionHasAName() {
        for (action in ThreadAction.entries) assert(threadTool(action, snoozed = false).label.isNotEmpty())
    }
}
