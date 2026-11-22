package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.LocalDraftState
import cc.hafa.inbox.core.mail.LocalThreadDraft
import cc.hafa.inbox.core.mail.LoadedList
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.mail.LocalDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The list's empty inbox, its errors with Retry, and rows sliding up as one above them leaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
class ThreadListStatesTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val calls = mutableListOf<String>()

    private val actions = RowActions(
        list = {}, tap = {}, done = {}, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {}, doneBundle = { _, _ -> },
        doneSection = {}, labelSettings = {}, loadMore = { calls += "more" }, refresh = { calls += "refresh" },
    )

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
            labelIds = listOf("INBOX"),
            unread = false,
        )
        return Row(thread, SnoozeStatus.NONE, null, date)
    }

    private fun today(vararg ids: String) =
        listOf(SectionModel("today", "Today", ids.mapIndexed { index, id -> ListEntry.Single(row(id, index + 1)) }, canDoneAll = true))

    private fun inbox(list: LoadedList) = MailModel(email = "me@example.com", now = now, lists = mapOf(View.INBOX.key to list))

    @Test
    fun anEmptyInboxSaysAllDone() {
        compose.setContent { ThreadList(inbox(LoadedList(loaded = true)), actions, PaddingValues()) }
        compose.onNodeWithText("All done. Nothing in your inbox.").assertExists()
    }

    @Test
    fun aSearchWithNoMatchesSaysSo() {
        val search = View.Search("from:nobody")
        val model = MailModel(email = "me@example.com", now = now, view = search, lists = mapOf(search.key to LoadedList(loaded = true)))
        compose.setContent { ThreadList(model, actions, PaddingValues()) }
        compose.onNodeWithText("No mail matches that search.").assertExists()
        compose.onNodeWithText("Nothing here.").assertDoesNotExist()
    }

    @Test
    fun draftsShowANewMessageNotInGmailYetAndOpenIt() {
        val drafts = View.Fixed(Mailbox.DRAFTS)
        val model = MailModel(email = "me@example.com", now = now, view = drafts, lists = mapOf(drafts.key to LoadedList(loaded = true)))
        val draft = LocalDraft(
            LocalThreadDraft("l1", null, LocalDraftState.DRAFT, now),
            null,
            "See you there",
            to = listOf(Person("Ann", "ann@example.com")),
            subject = "Lunch",
        )
        val opened = mutableListOf<String>()
        compose.setContent { ThreadList(model, actions, PaddingValues(), localDrafts = LocalOnlyRows(listOf(draft)) { opened += it }) }
        compose.onNodeWithText("Not in Gmail yet").assertExists()
        compose.onNodeWithText("Nothing here.").assertDoesNotExist()
        compose.onNodeWithText("Lunch — See you there").assertExists()
        compose.onNodeWithText("To Ann").performClick()
        compose.waitForIdle()
        assertEquals(listOf("l1"), opened)
    }

    @Test
    fun aListThatFailedOffersRetry() {
        compose.setContent { ThreadList(inbox(LoadedList(loaded = true, error = "You're offline.")), actions, PaddingValues()) }
        compose.onNodeWithText("You're offline.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun aNextPageThatFailedOffersRetryUnderTheRows() {
        val list = LoadedList(ids = listOf("t1"), nextPageToken = "page2", loaded = true, error = "You're offline.")
        compose.setContent { ThreadList(inbox(list).listing(today("t1")), actions, PaddingValues()) }
        compose.onNodeWithText("Subject t1", substring = true).assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("more"), calls)
    }

    @Test
    fun rowsBelowSlideUpWhenOneLeaves() {
        val model = inbox(LoadedList(ids = listOf("t1", "t2", "t3"), loaded = true))
        var shown by mutableStateOf(model.listing(today("t1", "t2", "t3")))
        compose.mainClock.autoAdvance = false
        compose.setContent { ThreadList(shown, actions, PaddingValues()) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        shown = shown.listing(today("t1", "t3"))
        val tops = List(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("Subject t3", substring = true).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("the row below moved in one jump: $tops", tops.distinct().size >= MIN_STEPS)
        assertTrue("the row below didn't settle: $tops", tops.takeLast(SETTLED).distinct().size == 1)
    }

    private companion object {
        const val FRAMES = 24
        const val MIN_STEPS = 4
        const val SETTLED = 3
        const val SETTLE_MS = 1_000L
    }
}
