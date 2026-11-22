package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.gmail.ConnectionEvent
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.threadRowKey
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException

/** The offline mark: shown when a Gmail call gets no answer, cleared by any later answer or the network coming back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateConnectionTest {
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

    @Volatile private var reachable = true

    private val thread =
        """{"id":"t1","historyId":"5","messages":[{"id":"mt1","threadId":"t1","labelIds":["INBOX"],"historyId":"5",""" +
            """"internalDate":"1000","snippet":"hi","payload":{"mimeType":"text/plain","headers":[""" +
            """{"name":"From","value":"Ana <ana@example.com>"},{"name":"Subject","value":"..."},""" +
            """{"name":"Message-ID","value":"<a1@x>"}],"body":{"size":2,"data":"aGk"}}}]}"""

    private val gmail = HttpTransport { _, url, _, _ ->
        if (!reachable) throw IOException("no network")
        val path = url.substringAfter("/users/me").substringBefore("?")
        when {
            path == "/threads" -> HttpResponse(200, """{"threads":[{"id":"t1","historyId":"5"}]}""")
            path == "/threads/t1" -> HttpResponse(200, thread)
            path == "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"5"}""")
            path == "/labels" -> HttpResponse(200, """{"labels":[]}""")
            else -> HttpResponse(200, "{}")
        }
    }

    private fun build(uid: String): MailState {
        var clock = 0L
        return MailState(
            context = context,
            uid = uid,
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

    private fun await(state: MailState, what: String, done: (MailModel) -> Boolean): MailModel {
        repeat(ATTEMPTS) {
            ShadowLooper.idleMainLooper()
            val model = state.model.value
            if (done(model)) return model
            Thread.sleep(POLL_MS)
        }
        error("$what never happened: offline ${state.model.value.offline}")
    }

    @Test
    fun `a thread read that comes back clears the mark a failed one set`() {
        val state = build("connection-read-test")
        await(state, "inbox load") { model -> model.lists[View.INBOX.key]?.loaded == true && "t1" in model.summaries }
        reachable = false
        state.dispatch(ListEvent.Tap(threadRowKey("t1")))
        await(state, "the mark") { model -> model.offline }
        state.close()
        await(state, "the close") { model -> model.openId == null }
        reachable = true
        state.dispatch(ListEvent.Tap(threadRowKey("t1")))
        await(state, "the mark clearing") { model -> model.detail?.id == "t1" && !model.offline }
    }

    @Test
    fun `the network coming back clears the mark at once`() {
        val state = build("connection-network-test")
        await(state, "inbox load") { model -> model.lists[View.INBOX.key]?.loaded == true }
        state.noteConnection(ConnectionEvent.NetworkDown)
        await(state, "the mark") { model -> model.offline }
        state.noteConnection(ConnectionEvent.NetworkUp)
        await(state, "the mark clearing") { model -> !model.offline }
    }

    @Test
    fun `a report from a call's thread is applied on the main thread`() {
        val state = build("connection-thread-test")
        await(state, "inbox load") { model -> model.lists[View.INBOX.key]?.loaded == true }
        val caller = Thread { state.noteConnection(ConnectionEvent.Unanswered) }
        caller.start()
        caller.join()
        assertFalse("changed off the main thread", state.model.value.offline)
        await(state, "the mark") { model -> model.offline }
    }

    private companion object {
        const val ATTEMPTS = 200
        const val POLL_MS = 25L
    }
}
