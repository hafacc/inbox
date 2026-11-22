package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.View
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Leaving search returns to the view the search was started from, not always Inbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateSearchOriginTest {
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

    private val gmail = HttpTransport { _, url, _, _ ->
        when (url.substringAfter("/users/me").substringBefore("?")) {
            "/profile" -> HttpResponse(200, """{"emailAddress":"me@example.com","historyId":"5"}""")
            "/labels" -> HttpResponse(200, """{"labels":[]}""")
            else -> HttpResponse(200, "{}")
        }
    }

    @Test
    fun `closing a search goes back to the view it was started from`() {
        var clock = 0L
        val state = MailState(
            context = context,
            uid = "search-origin-test",
            email = "me@example.com",
            tokens = Tokens,
            firestore = FirebaseFirestore.getInstance(),
            onPendingChanges = {},
            onNotificationDoneFailed = {},
            focusOn = MutableStateFlow(false),
            transport = gmail,
            gate = RateGate(now = { clock }, sleep = { clock += it }),
        )
        val spam = View.Fixed(Mailbox.SPAM)
        state.setView(spam)
        state.setView(View.Search("from:maya"))
        state.setView(View.Search("lease"))
        state.closeSearch()
        ShadowLooper.idleMainLooper()
        assertEquals(spam, state.model.value.view)
        state.closeSearch()
        assertEquals("closing again outside search changes nothing", spam, state.model.value.view)
    }
}
