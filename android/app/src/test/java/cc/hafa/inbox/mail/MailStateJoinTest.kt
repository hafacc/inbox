package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.mail.ThreadDetail
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.data.SnoozeSource
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.Collections
import java.util.UUID

/** An encrypted reply Gmail split into a thread of its own shows, opens and acts as one row with the thread it answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateJoinTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    // Label changes sent to Gmail, as thread id and request body.
    private val modified: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

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

    private fun message(id: String, threadId: String, date: Long, extra: String = "") =
        """{"id":"$id","threadId":"$threadId","labelIds":["INBOX"],"internalDate":"$date","snippet":"$id",""" +
            """"payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"ana@example.com"},""" +
            """{"name":"Subject","value":"..."},{"name":"Message-ID","value":"<$id@x>"}$extra]}}"""

    // The original, t1, and t2, the reply Gmail split off it, answering its first message.
    private val threads = mapOf(
        "t1" to """{"id":"t1","historyId":"5","messages":[${message("m1", "t1", 1000)},${message("m3", "t1", 3000)}]}""",
        "t2" to """{"id":"t2","historyId":"6","messages":[${message("m2", "t2", 2000, """,{"name":"In-Reply-To","value":"<m1@x>"}""")},""" +
            """${message("m4", "t2", 4000)}]}""",
    )

    // Whether Gmail has lost t2 (deleted for good elsewhere) since the list loaded.
    @Volatile private var replyGone = false

    private val gmail = HttpTransport { method, url, _, body ->
        val path = url.substringAfter("/users/me").substringBefore("?")
        val thread = path.removePrefix("/threads/").substringBefore("/")
        when {
            method == "POST" && path.endsWith("/modify") -> {
                modified += thread to body.orEmpty()
                HttpResponse(200, "{}")
            }
            path == "/threads" && "STARRED" !in url -> HttpResponse(200, """{"threads":[{"id":"t2","historyId":"6"},{"id":"t1","historyId":"5"}]}""")
            path == "/threads" -> HttpResponse(200, """{"threads":[]}""")
            path.startsWith("/threads/") && replyGone && thread == "t2" -> HttpResponse(404, "{}")
            path.startsWith("/threads/") -> HttpResponse(200, threads.getValue(thread))
            path == "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"6"}""")
            path == "/labels" -> HttpResponse(200, """{"labels":[]}""")
            path.startsWith("/labels/") -> HttpResponse(200, """{"id":"DRAFT"}""")
            else -> HttpResponse(200, "{}")
        }
    }

    private fun build(): MailState {
        var clock = 0L
        return MailState(
            context = context,
            // Its own account each time: Firestore keeps the snoozes one test writes for the next.
            uid = "join-test-${UUID.randomUUID()}",
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

    private fun loaded(): MailState {
        val state = build()
        awaitTrue("both threads load") {
            val model = state.model.value
            model.lists[View.INBOX.key]?.ids.orEmpty().containsAll(listOf("t1", "t2")) && "t1" in model.summaries && "t2" in model.summaries
        }
        return state
    }

    private fun sent(thread: String, label: String, adds: Boolean): Boolean = synchronized(modified) {
        modified.any { (id, body) -> id == thread && Regex(""""${if (adds) "add" else "remove"}LabelIds":\[[^\]]*"$label"""").containsMatchIn(body) }
    }

    @Test
    fun `the split reply shows in its original's row`() {
        val state = loaded()
        assertEquals(listOf("t1"), state.model.value.inboxRows.map { it.thread.id })
        assertEquals(4, state.model.value.inboxRows.single().thread.messageCount)
    }

    @Test
    fun `Done and its undo reach both threads`() {
        val state = loaded()
        state.done(listOf("t1"))
        awaitTrue("both archived") { sent("t1", "INBOX", adds = false) && sent("t2", "INBOX", adds = false) }
        assertEquals(listOf("t1", "t2"), state.undo.value?.items?.map { it.id })
        state.undoLast()
        awaitTrue("both back in the inbox") { sent("t1", "INBOX", adds = true) && sent("t2", "INBOX", adds = true) }
    }

    @Test
    fun `Done on the split-off thread's notification archives its whole row`() {
        val state = loaded()
        MainScope().launch { state.doneFromNotification("t2") }
        awaitTrue("both archived") { sent("t1", "INBOX", adds = false) && sent("t2", "INBOX", adds = false) }
    }

    @Test
    fun `snoozing the row snoozes both threads`() {
        val state = loaded()
        assertTrue(state.snooze("t1", System.currentTimeMillis() + DAY, SnoozeSource.SUGGESTION, null))
        assertEquals(listOf("t1", "t2"), state.undo.value?.items?.map { it.id })
    }

    @Test
    fun `the open row shows both threads' messages, and replies go to the newest one's thread`() {
        val state = loaded()
        state.open("t2")
        var detail: ThreadDetail? = null
        awaitTrue("the row opens") {
            detail = state.model.value.detail
            detail?.messages?.size == 4
        }
        val opened = requireNotNull(detail)
        assertEquals("t1", opened.id)
        assertEquals(listOf("m1", "m2", "m3", "m4"), opened.messages.map { it.id })
        assertEquals("t2", opened.threadOf(opened.messages.last()))
    }

    @Test
    fun `a joined thread Gmail no longer has leaves the open row, which opens with the rest`() {
        val state = loaded()
        replyGone = true
        state.open("t1")
        awaitTrue("the row opens without the reply") {
            state.model.value.detail?.messages?.map { it.id } == listOf("m1", "m3")
        }
        assertEquals(null, state.model.value.detailError)
        awaitTrue("the gone thread's row goes") { "t2" !in state.model.value.summaries }
    }

    private companion object {
        const val ATTEMPTS = 200
        const val POLL_MS = 25L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
