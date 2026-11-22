package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.mail.LoadedList
import cc.hafa.inbox.core.mail.View
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A list whose load fails, even after Gmail's usage limits are waited out, ends with Retry rather than loading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateLoadTest {
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

    private val limited = HttpResponse(403, """{"error":{"message":"Too many","errors":[{"reason":"rateLimitExceeded"}]}}""")

    // Gmail with one inbox thread; [answer] overrides the reply to a request when it gives one.
    private fun gmail(answer: (String) -> HttpResponse?) = HttpTransport { _, url, _, _ ->
        val path = url.substringAfter("/users/me").substringBefore("?")
        answer(url) ?: when {
            path == "/threads" -> HttpResponse(200, """{"threads":[{"id":"t1","historyId":"5"}]}""")
            path == "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"5"}""")
            path == "/labels" -> HttpResponse(200, """{"labels":[]}""")
            path.startsWith("/labels/") -> HttpResponse(200, """{"id":"DRAFT"}""")
            else -> HttpResponse(200, "{}")
        }
    }

    private fun build(transport: HttpTransport, focus: Boolean): MailState {
        var clock = 0L
        return MailState(
            context = context,
            uid = "load-test",
            email = "me@example.com",
            tokens = Tokens,
            firestore = FirebaseFirestore.getInstance(),
            onPendingChanges = {},
            onNotificationDoneFailed = {},
            focusOn = MutableStateFlow(focus),
            transport = transport,
            gate = RateGate(now = { clock }, sleep = { clock += it }),
        )
    }

    private fun settled(state: MailState, key: String): LoadedList {
        repeat(ATTEMPTS) {
            ShadowLooper.idleMainLooper()
            val list = state.model.value.lists[key]
            if (list != null && list.loaded && !list.loading) return list
            Thread.sleep(POLL_MS)
        }
        error("$key never finished loading: ${state.model.value.lists[key]}")
    }

    @Test
    fun `threads Gmail keeps limiting leave the inbox with an error and Retry, not loading`() {
        val state = build(gmail { url -> limited.takeIf { "/threads/t1" in url } }, focus = false)
        val inbox = settled(state, View.INBOX.key)
        assertEquals(listOf("t1"), inbox.ids)
        assertNotNull(inbox.error)
        assertFalse(state.model.value.summaries.containsKey("t1"))
    }

    @Test
    fun `the inbox behind focus mode that fails to load says so`() {
        val failing = HttpResponse(500, """{"error":{"message":"Backend Error"}}""")
        val state = build(gmail { url -> failing.takeIf { "/threads?" in url && "starred" !in url } }, focus = true)
        val inbox = settled(state, View.INBOX.key)
        assertEquals("Gmail said: Backend Error", inbox.error)
    }

    private companion object {
        const val ATTEMPTS = 200
        const val POLL_MS = 25L
    }
}
