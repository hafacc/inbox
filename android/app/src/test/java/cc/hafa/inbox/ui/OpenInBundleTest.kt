package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.bundleRowKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A thread opened deep in an open bundle: the list holds still behind it, and one that leaves the list goes with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class OpenInBundleTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val bundle = bundleRowKey("Label_1")

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

    private fun sectionsWithout(gone: Set<String>) = listOf(
        SectionModel(
            key = "today",
            title = "Today",
            entries = listOf(
                ListEntry.Single(row("t1", 1)),
                ListEntry.Group("Label_1", (1..30).map { row("b$it", 1 + it) }.filterNot { it.thread.id in gone }),
                ListEntry.Single(row("t2", 60)),
            ),
            canDoneAll = true,
        ),
    )

    private val actions = RowActions(
        list = {}, tap = {}, done = {}, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {}, doneBundle = { _, _ -> },
        doneSection = {}, labelSettings = {}, loadMore = {},
    )

    private var model by mutableStateOf(
        MailModel(email = "me@example.com", now = now).listing(sectionsWithout(emptySet())).step(ListEvent.Tap(bundle)).step(ListEvent.ShowAll),
    )

    private fun show() {
        compose.setContent {
            val states = rememberSaveableStateHolder()
            ThreadOverList(
                opened = model.takeIf { it.openId != null },
                key = { it.openId.orEmpty() },
                list = { states.SaveableStateProvider("inbox") { ThreadList(model, actions, PaddingValues()) } },
                thread = { Text("Thread ${it.openId}") },
            )
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Subject b22", substring = true))
        compose.waitForIdle()
    }

    private fun top(id: String): Float = compose.onNodeWithText("Subject $id", substring = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun openingAndClosingKeepsTheRowWhereItWas() {
        show()
        val before = top("b22")
        model = model.step(ListEvent.OpenThread("b22"))
        compose.waitForIdle()
        compose.onNodeWithText("Thread b22").assertExists()
        model = model.step(ListEvent.CloseThread)
        compose.waitForIdle()
        compose.onNodeWithText("Thread b22").assertDoesNotExist()
        assertEquals(before, top("b22"), 1f)
    }

    @Test
    fun aThreadThatLeavesAsItClosesGoesFromTheList() {
        show()
        model = model.step(ListEvent.OpenThread("b22"))
        compose.waitForIdle()
        model = model.step(ListEvent.CloseThread)
        model = model.listing(sectionsWithout(setOf("b22")))
        compose.waitForIdle()
        compose.onNodeWithText("Thread b22").assertDoesNotExist()
        compose.onNodeWithText("Subject b22", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Subject b23", substring = true).assertExists()
    }
}
