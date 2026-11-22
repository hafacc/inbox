package cc.hafa.inbox.data

import android.util.Log
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.send.SendSuggestions
import cc.hafa.inbox.core.send.VarySetting
import cc.hafa.inbox.core.theme.ThemePalette
import cc.hafa.inbox.pgp.PgpRecord
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

private const val TAG = "AccountStore"

/** `users/{uid}.addressSetup`: how far setting up the @hafa.cc address got (docs/address-contract.md). */
data class AddressSetup(
    /** `pending`, `verifying`, `active` or `releasing` */
    val status: String,
    /** what setup is waiting out (`confirm-by-hand`, `cloudflare`, `not-configured`), or null */
    val errorCode: String?,
    /** when the server last changed it, in milliseconds, or null */
    val updatedAt: Long? = null,
)

/** How remote images are shown: blocked until asked for, or always. */
enum class ImagePolicy(
    /** the value stored in settings */
    val wire: String,
) {
    /** blocked per sender until "Show images" */
    ASK("ask"),

    /** always shown, loaded straight from the sender */
    ALWAYS("always"),
}

/** What `users/{uid}` holds that the app reads: the claimed address and the user's settings. */
data class AccountSettings(
    /** the claimed @hafa.cc address, or null */
    val address: String? = null,
    /** setup progress of [address], or null */
    val addressSetup: AddressSetup? = null,
    /** how remote images are shown (`settings.images`) */
    val imagePolicy: ImagePolicy = ImagePolicy.ASK,
    /** senders whose images always show, lower-cased (`settings.imageSenders`, same as web) */
    val imageSenders: Set<String> = emptySet(),
    /** signature, markdown; null or blank for none */
    val signature: String? = null,
    /** the From address new messages start with, or null for the Gmail address */
    val sendAsDefault: String? = null,
    /** "Vary scheduled send times": on or off, and the largest shift */
    val vary: VarySetting = SendSuggestions.varySetting(null),
    /** the account's OpenPGP key and its sealed copies, or null when encryption isn't set up */
    val pgp: PgpRecord? = null,
    /** the color theme's id (`settings.theme`, same as web) */
    val theme: String = ThemePalette.defaultTheme,
    /** whether todos show among the mail and in a Todos view (`settings.showTodos`, on unless false) */
    val showTodos: Boolean = true,
    /** whether mail landing in a bundle sends no notification (`settings.quietBundles`, off unless true) */
    val quietBundles: Boolean = false,
    /** whether the account doc has been read at least once */
    val loaded: Boolean = false,
)

/** `users/{uid}`: the account's address and settings, followed live and written field by field. */
class AccountStore(private val firestore: FirebaseFirestore, private val uid: String) {
    private val state = MutableStateFlow(AccountSettings())
    // Whether the doc has been read (true) or the listener failed first (false); null while waiting.
    private val firstReadState = MutableStateFlow<Boolean?>(null)

    /** Whether the account doc has been read (true) or the listener failed first (false); null while waiting. */
    val firstRead: StateFlow<Boolean?> = firstReadState

    /** The current settings. */
    val settings: StateFlow<AccountSettings> = state

    private fun document() = firestore.collection("users").document(uid)

    private val listener = FirestoreListener<DocumentSnapshot>(
        "account",
        { document().addSnapshotListener(MetadataChanges.INCLUDE, it) },
        onError = { if (firstReadState.value == null) firstReadState.value = false },
        onSnapshot = ::read,
    )

    /** Whether [settings] are current (the listener answered and hasn't failed or stopped since). */
    val live: Boolean get() = listener.live

    /** Start following the account doc. */
    fun start() {
        if (firstReadState.value == false) firstReadState.value = null
        listener.start()
    }

