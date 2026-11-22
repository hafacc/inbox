package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import cc.hafa.inbox.core.mail.SelectedStatus
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
    fun inboxThreadsPutDoneSnoozeAndPinNextToTheButtonAndTheRestBeyond() {
        val threads = listOf(
            SelectedThread("a", listOf("INBOX", "UNREAD"), SelectedStatus.NONE),
            SelectedThread("b", listOf("INBOX"), SelectedStatus.NONE),
        )
        val picked = mutableListOf<SetAction>()
        val menu = menuFor(threads, picked)
        assertEquals(listOf("Done", "Snooze", "Pin"), menu.inner.map { it.label })
        assertEquals(listOf("Label", "Mark read", "Report spam"), menu.outer.map { it.label })
        menu.onPick(menu.outer[1])
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
}
