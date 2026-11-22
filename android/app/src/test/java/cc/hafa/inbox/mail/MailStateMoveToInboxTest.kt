package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.layoutKeys
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
import java.net.URLDecoder

/** Move to inbox on a row of the Done view takes the row out of it, and it stays out once Gmail has the change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateMoveToInboxTest {
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

    // Whether Gmail has t1 in the inbox: once the move reaches it.
    @Volatile private var inInbox = false

    // Gmail's history id, moved on by the move.
    @Volatile private var historyNow = "5"

    private fun thread(): String {
        val labels = if (inInbox) """"INBOX"""" else ""
        return """{"id":"t1","historyId":"$historyNow","messages":[{"id":"mt1","threadId":"t1","labelIds":[$labels],"historyId":"$historyNow",""" +
            """"internalDate":"1000","snippet":"hi","payload":{"mimeType":"text/plain","headers":[""" +
            """{"name":"From","value":"Ana <ana@example.com>"},{"name":"Subject","value":"Hello"},""" +
            """{"name":"Message-ID","value":"<a1@x>"}],"body":{"size":2,"data":"aGk"}}}]}"""
    }

    private val gmail = HttpTransport { method, url, _, _ ->
        val path = url.substringAfter("/users/me").substringBefore("?")
        val query = URLDecoder.decode(url.substringAfter("?", ""), "UTF-8")
        val listed = """{"threads":[{"id":"t1","historyId":"$historyNow"}]}"""
        when {
            path == "/threads" && "-in:inbox" in query -> HttpResponse(200, if (inInbox) "{}" else listed)
            path == "/threads" -> HttpResponse(200, if (inInbox) listed else "{}")
            path == "/threads/t1/modify" && method == "POST" -> {
                inInbox = true
                historyNow = "6"
                HttpResponse(200, thread())
            }
            path == "/threads/t1" -> HttpResponse(200, thread())
            path == "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"$historyNow"}""")
            path == "/labels" -> HttpResponse(200, """{"labels":[]}""")
            path.startsWith("/labels/") -> HttpResponse(200, """{"id":"DRAFT"}""")
            else -> HttpResponse(200, "{}")
        }
    }

    private fun await(state: MailState, what: String, done: (MailModel) -> Boolean): MailModel {
        repeat(ATTEMPTS) {
            ShadowLooper.idleMainLooper()
            val model = state.model.value
            if (done(model)) return model
            Thread.sleep(POLL_MS)
        }
        error("$what never happened: layout ${layoutKeys(state.model.value.list.layout)}")
    }

    private fun settle() = repeat(SETTLE_POLLS) {
        ShadowLooper.idleMainLooper()
        Thread.sleep(POLL_MS)
    }

    @Test
    fun `a done thread moved to the inbox leaves the Done view for good`() {
        var clock = 0L
        val state = MailState(
            context = context,
            uid = "move-to-inbox-test",
            email = "me@example.com",
            tokens = Tokens,
            firestore = FirebaseFirestore.getInstance(),
            onPendingChanges = {},
            onNotificationDoneFailed = {},
            focusOn = MutableStateFlow(false),
            transport = gmail,
            gate = RateGate(now = { clock }, sleep = { clock += it }),
        )
        val done = View.Fixed(Mailbox.DONE)
        await(state, "inbox load") { model -> model.lists[View.INBOX.key]?.loaded == true }
        state.setView(done)
        await(state, "Done listing t1") { model -> "t1" in layoutKeys(model.list.layout) }
        state.moveToInbox(listOf("t1"))
        ShadowLooper.idleMainLooper()
        assertFalse("still listed at once", "t1" in layoutKeys(state.model.value.list.layout))
        await(state, "the move reaching Gmail") { model -> inInbox && model.queue.isEmpty() }
        state.refresh(force = true)
        settle()
        assertFalse("listed again after Gmail had it", "t1" in layoutKeys(state.model.value.list.layout))
        assertFalse("listed again in the rows", state.model.value.list.rows.any { it.key == "t1" })
    }

    private companion object {
        const val SETTLE_POLLS = 40
        const val ATTEMPTS = 200
        const val POLL_MS = 25L
    }
}
