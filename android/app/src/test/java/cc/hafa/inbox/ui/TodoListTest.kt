package cc.hafa.inbox.ui

import cc.hafa.inbox.core.mail.ListEvent
import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
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
import cc.hafa.inbox.core.menu.HOLD_MS
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.data.TodoSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Todos among the inbox's threads and in the Todos view: their rows, editor, tabs and empty lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
class TodoListTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val calls = mutableListOf<String>()

    private val rowActions = RowActions(
        list = { event -> if (event is ListEvent.Pick) calls += "select:${event.key}" },
        tap = { calls += "open:$it" }, done = { calls += "threadDone:${it.joinToString()}" }, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {}, doneBundle = { _, _ -> },
        doneSection = {}, labelSettings = {}, loadMore = {},
    )

    private val todoActions = TodoActions(
        toggleDone = { calls += "done:$it" },
        wake = { calls += "wake:$it" },
        snooze = { calls += "snooze:$it" },
        togglePin = { calls += "pin:$it" },
        delete = { calls += "delete:$it" },
        startEditing = { calls += "edit:$it" },
        edit = { id, text -> calls += "text:$id:$text" },
        finishEditing = { id -> calls += "finish:$id" },
        enter = { calls += "enter:$it"; true },
    )

    private fun todo(id: String, text: String, minutesAgo: Long = 1, state: TodoState = TodoState.ACTIVE): Todo =
        Todo.new(id, text, now - minutesAgo * 60_000L).copy(state = state)

    private var back: OnBackPressedDispatcher? = null
    private var focusManager: FocusManager? = null

    private fun show(model: MailModel, snapshot: TodoSnapshot) {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            focusManager = LocalFocusManager.current
            val offers = remember { TagOffers() }
            CompositionLocalProvider(LocalTagOffers provides offers) {
                HoldMenuHost {
                    Column {
                        ThreadList(model.listing(), rowActions, PaddingValues(), Modifier.weight(1f), todos = snapshot, todoActions = todoActions)
                        TagBar(offers)
                    }
                }
            }
        }
    }

    private fun editing(id: String, todos: List<Todo>) {
        val snapshot = TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos, editingId = id)
        show(MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos), snapshot)
        compose.onNodeWithContentDescription("Todo").assertIsFocused()
    }

    @Test
    fun inboxShowsTodosAmongThreadsAndTheirChecksSelectThem() {
        val todos = listOf(todo("a", "Call *Sam* about #lease"), todo("b", "Water plants", minutesAgo = 5))
        show(MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos), TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos))
        compose.onNodeWithText("Call *Sam* about", substring = true).assertExists()
        compose.onNodeWithText("Water plants").performClick()
        compose.onAllNodesWithContentDescription("Select", useUnmergedTree = true)[0].performClick()
        assertEquals(listOf("edit:b", "select:todo:a"), calls)
    }

    @Test
    fun aLongPressOnATodosTextOpensItsHoldMenuAndDoesNotSelectIt() {
        val todos = listOf(todo("a", "Pay rent"))
        show(MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos), TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos))
        compose.onNodeWithText("Pay rent").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Pin").performClick()
        compose.waitForIdle()
        assertEquals(listOf("pin:a"), calls)
    }

    @Test
    fun talkBacksLongPressOnATodoRowOpensItsHoldMenu() {
        val todos = listOf(todo("a", "Pay rent"))
        show(MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos), TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos))
        compose.onNodeWithText("Pay rent").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf("delete:a"), calls)
    }

    @Test
    fun aSlideFromAHeldTodoRowDeletesItWithoutSelectingOrEditing() {
        val todos = listOf(todo("a", "Pay rent"))
        show(MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos), TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos))
        val text = compose.onAllNodesWithText("Pay rent", useUnmergedTree = true)[0]
        text.performTouchInput { down(centerLeft + Offset(4f, 0f)) }
        compose.mainClock.advanceTimeBy(HOLD_MS + 100)
        // The disc opens at the finger; near the top of the screen Delete is below it.
        text.performTouchInput {
            moveBy(Offset(0f, 90.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("delete:a"), calls)
    }

    @Test
    fun aTapOnATodoRowStillEditsAndWhileSelectingSelects() {
        val todos = listOf(todo("a", "Pay rent"))
        val model = MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos)
        val snapshot = TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos)
        var shown by mutableStateOf(model.listing())
        compose.setContent {
            HoldMenuHost { ThreadList(shown, rowActions, PaddingValues(), todos = snapshot, todoActions = todoActions) }
        }
        compose.onNodeWithText("Pay rent").performClick()
        shown = shown.step(ListEvent.Pick("todo:a", range = false))
        compose.onNodeWithText("Pay rent").performClick()
        compose.onNodeWithContentDescription("Unselect", useUnmergedTree = true).performClick()
        assertEquals(listOf("edit:a", "select:todo:a", "select:todo:a"), calls)
    }

    @Test
    fun anOpenTodoOffersKnownTagsAndNoStripOfActions() {
        val todos = listOf(todo("a", "Pay rent"), todo("b", "Plan #holiday"))
        val snapshot = TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos, editingId = "a")
        show(MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos), snapshot)
        compose.onNodeWithText("Snooze").assertDoesNotExist()
        compose.onNodeWithText("Pin").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithContentDescription("Todo").performTextInput(" #ho")
        compose.onNodeWithText("#holiday").performClick()
        assertEquals("text:a:Pay rent #holiday ", calls.last())
    }

    @Test
    fun todosViewListsOnlyActiveTodosWithoutTabs() {
        val done = listOf(todo("d", "Filed taxes", state = TodoState.DONE))
        val model = MailModel(email = "me@example.com", now = now, view = View.Todos, todosOn = true, todos = done)
        show(model, TodoSnapshot(enabled = true, loaded = true, todos = done, placed = done))
        compose.onNodeWithText("Nothing to do.").assertExists()
        compose.onNodeWithText("Filed taxes").assertDoesNotExist()
        compose.onNodeWithText("Active").assertDoesNotExist()
    }

    @Test
    fun enterInATodoWithTextStartsTheNextOne() {
        editing("a", listOf(todo("a", "Pay rent")))
        compose.onNodeWithContentDescription("Todo").performImeAction()
        assertEquals(listOf("enter:a"), calls)
    }

    @Test
    fun theCheckOfATodoBeingEditedSavesItsTextThenSelectsIt() {
        editing("a", listOf(todo("a", "Pay rent")))
        compose.onNodeWithContentDescription("Select", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("finish:a", "select:todo:a"), calls)
    }

    @Test
    fun holdingTheDotOfATodoBeingEditedSavesItsTextThenPins() {
        editing("a", listOf(todo("a", "Pay rent")))
        compose.onNodeWithContentDescription("Select", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Pin").performClick()
        compose.waitForIdle()
        assertEquals(listOf("finish:a", "pin:a"), calls)
    }

    @Test
    fun aSlideFromTheHeldDotDeletesThatTodoAlone() {
        val todos = listOf(todo("a", "Pay rent"), todo("b", "Water plants", minutesAgo = 5))
        val snapshot = TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos)
        show(MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos), snapshot)
        val dot = compose.onAllNodesWithContentDescription("Select", useUnmergedTree = true)[0]
        dot.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_MS + 100)
        // Near the top of the screen the half disc opens right (Pin) and down (Delete).
        dot.performTouchInput {
            moveBy(Offset(0f, 70.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("delete:a"), calls)
    }

    @Test
    fun whileRowsAreSelectedASwipeActsOnItsOwnRowOnly() {
        val todos = listOf(todo("a", "Pay rent"), todo("b", "Water plants", minutesAgo = 5))
        val sections = listOf(SectionModel("today", "Today", listOf(ListEntry.Single(row("t1")), TodoEntry(todos[0]), TodoEntry(todos[1])), canDoneAll = true))
        val model = MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos, lists = mapOf(View.INBOX.key to LoadedList(loaded = true)))
        val selected = model.listing(sections).step(ListEvent.Pick("todo:a", range = false)).step(ListEvent.Pick("todo:b", range = false))
        compose.setContent {
            ThreadList(selected, rowActions, PaddingValues(), todos = TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos), todoActions = todoActions)
        }
        compose.onNodeWithText("Pay rent").performTouchInput { swipeLeft(startX = right - 10f, endX = left) }
        compose.waitForIdle()
        compose.onNodeWithText("Subject t1").performTouchInput { swipeLeft(startX = right - 10f, endX = left) }
        compose.waitForIdle()
        assertEquals(listOf("done:a", "threadDone:t1"), calls)
    }

    @Test
    fun aTodoBeingEditedCanBeSwipedDone() {
        editing("a", listOf(todo("a", "Pay rent")))
        compose.onNodeWithContentDescription("Todo").performTouchInput { swipeLeft(startX = right - 10f, endX = left) }
        compose.waitForIdle()
        assertEquals(listOf("finish:a", "done:a"), calls)
    }

    @Test
    fun backLeavesEditing() {
        editing("a", listOf(todo("a", "Pay rent")))
        compose.runOnIdle { requireNotNull(back).onBackPressed() }
        compose.onNodeWithContentDescription("Todo").assertIsNotFocused()
        assertEquals(listOf("finish:a"), calls)
    }

    private fun row(id: String): Row {
        val thread = ThreadSummary(
            id = id,
            historyId = "1",
            subject = "Subject $id",
            snippet = "",
            senders = listOf(Person("Sender $id", "$id@example.com")),
            messageCount = 1,
            lastDate = now - 60_000L,
            labelIds = listOf("INBOX"),
            unread = false,
        )
        return Row(thread, SnoozeStatus.NONE, null, thread.lastDate)
    }

    @Test
    fun anEmailsTodoIsALineInItsRowThatTheRowsTapOpens() {
        val onEmail = EmailTodo.new("t1", "Reply to *Sam*", now)
        val sections = listOf(SectionModel("today", "Today", listOf(ListEntry.Single(row("t1"))), canDoneAll = true))
        val model = MailModel(
            email = "me@example.com",
            now = now,
            todosOn = true,
            emailTodos = listOf(onEmail),
            lists = mapOf(View.INBOX.key to LoadedList(loaded = true)),
        )
        compose.setContent {
            ThreadList(model.listing(sections), rowActions, PaddingValues(), todos = TodoSnapshot(enabled = true, loaded = true, emailTodos = listOf(onEmail)), todoActions = todoActions)
        }
        // No tag beside the text, which TalkBack hears as a todo.
        compose.onNodeWithText("TODO").assertDoesNotExist()
        compose.onNodeWithContentDescription("Todo: Reply to *Sam*", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Reply to *Sam*", substring = true).performClick()
        // The email's todo has no check, select dot or actions of its own: the row's are the email's.
        compose.onNodeWithContentDescription("Not done", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark done", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf("open:t1"), calls)
    }

    @Test
    fun theOpenEmailsTodoOpensToEditAndClosesWithItsText() {
        val onEmail = EmailTodo.new("t1", "Reply to Sam", now)
        var editing by mutableStateOf(false)
        compose.setContent {
            focusManager = LocalFocusManager.current
            EmailTodoLine(onEmail, editing = editing, actions = todoActions)
        }
        compose.onNodeWithText("Reply to Sam").performClick()
        assertEquals(listOf("edit:t1"), calls)
        editing = true
        compose.onNodeWithContentDescription("Todo").assertIsFocused()
        compose.onNodeWithText("Snooze").assertDoesNotExist()
        compose.onNodeWithText("Pin").assertDoesNotExist()
        compose.runOnIdle { requireNotNull(focusManager).clearFocus() }
        compose.waitForIdle()
        assertEquals(listOf("edit:t1", "finish:t1"), calls)
    }
}
