package cc.hafa.inbox.pgp

import android.content.Context
import android.os.Build
import android.util.Log
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.gmail.GmailClient
import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailThread
import cc.hafa.inbox.core.gmail.mapLimited
import cc.hafa.inbox.core.mail.Labels
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.decodeBase64Url
import cc.hafa.inbox.core.mail.messageView
import cc.hafa.inbox.core.pgp.DecryptedPreview
import cc.hafa.inbox.core.pgp.ENCRYPTING_DOMAIN
import cc.hafa.inbox.core.pgp.KeyPair
import cc.hafa.inbox.core.pgp.LockState
import cc.hafa.inbox.core.pgp.PgpErrorCode
import cc.hafa.inbox.core.pgp.PgpException
import cc.hafa.inbox.core.pgp.autocryptFor
import cc.hafa.inbox.core.pgp.autocryptHeaderValue
import cc.hafa.inbox.core.pgp.autocryptValues
import cc.hafa.inbox.core.pgp.decryptMessage
import cc.hafa.inbox.core.pgp.decryptedMessage
import cc.hafa.inbox.core.pgp.decryptedView
import cc.hafa.inbox.core.pgp.encryptionDecision
import cc.hafa.inbox.core.pgp.fingerprintOf
import cc.hafa.inbox.core.pgp.generateKeyPair
import cc.hafa.inbox.core.pgp.isEncryptedMessage
import cc.hafa.inbox.core.pgp.keyFromAnswer
import cc.hafa.inbox.core.pgp.lockedView
import cc.hafa.inbox.core.pgp.matchesDecrypted
import cc.hafa.inbox.core.pgp.decryptedRawView
import cc.hafa.inbox.core.pgp.seal
import cc.hafa.inbox.core.pgp.sealWithPhrase
import cc.hafa.inbox.core.pgp.senderAddress
import cc.hafa.inbox.core.pgp.splitAddress
import cc.hafa.inbox.core.pgp.unseal
import cc.hafa.inbox.core.pgp.unsealWithPhrase
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.core.search.SearchPlan
import cc.hafa.inbox.data.AccountStore
import cc.hafa.inbox.data.MailDao
import cc.hafa.inbox.data.safeFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Encryption"
private const val SEARCH_PAGE = 50
private const val SEARCH_LIMIT = 200
private const val SEARCH_CONCURRENCY = 4

/** Where encrypted mail stands for the account on this device. */
enum class EncryptionStatus {
    /** the account doc hasn't been read yet */
    LOADING,

    /** no active @hafa.cc address, so nothing to set up */
    NO_ADDRESS,

    /** the address is active but encryption isn't set up */
    OFF,

    /** set up, but this device hasn't unlocked the key */
    LOCKED,

    /** set up and unlocked here */
    UNLOCKED,
}

/** What an outgoing message gets: its Autocrypt header, and the keys to encrypt to when it is encrypted. */
class Outgoing(
    /** the `Autocrypt` header value, or null */
    val autocrypt: String?,
    /** every recipient's key and the sender's own, when the message is encrypted; else null */
    val encryptTo: List<String>?,
    /** the unlocked private key that signs it, when encrypted */
    val signingKey: String?,
    /** the message must be encrypted but this device is locked: hold it until unlocked */
    val waitForUnlock: Boolean = false,
)

/** Encrypted mail is needed but this device hasn't unlocked the key. */
class MailLockedException : Exception("encrypted mail is locked on this device")

/** A message as the thread shows it, and as the plain cache may keep it (no decrypted content). */
class Revealed(
    /** the message as the thread shows it */
    val shown: MessageView,
    /** the message with no decrypted content, for the plain cache */
    val cacheable: MessageView,
)

/**
 * Encrypted mail for one account (docs/pgp-contract.md): setting up and unlocking the key,
 * passkeys and the recovery phrase, decrypting threads, notifications and searches, and what
 * outgoing mail is encrypted to. The unlocked private key lives only in memory and in a file
 * sealed by an Android Keystore key ([DeviceVault.privateKey]).
 */
