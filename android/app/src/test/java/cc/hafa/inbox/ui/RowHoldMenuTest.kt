package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.LoadedList
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionEntry
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.TodoEntry
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.bundleRowKey
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.data.TodoSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long press on an email's, a todo's or a closed bundle's row opens the same round menu around the finger, Pin and
 * Delete placed alike (`rowHoldWedges`), turned leftward near the right edge; TalkBack's long press opens it on the
 * row's avatar, dot or mark, and its actions on the row match what the swipes and the menu offer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class RowHoldMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val calls = mutableListOf<String>()
    private val menu = HoldMenuState()

    private val rowActions = RowActions(
        list = {}, tap = {}, done = {}, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {},
        doneBundle = { labelId, ids -> calls += "doneBundle:$labelId:${ids.joinToString()}" },
        doneSection = {}, labelSettings = {}, loadMore = {},
        act = { id, action -> calls += "act:$id:${action.name}" },
        snoozeBundle = { labelId, ids -> calls += "snoozeBundle:$labelId:${ids.joinToString()}" },
        pinBundle = { labelId, ids -> calls += "pinBundle:$labelId:${ids.joinToString()}" },
        unpinBundle = { labelId, ids -> calls += "unpinBundle:$labelId:${ids.joinToString()}" },
        deleteBundle = { labelId, ids -> calls += "deleteBundle:$labelId:${ids.joinToString()}" },
    )

    private val todoActions = TodoActions(
        toggleDone = {}, wake = {}, snooze = {}, togglePin = { calls += "pin:$it" }, delete = { calls += "delete:$it" },
        startEditing = {}, edit = { _, _ -> }, finishEditing = {}, enter = { true },
    )

    private fun thread(id: String, labelIds: List<String>): ThreadSummary = ThreadSummary(
        id = id,
        historyId = "1",
        subject = "Subject $id",
        snippet = "",
        senders = listOf(Person("Sender $id", "$id@example.com")),
        messageCount = 1,
        lastDate = now - 60_000L,
        labelIds = labelIds,
        unread = false,
    )

    private fun row(id: String, labelIds: List<String> = listOf("INBOX")): Row {
        val summary = thread(id, labelIds)
        return Row(summary, SnoozeStatus.NONE, null, summary.lastDate)
    }

    private fun show(entries: List<SectionEntry<Row>>, todos: List<Todo> = emptyList(), view: View = View.INBOX, open: String? = null) {
        val sections = listOf(SectionModel("today", "Today", entries, canDoneAll = true))
        val model = MailModel(
            email = "me@example.com",
            now = now,
            view = view,
            todosOn = todos.isNotEmpty(),
            todos = todos,
            labels = listOf(GmailLabel("Label_1", "Errands")),
            lists = mapOf(view.key to LoadedList(loaded = true)),
        )
        val listed = model.listing(sections).let { if (open != null) it.step(ListEvent.Tap(open)) else it }
        compose.setContent {
            HoldMenuHost(menu) {
                ThreadList(listed, rowActions, PaddingValues(), todos = TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos), todoActions = todoActions)
            }
        }
        compose.waitForIdle()
    }

    // Long-presses [node] at [at] (its own pixels) and returns that point in root pixels.
    private fun holdAt(node: SemanticsNodeInteraction, at: (width: Float, height: Float) -> Offset): Offset {
        val shown = node.fetchSemanticsNode()
        val point = at(shown.size.width.toFloat(), shown.size.height.toFloat())
        node.performTouchInput { longClick(point) }
        compose.waitForIdle()
        return shown.positionInRoot + point
    }

    private fun middleOf(node: SemanticsNodeInteraction): Offset {
        val shown = node.fetchSemanticsNode()
        return shown.positionInRoot + Offset(shown.size.width / 2f, shown.size.height / 2f)
    }

    private fun assertNear(expected: Offset, actual: Offset?) {
        val center = requireNotNull(actual) { "no menu open" }
        assertEquals(expected.x, center.x, 1f)
        assertEquals(expected.y, center.y, 1f)
    }

    // The open menu's wedges as id and starting angle.
    private fun shownArcs(): List<Pair<String, Float>> = requireNotNull(menu.spec).wedges.map { it.item.id to it.arc.from }

    private fun talkBackLabels(node: SemanticsNodeInteraction): List<String> =
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun withActions(label: String) = SemanticsMatcher("has $label") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }

    @Test
    fun anEmailHeldAnywhereOpensPinAndDeleteAtTheFinger() {
        show(listOf(ListEntry.Single(row("t1"))))
        val pressed = holdAt(compose.onNodeWithText("Subject t1")) { width, height -> Offset(width / 2f, height / 2f) }
        assertNear(pressed, menu.spec?.center)
        // The row is near the top of the screen, so the disc opens right (Pin) and down (Delete).
        assertEquals(listOf("PIN" to -45f, "DELETE" to -105f), shownArcs())
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf("act:t1:DELETE"), calls)
    }

    @Test
    fun anEmailLowerDownOpensStraightUpAndNearTheBottomLeftTurnsAsHigherUp() {
        show((1..12).map { ListEntry.Single(row("t$it")) })
        holdAt(compose.onNodeWithText("Subject t5")) { width, height -> Offset(width / 2f, height / 2f) }
        assertEquals(listOf("PIN" to 90f, "DELETE" to 30f), shownArcs())
        menu.close()
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode()
        val subject = compose.onNodeWithText("Subject t1").fetchSemanticsNode()
        // A press 40 dp above the screen's bottom, wherever the list puts a row there.
        val pressed = Offset(subject.positionInRoot.x + 4f, screen.size.height - 40f * subject.layoutInfo.density.density)
        compose.onRoot().performTouchInput { longClick(pressed) }
        compose.waitForIdle()
        assertEquals(listOf("PIN" to 45f, "DELETE" to -15f), shownArcs())
    }

    @Test
    fun anEmailHeldNearTheRightEdgeOpensLeftward() {
        show(listOf(ListEntry.Single(row("t1"))))
        val sender = compose.onNodeWithText("Sender t1")
        val shown = sender.fetchSemanticsNode()
        val screenWidth = compose.onRoot().fetchSemanticsNode().size.width
        // 20 dp in from the screen's right edge, on the row.
        val pressed = Offset(screenWidth - 20f * shown.layoutInfo.density.density, shown.positionInRoot.y + shown.size.height / 2f)
        sender.performTouchInput { longClick(pressed - shown.positionInRoot) }
        compose.waitForIdle()
        assertNear(pressed, menu.spec?.center)
        // Mirrored near the top: Pin to the left, Delete below.
        assertEquals(listOf("PIN" to 165f, "DELETE" to 225f), shownArcs())
    }

    @Test
    fun aPinnedEmailOffersUnpinAndOneInTheTrashMoveToInbox() {
        show(listOf(ListEntry.Single(row("t1", listOf("INBOX", "STARRED"))), ListEntry.Single(row("t2", listOf("TRASH")))))
        holdAt(compose.onNodeWithText("Subject t1")) { width, height -> Offset(width / 2f, height / 2f) }
        assertEquals(listOf("UNPIN", "DELETE"), shownArcs().map { it.first })
        menu.close()
        compose.waitForIdle()
        holdAt(compose.onNodeWithText("Subject t2")) { width, height -> Offset(width / 2f, height / 2f) }
        assertEquals(listOf("PIN", "UNTRASH"), shownArcs().map { it.first })
    }

    @Test
    fun talkBacksLongPressOpensTheEmailsMenuOnItsAvatarAndItsActionsAreTheSwipesPinAndDelete() {
        show(listOf(ListEntry.Single(row("t1"))))
        val node = compose.onNode(withActions("Delete"))
        assertTrue(talkBackLabels(node).containsAll(listOf("Done", "Snooze", "Pin", "Delete")))
        assertTrue(talkBackLabels(node).none { it in listOf("Label", "Report spam", "Mark unread", "Wake now") })
        node.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertNear(middleOf(compose.onAllNodesWithContentDescription("Select", useUnmergedTree = true)[0]), menu.spec?.center)
        assertEquals(listOf("PIN", "DELETE"), shownArcs().map { it.first })
    }

    @Test
    fun aTodoHeldOnItsTextOpensAtTheFingerAndTalkBackOnItsDot() {
        val todo = Todo.new("a", "Pay rent", now - 60_000L)
        show(listOf(TodoEntry(todo)), todos = listOf(todo))
        val pressed = holdAt(compose.onNodeWithText("Pay rent")) { width, height -> Offset(width - 4f, height / 2f) }
        assertNear(pressed, menu.spec?.center)
        assertEquals(listOf("pin", "delete"), shownArcs().map { it.first })
        menu.close()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Select", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertNear(middleOf(compose.onNodeWithContentDescription("Select", useUnmergedTree = true)), menu.spec?.center)
    }

    @Test
    fun aDoneTodoOffersPinAndDeleteAsAnArchivedEmailDoes() {
        val todo = Todo.new("d", "Filed taxes", now - 60_000L).copy(state = TodoState.DONE)
        show(listOf(TodoEntry(todo)), todos = listOf(todo), view = View.Fixed(Mailbox.DONE))
        holdAt(compose.onNodeWithText("Filed taxes")) { width, height -> Offset(width / 2f, height / 2f) }
        assertEquals(listOf("pin" to -45f, "delete" to -105f), shownArcs())
        compose.onNodeWithContentDescription("Pin").performClick()
        compose.waitForIdle()
        assertEquals(listOf("pin:d"), calls)
    }

    @Test
    fun aClosedBundleHeldOpensPinAllAndDeleteAllAtTheFinger() {
        show(listOf(ListEntry.Group("Label_1", listOf(row("b1", listOf("INBOX", "Label_1")), row("b2", listOf("INBOX", "Label_1"))))))
        val pressed = holdAt(compose.onNodeWithText("Errands")) { width, height -> Offset(width / 2f, height / 2f) }
        assertNear(pressed, menu.spec?.center)
        assertEquals(listOf("PIN" to -45f, "DELETE" to -105f), shownArcs())
        assertEquals(listOf("Pin all", "Delete all"), requireNotNull(menu.spec).wedges.map { it.item.label })
        compose.onNodeWithContentDescription("Delete all").performClick()
        compose.waitForIdle()
        assertEquals(listOf("deleteBundle:Label_1:b1, b2"), calls)
    }

    @Test
    fun aBundleWhoseThreadsAreAllPinnedOffersUnpinAll() {
        show(listOf(ListEntry.Group("Label_1", listOf(row("b1", listOf("INBOX", "Label_1", "STARRED")), row("b2", listOf("INBOX", "Label_1", "STARRED"))))))
        holdAt(compose.onNodeWithText("Errands")) { width, height -> Offset(width / 2f, height / 2f) }
        assertEquals(listOf("Unpin all", "Delete all"), requireNotNull(menu.spec).wedges.map { it.item.label })
        compose.onNodeWithContentDescription("Unpin all").performClick()
        compose.waitForIdle()
        assertEquals(listOf("unpinBundle:Label_1:b1, b2"), calls)
    }

    @Test
    fun talkBackOffersABundlesSwipesAndItsMenu() {
        show(listOf(ListEntry.Group("Label_1", listOf(row("b1", listOf("INBOX", "Label_1")), row("b2", listOf("INBOX", "Label_1"))))))
        val node = compose.onNode(withActions("Pin all"))
        assertEquals(listOf("Done all", "Snooze all", "Pin all", "Delete all"), talkBackLabels(node))
        val pin = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Pin all" }
        compose.runOnIdle { pin.action() }
        node.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(listOf("PIN", "DELETE"), shownArcs().map { it.first })
        assertEquals(listOf("pinBundle:Label_1:b1, b2"), calls)
    }

    @Test
    fun anOpenBundlesHeaderOpensTheSameMenu() {
        show(listOf(ListEntry.Group("Label_1", listOf(row("b1", listOf("INBOX", "Label_1")), row("b2", listOf("INBOX", "Label_1"))))), open = bundleRowKey("Label_1"))
        holdAt(compose.onAllNodesWithText("Errands")[0]) { width, height -> Offset(width / 2f, height / 2f) }
        assertEquals(listOf("Pin all", "Delete all"), requireNotNull(menu.spec).wedges.map { it.item.label })
        compose.onNodeWithContentDescription("Delete all").performClick()
        compose.waitForIdle()
        assertEquals(listOf("deleteBundle:Label_1:b1, b2"), calls)
    }

    @Test
    fun talkBackOffersAnOpenBundlesMenuButNoSwipes() {
        show(listOf(ListEntry.Group("Label_1", listOf(row("b1", listOf("INBOX", "Label_1")), row("b2", listOf("INBOX", "Label_1"))))), open = bundleRowKey("Label_1"))
        assertEquals(listOf("Pin all", "Delete all"), talkBackLabels(compose.onNode(withActions("Pin all"))))
    }
}
