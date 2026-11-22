package cc.hafa.inbox

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import cc.hafa.inbox.auth.AuthManager
import cc.hafa.inbox.core.gmail.ConnectionEvent
import cc.hafa.inbox.core.notify.notificationTag
import cc.hafa.inbox.core.notify.todoIdOf
import cc.hafa.inbox.data.DraftStore
import cc.hafa.inbox.data.FlushWorker
import cc.hafa.inbox.data.MailCache
import cc.hafa.inbox.data.MailDatabase
import cc.hafa.inbox.data.ParkedWork
import cc.hafa.inbox.data.SavedMailWorker
import cc.hafa.inbox.data.CornerSideStore
import cc.hafa.inbox.data.FocusStore
import cc.hafa.inbox.data.ThemeStore
import cc.hafa.inbox.data.RecentSearchStore
import cc.hafa.inbox.mail.Addresses
import cc.hafa.inbox.mail.ComposeRequest
import cc.hafa.inbox.mail.LauncherRequests
import cc.hafa.inbox.mail.describeFile
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.notify.Devices
import cc.hafa.inbox.notify.NOTIFY_PREFS
import cc.hafa.inbox.notify.Notifier
import cc.hafa.inbox.notify.SnoozeAlarm
import cc.hafa.inbox.pgp.DeviceVault
import cc.hafa.inbox.ui.theme.themeTokens
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "InboxApplication"

// How long a cold-started app gets to set up the signed-in account's mail.
private const val SESSION_WAIT_MS = 10_000L