class Encryption(
    context: Context,
    private val uid: String,
    private val gmailAddress: String,
    private val scope: CoroutineScope,
    private val account: AccountStore,
    private val gmail: GmailClient,
    dao: MailDao,
) {
    /** Recipients' keys. */
    val directory = KeyDirectory(dao, scope)

    /**
     * Whether a message from [from] to [recipients] must be encrypted but this device hasn't
     * unlocked the key, so sending it has to ask for an unlock first. Waits for every lookup.
     */
    suspend fun needsUnlock(from: String, recipients: List<String>): Boolean {
        // Without the account doc it isn't known whether the account has a key; the send waits in flush.
        if (!account.awaitLoaded()) return false
        if (account.settings.value.pgp == null || privateKey() != null) return false
        val keys = runSuspendCatching { directory.keysFor(recipients) }.getOrDefault(emptyMap())
        return encryptionDecision(from, recipients, keys).encrypt
    }

    private val decrypted = DecryptedStore(dao, DeviceVault.mailCache(uid))
    private val keyVault = DeviceVault.privateKey(uid)
    private val keyFile = File(context.noBackupFilesDir, "pgp-${safeFileName(uid)}.key")
    // Attachments opened from mail are written here for the viewing app, decrypted ones included.
    private val openedAttachments = File(context.cacheDir, "attachments")
    private val unlockedKey = MutableStateFlow<String?>(null)
    private val onDevice = MutableStateFlow(keyFile.exists())
    private val keyMutex = Mutex()
    private val previewState = MutableStateFlow<Map<String, DecryptedPreview>>(emptyMap())

    /** Called after this device unlocks the key, so open threads decrypt. */
    var onUnlocked: () -> Unit = {}

    /** Called when this device locks because the key changed elsewhere, after the key is gone. */
    var onLocked: suspend () -> Unit = {}

    /**
     * Whether composer state may be written to the device: once the account doc is read, and not
     * while the account has a key this device hasn't unlocked (there is nothing to seal it with).
     */
    fun keepsComposerState(): Boolean {
        val settings = account.settings.value
        return settings.loaded && (settings.pgp == null || onDevice.value)
    }

    /** Subject and text of decrypted threads, by thread id, for the thread list. */
    val previews: StateFlow<Map<String, DecryptedPreview>> = previewState

    /** Where encrypted mail stands. */
    val status: StateFlow<EncryptionStatus> = combine(account.settings, onDevice) { settings, unlocked ->
        when {
            !settings.loaded -> EncryptionStatus.LOADING
            settings.pgp != null -> if (unlocked) EncryptionStatus.UNLOCKED else EncryptionStatus.LOCKED
            settings.address != null && settings.addressSetup?.status == "active" -> EncryptionStatus.OFF
            else -> EncryptionStatus.NO_ADDRESS
        }
    }.stateIn(scope, SharingStarted.Eagerly, EncryptionStatus.LOADING)

    /** Load the thread list's previews, and lock this device when the account's key changes elsewhere. */
    fun start() {
        scope.launch { previewState.value = decrypted.previews() }
        scope.launch {
            account.settings.collect { settings ->
                if (!settings.loaded || !onDevice.value) return@collect
                val record = settings.pgp
                val held = unlockedKey.value
                // Reset (or turned off) on another device: this device's key no longer opens new mail.
                // A key file this device can't open (its Keystore key is gone) or that isn't the account's locks too.
                if (record == null || (held != null && !sameKey(record.publicKey, held)) || (held == null && privateKey() == null)) {
                    lockDevice()
                }
            }
        }
    }

    private fun sameKey(publicKey: String, privateKey: String): Boolean =
        runCatching { fingerprintOf(publicKey) == fingerprintOf(privateKey) }.getOrDefault(false)

    /** The unlocked private key, armored, or null when this device hasn't unlocked it. */
    suspend fun privateKey(): String? = keyMutex.withLock {
        unlockedKey.value?.let { return@withLock it }
        if (!keyFile.exists()) return@withLock null
        val armored = withContext(Dispatchers.IO) {
            runCatching { String(keyVault.open(keyFile.readBytes()), Charsets.UTF_8) }
                .onFailure { Log.w(TAG, "device key unreadable", it) }
                .getOrNull()
        } ?: return@withLock null
        val record = account.settings.value.pgp
        if (record != null && !sameKey(record.publicKey, armored)) return@withLock null
        unlockedKey.value = armored
        armored
    }

    private suspend fun storeOnDevice(privateKey: String) {
        withContext(Dispatchers.IO) { keyFile.writeBytes(keyVault.seal(privateKey.toByteArray(Charsets.UTF_8))) }
        unlockedKey.value = privateKey
        onDevice.value = true
        onUnlocked()
    }

    /** Forget the unlocked key, the decrypted cache and composer state on this device. */
    private suspend fun lockDevice() {
        forgetKey()
        onLocked()
    }

    private suspend fun forgetKey() {
        keyMutex.withLock {
            unlockedKey.value = null
            onDevice.value = false
            withContext(Dispatchers.IO) {
                keyFile.delete()
                keyVault.delete()
                openedAttachments.deleteRecursively()
            }
        }
        decrypted.clear()
        previewState.value = emptyMap()
    }

    /** Delete this account's device keys without the database (it is being deleted whole); nothing makes them again after. */
    fun forgetDevice() {
        keyVault.close()
        decrypted.close()
        unlockedKey.value = null
        keyFile.delete()
        keyVault.delete()
        DeviceVault.mailCache(uid).delete()
        openedAttachments.deleteRecursively()
    }

    private fun record(): PgpRecord = account.settings.value.pgp ?: throw IllegalStateException("encryption isn't set up")

    /** A new key pair for the account's @hafa.cc address. */
    suspend fun newKeyPair(name: String): KeyPair {
        val address = account.settings.value.address ?: throw IllegalStateException("no address")
        return withContext(Dispatchers.Default) { generateKeyPair(address, name) }
    }

    /** Make a passkey (Credential Manager prompt) and seal [privateKey] with it; [existing] are the passkeys already listed. */
    suspend fun createPasskey(activity: Context, privateKey: String, existing: List<SealedCopy>): SealedCopy {
        val address = account.settings.value.address ?: throw IllegalStateException("no address")
        val created = Passkeys.create(activity, uid, gmailAddress, address, existing.map { it.credentialId })
        val sealed = withContext(Dispatchers.Default) { seal(created.secret, privateKey) }
        return SealedCopy(created.credentialId, created.salt, sealed, Build.MODEL.take(PgpRecord.MAX_NAME), System.currentTimeMillis())
    }

    /** Store a new key pair (setup, or reset): its passkey copies and its recovery phrase copy, then unlock here. */
    suspend fun finishSetup(pair: KeyPair, sealed: List<SealedCopy>, phrase: String) {
        val recovery = withContext(Dispatchers.Default) { sealWithPhrase(phrase, pair.privateKey) }
        forgetKey()
        account.writePgp(PgpRecord(pair.publicKey, sealed, recovery))
        storeOnDevice(pair.privateKey)
    }

    /** Unlock with a passkey (Credential Manager prompt). Throws [PasskeyException] or [cc.hafa.inbox.core.pgp.SealException]. */
    suspend fun unlockWithPasskey(activity: Context) {
        val record = record()
        val asserted = Passkeys.assert(activity, record.sealed.associate { it.credentialId to it.salt })
        val copy = record.sealed.firstOrNull { Passkeys.normalizedId(it.credentialId) == asserted.credentialId }
            ?: throw PasskeyException(PasskeyProblem.NONE_FOUND, "no sealed copy for that passkey")
        val privateKey = withContext(Dispatchers.Default) { unseal(asserted.secret, copy.sealedPrivateKey) }
        if (!sameKey(record.publicKey, privateKey)) throw IllegalStateException("the sealed key isn't the account's key")
        storeOnDevice(privateKey)
    }

    /** Unlock with the recovery phrase; throws [cc.hafa.inbox.core.pgp.SealException] for a wrong phrase. */
    suspend fun unlockWithPhrase(phrase: String) {
        val record = record()
        val privateKey = withContext(Dispatchers.Default) { unsealWithPhrase(phrase, record.recoverySealed) }
        if (!sameKey(record.publicKey, privateKey)) throw IllegalStateException("the sealed key isn't the account's key")
        storeOnDevice(privateKey)
    }

    /** Add a passkey for this device (unlocked only). */
    suspend fun addPasskey(activity: Context) {
        val record = record()
        val privateKey = privateKey() ?: throw IllegalStateException("locked")
        val copy = createPasskey(activity, privateKey, record.sealed)
        account.writePgp(record().let { latest -> latest.copy(sealed = (latest.sealed + copy).takeLast(PgpRecord.MAX_PASSKEYS)) })
    }

    /** Stop a passkey from unlocking mail. */
    suspend fun removePasskey(credentialId: String) {
        val record = record()
        account.writePgp(record.copy(sealed = record.sealed.filterNot { it.credentialId == credentialId }))
    }

    /** Replace the recovery phrase's copy with one sealed under [phrase] (unlocked only). */
    suspend fun replaceRecoveryPhrase(phrase: String) {
        val privateKey = privateKey() ?: throw IllegalStateException("locked")
        val recovery = withContext(Dispatchers.Default) { sealWithPhrase(phrase, privateKey) }
        account.writePgp(record().copy(recoverySealed = recovery))
    }

    /** The account's public key, armored, or null when encryption isn't set up. */
    fun ownPublicKey(): String? = account.settings.value.pgp?.publicKey

    private fun ownAddress(): String? = account.settings.value.address?.lowercase()

    private suspend fun verificationKeys(sender: String?): List<String> {
        if (sender == null) return emptyList()
        val own = account.settings.value.pgp?.publicKey?.takeIf { sender == ownAddress() }
        return listOfNotNull(own ?: runSuspendCatching { directory.lookup(sender) }.getOrNull())
    }

    private fun lockStateOf(error: PgpException): LockState =
        if (error.code == PgpErrorCode.WRONG_KEY) LockState.WRONG_KEY else LockState.MALFORMED

    private suspend fun decryptOne(message: GmailMessage, privateKey: String): MessageView {
        val raw = gmail.getRawMessage(message.id)
        val verification = verificationKeys(senderAddress(message))
        return try {
            val result = withContext(Dispatchers.Default) { decryptMessage(decodeBase64Url(raw.raw), listOf(privateKey), verification) }
            decryptedView(message, result)
        } catch (error: PgpException) {
            if (error.code == PgpErrorCode.NOT_ENCRYPTED) messageView(message) else lockedView(message, lockStateOf(error))
        }
    }

    /**
     * Decrypt a thread's encrypted messages (format `full`): from the cache when kept, else fetched
     * raw and decrypted, then kept. Returns the shown and cacheable form of each encrypted message
     * by id; messages that aren't encrypted are left out. Also takes senders' keys from Autocrypt.
     */
    suspend fun reveal(thread: GmailThread): Map<String, Revealed> {
        val messages = thread.messages.orEmpty()
        val encrypted = messages.filter(::isEncryptedMessage)
        val result = mutableMapOf<String, Revealed>()
        if (encrypted.isNotEmpty()) {
            val stored = decrypted.get(encrypted.map { it.id })
            val privateKey = privateKey()
            for (message in encrypted) {
                val cacheable = lockedView(message, LockState.LOCKED)
                val shown = stored[message.id] ?: when (privateKey) {
                    null -> cacheable
                    else -> decryptOne(message, privateKey).also { view ->
                        if (view.lock?.state == LockState.OPEN) decrypted.put(thread.id, view)
                    }
                }
                result[message.id] = Revealed(shown, if (shown.lock?.state == LockState.OPEN) cacheable else shown)
            }
            val opened = result.values.map { it.shown }.filter { it.lock?.state == LockState.OPEN }
            if (opened.isNotEmpty()) {
                val preview = DecryptedStore.threadPreview(opened.map { DecryptedPreview(it.subject, it.snippet, it.date) })
                previewState.update { it + (thread.id to preview) }
            }
        }
        noteAutocrypt(messages)
        return result
    }

    /** A cached thread's encrypted messages, decrypted from the device's encrypted cache where kept. */
    suspend fun restore(views: List<MessageView>): List<MessageView> {
        val locked = views.filter { it.lock != null }
        if (locked.isEmpty()) return views
        val stored = decrypted.get(locked.map { it.id })
        return views.map { stored[it.id] ?: it }
    }

    private suspend fun noteAutocrypt(messages: List<GmailMessage>) {
        val own = ownAddress()
        for (message in messages) {
            val labels = message.labelIds.orEmpty()
            if (Labels.SPAM in labels || Labels.DRAFT in labels) continue
            val sender = senderAddress(message) ?: continue
            if (sender == own) continue
            val header = autocryptFor(autocryptValues(message), sender) ?: continue
            val key = withContext(Dispatchers.Default) { keyFromAnswer(header.keydata, sender) } ?: continue
            directory.noteAutocrypt(sender, key, message.internalDate?.toLongOrNull() ?: 0L)
        }
    }

    /** A draft's message ready for the composer: decrypted when it is encrypted. Throws when it can't be. */
    suspend fun openDraft(message: GmailMessage): GmailMessage {
        if (!isEncryptedMessage(message)) return message
        val privateKey = privateKey() ?: throw MailLockedException()
        val raw = gmail.getRawMessage(message.id).raw
        // Signed by the account's own key, so its inner To and Cc are trusted.
        val ownKey = listOfNotNull(ownPublicKey())
        val result = withContext(Dispatchers.Default) { decryptMessage(decodeBase64Url(raw), listOf(privateKey), ownKey) }
        return decryptedMessage(message, result)
    }

    /** The subject and start of text of an encrypted message, for its notification; null when it can't be opened here. */
    suspend fun notificationPreview(messageId: String): DecryptedPreview? {
        val privateKey = privateKey() ?: return null
        val raw = gmail.getRawMessage(messageId)
        val view = try {
            withContext(Dispatchers.Default) { decryptedRawView(raw, decodeBase64Url(raw.raw), listOf(privateKey), emptyList()) }
        } catch (_: PgpException) {
            return null
        }
        return DecryptedPreview(view.subject, view.snippet, view.date)
    }

    /**
     * Search inside encrypted mail: run [query] (the search's operators and the encrypted-mail
     * terms) a page at a time, newest first, decrypt each message the cache doesn't hold and match
     * the free-text words; [onMatches] gets each page's matching thread ids. Nothing fetched here
     * is kept. Stops after 200 messages.
     */
    suspend fun searchEncrypted(plan: SearchPlan, query: String, onMatches: suspend (List<String>) -> Unit) {
        val privateKey = privateKey() ?: return
        var pageToken: String? = null
        var examined = 0
        while (examined < SEARCH_LIMIT) {
            val page = gmail.listMessages(query, pageToken, SEARCH_PAGE)
            val stubs = page.messages.orEmpty().take(SEARCH_LIMIT - examined)
            examined += stubs.size
            val stored = decrypted.get(stubs.map { it.id })
            val views = mapLimited(stubs, SEARCH_CONCURRENCY) { stub ->
                stored[stub.id] ?: run {
                    val raw = gmail.getRawMessage(stub.id)
                    withContext(Dispatchers.Default) { decryptedRawView(raw, decodeBase64Url(raw.raw), listOf(privateKey), emptyList()) }
                }
            }
            val matches = stubs.zip(views).mapNotNull { (stub, view) ->
                view.getOrNull()?.takeIf { matchesDecrypted(plan, it) }?.let { stub.threadId }
            }.distinct()
            if (matches.isNotEmpty()) onMatches(matches)
            pageToken = page.nextPageToken ?: break
        }
    }

    /** Threads whose decrypted messages in the device's cache match a search, for searching offline. */
    suspend fun searchCached(plan: SearchPlan): Set<String> =
        decrypted.all().filter { matchesDecrypted(plan, it.view) }.map { it.threadId }.toSet()

    /**
     * How a message from [from] to [recipients] goes out: mail from the account's @hafa.cc address
     * carries its Autocrypt header, and is encrypted when every recipient has a key
     * ([encryptionDecision]). On a device that hasn't unlocked the key it waits for an unlock,
     * unless the user chose [unencrypted]. Looks up keys it doesn't have yet.
     */
    suspend fun outgoing(from: String, recipients: List<String>, unencrypted: Boolean = false): Outgoing {
        val record = account.settings.value.pgp ?: return Outgoing(null, null, null)
        val fromHafa = runCatching { splitAddress(from).domain }.getOrDefault("") == ENCRYPTING_DOMAIN
        if (!fromHafa) return Outgoing(null, null, null)
        val autocrypt = if (addressKey(from) == ownAddress()) runCatching { autocryptHeaderValue(from, record.publicKey) }.getOrNull() else null
        val keys = directory.keysFor(recipients)
        val decision = encryptionDecision(from, recipients, keys)
        if (!decision.encrypt) return Outgoing(autocrypt, null, null)
        val privateKey = privateKey()
            ?: return Outgoing(autocrypt, null, null, waitForUnlock = !unencrypted)
        val recipientKeys = recipients.mapNotNull { keys[addressKey(it)] }.distinct()
        return Outgoing(autocrypt, recipientKeys + record.publicKey, privateKey)
    }
}
