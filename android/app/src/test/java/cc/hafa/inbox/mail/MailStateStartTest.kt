package cc.hafa.inbox.mail

import android.app.Application
import cc.hafa.inbox.core.gmail.TokenSource
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException

/** Signing in builds [MailState] on the main thread, where its own coroutines start before its constructor returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MailStateStartTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val outer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val failures = mutableListOf<Throwable>()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    private object NoTokens : TokenSource {
        override suspend fun get(): String = throw IOException("offline in tests")

        override fun invalidate() = Unit
    }

    @Before
    fun setUp() {
        // Coroutines on the main thread report a crash here rather than to the caller.
        Thread.setDefaultUncaughtExceptionHandler { _, error -> failures += error }
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

    @After
    fun tearDown() {
        outer.cancel()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun build() = MailState(
        context = context,
        uid = "u1",
        email = "me@example.com",
        tokens = NoTokens,
        firestore = FirebaseFirestore.getInstance(),
        onPendingChanges = {},
        onNotificationDoneFailed = {},
        focusOn = MutableStateFlow(false),
    )

    @Test
    fun `starts inside a main-thread collector as sign-in does`() {
        val signedIn = MutableStateFlow<String?>(null)
        var built: MailState? = null
        outer.launch {
            signedIn.collect { uid ->
                if (uid != null) {
                    // Sign-in waits on Firebase first, so it builds the state resumed from the main looper.
                    yield()
                    built = build()
                }
            }
        }
        signedIn.value = "u1"
        ShadowLooper.idleMainLooper()
        assertEquals("u1", built?.uid)
        assertEquals(emptyList<Throwable>(), failures)
    }

    @Test
    fun `a label edit not yet in Gmail counts as a change sign-out would lose`() {
        val state = build()
        ShadowLooper.idleMainLooper()
        state.createLabel("Trips", null)
        ShadowLooper.idleMainLooper()
        assertEquals(1 to 0, runBlocking { state.unsyncedWork() })
    }

    @Test
    fun `starts when built directly on the main thread`() {
        assertEquals("u1", build().uid)
        assertEquals(emptyList<Throwable>(), failures)
    }
}
