package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.data.MailCache
import cc.hafa.inbox.data.MailDatabase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A thread Gmail no longer has (deleted for good) leaves the screen and the device's cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateGoneTest {
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

    // Gmail lists t1 at a newer history than the device kept, then answers "not found" for it.
    private val gmail = HttpTransport { _, url, _, _ ->
        val path = url.substringAfter("/users/me").substringBefore("?")
        when {
            path == "/threads" -> HttpResponse(200, """{"threads":[{"id":"t1","historyId":"5"}]}""")
            path == "/threads/t1" -> HttpResponse(404, """{"error":{"code":404,"message":"Requested entity was not found."}}""")
            path == "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"5"}""")
            path == "/labels" -> HttpResponse(200, """{"labels":[]}""")
            path.startsWith("/labels/") -> HttpResponse(200, """{"id":"DRAFT"}""")
            else -> HttpResponse(200, "{}")
        }
    }

    @Test
    fun `a kept row Gmail answers not found for leaves the screen and the cache`() {
        val uid = "gone-test"
        val kept = ThreadSummary("t1", "3", "Hello", "", emptyList(), 1, 0L, listOf("INBOX"), unread = false)
        val database = MailDatabase.open(context, uid)
        val cache = MailCache(database.mailDao())
        runBlocking {
            cache.putSummaries(listOf(kept))
            cache.putList(View.INBOX.key, listOf("t1"), null)
        }
        var clock = 0L
        val state = MailState(
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
        var stored = listOf(kept)
        var attempts = 0
        while (attempts++ < ATTEMPTS) {
            ShadowLooper.idleMainLooper()
            val list = state.model.value.lists[View.INBOX.key]
            stored = runBlocking { cache.summaries(listOf("t1")) }
            if (list != null && list.loaded && !list.loading && stored.isEmpty()) break
            Thread.sleep(POLL_MS)
        }
        assertFalse(state.model.value.summaries.containsKey("t1"))
        assertTrue("still cached: $stored", stored.isEmpty())
        database.close()
    }

    private companion object {
        const val ATTEMPTS = 200
        const val POLL_MS = 25L
    }
}
