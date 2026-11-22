package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.mail.UndoKind
import cc.hafa.inbox.core.mail.View
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

/** A todo on an email whose text is cleared out is deleted when its edit ends, with undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateEmailTodoTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private object Tokens : TokenSource {
        override suspend fun get(): String = "token"

        override fun invalidate() = Unit
    }

    @Before
    fun setUp() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey("test-api-key")
                    .setApplicationId("1:1:android:1")
                    .setProjectId("test-project")
                    .build(),
            )
        }
    }

    private val thread =
        """{"id":"t1","historyId":"5","messages":[{"id":"m1","threadId":"t1","labelIds":["INBOX"],"historyId":"5",""" +
            """"internalDate":"1000","snippet":"hi","payload":{"mimeType":"text/plain","headers":[""" +
            """{"name":"From","value":"ana@example.com"},{"name":"Subject","value":"Plans"},""" +
            """{"name":"Message-ID","value":"<m1@x>"}],"body":{"size":2,"data":"aGk"}}}]}"""

    private val gmail = HttpTransport { _, url, _, _ ->
        when (url.substringAfter("/users/me").substringBefore("?")) {
            "/threads" -> HttpResponse(200, """{"threads":[{"id":"t1","historyId":"5"}]}""")
            "/threads/t1" -> HttpResponse(200, thread)
            "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"7"}""")
            else -> HttpResponse(200, "{}")
        }
    }

    private fun build(): MailState {
        var clock = 0L
        return MailState(
            context = context,
            uid = "email-todo-test-${UUID.randomUUID()}",
            email = "me@example.com",
            tokens = Tokens,
            firestore = FirebaseFirestore.getInstance(),
            onPendingChanges = {},
            onNotificationDoneFailed = {},
            focusOn = MutableStateFlow(false),
            transport = gmail,
            gate = RateGate(now = { clock }, sleep = { clock += it }),
        )
    }

    private fun awaitTrue(what: String, check: () -> Boolean) {
        repeat(ATTEMPTS) {
            ShadowLooper.idleMainLooper()
            if (check()) return
            Thread.sleep(POLL_MS)
        }
        error("never: $what")
    }

    // The account with t1 in the inbox, todos on, and a saved todo on t1.
    private fun withEmailTodo(): MailState {
        val state = build()
        state.todos.setEnabled(true)
        awaitTrue("the inbox loads") { "t1" in state.model.value.summaries && state.model.value.lists[View.INBOX.key]?.loaded == true }
        state.editThreadTodo("t1")
        val id = requireNotNull(state.todos.snapshot.value.editingId)
        state.todos.edit(id, "call ana")
        state.todos.setEditing(null)
        awaitTrue("the todo on the email") { state.model.value.emailTodos.map { it.text } == listOf("call ana") }
        return state
    }

    @Test
    fun `clearing an email todo's text and ending the edit deletes it, and undo brings it back`() {
        val state = withEmailTodo()
        state.editThreadTodo("t1")
        val id = requireNotNull(state.todos.snapshot.value.editingId)
        state.todos.edit(id, "")
        state.todos.setEditing(null)
        ShadowLooper.idleMainLooper()
        assertEquals(UndoKind.DELETE, state.undo.value?.kind)
        awaitTrue("the todo gone from the model") { state.model.value.emailTodos.isEmpty() }
        assertNull("the email's todo line", state.emailTodoOf(listOf("t1")))
        assertEquals("the email left as it is", true, state.model.value.summaries["t1"]?.isInInbox)

        state.undoLast()
        awaitTrue("the todo back") { state.model.value.emailTodos.map { it.text } == listOf("call ana") }
        assertEquals("call ana", state.emailTodoOf(listOf("t1"))?.text)
    }

    private companion object {
        const val ATTEMPTS = 200
        const val POLL_MS = 25L
    }
}
