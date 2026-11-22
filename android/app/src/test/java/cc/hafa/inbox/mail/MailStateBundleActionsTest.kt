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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actions whose effects arrive after they show: Done all, Pin all and Delete all on a bundle Gmail
 * has more of offer their undo at once, a label change Gmail refuses puts the row back and says so, and a flush asked for
 * while one runs answers with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateBundleActionsTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    // Label changes sent to Gmail, as thread id and request body.
    private val modified: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    // Holds Gmail's listing of the bundle's every thread until a test lets it answer.
    @Volatile private var listing = CompletableDeferred(Unit)

    // Holds Gmail's answer to label changes until a test lets it go.
    @Volatile private var modifying = CompletableDeferred(Unit)

    // Whether Gmail refuses label changes for good.
    @Volatile private var refusing = false

    // Whether a label change has reached Gmail, answered or not.
    private val modifyStarted = AtomicBoolean()

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
        """{"id":"t1","historyId":"5","messages":[{"id":"m1","threadId":"t1","labelIds":["INBOX","Label_1"],"historyId":"5",""" +
            """"internalDate":"1000","snippet":"hi","payload":{"mimeType":"text/plain","headers":[""" +
            """{"name":"From","value":"ana@example.com"},{"name":"Subject","value":"Plans"},""" +
            """{"name":"Message-ID","value":"<m1@x>"}],"body":{"size":2,"data":"aGk"}}}]}"""

    private val gmail = HttpTransport { method, url, _, body ->
        val path = url.substringAfter("/users/me").substringBefore("?")
        when {
            method == "POST" && path.endsWith("/modify") -> {
                modifyStarted.set(true)
                modifying.await()
                modified += path.removePrefix("/threads/").substringBefore("/") to body.orEmpty()
                if (refusing) HttpResponse(400, """{"error":{"message":"Invalid label"}}""") else HttpResponse(200, "{}")
            }
            // Done all lists the bundle's pinned threads, then every one.
            path == "/threads" && "maxResults=500" in url && "STARRED" in url -> HttpResponse(200, "{}")
            path == "/threads" && "maxResults=500" in url -> {
                listing.await()
                HttpResponse(200, """{"threads":[{"id":"t1","historyId":"5"},{"id":"t9","historyId":"7"}]}""")
            }
            // The bundle's next page fails, so it isn't topped up while the test runs.
            path == "/threads" && "pageToken" in url -> HttpResponse(500, """{"error":{"message":"later"}}""")
            path == "/threads" && "Label_1" in url -> HttpResponse(200, """{"threads":[{"id":"t1","historyId":"5"}],"nextPageToken":"p2"}""")
            path == "/threads" && "STARRED" in url -> HttpResponse(200, "{}")
            path == "/threads" -> HttpResponse(200, """{"threads":[{"id":"t1","historyId":"5"}]}""")
            path == "/threads/t1" -> HttpResponse(200, thread)
            path == "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"7"}""")
            path == "/labels" -> HttpResponse(200, """{"labels":[{"id":"Label_1","name":"Work","type":"user"}]}""")
            path.startsWith("/labels/") -> HttpResponse(200, """{"id":"DRAFT"}""")
            else -> HttpResponse(200, "{}")
        }
    }

    private fun build(): MailState {
        var clock = 0L
        return MailState(
            context = context,
            uid = "bundle-actions-test-${UUID.randomUUID()}",
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

    private fun settle() = repeat(SETTLE_POLLS) {
        ShadowLooper.idleMainLooper()
        Thread.sleep(POLL_MS)
    }

    // The account with t1 loaded in the Work bundle, which Gmail has more of.
    private fun loaded(): MailState {
        val state = build()
        awaitTrue("the bundle loads") {
            val model = state.model.value
            "t1" in model.summaries && model.bundleHasMore("Label_1") && model.lists[View.INBOX.key]?.loaded == true
        }
        return state
    }

    private fun sent(thread: String, label: String, adds: Boolean): Boolean = synchronized(modified) {
        modified.any { (id, body) -> id == thread && Regex(""""${if (adds) "add" else "remove"}LabelIds":\[[^\]]*"$label"""").containsMatchIn(body) }
    }

    @Test
    fun `Done all on a bundle Gmail has more of offers its undo at once, and undo takes back every thread`() {
        listing = CompletableDeferred()
        val state = loaded()
        state.doneBundle("Label_1", listOf("t1"))
        ShadowLooper.idleMainLooper()
        val first = state.undo.value
        assertEquals(UndoKind.DONE, first?.kind)
        assertEquals(listOf("t1"), first?.items?.map { it.id })
        listing.complete(Unit)
        awaitTrue("the rest archived") { sent("t9", "INBOX", adds = false) }
        awaitTrue("the rest joining the undo") { state.undo.value?.items?.map { it.id } == listOf("t1", "t9") }
        state.undoLast()
        awaitTrue("both back in the inbox") { sent("t1", "INBOX", adds = true) && sent("t9", "INBOX", adds = true) }
    }

    @Test
    fun `Pin all on a bundle pins every thread Done all takes, Gmail's other ones too, as one undo`() {
        listing = CompletableDeferred()
        val state = loaded()
        state.pinBundle("Label_1", listOf("t1"))
        ShadowLooper.idleMainLooper()
        assertEquals(UndoKind.PIN, state.undo.value?.kind)
        listing.complete(Unit)
        awaitTrue("both pinned") { sent("t1", "STARRED", adds = true) && sent("t9", "STARRED", adds = true) }
        awaitTrue("the rest joining the undo") { state.undo.value?.items?.map { it.id } == listOf("t1", "t9") }
    }

    @Test
    fun `Delete all on a bundle moves every thread Done all takes to the trash, as one undo`() {
        val state = loaded()
        state.deleteBundle("Label_1", listOf("t1"))
        ShadowLooper.idleMainLooper()
        assertEquals(UndoKind.TRASH, state.undo.value?.kind)
        awaitTrue("both in the trash") { sent("t1", "TRASH", adds = true) && sent("t9", "TRASH", adds = true) }
        awaitTrue("the rest joining the undo") { state.undo.value?.items?.map { it.id } == listOf("t1", "t9") }
    }

    @Test
    fun `undoing Done all before Gmail lists the rest leaves the rest alone`() {
        listing = CompletableDeferred()
        val state = loaded()
        state.doneBundle("Label_1", listOf("t1"))
        ShadowLooper.idleMainLooper()
        state.undoLast()
        listing.complete(Unit)
        settle()
        assertFalse("the rest archived after the undo", sent("t9", "INBOX", adds = false))
        assertTrue("the shown one not put back", state.model.value.summaries["t1"]?.isInInbox == true)
    }

    @Test
    fun `a change Gmail refuses for good puts the row back as Gmail has it and says so`() {
        refusing = true
        modifying = CompletableDeferred()
        val state = loaded()
        state.done(listOf("t1"))
        ShadowLooper.idleMainLooper()
        assertFalse("still in the inbox at once", state.model.value.summaries["t1"]?.isInInbox == true)
        modifying.complete(Unit)
        awaitTrue("the refusal") { state.model.value.queue.isEmpty() && sent("t1", "INBOX", adds = false) }
        awaitTrue("the row as Gmail has it") { state.model.value.summaries["t1"]?.isInInbox == true }
        val notice = state.sending.notice.value
        assertTrue("told $notice", notice is SendNotice.Info && notice.text.startsWith("Couldn't change a conversation. Gmail said: "))
    }

    @Test
    fun `a flush asked for while one runs answers when that one has sent the change`() {
        modifying = CompletableDeferred()
        val state = loaded()
        state.done(listOf("t1"))
        awaitTrue("the change on its way") { modifyStarted.get() }
        var sentWhenAnswered: Int? = null
        MainScope().launch {
            state.flush()
            sentWhenAnswered = modified.size
        }
        ShadowLooper.idleMainLooper()
        modifying.complete(Unit)
        awaitTrue("the second flush answering") { sentWhenAnswered != null }
        assertEquals(1, sentWhenAnswered)
        assertEquals(1, modified.size)
    }

    private companion object {
        const val ATTEMPTS = 200
        const val SETTLE_POLLS = 40
        const val POLL_MS = 25L
    }
}