/** App-wide singletons: Firebase, sign-in, and the signed-in account's [MailState]. */
class InboxApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Sign-in, or null while [FirebaseConfig] holds placeholders. */
    var authManager: AuthManager? = null
        private set

    private val sessionState = MutableStateFlow<MailState?>(null)

    /** This phone's registration for pushes, or null while [FirebaseConfig] holds placeholders. */
    var devices: Devices? = null
        private set

    private val parked by lazy { ParkedWork(this) }

    // A thread to open from a notification, and whether to start a reply in it, with what text.
    private val pendingOpen = MutableStateFlow<OpenRequest?>(null)

    // A send to take back from a notification's Undo: its outbox and local ids.
    private val pendingUndo = MutableStateFlow<Pair<String, String>?>(null)

    // A new message another app asked for, opened once the account's mail is ready.
    private val pendingCompose = MutableStateFlow<ComposeRequest?>(null)

    /** The signed-in account's mail, or null while signed out. */
    val session: StateFlow<MailState?> = sessionState

    /** A launcher shortcut tapped, waiting for the mail screens (and sign-in) to take it. */
    val launcher = LauncherRequests()

    /** The color theme shown, the account's once it loads. */
    val theme: ThemeStore by lazy { ThemeStore(this) }

    /** Focus mode, this device's for every account. */
    val focus: FocusStore by lazy { FocusStore(this) }

    /** Which bottom corner the round buttons sit in, on this device. */
    val cornerSide: CornerSideStore by lazy { CornerSideStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Reads the saved theme and parses the palette off the main thread, before the first frame needs them.
        scope.launch(Dispatchers.Default) {
            themeTokens(theme.themeId.value, dark = false)
            themeTokens(theme.themeId.value, dark = true)
        }
        // With app/google-services.json present, Firebase sets itself up from it before this runs.
        if (FirebaseApp.getApps(this).isEmpty()) {
            if (FirebaseConfig.hasPlaceholders()) return
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey(FirebaseConfig.API_KEY)
                    .setApplicationId(FirebaseConfig.APPLICATION_ID)
                    .setProjectId(FirebaseConfig.PROJECT_ID)
                    .setGcmSenderId(FirebaseConfig.PROJECT_NUMBER)
                    .setStorageBucket(FirebaseConfig.STORAGE_BUCKET)
                    .build(),
            )
        }
        if (FirebaseConfig.WEB_CLIENT_ID.startsWith("PLACEHOLDER")) return
        val auth = AuthManager(this, scope)
        authManager = auth
        val phone = Devices(this, FirebaseFirestore::getInstance)
        devices = phone
        auth.beforeSignOut = phone::forget
        auth.afterAccountDeleted = phone::release
        scope.launch {
            var first = true
            auth.currentUser.collect { user ->
                val current = sessionState.value
                // Signed out at start: whatever a sign-out cut short left behind goes now.
                if (first && user == null) clearLeftovers()
                first = false
                if (user?.uid == current?.uid) return@collect
                if (current != null) {
                    // Signed out, or another account: this device keeps nothing of the old one but,
                    // after a forced sign-out, its unsent work for its next sign-in.
                    val forced = auth.takeForcedSignOut() && user == null
                    sessionState.value = null
                    current.clearDevice(if (forced) parked else null)
                    if (!forced) dropParked()
                    clearAppData()
                }
                if (user != null) {
                    restoreParked(user.uid)
                    registerDevice(user.uid)
                    sessionState.value = MailState(
                        context = this@InboxApplication,
                        uid = user.uid,
                        email = auth.email,
                        tokens = auth.tokens,
                        firestore = FirebaseFirestore.getInstance(),
                        onPendingChanges = { FlushWorker.schedule(this@InboxApplication) },
                        onNotificationDoneFailed = { threadId ->
                            Notifier.showDoneFailed(this@InboxApplication, threadId, notificationTag(threadId))
                        },
                        focusOn = focus.on,
                        onSavedMailUnfilled = { SavedMailWorker.scheduleFill(this@InboxApplication) },
                    )
                    SavedMailWorker.schedule(this@InboxApplication)
                }
            }
        }
        scope.launch {
            combine(sessionState, pendingOpen) { session, target -> session to target }.collect { (session, target) ->
                if (session != null && target != null) {
                    pendingOpen.value = null
                    val todoId = todoIdOf(target.threadId)
                    when {
                        todoId != null -> session.openTodo(todoId)
                        target.reply -> session.replyFromNotification(target.threadId, target.text)
                        else -> session.open(target.threadId)
                    }
                }
            }
        }
        scope.launch {
            combine(sessionState, pendingUndo) { session, target -> session to target }.collect { (session, target) ->
                if (session != null && target != null) {
                    pendingUndo.value = null
                    session.sending.undoSend(target.first, target.second)
                }
            }
        }
        scope.launch {
            combine(sessionState, pendingCompose) { session, request -> session to request }.collect { (session, request) ->
                if (session != null && request != null) {
                    pendingCompose.value = null
                    val files = withContext(Dispatchers.IO) { request.files.map { describeFile(this@InboxApplication, it) } }
                    session.sending.composeFrom(request.prefill, files) { index -> contentResolver.openInputStream(request.files[index]) }
                }
            }
        }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        sessionState.value?.let { session ->
                            session.noteConnection(ConnectionEvent.NetworkUp)
                            session.wake()
                        }
                    }
                }

                // Another network may already have taken the lost one's place.
                override fun onLost(network: Network) {
                    scope.launch {
                        if (connectivity.activeNetwork == null) sessionState.value?.noteConnection(ConnectionEvent.NetworkDown)
                    }
                }
            },
        )
    }

    /**
     * The signed-in account's mail for work started without the app on screen (a worker, a
     * notification's button), once a cold start has set it up; null when signed out or not set up
     * in time.
     */
    suspend fun awaitSession(): MailState? =
        if (authManager?.currentUser?.value == null) null else withTimeoutOrNull(SESSION_WAIT_MS) { session.filterNotNull().first() }

    /** The signed-in account's Gmail address, or null when signed out. */
    fun signedInEmail(): String? {
        val auth = authManager ?: return null
        return if (auth.currentUser.value == null) null else auth.email.ifEmpty { null }
    }

    // Unsent work kept by a forced sign-out goes back to its account; another account's is dropped.
    private suspend fun restoreParked(uid: String) {
        val parkedUid = withContext(Dispatchers.IO) { parked.parkedUid() } ?: return
        if (parkedUid != uid) {
            dropParked()
            return
        }
        try {
            val back = withContext(Dispatchers.IO) { parked.unpark(uid, DraftStore.directoryOf(this@InboxApplication, uid)) } ?: return
            val database = MailDatabase.open(this, uid)
            try {
                val dao = database.mailDao()
                back.drafts.forEach { dao.putDraft(it) }
                back.queue.forEach { dao.putQueued(it) }
                val cache = MailCache(dao)
                if (back.accountQueue.isNotEmpty()) cache.putAccountQueue(back.accountQueue)
                if (back.retags.isNotEmpty()) cache.putRetags(back.retags)
            } finally {
                database.close()
            }
            withContext(Dispatchers.IO) { parked.delete() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "restoring unsent work failed", error)
        }
    }

    // Unsent work kept for an account, and the key it is sealed with.
    private suspend fun dropParked() = withContext(Dispatchers.IO) {
        parked.parkedUid()?.let { DeviceVault.drafts(it).delete() }
        parked.delete()
    }

    // Every account's files and keys a sign-out cut short may have left, but work kept for the last account.
    private suspend fun clearLeftovers() {
        withContext(Dispatchers.IO) {
            val keep = parked.parkedUid()
            databaseList().filter { it.startsWith("mail-") && it.endsWith(".db") }.forEach(::deleteDatabase)
            File(filesDir, "drafts").deleteRecursively()
            noBackupFilesDir.listFiles()?.filter { it.name.startsWith("pgp-") && it.name.endsWith(".key") }?.forEach { it.delete() }
            DeviceVault.deleteAll(keep = keep?.let(DeviceVault::drafts))
            Addresses.clearAll(this@InboxApplication)
        }
        clearAppData()
        devices?.let { phone ->
            try {
                phone.finishForget(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "giving up the push token failed", error)
            }
        }
    }

    // What isn't kept per account: Firestore's offline cache, queued work, snooze reminders, shown notifications, photo caches, opened files, settings.
    private suspend fun clearAppData() {
        try {
            // A terminated instance can't be used again; getInstance() makes a fresh one for the next sign-in.
            val firestore = FirebaseFirestore.getInstance()
            firestore.terminate().await()
            firestore.clearPersistence().await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Firestore cache clear failed", error)
        }
        WorkManager.getInstance(this).cancelAllWork()
        SnoozeAlarm.clear(this)
        NotificationManagerCompat.from(this).cancelAll()
        val images = SingletonImageLoader.get(this)
        images.memoryCache?.clear()
        withContext(Dispatchers.IO) {
            images.diskCache?.clear()
            val imageDir = images.diskCache?.directory?.toFile()
            // The rest of the cache: opened attachments and the message view's web cache.
            cacheDir.listFiles()?.filter { it != imageDir }?.forEach { it.deleteRecursively() }
            deleteSharedPreferences(NOTIFY_PREFS)
            RecentSearchStore.clear(this@InboxApplication)
        }
    }

    private fun registerDevice(uid: String) {
        val phone = devices ?: return
        scope.launch {
            try {
                phone.register(uid)
                // The phone's snooze wait is kept under its token, known only now.
                SnoozeAlarm.check(this@InboxApplication)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "device registration failed", error)
            }
        }
    }

    /** Save a token FCM just issued for this phone. */
    fun tokenIssued(token: String) {
        scope.launch {
            val phone = devices ?: return@launch
            try {
                val uid = authManager?.currentUser?.value?.uid
                phone.tokenIssued(uid, token)
                if (uid != null) SnoozeAlarm.check(this@InboxApplication)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "device registration failed", error)
            }
        }
    }

    /** Open a thread from a notification, once the account's mail is ready; [reply] starts a reply in it with [text]. */
    fun openFromNotification(threadId: String, reply: Boolean, text: String = "") {
        pendingOpen.value = OpenRequest(threadId, reply, text)
    }

    /** Open a new message another app asked for, once the account's mail is ready. */
    fun composeFromIntent(request: ComposeRequest) {
        pendingCompose.value = request
    }

    /** Take back a send from a notification's Undo, once the account's mail is ready; its draft opens. */
    fun undoSendFromNotification(outboxId: String, localId: String) {
        pendingUndo.value = outboxId to localId
    }
}

// A thread to open from a notification: whether to start a reply in it, and the reply's text.
private data class OpenRequest(val threadId: String, val reply: Boolean, val text: String)
