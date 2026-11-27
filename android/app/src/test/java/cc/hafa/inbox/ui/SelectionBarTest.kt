package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import cc.hafa.inbox.core.mail.SelectedStatus
import cc.hafa.inbox.core.mail.SelectedTodo
import cc.hafa.inbox.core.mail.SelectedThread
import cc.hafa.inbox.core.mail.SetAction
import cc.hafa.inbox.core.mail.selectionActions
import cc.hafa.inbox.core.mail.selectionInboxPlan
import cc.hafa.inbox.core.mail.selectionPlan
import cc.hafa.inbox.core.mail.todoSelectionPlan
import cc.hafa.inbox.ui.theme.InboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The selection bar keeps Clear, the count and Select all; its actions are the corner button's two rings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp", application = Application::class)
class SelectionBarTest {
    @get:Rule
    val compose = createComposeRule()

    private fun menuFor(threads: List<SelectedThread>, picked: MutableList<SetAction> = mutableListOf(), inboxed: MutableList<List<String>> = mutableListOf()) =
        selectionCornerMenu(
            plan = selectionPlan(threads),
            todoPlan = todoSelectionPlan(threads, emptyList()),
            inbox = selectionInboxPlan(threads),
            actions = selectionActions(threads.size, 0),
            threadCount = threads.size,
            onAction = { picked += it },
            onDelete = {},
            onInbox = { inboxed += it },
        )

    @Test
    fun theBarHoldsOnlyClearTheCountAndSelectAll() {
        compose.setContent {
            InboxTheme(darkTheme = false) {
                SelectionBar(count = 2, allSelected = false, onClear = {}, onSelectAll = {})
            }
        }
        val order = listOf("Clear selection", "2 selected", "Select all")
        val lefts = order.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)
        compose.onNodeWithContentDescription("Done").assertDoesNotExist()
        compose.onNodeWithContentDescription("Label").assertDoesNotExist()
    }

    @Test
    fun inboxThreadsPutDoneAndSnoozeNextToTheButtonAndPinFirstBeyond() {
        val threads = listOf(
            SelectedThread("a", listOf("INBOX", "UNREAD"), SelectedStatus.NONE),
            SelectedThread("b", listOf("INBOX"), SelectedStatus.NONE),
        )
        val picked = mutableListOf<SetAction>()
        val menu = menuFor(threads, picked)
        assertEquals(listOf("Done", "Snooze"), menu.inner.map { it.label })
        assertEquals(listOf("Pin", "Label", "Mark read", "Report spam", "Delete"), menu.outer.map { it.label })
        menu.onPick(menu.outer[2])
        menu.onPick(menu.inner[0])
        assertEquals(listOf(SetAction.UNREAD, SetAction.DONE), picked)
    }

    @Test
    fun aWokenThreadOutOfTheInboxOffersMoveToInboxInDonesPlace() {
        val threads = listOf(SelectedThread("a", listOf("Label_1"), SelectedStatus.LAPSED))
        val inboxed = mutableListOf<List<String>>()
        val menu = menuFor(threads, inboxed = inboxed)
        assertEquals("Move to inbox", menu.inner.first().label)
        assertEquals(false, (menu.inner + menu.outer).any { it.label == "Done" })
        menu.onPick(menu.inner.first())
        assertEquals(listOf(listOf("a")), inboxed)
    }

    @Test
    fun trashThreadsOfferMoveToInboxInDeletesPlace() {
        val threads = listOf(SelectedThread("a", listOf("TRASH"), SelectedStatus.NONE))
        var deleted = false
        val menu = selectionCornerMenu(
            plan = selectionPlan(threads),
            todoPlan = todoSelectionPlan(threads, emptyList()),
            inbox = selectionInboxPlan(threads),
            actions = selectionActions(threads.size, 0),
            threadCount = threads.size,
            onAction = {},
            onDelete = { deleted = true },
            onInbox = {},
        )
        assertEquals(listOf("Snooze", "Pin"), menu.inner.map { it.label })
        assertEquals(listOf("Label", "Mark unread", "Report spam", "Move to inbox"), menu.outer.map { it.label })
        menu.onPick(menu.outer.last())
        assertEquals(true, deleted)
    }

    @Test
    fun threadsAndTodosTogetherPutPinAndDeleteBeyond() {
        val threads = listOf(SelectedThread("a", listOf("INBOX"), SelectedStatus.NONE))
        val todos = listOf(SelectedTodo("t1", done = false, pinned = false))
        val menu = selectionCornerMenu(
            plan = selectionPlan(threads),
            todoPlan = todoSelectionPlan(threads, todos),
            inbox = selectionInboxPlan(threads),
            actions = selectionActions(threads.size, todos.size),
            threadCount = threads.size,
            onAction = {},
            onDelete = {},
            onInbox = {},
        )
        assertEquals(listOf("Done", "Snooze"), menu.inner.map { it.label })
        assertEquals(listOf("Pin", "Delete"), menu.outer.map { it.label })
    }

    @Test
    fun todosAlonePutDoneAloneNextToTheButtonAndSnoozePinAndDeleteBeyond() {
        val todos = listOf(SelectedTodo("t1", done = false, pinned = false), SelectedTodo("t2", done = false, pinned = false))
        var deleted = false
        val menu = selectionCornerMenu(
            plan = selectionPlan(emptyList()),
            todoPlan = todoSelectionPlan(emptyList(), todos),
            inbox = selectionInboxPlan(emptyList()),
            actions = selectionActions(0, todos.size),
            threadCount = 0,
            onAction = {},
            onDelete = { deleted = true },
            onInbox = {},
        )
        assertEquals(listOf("Done"), menu.inner.map { it.label })
        assertEquals(listOf("Snooze", "Pin", "Delete"), menu.outer.map { it.label })
        menu.onPick(menu.outer.last())
        assertEquals(true, deleted)
    }
}
