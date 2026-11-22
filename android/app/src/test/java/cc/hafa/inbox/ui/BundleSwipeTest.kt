package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.LoadedList
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.TodoEntry
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.mail.bundleKey
import cc.hafa.inbox.core.mail.bundleRowKey
import cc.hafa.inbox.core.todo.Todo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A closed bundle's row swipes as a thread's does, on every thread in it; TalkBack offers the same as actions. An open
 * one's failed next page says why, with Retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class BundleSwipeTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val calls = mutableListOf<String>()

    private val actions = RowActions(
        list = { event -> if (event is ListEvent.Tap) calls += "tap:${event.key}" },
        tap = {},
        done = { calls += "done:${it.joinToString()}" },
        snooze = { calls += "snooze:$it" },
        togglePin = {},
        wakeNow = {},
        loadMoreBundle = { calls += "loadMoreBundle:$it" },
        doneBundle = { labelId, ids -> calls += "doneBundle:$labelId:${ids.joinToString()}" },
        doneSection = {},
        labelSettings = {},
        loadMore = {},
        snoozeBundle = { labelId, ids -> calls += "snoozeBundle:$labelId:${ids.joinToString()}" },
    )

    private fun row(id: String, minutes: Int): Row {
        val date = now - minutes * 60_000L
        val thread = ThreadSummary(
            id = id,
            historyId = "1",
            subject = "Subject $id",
            snippet = "",
            senders = listOf(Person("Sender $id", "$id@example.com")),
            messageCount = 1,
            lastDate = date,
            labelIds = listOf("INBOX", "Label_1"),
            unread = false,
        )
        return Row(thread, SnoozeStatus.NONE, null, date)
    }

    private fun model(open: Boolean): MailModel {
        val sections = listOf(
            SectionModel("today", "Today", listOf(ListEntry.Group("Label_1", (1..3).map { row("b$it", it) })), canDoneAll = true),
        )
        val listed = MailModel(email = "me@example.com", now = now, lists = mapOf(View.INBOX.key to LoadedList(loaded = true))).listing(sections)
        return if (open) listed.step(ListEvent.Tap(bundleRowKey("Label_1"))) else listed
    }

    private fun failedPage(): MailModel {
        val sections = listOf(
            SectionModel("today", "Today", listOf(ListEntry.Group("Label_1", (1..3).map { row("b$it", it) })), canDoneAll = true),
        )
        val lists = mapOf(
            View.INBOX.key to LoadedList(loaded = true),
            bundleKey("Label_1") to LoadedList(listOf("b1", "b2", "b3"), "page2", loaded = true, moreError = "You're offline."),
        )
        val model = MailModel(email = "me@example.com", now = now, labels = listOf(GmailLabel("Label_1", "Label_1")), lists = lists)
        return model.listing(sections).step(ListEvent.Tap(bundleRowKey("Label_1")))
    }

    private fun show(open: Boolean) {
        compose.setContent { ThreadList(model(open), actions, PaddingValues()) }
        compose.waitForIdle()
    }

    private fun customActions(label: String) = SemanticsMatcher("has $label") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }

    @Test
    fun aBundleCountsItsTodosAndNamesThemOnItsLineInItsOrder() {
        val todo = Todo.new("t1", "Buy *milk* #Label_1", now - 90_000L)
        val items = listOf(ListEntry.Single(row("b1", 1)), TodoEntry(todo), ListEntry.Single(row("b2", 2)))
        val group = ListEntry.Group("Label_1", listOf(row("b1", 1), row("b2", 2)), listOf(todo), items)
        val sections = listOf(SectionModel("today", "Today", listOf(group), canDoneAll = true))
        val model = MailModel(email = "me@example.com", now = now, lists = mapOf(View.INBOX.key to LoadedList(loaded = true))).listing(sections)
        compose.setContent { ThreadList(model, actions, PaddingValues()) }
        compose.waitForIdle()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("Subject b1 · Buy milk #Label_1 · Subject b2", substring = true).assertExists()
        compose.onNodeWithText("Label_1").performTouchInput { swipeLeft(startX = right - 10f, endX = left) }
        compose.waitForIdle()
        assertEquals(listOf("doneBundle:Label_1:b1, b2"), calls)
    }

    @Test
    fun swipingLeftMarksEveryThreadDone() {
        show(open = false)
        compose.onNodeWithText("Label_1").performTouchInput { swipeLeft(startX = right - 10f, endX = left) }
        compose.waitForIdle()
        assertEquals(listOf("doneBundle:Label_1:b1, b2, b3"), calls)
    }

    @Test
    fun swipingRightSnoozesThemAll() {
        show(open = false)
        compose.onNodeWithText("Label_1").performTouchInput { swipeRight(startX = left + 10f, endX = right) }
        compose.waitForIdle()
        assertEquals(listOf("snoozeBundle:Label_1:b1, b2, b3"), calls)
    }

    @Test
    fun talkBackOffersTheSwipes() {
        show(open = false)
        for (label in listOf("Done all", "Snooze all")) {
            val node = compose.onNode(customActions(label)).fetchSemanticsNode()
            val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
            compose.runOnIdle { action.action() }
        }
        assertEquals(listOf("doneBundle:Label_1:b1, b2, b3", "snoozeBundle:Label_1:b1, b2, b3"), calls)
    }

    @Test
    fun aFailedNextPageSaysWhyAndRetries() {
        compose.setContent { ThreadList(failedPage(), actions, PaddingValues()) }
        compose.onNodeWithText("You're offline.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(listOf("loadMoreBundle:Label_1"), calls.filterNot { it.startsWith("tap:") })
    }

    @Test
    fun anOpenBundlesHeaderDoesNotSwipe() {
        show(open = true)
        compose.onNodeWithText("Label_1").performTouchInput { swipeLeft(startX = right - 10f, endX = left) }
        compose.waitForIdle()
        compose.onNode(customActions("Done all")).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls.filterNot { it.startsWith("tap:") })
    }
}
