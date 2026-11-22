package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToIndex
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.todoRowKey
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.data.TodoSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A new todo is scrolled into view, however many pinned rows sit above where it goes, and kept there until the user scrolls away or the edit settles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class NewTodoRevealTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    private val rowActions = RowActions(
        list = {}, tap = {}, done = {}, snooze = {}, togglePin = {}, wakeNow = {}, loadMoreBundle = {}, doneBundle = { _, _ -> },
        doneSection = {}, labelSettings = {}, loadMore = {},
    )

    private val todoActions = TodoActions(
        toggleDone = {}, wake = {}, snooze = {}, togglePin = {}, delete = {}, startEditing = {}, edit = { _, _ -> },
        finishEditing = {}, enter = { true },
    )

    private fun model(todos: List<Todo>): MailModel = MailModel(email = "me@example.com", now = now, todosOn = true, todos = todos)

    // Shows 30 pinned todos, then starts a new todo below them as the app does: the todo store
    // starts editing it and the reveal is asked for at once, and the todo arrives in the model's
    // next layout after.
    private fun startNewTodo(toTop: TopRequests) {
        val pins = (1..30).map { Todo.new("p$it", "Pinned $it", now - it * 60_000L).copy(pinned = true) }
        val fresh = Todo.new("n", "", now)
        var shown by mutableStateOf(model(pins).listing() to TodoSnapshot(enabled = true, loaded = true, todos = pins, placed = pins))
        compose.setContent {
            val (mail, snapshot) = shown
            ThreadList(mail, rowActions, PaddingValues(), todos = snapshot, todoActions = todoActions, toTop = toTop)
        }
        compose.waitForIdle()
        val todos = pins + fresh
        val asked = shown.first.step(ListEvent.Reveal(todoRowKey("n")))
        shown = asked to TodoSnapshot(enabled = true, loaded = true, todos = todos, placed = todos, editingId = "n")
        compose.waitForIdle()
        shown = asked.copy(todos = todos).listing() to shown.second
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Todo").assertIsDisplayed()
    }

    @Test
    fun aNewTodoBelowManyPinsScrollsIntoView() {
        val toTop = TopRequests()
        startNewTodo(toTop)
        // Something other than the user scrolls the list away before the edit has settled, as the
        // todo's text field taking focus can as it comes on screen; the todo is brought back.
        toTop.request()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Todo").assertIsDisplayed()
    }

    @Test
    fun aUserScrollAwayIsKept() {
        startNewTodo(TopRequests())
        // An accessibility service's scroll, like a drag, wheel or key, is the user leaving the todo.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Todo").assertIsNotDisplayed()
    }

    @Test
    fun aScrollAwayOnceTheEditSettledIsKept() {
        val toTop = TopRequests()
        startNewTodo(toTop)
        compose.mainClock.advanceTimeBy(2_000)
        toTop.request()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Todo").assertIsNotDisplayed()
    }
}
