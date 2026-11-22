package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.LoadedList
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.TodoEntry
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.data.TodoSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where a list's select marks sit: a thread's avatar and a todo's dot share one column, as on the web. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
class RowMarksTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    private val rowActions = RowActions(
        list = {}, tap = {}, done = {}, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {}, doneBundle = { _, _ -> },
        doneSection = {}, labelSettings = {}, loadMore = {},
    )

    private val todoActions = TodoActions(
        toggleDone = {}, wake = {}, snooze = {}, togglePin = {}, delete = {}, startEditing = {},
        edit = { _, _ -> }, finishEditing = { }, enter = { true },
    )

    @Test
    fun aThreadsAvatarAndATodosDotAreCenteredInOneColumnWithTheirTextInLine() {
        val thread = ThreadSummary(
            id = "t1",
            historyId = "1",
            subject = "Lease",
            snippet = "",
            senders = listOf(Person("Sam", "sam@example.com")),
            messageCount = 1,
            lastDate = now - 60_000L,
            labelIds = listOf("INBOX"),
            unread = false,
        )
        val todo = Todo.new("a", "Water plants", now - 120_000L)
        val sections = listOf(
            SectionModel("today", "Today", listOf(ListEntry.Single(Row(thread, SnoozeStatus.NONE, null, thread.lastDate)), TodoEntry(todo)), canDoneAll = true),
        )
        val model = MailModel(
            email = "me@example.com",
            now = now,
            todosOn = true,
            todos = listOf(todo),
            lists = mapOf(View.INBOX.key to LoadedList(loaded = true)),
        )
        compose.setContent {
            HoldMenuHost {
                ThreadList(
                    model.listing(sections),
                    rowActions,
                    PaddingValues(),
                    todos = TodoSnapshot(enabled = true, loaded = true, todos = listOf(todo), placed = listOf(todo)),
                    todoActions = todoActions,
                )
            }
        }
        val marks = (0..1).map { compose.onAllNodesWithContentDescription("Select")[it].getUnclippedBoundsInRoot() }
        compose.onAllNodesWithContentDescription("Select").assertCountEquals(2)
        val (first, second) = marks.map { (it.left + it.right).value / 2 }
        assertEquals(first, second, 1f)
        val senderStart = compose.onNodeWithText("Sam", useUnmergedTree = true).getUnclippedBoundsInRoot().left
        val todoStart = compose.onNodeWithText("Water plants", useUnmergedTree = true).getUnclippedBoundsInRoot().left
        assertEquals(senderStart.value, todoStart.value, 1f)
        // The avatar takes 36 dp of the row, as on the web (its text starts 14 dp past it), though both
        // marks take taps on a 48 dp square.
        assertEquals(36f / 2 + 14f, senderStart.value - first, 1f)
        assertEquals(listOf(48f, 48f), marks.map { Math.round((it.right - it.left).value).toFloat() })
    }
}