    private fun read(snapshot: DocumentSnapshot?) {
        // The doc always exists once signed in; a missing one from the offline cache isn't an answer yet.
        if (snapshot == null || (!snapshot.exists() && snapshot.metadata.isFromCache)) return
        val settings = snapshot.get("settings") as? Map<*, *>
        val setup = snapshot.get("addressSetup") as? Map<*, *>
        state.value = AccountSettings(
            address = (snapshot.get("address") as? String)?.takeIf(String::isNotBlank),
            addressSetup = (setup?.get("status") as? String)?.let { status ->
                AddressSetup(
                    status,
                    (setup["error"] as? Map<*, *>)?.get("code") as? String,
                    (setup["updatedAt"] as? Timestamp)?.toDate()?.time,
                )
            },
            imagePolicy = ImagePolicy.entries.firstOrNull { it.wire == settings?.get("images") } ?: ImagePolicy.ASK,
            imageSenders = (settings?.get("imageSenders") as? Map<*, *>).orEmpty()
                .filterValues { it == true }.keys.mapNotNull { (it as? String)?.lowercase() }.toSet(),
            signature = settings?.get("signature") as? String,
            sendAsDefault = (settings?.get("sendAsDefault") as? String)?.takeIf(String::isNotBlank),
            vary = SendSuggestions.varySetting(settings),
            pgp = PgpRecord.parse(snapshot.get("pgp")),
            theme = ThemePalette.themeIdFrom(settings?.get("theme")),
            showTodos = settings?.get("showTodos") != false,
            quietBundles = settings?.get("quietBundles") == true,
            loaded = true,
        )
        firstReadState.value = true
    }

    /**
     * Wait for the account doc's first read; false when the listener failed before it (nothing
     * that depends on the account, like whether to encrypt, may go ahead then).
     */
    suspend fun awaitLoaded(): Boolean = firstReadState.filterNotNull().first()

    /** Follow again after a listener error. */
    fun reattach() = start()

    /** Stop following. */
    fun stop() = listener.stop()

    // Firestore merges nested maps on a merge set, so other settings stay as they are.
    private fun writeSetting(name: String, value: Any?) {
        document().set(mapOf("settings" to mapOf(name to value)), SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "settings write failed", it) }
    }

    /** Save the signature (markdown; blank clears it). */
    fun setSignature(markdown: String) {
        val value = markdown.trimEnd().ifBlank { null }
        state.value = state.value.copy(signature = value)
        writeSetting("signature", value)
    }

    /** Replace `pgp` whole, or remove it with null (resetting encryption); waits for the server. */
    suspend fun writePgp(record: PgpRecord?) {
        document().update("pgp", record?.toFirestore() ?: FieldValue.delete()).await()
        state.value = state.value.copy(pgp = record)
    }

    /** Save the color theme for every device on this account. */
    fun setTheme(id: String) {
        state.value = state.value.copy(theme = id)
        writeSetting("theme", id)
    }

    /** Show todos or not, on every device of this account. */
    fun setShowTodos(on: Boolean) {
        state.value = state.value.copy(showTodos = on)
        writeSetting("showTodos", on)
    }

    /** Turn notifications for mail landing in a bundle off or back on, for every device of this account. */
    fun setQuietBundles(on: Boolean) {
        state.value = state.value.copy(quietBundles = on)
        writeSetting("quietBundles", on)
    }

    /** Save how remote images are shown. */
    fun setImagePolicy(policy: ImagePolicy) {
        state.value = state.value.copy(imagePolicy = policy)
        writeSetting("images", policy.wire)
    }

    /** Always show images from [email], or stop. */
    fun setImageSender(email: String, always: Boolean) {
        val key = addressKey(email)
        if (key.isEmpty()) return
        val current = state.value.imageSenders
        state.value = state.value.copy(imageSenders = if (always) current + key else current - key)
        val write = if (always) {
            document().set(mapOf("settings" to mapOf("imageSenders" to mapOf(key to true))), SetOptions.merge())
        } else {
            document().update(FieldPath.of("settings", "imageSenders", key), FieldValue.delete())
        }
        write.addOnFailureListener { Log.w(TAG, "image senders write failed", it) }
    }

    /** Save the default From address. */
    fun setSendAsDefault(email: String) {
        state.value = state.value.copy(sendAsDefault = email)
        writeSetting("sendAsDefault", email)
    }

    /** Turn "Vary scheduled send times" on or off. */
    fun setVarySendTimes(on: Boolean) {
        state.value = state.value.copy(vary = state.value.vary.copy(enabled = on))
        writeSetting("varySendTimes", on)
    }

    /** Set the largest shift "Vary scheduled send times" applies, in whole minutes (1–30). */
    fun setVarySendMaxMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(SendSuggestions.MIN_MAX_SHIFT_MINUTES, SendSuggestions.MAX_MAX_SHIFT_MINUTES)
        state.value = state.value.copy(vary = state.value.vary.copy(maxMinutes = clamped))
        writeSetting("varySendMaxMinutes", clamped.toLong())
    }
}
