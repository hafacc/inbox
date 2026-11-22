package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.bundleRowKey
import cc.hafa.inbox.core.todo.Todo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tap on the list faded behind an open bundle, and a menu opening and closing, go through the list's layers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
class ListLayersTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L

    private fun row(id: String, minutes: Int): Row {
        val date = now - minutes * 60_000L
        val thread = ThreadSummary(
            id = id,
            historyId = "1",
            subject = "Subject $id",
            snippet = "Snippet $id",
            senders = listOf(Person("Sender $id", "$id@example.com")),
            messageCount = 1,
            lastDate = date,
            labelIds = listOf("INBOX", "Label_1"),
            unread = false,
        )
        return Row(thread, SnoozeStatus.NONE, null, date)
    }

    private val sections = listOf(
        SectionModel(
            key = "today",
            title = "Today",
            entries = listOf(
                ListEntry.Single(row("t1", 1)),
                ListEntry.Group("Label_1", (1..3).map { row("b$it", 1 + it) }),
                ListEntry.Single(row("t2", 20)),
            ),
            canDoneAll = true,
        ),
    )

    @Test
    fun aTapOnTheFadedListClosesTheMenuBeforeTheBundle() {
        var model by mutableStateOf(
            MailModel(email = "me@example.com", now = now).listing(sections).step(ListEvent.Tap(bundleRowKey("Label_1"))),
        )
        val events = mutableListOf<ListEvent<Row, Todo>>()
        val actions = RowActions(
            list = { event ->
                events += event
                model = model.step(event)
            },
            tap = {}, done = {}, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {}, doneBundle = { _, _ -> },
            doneSection = {}, labelSettings = {}, loadMore = {},
        )
        model = model.step(ListEvent.OpenMenu("labels"))
        compose.setContent { ThreadList(model, actions, PaddingValues()) }
        compose.waitForIdle()

        compose.onAllNodesWithTag(DIMMED_COVER_TAG)[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf<ListEvent<Row, Todo>>(ListEvent.Dismiss), events)
        assertEquals(null, model.list.menu)
        assertEquals("Label_1", model.list.bundle?.labelId)

        compose.onAllNodesWithTag(DIMMED_COVER_TAG)[0].performClick()
        compose.waitForIdle()
        assertEquals(null, model.list.bundle)
    }

    @Test
    fun aMenuTellsTheListWhileItIsOpen() {
        val events = mutableListOf<ListEvent<Nothing, Nothing>>()
        var open by mutableStateOf(false)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) ListMenu("labels", open, onEvent = { events += it }) }
        compose.waitForIdle()
        assertEquals(emptyList<ListEvent<Nothing, Nothing>>(), events)

        open = true
        compose.waitForIdle()
        open = false
        compose.waitForIdle()
        open = true
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        assertEquals(
            listOf(ListEvent.OpenMenu("labels"), ListEvent.CloseMenu("labels"), ListEvent.OpenMenu("labels"), ListEvent.CloseMenu("labels")),
            events,
        )
    }
}
