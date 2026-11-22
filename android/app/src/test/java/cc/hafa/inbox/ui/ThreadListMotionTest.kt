package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.bundleRowKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opening and closing a bundle, and showing all of it, move the rows below it and its own row over several frames rather than in one jump. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
class ThreadListMotionTest {
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

    private val sections = listOf(
        SectionModel(
            key = "today",
            title = "Today",
            entries = listOf(
                ListEntry.Single(row("t1", 1)),
                ListEntry.Group("Label_1", (1..8).map { row("b$it", 1 + it) }),
                ListEntry.Single(row("t2", 20)),
            ),
            canDoneAll = true,
        ),
    )

    private val actions = RowActions(
        list = {}, tap = {}, done = {}, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {}, doneBundle = { _, _ -> },
        doneSection = {}, labelSettings = {}, loadMore = {},
    )

    // The last row's top, in pixels; while it is faded only its cover can be found, and TalkBack can't act on it.
    private fun lastRowTop(): Float {
        val covers = compose.onAllNodesWithTag(DIMMED_COVER_TAG).fetchSemanticsNodes()
        covers.forEach { assertTrue("a faded row is a TalkBack stop", SemanticsActions.OnClick !in it.config) }
        val node = covers.lastOrNull() ?: compose.onNodeWithText("Subject t2", substring = true).fetchSemanticsNode()
        return node.boundsInRoot.top
    }

    // The bundle's row's height, in pixels, which changes as the line under its name folds away or comes back.
    private fun bundleRowHeight(): Float =
        compose.onNode((hasClickLabel("Open") or hasClickLabel("Close")) and hasText("Label_1")).fetchSemanticsNode().boundsInRoot.height

    private fun hasClickLabel(label: String): SemanticsMatcher =
        SemanticsMatcher("click label $label") { SemanticsActions.OnClick in it.config && it.config[SemanticsActions.OnClick].label == label }

    // Whether the bundle's row shows its line of senders and subjects.
    private fun summaryShown(): Boolean =
        compose.onAllNodesWithText("Sender b1, Sender b2", substring = true).fetchSemanticsNodes().isNotEmpty()

    // Change the model, then record where the last row's top is each frame for 400 ms.
    private fun framesAfter(change: () -> Unit): List<Float> = framesAfter(::lastRowTop, change)

    // Change the model, then record what `measure` reads each frame for 400 ms.
    private fun framesAfter(measure: () -> Float, change: () -> Unit): List<Float> {
        change()
        return List(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            measure()
        }
    }

    private fun assertMoves(name: String, tops: List<Float>) {
        val distinct = tops.distinct()
        assertTrue("$name moved in one jump or not at all: $tops", distinct.size >= MIN_STEPS)
        assertTrue("$name didn't settle: $tops", tops.takeLast(SETTLED).distinct().size == 1)
    }

    @Test
    fun bundleOpensClosesAndShowsAllOverSeveralFrames() {
        var model by mutableStateOf(MailModel(email = "me@example.com", now = now).listing(sections))
        compose.mainClock.autoAdvance = false
        compose.setContent { ThreadList(model, actions, PaddingValues()) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)

        assertMoves("bundle open", framesAfter { model = model.step(ListEvent.Tap(bundle)) })
        assertMoves("show all", framesAfter { model = model.step(ListEvent.ShowAll) })
        assertMoves("bundle close", framesAfter { model = model.step(ListEvent.CloseBundle) })
    }

    @Test
    fun bundleSummaryFoldsAwayWhileOpenAndComesBackWhenClosed() {
        var model by mutableStateOf(MailModel(email = "me@example.com", now = now).listing(sections))
        compose.mainClock.autoAdvance = false
        compose.setContent { ThreadList(model, actions, PaddingValues()) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertTrue("a closed bundle hid its summary", summaryShown())
        assertMoves("summary folding", framesAfter(::bundleRowHeight) { model = model.step(ListEvent.Tap(bundle)) })
        assertTrue("an open bundle still shows its summary", !summaryShown())
        assertMoves("summary unfolding", framesAfter(::bundleRowHeight) { model = model.step(ListEvent.CloseBundle) })
        assertTrue("a closed bundle didn't bring its summary back", summaryShown())
    }

    private companion object {
        const val FRAMES = 30
        const val MIN_STEPS = 5
        const val SETTLED = 3
        const val SETTLE_MS = 1_000L
    }
}
