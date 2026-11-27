package cc.hafa.inbox.mail

import android.content.Context
import android.util.Log
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cc.hafa.inbox.core.address.ADDRESS_DOMAIN
import cc.hafa.inbox.core.compose.ComposeKind
import cc.hafa.inbox.core.compose.ComposePrefill
import cc.hafa.inbox.core.compose.DraftContent
import cc.hafa.inbox.core.compose.DraftFile
import cc.hafa.inbox.core.compose.InvalidAddressError
import cc.hafa.inbox.core.compose.PendingSend
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.compose.addressProblem
import cc.hafa.inbox.core.compose.buildProblem
import cc.hafa.inbox.core.compose.buildMime
import cc.hafa.inbox.core.compose.composeInput
import cc.hafa.inbox.core.compose.draftFromMessage
import cc.hafa.inbox.core.compose.draftFromView
import cc.hafa.inbox.core.compose.draftInReplyTo
import cc.hafa.inbox.core.compose.forwardDraft
import cc.hafa.inbox.core.compose.isEmpty
import cc.hafa.inbox.core.compose.isPlainAddress
import cc.hafa.inbox.core.compose.messageIds
import cc.hafa.inbox.core.compose.quotedContentIds
import cc.hafa.inbox.core.compose.randomBoundary
import cc.hafa.inbox.core.compose.replyDraft
import cc.hafa.inbox.core.compose.senderList
import cc.hafa.inbox.core.contacts.PeopleClient
import cc.hafa.inbox.core.gmail.GmailClient
import cc.hafa.inbox.core.gmail.GmailDraft
import cc.hafa.inbox.core.gmail.GmailException
import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailSendAs
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.gmail.isNotFound
import cc.hafa.inbox.core.gmail.isRetryable
import cc.hafa.inbox.core.mail.LocalDraftState
import cc.hafa.inbox.core.mail.LocalThreadDraft
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.OfflineDraftsInput
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.ThreadDetail
import cc.hafa.inbox.core.mail.decodeBase64Url
import cc.hafa.inbox.core.mail.draftSnippet
import cc.hafa.inbox.core.mail.headerValue
import cc.hafa.inbox.core.mail.offlineDraftOf
import cc.hafa.inbox.core.mail.offlineEditable
import cc.hafa.inbox.core.mail.parseAddressList
import cc.hafa.inbox.core.mail.savedDraftParts
import cc.hafa.inbox.core.mail.threadDetail
import cc.hafa.inbox.core.pgp.EncryptedSendKind
import cc.hafa.inbox.core.pgp.encryptMime
import cc.hafa.inbox.core.pgp.planEncryptedSends
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.core.send.DeviceClaim
import cc.hafa.inbox.core.send.DirectSendDecision
import cc.hafa.inbox.core.send.DirectSendDeps
import cc.hafa.inbox.core.send.DirectSendDoc
import cc.hafa.inbox.core.send.DirectSendInput
import cc.hafa.inbox.core.send.DirectSendResult
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.send.QueuedSend
import cc.hafa.inbox.core.send.SendSuggestions
import cc.hafa.inbox.core.send.SendingDraft
import cc.hafa.inbox.core.send.baseOutboxId
import cc.hafa.inbox.core.send.blockedText
import cc.hafa.inbox.core.send.copyNumber
import cc.hafa.inbox.core.send.copyOutboxId
import cc.hafa.inbox.core.send.directSendDecision
import cc.hafa.inbox.core.send.failureText
import cc.hafa.inbox.core.send.heldSendIds
import cc.hafa.inbox.core.send.inFlight
import cc.hafa.inbox.core.send.isCopyId
import cc.hafa.inbox.core.send.isGmailRoute
import cc.hafa.inbox.core.send.mayHaveGone
import cc.hafa.inbox.core.send.nextCopyNumbers
import cc.hafa.inbox.core.send.readyToDelete
import cc.hafa.inbox.core.send.retryingText
import cc.hafa.inbox.core.send.sendDirectly
import cc.hafa.inbox.data.AccountStore
import cc.hafa.inbox.data.ContactsStore
import cc.hafa.inbox.data.DraftStore
import cc.hafa.inbox.data.MailCache
import cc.hafa.inbox.data.MailDao
import cc.hafa.inbox.data.OutboxStore
import cc.hafa.inbox.data.PhotoCache
import cc.hafa.inbox.data.SnoozeStatsStore
import cc.hafa.inbox.data.StoredDraft
import cc.hafa.inbox.pgp.Encryption
import cc.hafa.inbox.pgp.MailLockedException
import cc.hafa.inbox.pgp.Outgoing
import com.google.firebase.firestore.FirebaseFirestore
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

private const val TAG = "Sending"
private const val SEND_AS_KEY = "sendAs"
private const val SEND_AS_MAX_AGE_MS = 24 * 60 * 60 * 1000L
private const val NOTICE_MS = 5_000L
private const val MAX_DRAFT_PAGES = 5
// Said when a draft can't be opened offline.
private const val OPENS_ONLINE = "This draft opens once you're back online."

/** What the bar at the bottom says about sending. */
sealed interface SendNotice {
    /** Sending after the undo delay; undo until [until]. */
    data class Sending(
        /** id of its outbox doc */
        val outboxId: String,
        /** id of its draft on this device */
        val localId: String,
        /** when the undo window closes (epoch milliseconds) */
        val until: Long,
    ) : SendNotice

    /** Scheduled for [sendAt]; undo cancels it. */
    data class Scheduled(
        /** id of its outbox doc */
        val outboxId: String,
        /** id of its draft on this device */
        val localId: String,
        /** when it sends (epoch milliseconds) */
        val sendAt: Long,
    ) : SendNotice

    /** Gone. */
    data object Sent : SendNotice

    /** Didn't send; the Outbox view shows why. */
    data class Failed(
        /** the line shown */
        val text: String,
    ) : SendNotice

    /** A closed composer's draft was kept; tapping the bar opens it again. */
    data class Saved(
        /** id of its draft on this device */
        val localId: String,
    ) : SendNotice

    /** A draft was discarded; undo brings the composer back until the bar goes. */
    data class Discarded(
        /** id of its draft on this device */
        val localId: String,
    ) : SendNotice

    /** Anything else worth a line. */
    data class Info(
        /** the line shown */
        val text: String,
    ) : SendNotice
}

/** A message not sent yet (scheduled, going, retrying, blocked or failed) with what it is, for the Outbox view. */
data class OutboxItem(
    /** the outbox doc */
    val doc: OutboxDoc,
    /** the message's subject, when its draft is known */
    val subject: String?,
    /** the message's recipients */
    val to: List<Person>,
    /** one Bcc recipient's copy of an encrypted send; [to] is that recipient */
    val bccCopy: Boolean = false,
    /** a send held on this device with no outbox doc yet; [doc] stands in for the one it will write */
    val held: Boolean = false,
    /** the held send's draft on this device */
    val localId: String? = null,
)

// The Outbox's groups, in order.
private const val OUTBOX_FAILED = 0
private const val OUTBOX_BLOCKED = 1
private const val OUTBOX_UNDER_WAY = 2
private const val OUTBOX_SCHEDULED = 3

/** Whether a failed Gmail call waits to be tried again: a usage limit, a Google error, or no Gmail answer at all. */
fun retryLater(error: Throwable): Boolean = error !is GmailException || isRetryable(error)

/** Whether every doc of the send [sendId] (its main doc and Bcc copies) in [docs] has gone. */
fun sendFinished(docs: List<OutboxDoc>, sendId: String): Boolean {
    val group = docs.filter { baseOutboxId(it.id) == sendId }
    return group.isNotEmpty() && group.all { it.status == OutboxStatus.SENT }
}

/**
 * Whether a send built from a draft may still write its outbox docs, given the draft as it is
 * now: not undone, discarded or sent again meanwhile. A first send needs the same [pending] send
 * still waiting on the draft; a scheduled send's edit ([rewrite]) needs the draft still tied to
 * that doc, and the doc still [waiting].
 */
fun sendStands(current: StoredDraft?, pending: PendingSend, rewrite: Boolean, waiting: Boolean): Boolean =
    when {
        current == null || current.deleted -> false
        rewrite -> current.pendingSend == null && current.content.outboxId == pending.outboxId && waiting
        else -> current.pendingSend == pending
    }

/**
 * Compose and send: the open composer, drafts (on the device, then Gmail), the outbox, the From
 * addresses, signature and send settings, contacts for autocomplete, and send-time learning.
 * Main-thread only; owned by [MailState].
 */
class Sending(
    private val appContext: Context,
    uid: String,
    private val scope: CoroutineScope,
    private val gmail: GmailClient,
    tokens: TokenSource,
    firestore: FirebaseFirestore,
    /** The account's address and settings. */
    val account: AccountStore,
    /** Encrypted mail: what outgoing mail is encrypted to, and opening encrypted drafts. */
    val encryption: Encryption,
    private val cache: MailCache,
    dao: MailDao,
    private val accountEmail: String,
    private val onPendingChanges: () -> Unit,
    private val attachmentBytes: suspend (messageId: String, partId: String, attachmentId: String) -> ByteArray,
) {
    /** Drafts on the device. */
    val drafts = DraftStore(appContext, uid, dao)

    /** Whether the app is online; offline, a send waits on the device even when Gmail holds its draft. */
    var online: () -> Boolean = { true }

    /** What the saved copy and this device know of drafts, to open one offline. */
    var offlineDrafts: suspend () -> OfflineDraftsInput? = { null }

    /** Undo and scheduled sends. */
    val outbox = OutboxStore(firestore, uid)

    /** Contacts for autocomplete. */
    val contacts = ContactsStore(dao, cache, PeopleClient(tokens)) { url -> PhotoCache.forget(appContext, url) }

    /** Learned send times, kept apart from snooze's. */
    val sendStats = SnoozeStatsStore(firestore, uid, scope, docName = SendSuggestions.STATS_DOC)

    private val sendAs = MutableStateFlow<List<GmailSendAs>>(emptyList())

    /** Gmail's send-as addresses, confirmed or not, as last fetched. */
    val sendAsList: StateFlow<List<GmailSendAs>> = sendAs

    /** Addresses the user can send from: the @hafa.cc address once it is active, then Gmail's verified send-as list. */
    val senders: StateFlow<List<Person>> = combine(sendAs, account.settings) { list, settings ->
        senderList(list, accountEmail, settings.address?.takeIf { settings.addressSetup?.status == "active" })
    }.stateIn(scope, SharingStarted.Eagerly, listOf(Person("", accountEmail)))

    /** Fetch Gmail's send-as addresses again now. */
    suspend fun reloadSendAs() {
        try {
            val list = gmail.listSendAs()
            sendAs.value = list
            sendAsFetchedAt = System.currentTimeMillis()
            cache.putMeta(SEND_AS_KEY, ListSerializer(GmailSendAs.serializer()), list)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "send-as read failed", error)
        }
    }

    private val composerState = MutableStateFlow<ComposeSession?>(null)

    /** The open composer, or null. */
    val composer: StateFlow<ComposeSession?> = composerState

    private val parkedState = MutableStateFlow(false)

    /** Whether the open composer is parked: shrunk into the strip at the bottom of the screen, with what is under it usable. */
    val parked: StateFlow<Boolean> = parkedState

    private val noticeState = MutableStateFlow<SendNotice?>(null)

    /** The sending bar's message, or null. */
    val notice: StateFlow<SendNotice?> = noticeState

    private val localByOutbox = MutableStateFlow<Map<String, DraftContent>>(emptyMap())
    // Drafts on this device with a send waiting on them.
    private val queuedSends = MutableStateFlow<List<StoredDraft>>(emptyList())
    private val remoteHeads = MutableStateFlow<Map<String, Pair<String, List<Person>>>>(emptyMap())
    private val headsRequested = mutableSetOf<String>()
    private val failuresShown = mutableSetOf<String>()
    // Bcc copy docs written for each send, by send id, so undo finds them before the outbox listener does.
    private val copiesWritten = mutableMapOf<String, MutableMap<String, String>>()

    // Sends now this device wrote, by outbox id, to the sendAt it wrote, until undone or settled:
    // the end of their undo window may send them from here.
    private val directSends = mutableMapOf<String, Long>()
    private val retriesShown = mutableSetOf<String>()
    // Discarded composers still in their undo window, by draft id on this device.
    private val discarding = mutableMapOf<String, Pair<ComposeSession, Job>>()
    // The flush running, which a flush asked for meanwhile shares.
    private var flushRun: Deferred<Boolean>? = null
    // A flush was asked for while one ran, after it had read the drafts: it goes round again.
    private var flushAgain = false
    // Flushes once the account and the outbox have been read, when a flush came before that.
    private var flushOnRead: Job? = null
    // Drafts sent with undo (or sent by the app itself) whose send hasn't gone yet, by draft id on this device.
    private val sendingLocal = mutableSetOf<String>()
    // Whether sendingLocal has taken in the sends still waiting from before the app last stopped.
    private var sendingSeeded = false
    // Sends that went, kept showing until the open thread has Gmail's copy, with that thread's history id when they went.
    private val sentLocal = mutableMapOf<String, Pair<LocalDraft, String?>>()
    private var storedLocal = emptyList<StoredDraft>()
    private var openDetail: ThreadDetail? = null
    private val localState = MutableStateFlow<List<LocalDraft>>(emptyList())

    /** Drafts on this device as their threads show them: being written, discarded or being sent. */
    val localDrafts: StateFlow<List<LocalDraft>> = localState

    /**
     * Everything not sent yet: failed first, then blocked, then what's going out or retrying (sends
     * held on this device among them), then scheduled sends soonest first.
     */
    val outboxItems: StateFlow<List<OutboxItem>> =
        combine(outbox.docs, localByOutbox, remoteHeads, queuedSends) { docs, local, remote, queued ->
            val heldIds = heldSendIds(
                queued.mapNotNull { draft -> draft.pendingSend?.let { QueuedSend(draft.content.localId, it.outboxId, docWritten = false) } },
                docs.map { it.id },
            ).toSet()
            val held = queued.filter { it.content.localId in heldIds }.mapNotNull { draft ->
                val pending = draft.pendingSend ?: return@mapNotNull null
                val content = draft.content
                val item = OutboxItem(
                    OutboxDoc(pending.outboxId, "", content.from.email, OutboxStatus.WAITING, pending.sendAt, pending.chosenAt, content.threadId),
                    content.subject,
                    (content.to + content.cc).ifEmpty { content.bcc },
                    held = true,
                    localId = content.localId,
                )
                item to if (pending.now) OUTBOX_UNDER_WAY else OUTBOX_SCHEDULED
            }
            val listed = docs.filter { it.status != OutboxStatus.SENT }.map { doc ->
                val head = remote[doc.draftId]
                val copy = isCopyId(doc.id)
                val draft = local[baseOutboxId(doc.id)]
                val item = OutboxItem(
                    doc,
                    draft?.subject ?: head?.first,
                    (if (copy) null else draft?.to) ?: head?.second.orEmpty(),
                    bccCopy = copy,
                )
                item to when {
                    doc.status == OutboxStatus.FAILED -> OUTBOX_FAILED
                    doc.status == OutboxStatus.BLOCKED -> OUTBOX_BLOCKED
                    doc.inFlight -> OUTBOX_UNDER_WAY
                    else -> OUTBOX_SCHEDULED
                }
            }
            (listed + held).sortedWith(compareBy<Pair<OutboxItem, Int>> { it.second }.thenBy { it.first.doc.sendAt }).map { it.first }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Drafts this device is sending: queued with a send, or held by an outbox doc not yet sent
     * whose draft is kept here; Drafts leaves them out before Gmail lists them (see [outboxDrafts]).
     */
    val sendingDrafts: StateFlow<List<SendingDraft>> =
        combine(outbox.docs, localByOutbox, queuedSends) { docs, local, queued ->
            val pending = docs.filter { it.status != OutboxStatus.SENT }.map { it.id }.toSet()
            val queuedIds = queued.map { it.content.localId }.toSet()
            val held = local.filterKeys { it in pending }.values.filter { it.localId !in queuedIds }
            (queued.map { it.content } + held).map { SendingDraft(it.draftId, it.messageId, it.threadId ?: it.draftThreadId) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Whether the draft an outbox doc sends is going out or being retried, so it mustn't change.
    private fun isLocked(outboxId: String?): Boolean =
        outboxId != null && outbox.docs.value.firstOrNull { it.id == outboxId }?.inFlight == true

    /** Start following settings and the outbox, and read the device's copies. */
    fun start() {
        account.start()
        outbox.start()
        sendStats.start()
        scope.launch {
            cache.meta(SEND_AS_KEY, ListSerializer(GmailSendAs.serializer()))?.let { sendAs.value = it }
            runSuspendCatching { contacts.load() }.onFailure { Log.w(TAG, "contacts read failed", it) }
            refreshLocal()
            refresh()
        }
        scope.launch { outbox.docs.collect(::onOutbox) }
        // Composer state kept in memory while locked is written down once it may be.
        scope.launch {
            encryption.status.collect {
                if (encryption.keepsComposerState()) runSuspendCatching { drafts.persistMemory() }.onFailure { Log.w(TAG, "draft write failed", it) }
            }
        }
        scope.launch {
            noticeState.collectLatest { notice ->
                val wait = when (notice) {
                    null -> return@collectLatest
                    is SendNotice.Sending -> notice.until - System.currentTimeMillis()
                    is SendNotice.Failed -> 2 * NOTICE_MS
                    else -> NOTICE_MS
                }
                delay(wait.coerceAtLeast(0))
                if (noticeState.value === notice) noticeState.value = null
            }
        }
    }

    private fun stop() {
        account.stop()
        outbox.stop()
        sendStats.stop()
    }

    /** Stop for good (sign-out): nothing written afterwards may make the drafts' key again. */
    fun close() {
        stop()
        drafts.close()
    }

    // When the send-as list was last fetched in this process; they change rarely, and only in Gmail.
    private var sendAsFetchedAt = 0L

    /** Back online or in front: catch up on send-as addresses and contacts. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Nothing may change on a sent doc once its two minutes pass, so look again on each wake.
        outbox.docs.value.filter { readyToDelete(it, now) }.forEach { outbox.delete(it.id) }
        account.reattach()
        outbox.reattach()
        sendStats.reattach()
        if (force || now - sendAsFetchedAt >= SEND_AS_MAX_AGE_MS) scope.launch { reloadSendAs() }
        scope.launch {
            try {
                contacts.sync(force)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "contacts sync failed", error)
            }
        }
    }

    private suspend fun refreshLocal() {
        val all = runSuspendCatching { drafts.all() }.getOrDefault(emptyList())
        localByOutbox.value = all.mapNotNull { stored ->
            val id = stored.content.outboxId ?: stored.pendingSend?.outboxId
            id?.let { it to stored.content }
        }.toMap()
        queuedSends.value = all.filter { it.pendingSend != null && !it.deleted }
        storedLocal = all
        if (!sendingSeeded) {
            sendingSeeded = true
            sendingLocal += all.filter { it.pendingSend?.now == true }.map { it.content.localId }
        }
        publishLocal()
    }

    private fun localDraft(content: DraftContent, state: LocalDraftState, updatedAt: Long) = LocalDraft(
        LocalThreadDraft(content.localId, content.messageId, state, updatedAt),
        content.threadId,
        draftSnippet(content.markdown),
        draftThreadId = content.draftThreadId,
        to = content.to,
        bcc = content.bcc,
        subject = content.subject,
    )

    // A discard waiting out its undo window already takes the draft out of its thread.
    private fun publishLocal() {
        val stored = storedLocal.map { draft ->
            val localId = draft.content.localId
            val state = when {
                draft.deleted || localId in discarding -> LocalDraftState.DELETED
                localId in sendingLocal -> LocalDraftState.SENDING
                else -> LocalDraftState.DRAFT
            }
            localDraft(draft.content, state, draft.updatedAt)
        }
        val known = stored.map { it.draft.localId }.toSet()
        val waiting = discarding.filterKeys { it !in known }.map { (_, entry) ->
            localDraft(entry.first.content(), LocalDraftState.DELETED, 0)
        }
        localState.value = stored + waiting + sentLocal.filterKeys { it !in known }.values.map { it.first }
    }

    /**
     * The open thread changed ([detail], null when none is open): sends that went stop showing
     * once it has Gmail's copy, and at once when their thread isn't open.
     */
    fun settleSent(detail: ThreadDetail?) {
        openDetail = detail
        val before = sentLocal.size
        sentLocal.entries.removeAll { (_, entry) ->
            val (local, historyId) = entry
            val messageId = local.draft.messageId
            val waiting = detail != null && local.threadId in detail.threadIds && detail.messages.none { it.id == messageId } &&
                (detail.historyId == historyId || detail.drafts.any { it.id == messageId })
            !waiting
        }
        if (sentLocal.size != before) publishLocal()
    }

    private fun onOutbox(docs: List<OutboxDoc>) {
        val now = System.currentTimeMillis()
        val current = noticeState.value
        val tracked = when (current) {
            is SendNotice.Sending -> current.outboxId
            is SendNotice.Scheduled -> current.outboxId
            else -> null
        }
        for (doc in docs) {
            when (doc.status) {
                OutboxStatus.SENT -> {
                    if (doc.id == tracked) noticeState.value = SendNotice.Sent
                    val sendId = baseOutboxId(doc.id)
                    // An encrypted send's draft stays until its Bcc copies have gone too.
                    val local = localByOutbox.value[sendId]?.takeIf { sendFinished(docs, sendId) }
                    if (local != null) {
                        if (sendingLocal.remove(local.localId)) {
                            val stored = storedLocal.firstOrNull { it.content.localId == local.localId }?.content ?: local
                            sentLocal[local.localId] =
                                localDraft(stored, LocalDraftState.SENDING, System.currentTimeMillis()) to openDetail?.historyId
                            settleSent(openDetail)
                        }
                        scope.launch {
                            drafts.remove(local.localId)
                            refreshLocal()
                        }
                    }
                    if (readyToDelete(doc, now)) outbox.delete(doc.id)
                }
                OutboxStatus.FAILED -> {
                    stopSending(doc.id)
                    if (failuresShown.add(doc.id)) noticeState.value = SendNotice.Failed(failureText(doc.errorCode, doc.errorMessage))
                }
                OutboxStatus.RETRYING -> if (doc.id == tracked && retriesShown.add(doc.id)) {
                    noticeState.value = SendNotice.Info(retryingText(doc.nextAttemptAt))
                }
                OutboxStatus.BLOCKED -> {
                    stopSending(doc.id)
                    if (doc.id == tracked && retriesShown.add(doc.id)) {
                        noticeState.value = SendNotice.Info("Not sent yet. ${blockedText(doc.blockedOn)}.")
                    }
                }
                else -> Unit
            }
            if (doc.id !in localByOutbox.value && doc.draftId !in remoteHeads.value && headsRequested.add(doc.draftId)) {
                scope.launch { fetchHead(doc.draftId) }
            }
        }
    }

    // A send that didn't go shows as its draft again.
    private fun stopSending(outboxId: String) {
        val localId = localByOutbox.value[baseOutboxId(outboxId)]?.localId ?: return
        if (sendingLocal.remove(localId)) publishLocal()
    }

    private suspend fun fetchHead(draftId: String) {
        try {
            val headers = gmail.getDraft(draftId, full = false).message?.payload?.headers
            // A Bcc copy has no To outside: its one recipient is in Bcc.
            val people = parseAddressList(headerValue(headers, "To")).ifEmpty { parseAddressList(headerValue(headers, "Bcc")) }
            val head = (headerValue(headers, "Subject") ?: "") to people
            remoteHeads.value = remoteHeads.value + (draftId to head)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            headsRequested -= draftId
        }
    }

    /** The From address new messages start with: the setting, else Gmail's default. */
    fun defaultFrom(): Person {
        val list = senders.value
        val wanted = account.settings.value.sendAsDefault
        return list.firstOrNull { wanted != null && addressKey(it.email) == addressKey(wanted) }
            ?: sendAs.value.firstOrNull { it.isDefault && it.usable }?.let { default -> list.firstOrNull { it.email == default.sendAsEmail } }
            ?: list.first()
    }

    private fun signature(): String? = account.settings.value.signature?.takeIf(String::isNotBlank)

    private fun newLocalId() = "d" + UUID.randomUUID().toString().replace("-", "")

    private fun newFileId() = "f" + UUID.randomUUID().toString().replace("-", "").take(16)

    /** Open a composer for a new message. */
    fun composeNew(to: List<Person> = emptyList()) {
        show(
            ComposeSession(
                DraftContent(localId = newLocalId(), from = defaultFrom(), to = to, signature = signature()),
                ComposeKind.NEW,
            ),
        )
    }

    /** Open a composer for a new message another app asked for: [prefill]'s recipients, subject and body, then [files] as they copy in. */
    fun composeFrom(prefill: ComposePrefill, files: List<Pair<String, String>> = emptyList(), open: (Int) -> InputStream?) {
        val session = ComposeSession(
            DraftContent(
                localId = newLocalId(),
                from = defaultFrom(),
                to = prefill.to,
                cc = prefill.cc,
                bcc = prefill.bcc,
                subject = prefill.subject,
                markdown = prefill.body,
                signature = signature(),
            ),
            ComposeKind.NEW,
        )
        // A message already being written is kept as a draft, as closing it would.
        show(session)
        files.forEachIndexed { index, (name, type) ->
            scope.launch {
                addFile(session, name, type, null) { open(index) }
            }
        }
    }

    /** Open a composer replying to (or forwarding) [message] of [threadId]; a reply's body starts as [markdown], the caret after it. */
    fun composeTo(kind: ComposeKind, threadId: String, message: MessageView, markdown: String = "") {
        show(startSession(kind, threadId, message, markdown))
    }

    /**
     * Answer [message] of [threadId] in the composer (a reply, reply all or forward, by [kind]); a
     * reply or forward this device already started in the row's [rowThreads] opens again as it was.
     */
    fun answer(kind: ComposeKind, threadId: String, message: MessageView, rowThreads: Set<String> = setOf(threadId)) {
        // A reply to this thread parked in the strip opens again, as on the web, before its draft is saved.
        val current = composerState.value
        if (parkedState.value && current != null && current.kind != ComposeKind.NEW && current.threadId.inAny(rowThreads)) {
            unpark()
            return
        }
        scope.launch {
            val kept = keptAnswer(rowThreads)
            if (kept != null) {
                keepFromDiscard(kept.content.localId)
                show(ComposeSession(kept.content, ComposeKind.REPLY))
            } else {
                show(startSession(kind, threadId, message, ""))
            }
        }
    }

    /**
     * Send [markdown] as a reply to [message] of [threadId] from a notification, once the quote's
     * images are in, with nothing opened, returning once the send is saved on the device to go out
     * (with the undo delay). Returns its outbox and local ids, for undo; null when it can't go as is
     * (no recipient, an address problem, a locked key).
     */
    suspend fun replyFromNotification(threadId: String, message: MessageView, markdown: String): Pair<String, String>? {
        val fetches = mutableListOf<Job>()
        val session = startSession(ComposeKind.REPLY, threadId, message, markdown, fetches)
        fetches.joinAll()
        val recipients = session.to + session.cc + session.bcc
        if (recipients.isEmpty() || addressProblem(listOf(session.from) + recipients) != null || needsUnlock(session)) return null
        val outboxId = OutboxStore.newId()
        val sendAt = System.currentTimeMillis() + SendSuggestions.UNDO_SEND_DELAY_MS
        val content = session.content().copy(outboxId = outboxId)
        noticeState.value = SendNotice.Sending(outboxId, content.localId, sendAt)
        sendingLocal += content.localId
        saveSend(content, PendingSend(outboxId, sendAt, null, now = true))
        return outboxId to content.localId
    }

    // A composer for [kind] on [message], its files' fetches added to [fetches].
    private fun startSession(
        kind: ComposeKind,
        threadId: String,
        message: MessageView,
        markdown: String,
        fetches: MutableList<Job> = mutableListOf(),
    ): ComposeSession {
        val localId = newLocalId()
        val content = when (kind) {
            ComposeKind.FORWARD -> forwardDraft(localId, threadId, message, defaultFrom(), signature(), ZoneId.systemDefault())
            ComposeKind.NEW -> DraftContent(localId = localId, from = defaultFrom(), signature = signature())
            else -> replyDraft(localId, threadId, message, senders.value, defaultFrom(), kind == ComposeKind.REPLY_ALL, signature())
        }
        val reply = kind == ComposeKind.REPLY || kind == ComposeKind.REPLY_ALL
        val session = ComposeSession(if (reply && markdown.isNotEmpty()) content.copy(markdown = markdown) else content, kind)
        if (reply && markdown.isNotEmpty()) {
            session.body.state.selection = TextRange(session.body.state.annotatedString.text.length)
        }
        if (reply) fetches += attachQuotedImages(session, message)
        if (kind == ComposeKind.FORWARD) {
            for (attachment in message.body.attachments) {
                val file = DraftFile(newFileId(), attachment.filename, attachment.mimeType.ifEmpty { "application/octet-stream" }, attachment.size)
                fetches += fetchFile(session, file) {
                    attachment.data?.let { decoded(it) } ?: attachmentBytes(message.id, attachment.partId, attachment.attachmentId ?: error("no data"))
                }
            }
        }
        return session
    }

    // The quote shows the original's inline images by Content-ID; attach them again under the same ids.
    private fun attachQuotedImages(session: ComposeSession, message: MessageView): List<Job> =
        quotedContentIds(message.body.html).mapNotNull { contentId ->
            val part = message.body.inline.firstOrNull { it.contentId == contentId } ?: return@mapNotNull null
            val file = DraftFile(newFileId(), "image.${part.mimeType.substringAfter('/', "img")}", part.mimeType, 0, contentId)
            fetchFile(session, file) {
                part.data?.let { decoded(it) } ?: attachmentBytes(message.id, part.partId, part.attachmentId ?: error("no data"))
            }
        }

    private suspend fun decoded(base64Url: String): ByteArray = withContext(Dispatchers.Default) { decodeBase64Url(base64Url) }

    private fun fetchFile(session: ComposeSession, file: DraftFile, bytes: suspend () -> ByteArray): Job {
        session.fetching++
        return scope.launch {
            try {
                val data = bytes()
                drafts.writeFile(session.localId, file.id, data)
                session.fetched += file.id
                session.files += file.copy(size = data.size.toLong())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                noticeState.value = SendNotice.Info("Couldn't get ${file.filename}.")
            } finally {
                session.fetching--
            }
        }
    }

    // A reply or forward in one of [threadIds] (a row's) already started on this device and not on its way, the newest.
    private suspend fun keptAnswer(threadIds: Set<String>): StoredDraft? =
        runSuspendCatching { drafts.all() }.getOrDefault(emptyList())
            .lastOrNull { it.content.threadId.inAny(threadIds) && !it.deleted && it.pendingSend == null && it.content.outboxId == null }

    // Opened again while its discard could still be undone: it stays, back in its thread, and the undo goes.
    private fun keepFromDiscard(localId: String) {
        discarding.remove(localId)?.second?.cancel() ?: return
        publishLocal()
        if ((noticeState.value as? SendNotice.Discarded)?.localId == localId) noticeState.value = null
    }

    // Reopens a draft kept on this device.
    private suspend fun openLocal(localId: String): Boolean {
        keepFromDiscard(localId)
        val stored = drafts.get(localId)?.takeIf { !it.deleted } ?: return false
        val session = ComposeSession(stored.content, if (stored.content.threadId == null) ComposeKind.NEW else ComposeKind.REPLY)
        if (session.outboxId == null) session.outboxId = stored.content.draftId?.let(::outboxFor)?.id
        show(session)
        return true
    }

    private fun outboxFor(draftId: String): OutboxDoc? =
        outbox.docs.value.firstOrNull { it.draftId == draftId && it.status != OutboxStatus.SENT }

    // The message a reopened reply answers, from the cached thread or else Gmail's.
    private suspend fun repliedTo(threadId: String, inReplyTo: String): MessageView? {
        fun find(detail: ThreadDetail?) = detail?.messages?.lastOrNull { it.messageIdHeader?.trim() == inReplyTo }
        find(runSuspendCatching { cache.thread(threadId) }.getOrNull())?.let { return it }
        return try {
            val thread = gmail.getThread(threadId, full = true)
            find(withContext(Dispatchers.Default) { threadDetail(thread) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    /** Open a Gmail draft in the composer: this device's copy if it has one, else Gmail's. */
    suspend fun openDraft(draftId: String, outboxDocGiven: OutboxDoc? = null) {
        if (parkedState.value && composerState.value?.draftId == draftId) {
            unpark()
            return
        }
        val outboxDoc = outboxDocGiven ?: outboxFor(draftId)
        val local = drafts.all().firstOrNull { it.content.draftId == draftId && !it.deleted }
        if (local != null) {
            openLocal(local.content.localId)
        } else {
            try {
                val draft = gmail.getDraft(draftId)
                val message = draft.message?.let { encryption.openDraft(it) } ?: return
                val inReplyTo = draftInReplyTo(message)
                val original = if (inReplyTo != null && message.threadId.isNotEmpty()) repliedTo(message.threadId, inReplyTo) else null
                val (opened, remoteFiles) = draftFromMessage(newLocalId(), draftId, message, ::newFileId, original)
                val content = (if (outboxDoc != null && opened.bcc.isEmpty()) opened.copy(bcc = copiesBcc(outboxDoc.id)) else opened)
                    .copy(draftThreadId = message.threadId.takeIf(String::isNotEmpty))
                val session = ComposeSession(content.copy(outboxId = outboxDoc?.id), if (content.threadId == null) ComposeKind.NEW else ComposeKind.REPLY)
                show(session)
                for (remote in remoteFiles) {
                    fetchFile(session, remote.file) {
                        remote.data?.let { decoded(it) } ?: attachmentBytes(message.id, remote.partId, remote.attachmentId ?: error("no data"))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isOffline(error)) {
                    openSaved(messageId = offlineDrafts()?.refs?.firstOrNull { it.draftId == draftId }?.messageId ?: "")
                } else {
                    noticeState.value = SendNotice.Info(openFailedText(error))
                }
                return
            }
        }
        composerState.value?.let { session ->
            if (outboxDoc != null) {
                session.outboxId = outboxDoc.id
                session.scheduledAt = outboxDoc.sendAt
            }
        }
    }

    private fun openFailedText(error: Exception): String = when {
        error is MailLockedException -> "Unlock encrypted mail to open this draft."
        isOffline(error) -> OPENS_ONLINE
        else -> "Couldn't open the draft."
    }

    // Opens a draft while Gmail can't be reached: this device's own copy when it has one, else the
    // saved copy's when it holds all of it (see offlineEditable), else says it opens once back online.
    private suspend fun openSaved(messageId: String? = null, threadIds: Set<String> = emptySet(), before: () -> Unit = {}) {
        val local = drafts.all().lastOrNull { draft ->
            !draft.deleted &&
                ((messageId != null && draft.content.messageId == messageId) ||
                    draft.content.threadId.inAny(threadIds) ||
                    draft.content.draftThreadId.inAny(threadIds))
        }
        if (local != null) {
            before()
            if (openLocal(local.content.localId)) return
        }
        val input = offlineDrafts()
        val ref = input?.let { offlineDraftOf(it, messageId, threadIds) }
        val message = ref?.let { found -> cache.storedThread(found.threadId)?.drafts?.firstOrNull { it.id == found.messageId } }
        if (ref == null || message == null || !offlineEditable(savedDraftParts(message))) {
            noticeState.value = SendNotice.Info(OPENS_ONLINE)
            return
        }
        before()
        val inReplyTo = messageIds(message.inReplyTo).firstOrNull()
        val original = inReplyTo?.let { repliedTo(ref.threadId, it) }
        val (opened, remoteFiles) = draftFromView(newLocalId(), ref.draftId, ref.threadId, message, ::newFileId, original)
        val content = opened.copy(draftThreadId = ref.threadId)
        val session = ComposeSession(content, if (content.threadId == null) ComposeKind.NEW else ComposeKind.REPLY)
        session.fromSavedCopy = true
        show(session)
        for (remote in remoteFiles) {
            fetchFile(session, remote.file) { decoded(remote.data ?: error("no data")) }
        }
    }

    private fun sendDocs(sendId: String): List<OutboxDoc> =
        outbox.docs.value.filter { baseOutboxId(it.id) == sendId }

    // An encrypted send's main draft has no Bcc; its recipients are in the copies' drafts.
    private suspend fun copiesBcc(sendId: String): List<Person> =
        sendDocs(sendId).filter { isCopyId(it.id) }.sortedBy { copyNumber(it.id) ?: Int.MAX_VALUE }.flatMap { doc ->
            runSuspendCatching {
                val message = gmail.getDraft(doc.draftId).message?.let { encryption.openDraft(it) }
                parseAddressList(headerValue(message?.payload?.headers, "Bcc"))
            }.getOrDefault(emptyList())
        }

    // Undo and cancel take back every doc of a send, and the copies' drafts with them. Called only
    // while the drafts are held ([withdrawHeld]), as [commitSend] writes them, so a send whose docs
    // are being written is either taken back here or never written.
    private fun withdraw(sendId: String) {
        directSends.remove(sendId)
        outbox.delete(sendId)
        val copies = sendDocs(sendId).filter { isCopyId(it.id) }.associate { it.id to it.draftId } +
            copiesWritten.remove(sendId).orEmpty()
        for (docId in copies.keys) outbox.delete(docId)
        if (copies.isEmpty()) return
        scope.launch {
            for (draftId in copies.values) {
                try {
                    gmail.deleteDraft(draftId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!isNotFound(error)) Log.w(TAG, "copy draft delete failed", error)
                }
            }
        }
    }

    // Take back send [sendId] (when given) and change draft [localId] (when given) by [change] in one
    // step, under the drafts lock [commitSend] writes a send's docs under; returns the draft as changed.
    private suspend fun withdrawHeld(localId: String?, sendId: String?, change: (StoredDraft?) -> StoredDraft?): StoredDraft? =
        if (localId == null) {
            sendId?.let(::withdraw)
            null
        } else {
            drafts.whileHeld(localId) { current ->
                sendId?.let(::withdraw)
                change(current).let { it to it }
            }
        }

    /** Open a draft shown in a thread: this device's copy, else Gmail's. */
    fun openLocalDraft(localId: String) {
        scope.launch {
            val stored = drafts.get(localId)?.content
            if (!openLocal(localId)) {
                stored?.draftId?.let { openDraft(it) } ?: stored?.messageId?.let { openDraftMessage(it) }
            }
        }
    }

    /** Open the draft whose Gmail message is [messageId] (a draft shown in a thread). */
    fun openDraftMessage(messageId: String) {
        scope.launch { findAndOpenDraft(onMissing = {}, offline = { openSaved(messageId = messageId) }) { it.message?.id == messageId } }
    }

    /** Open the draft of a row in Drafts, its Gmail threads [threadIds], in the composer; [onMissing] when it has none left. */
    fun openThreadDraft(threadIds: Set<String>, onMissing: () -> Unit) {
        scope.launch {
            findAndOpenDraft(onMissing = onMissing, offline = { openSaved(threadIds = threadIds) }) { it.message?.threadId in threadIds }
        }
    }

    private suspend fun findAndOpenDraft(onMissing: () -> Unit, offline: suspend () -> Unit, matches: (GmailDraft) -> Boolean) {
        try {
            var pageToken: String? = null
            for (page in 0 until MAX_DRAFT_PAGES) {
                val result = gmail.listDrafts(pageToken)
                // drafts.list is newest first.
                val found = result.drafts.orEmpty().firstOrNull(matches)
                if (found != null) {
                    openDraft(found.id)
                    return
                }
                pageToken = result.nextPageToken ?: break
            }
            onMissing()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isOffline(error)) offline() else noticeState.value = SendNotice.Info(openFailedText(error))
        }
    }

    /** Save [content], what's typed (debounced by the composer); Gmail gets it at the next flush. */
    fun autosave(session: ComposeSession, content: DraftContent) {
        if (isLocked(session.outboxId) || untouchedSchedule(session)) return
        if (content.isEmpty() && content.draftId == null) return
        scope.launch {
            drafts.save(content)
            requestFlush()
        }
    }

    /** Copy a picked or shared file into the draft and add it; one that can't be read or is too big for a message is noted and left out (null). */
    suspend fun addFile(session: ComposeSession, filename: String, mimeType: String, contentId: String?, open: () -> InputStream?): DraftFile? {
        val id = newFileId()
        val size = try {
            drafts.copyIn(session.localId, id) { open() ?: throw FileNotFoundException(filename) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "file copy failed", error)
            showNotice("Couldn't add $filename")
            return null
        }
        if (size == null) {
            showNotice("$filename is over Gmail's 35 MB limit.")
            return null
        }
        val file = DraftFile(id, filename, mimeType, size, contentId)
        session.files += file
        return file
    }

    /** A fresh Content-ID for an inline image. */
    fun newContentId(): String = "${newFileId()}@inbox"

    // A scheduled send opened and left as it was: saving it would rebuild its messages for nothing.
    private fun untouchedSchedule(session: ComposeSession): Boolean = session.outboxId != null && !session.edited

    /** Whether the composer holds a scheduled send the user changed, so closing it saves the send's messages again. */
    fun editsSchedule(session: ComposeSession): Boolean =
        session.edited && waitingDoc(session.outboxId) != null

    /**
     * Whether sending what the composer holds must ask to unlock first: it must be encrypted, this
     * device hasn't unlocked the key, and the user hasn't chosen to send without encryption.
     */
    suspend fun needsUnlock(session: ComposeSession): Boolean =
        !session.sendUnencrypted &&
            encryption.needsUnlock(session.from.email, (session.to + session.cc + session.bcc).map { it.email })

    // Shows [session] in the composer, keeping a different message open or parked there as a draft
    // first; the parked message itself opening again (from its thread or Drafts) just comes back.
    private fun show(session: ComposeSession) {
        val current = composerState.value
        if (current != null && current !== session && current.localId == session.localId) {
            parkedState.value = false
            return
        }
        if (current != null && current !== session) close(current)
        parkedState.value = false
        composerState.value = session
    }

    private fun takeDown(session: ComposeSession) {
        if (composerState.value === session) {
            composerState.value = null
            parkedState.value = false
        }
    }

    /** Park the open [session] in the strip at the bottom of the screen, saving its draft to Gmail now. */
    fun park(session: ComposeSession) {
        if (composerState.value !== session) return
        parkedState.value = true
        scope.launch { autosave(session, session.renderedContent()) }
    }

    /** Open the parked composer again as it was. */
    fun unpark() {
        parkedState.value = false
    }

    /** Close the composer, keeping what's typed as a draft unless there's nothing. */
    fun close(session: ComposeSession) {
        takeDown(session)
        if (isLocked(session.outboxId) || untouchedSchedule(session)) return
        val content = session.content()
        scope.launch {
            if (content.isEmpty() && content.draftId == null) {
                drafts.remove(content.localId)
            } else {
                drafts.save(content)
                drafts.pruneFiles(content)
                if (content.outboxId == null) noticeState.value = SendNotice.Saved(content.localId)
            }
            refreshLocal()
            requestFlush()
        }
    }

    /**
     * Close the composer and throw its draft away once the undo window ends, here and in Gmail; a
     * scheduled send of it is canceled then. Until then [undoDiscard] brings the composer back.
     */
    fun discard(session: ComposeSession) {
        takeDown(session)
        // One discard waits at a time; an earlier one goes through now.
        for (localId in discarding.keys.toList()) finishDiscard(localId)
        val job = scope.launch {
            delay(NOTICE_MS)
            finishDiscard(session.localId)
        }
        discarding[session.localId] = session to job
        publishLocal()
        noticeState.value = SendNotice.Discarded(session.localId)
    }

    /** Take back a discard still in its undo window: the composer opens again as it was. */
    fun undoDiscard(localId: String) {
        val (session, job) = discarding.remove(localId) ?: run {
            noticeState.value = null
            return
        }
        job.cancel()
        publishLocal()
        noticeState.value = null
        show(session)
    }

    private fun finishDiscard(localId: String) {
        val (session, job) = discarding.remove(localId) ?: return
        job.cancel()
        val content = session.content()
        scope.launch {
            // Left deleted, so the next flush deletes it in Gmail when it has one and forgets it.
            val kept = withdrawHeld(content.localId, content.outboxId) { current ->
                val draftId = current?.content?.draftId ?: content.draftId
                val stored = current ?: StoredDraft(content, dirty = false, deleted = true, pendingSend = null, updatedAt = 0).takeIf { draftId != null }
                stored?.copy(content = content.copy(draftId = draftId), deleted = true, pendingSend = null)
            }
            // Never stored: only files fetched for it are left.
            if (kept == null) drafts.remove(content.localId)
            refreshLocal()
            requestFlush()
        }
    }

    /**
     * Send the composer's message at [sendAt] (now plus the undo delay for Send). The draft goes
     * to Gmail first; the outbox doc follows once Gmail has given it an id.
     */
    fun send(session: ComposeSession, sendAt: Long, chosenAt: Long?, undoable: Boolean) {
        takeDown(session)
        val existing = session.outboxId?.let { id -> outbox.docs.value.firstOrNull { it.id == id } }
        val reuse = existing != null && existing.status in setOf(OutboxStatus.WAITING, OutboxStatus.FAILED, OutboxStatus.BLOCKED)
        val outboxId = if (reuse) existing.id else OutboxStore.newId()
        if (existing != null && !reuse) Log.w(TAG, "outbox ${existing.id} already ${existing.status}; writing a new one")
        val content = session.content().copy(outboxId = outboxId)
        noticeState.value = if (undoable) {
            SendNotice.Sending(outboxId, content.localId, sendAt)
        } else {
            SendNotice.Scheduled(outboxId, content.localId, sendAt)
        }
        if (undoable) sendingLocal += content.localId else sendingLocal -= content.localId
        // Untouched since it opened from Gmail, its Gmail draft already holds it, unless a save is
        // still owed. Offline it waits on the device like any send, so the Outbox says so.
        val inGmail = !session.edited && !session.fromSavedCopy && content.draftId != null && online()
        scope.launch {
            saveSend(content, PendingSend(outboxId, sendAt, chosenAt, unencrypted = session.sendUnencrypted, now = undoable), inGmail)
            requestFlush()
        }
    }

    // Saves a send's draft on the device with its send waiting, for the next flush; one [inGmail] isn't uploaded again.
    private suspend fun saveSend(content: DraftContent, pending: PendingSend, inGmail: Boolean = false) {
        val owed = drafts.get(content.localId)?.dirty == true
        drafts.save(content)
        drafts.pruneFiles(content)
        drafts.update(content.localId) { it?.copy(pendingSend = pending, dirty = it.dirty && (owed || !inGmail)) }
        refreshLocal()
    }

    /**
     * Send a message the app wrote itself (an unsubscribe request, an RSVP) right away, through the
     * same drafts and outbox as any send; no composer, no undo. Throws [InvalidAddressError] when
     * an address can't be sent to, before anything is queued.
     */
    fun sendNow(content: DraftContent) {
        val outboxId = OutboxStore.newId()
        val sendAt = System.currentTimeMillis()
        val ready = content.copy(localId = newLocalId(), from = content.from.takeIf { it.email.isNotEmpty() } ?: defaultFrom(), outboxId = outboxId)
        (listOf(ready.from) + ready.to + ready.cc + ready.bcc).firstOrNull { !isPlainAddress(it.email.trim()) }?.let { throw InvalidAddressError(it.email) }
        sendingLocal += ready.localId
        scope.launch {
            drafts.save(ready)
            drafts.update(ready.localId) { it?.copy(pendingSend = PendingSend(outboxId, sendAt, null, now = true)) }
            refreshLocal()
            requestFlush()
        }
    }

    /** The address a message the app writes for the user goes from: the one [message] reached, else the default. */
    fun fromFor(message: MessageView): Person {
        val reached = (message.to + message.cc).map { addressKey(it.email) }.toSet()
        return senders.value.firstOrNull { addressKey(it.email) in reached } ?: defaultFrom()
    }

    /** Take back a send: cancel its outbox doc (or the write still waiting) and reopen the draft. */
    fun undoSend(outboxId: String, localId: String) {
        sendingLocal -= localId
        noticeState.value = null
        scope.launch {
            // Saved again whole, so an encrypted send's main draft gets its Bcc list back.
            withdrawHeld(localId, outboxId) { it?.copy(pendingSend = null, dirty = true, content = it.content.copy(outboxId = null)) }
            refreshLocal()
            openLocal(localId)
        }
    }

    /** Open an outbox doc's draft: to edit while scheduled, blocked or failed (sending again reschedules), read-only while it goes out. */
    fun editScheduled(doc: OutboxDoc) {
        val main = outbox.docs.value.firstOrNull { it.id == baseOutboxId(doc.id) } ?: doc
        scope.launch { openDraft(main.draftId, main) }
    }

    /** Cancel a send that hasn't gone (scheduled, retrying or blocked); the draft stays in Drafts. */
    fun cancelScheduled(doc: OutboxDoc) {
        val sendId = baseOutboxId(doc.id)
        // The rules refuse deleting a doc mid-attempt.
        if (outbox.docs.value.any { baseOutboxId(it.id) == sendId && it.status == OutboxStatus.SENDING }) {
            noticeState.value = SendNotice.Info("It's being sent right now, so it can't be canceled.")
            return
        }
        composerState.value?.takeIf { it.outboxId == sendId }?.let { session ->
            session.outboxId = null
            session.scheduledAt = null
        }
        val localId = localByOutbox.value[sendId]?.localId
        scope.launch {
            withdrawHeld(localId, sendId) { it?.copy(pendingSend = null, dirty = true, content = it.content.copy(outboxId = null)) }
            refreshLocal()
        }
        noticeState.value = SendNotice.Info(
            if (mayHaveGone(doc)) "Canceled. It may already have gone, so check Sent." else "Canceled. The message is in Drafts.",
        )
    }

    /** Take a send held on this device out of the outbox before it has a doc: the message stays a draft. */
    fun cancelHeld(localId: String) {
        sendingLocal -= localId
        scope.launch {
            val pending = drafts.get(localId)?.pendingSend ?: return@launch
            // Saved again whole, so an encrypted send's main draft gets its Bcc list back.
            withdrawHeld(localId, pending.outboxId) { it?.copy(pendingSend = null, dirty = true, content = it.content.copy(outboxId = null)) }
            refreshLocal()
            requestFlush()
            noticeState.value = SendNotice.Info(
                if (pending.now) "Canceled. The message is in Drafts." else "Schedule canceled. The message is in Drafts.",
            )
        }
    }

    /** Send a failed or blocked message again, after the undo delay. */
    fun sendAgain(doc: OutboxDoc) {
        val sendAt = System.currentTimeMillis() + SendSuggestions.UNDO_SEND_DELAY_MS
        failuresShown -= doc.id
        retriesShown -= doc.id
        outbox.write(doc.id, doc.draftId, doc.from, sendAt, null, doc.threadId)
        val localId = localByOutbox.value[doc.id]?.localId
        noticeState.value = if (localId != null) SendNotice.Sending(doc.id, localId, sendAt) else SendNotice.Info("Sending again")
    }

    /** Show [text] in the app's dark pill, from any screen. */
    fun showNotice(text: String) {
        noticeState.value = SendNotice.Info(text)
    }

    /** Hide the sending bar. */
    fun dismissNotice() {
        noticeState.value = null
    }

    /** Open a kept draft in the composer again, from this device or else from Gmail. */
    fun reopenDraft(localId: String) {
        noticeState.value = null
        scope.launch {
            val draftId = drafts.get(localId)?.content?.draftId
            if (!openLocal(localId) && draftId != null) openDraft(draftId)
        }
    }

    private fun requestFlush() {
        scope.launch {
            if (!flush()) onPendingChanges()
        }
    }

    /**
     * Send drafts saved on the device to Gmail and write the outbox docs waiting on them; stops at
     * the first one the network or Gmail can't take yet. Returns whether nothing is left.
     */
    suspend fun flush(): Boolean {
        val running = flushRun
        if (running != null && running.isActive) {
            flushAgain = true
            return running.await()
        }
        val run = scope.async { flushAll() }
        flushRun = run
        return run.await()
    }

    // Passes over the drafts until one ran with nothing asked for meanwhile; see [flush].
    private suspend fun flushAll(): Boolean {
        if (!readyToFlush()) return false
        do {
            flushAgain = false
            flushOnce()?.let { return it }
        } while (flushAgain)
        refreshLocal()
        return true
    }

    // Whether to encrypt, and an encrypted send's Bcc copies, come from the account and the outbox:
    // nothing goes until both have been read. While a read is awaited (offline at a cold start, say)
    // the flush waits for nothing; it runs again once both are read.
    private fun readyToFlush(): Boolean {
        val accountRead = account.firstRead.value
        val outboxRead = outbox.firstRead.value
        if (accountRead == true && outboxRead == true) return true
        if ((accountRead == null || outboxRead == null) && flushOnRead?.isActive != true) {
            flushOnRead = scope.launch { if (account.awaitLoaded() && outbox.awaitLoaded()) requestFlush() }
        }
        return false
    }

    // One pass over the drafts on the device; false when the network or Gmail can't take one yet, else null.
    private suspend fun flushOnce(): Boolean? {
        for (draft in drafts.all()) {
            val localId = draft.content.localId
            try {
                if (draft.deleted) {
                    draft.content.draftId?.let { id ->
                        try {
                            gmail.deleteDraft(id)
                        } catch (error: GmailException) {
                            if (!isNotFound(error)) throw error
                        }
                    }
                    drafts.remove(localId)
                    continue
                }
                // Its send is under way: the draft must stay as it is until that settles.
                if (isLocked(draft.content.outboxId)) continue
                val pending = draft.pendingSend
                val scheduled = if (pending == null) waitingDoc(draft.content.outboxId) else null
                when {
                    pending != null -> writeSend(draft, pending, rewrite = false)
                    // An edited scheduled send: its messages are built and saved again, its time kept.
                    scheduled != null -> if (draft.dirty) {
                        writeSend(draft, PendingSend(scheduled.id, scheduled.sendAt, scheduled.chosenAt), rewrite = true)
                    }
                    draft.dirty -> upload(draft)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val problem = buildProblem(error)
                if (problem == null && retryLater(error)) {
                    return false
                }
                // Gmail refused it for good (too big), or an address or header can't be sent: keep it here, unsent, and say so.
                Log.w(TAG, "draft $localId refused", error)
                drafts.update(localId) { it?.copy(dirty = false, pendingSend = null) }
                sendingLocal -= localId
                noticeState.value = SendNotice.Failed(
                    when {
                        problem == null -> "Gmail didn't take the draft: ${error.message}"
                        draft.pendingSend != null -> "Not sent. $problem"
                        else -> "Couldn't save the draft to Gmail. $problem"
                    },
                )
                // Put away on Send; brought back to fix.
                if (problem != null && draft.pendingSend != null && composerState.value == null) openLocal(localId)
            }
        }
        return null
    }

    private fun recipientsOf(content: DraftContent): List<String> = (content.to + content.cc + content.bcc).map { it.email }

    // The message as it goes to Gmail: with its Autocrypt header, and encrypted when [outgoing] says so.
    private suspend fun messageFor(
        content: DraftContent,
        outgoing: Outgoing,
        to: List<Person> = content.to,
        cc: List<Person> = content.cc,
        bcc: List<Person> = content.bcc,
        encryptTo: List<String>? = outgoing.encryptTo,
        bccCopy: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val input = content.composeInput(randomBoundary()) { drafts.read(content.localId, it) }.copy(
            to = to,
            cc = cc,
            bcc = bcc,
            extraHeaders = listOfNotNull(outgoing.autocrypt?.let { "Autocrypt" to it }),
        )
        val mime = buildMime(input)
        val signingKey = outgoing.signingKey
        if (encryptTo == null || signingKey == null) {
            mime
        } else {
            withContext(Dispatchers.Default) { encryptMime(mime, encryptTo, signingKey, randomBoundary(), bccCopy = bccCopy) }
        }
    }

    private suspend fun saveDraft(existing: String?, mime: String, threadId: String?): GmailDraft =
        if (existing == null) {
            gmail.createDraft(mime, threadId)
        } else {
            try {
                gmail.updateDraft(existing, mime, threadId)
            } catch (error: GmailException) {
                // Deleted elsewhere (or already sent): save it as a new draft.
                if (!isNotFound(error)) throw error
                gmail.createDraft(mime, threadId)
            }
        }

    private fun waitForUnlock() {
        noticeState.value = SendNotice.Info("Unlock encrypted mail to save or send this message.")
    }

    private fun waitingDoc(outboxId: String?): OutboxDoc? =
        outboxId?.let { id -> outbox.docs.value.firstOrNull { it.id == id && it.status == OutboxStatus.WAITING } }

    // Keeps a doc as it is unless something in it changes, so an edit doesn't touch what it needn't.
    private fun writeDoc(docId: String, draftId: String, from: String, pending: PendingSend, threadId: String?) {
        val existing = outbox.docs.value.firstOrNull { it.id == docId }
        val same = existing != null && existing.status == OutboxStatus.WAITING && existing.draftId == draftId &&
            existing.from == from && existing.sendAt == pending.sendAt && existing.chosenAt == pending.chosenAt &&
            existing.threadId == threadId
        if (!same) outbox.write(docId, draftId, from, pending.sendAt, pending.chosenAt, threadId)
    }

    private fun dropCopies(copies: List<OutboxDoc>) {
        for (doc in copies) {
            outbox.delete(doc.id)
            scope.launch { runSuspendCatching { gmail.deleteDraft(doc.draftId) } }
        }
    }

    private fun deleteDrafts(draftIds: List<String>) {
        if (draftIds.isEmpty()) return
        scope.launch {
            for (id in draftIds) runSuspendCatching { gmail.deleteDraft(id) }.onFailure { Log.w(TAG, "draft delete failed", it) }
        }
    }

    /**
     * Write a send's outbox docs ([docs]: doc id to draft id) if the draft, read again now, still
     * asks for this send ([sendStands]); then drop the copies it replaced. Otherwise (undone,
     * discarded or sent again while the drafts saved) nothing goes, and the drafts made for it
     * ([created]) are deleted. The main doc names the draft's thread ([draftThreadId] for a new
     * message), so Drafts leaves it out before Gmail lists the draft. Returns whether the docs were
     * written.
     */
    private suspend fun commitSend(
        draft: StoredDraft,
        pending: PendingSend,
        rewrite: Boolean,
        docs: List<Pair<String, String>>,
        replaced: List<OutboxDoc>,
        created: List<String>,
        draftThreadId: String?,
    ): Boolean {
        val content = draft.content
        val written = drafts.whileHeld(content.localId) { current ->
            if (!sendStands(current, pending, rewrite, waitingDoc(pending.outboxId) != null)) {
                null to false
            } else {
                for ((docId, draftId) in docs) {
                    val threadId = if (docId == pending.outboxId) content.threadId ?: draftThreadId else content.threadId
                    writeDoc(docId, draftId, content.from.email, pending, threadId)
                    if (isCopyId(docId)) copiesWritten.getOrPut(pending.outboxId) { mutableMapOf() }[docId] = draftId
                }
                val next = if (rewrite) null else current?.copy(pendingSend = null, content = current.content.copy(outboxId = pending.outboxId))
                next to true
            }
        }
        if (written) dropCopies(replaced) else deleteDrafts(created)
        if (written && pending.now && docs.size == 1) sendWhenDue(pending.outboxId, docs.single().second, content.from.email, pending.sendAt)
        return written
    }

    /**
     * When a send now's undo window ends, send it from this device if it is still here, online and
     * from a Gmail address, skipping the server task's cold start; the server's task stays the
     * backup ([sendDirectly], [directSendDecision]).
     */
    private fun sendWhenDue(outboxId: String, draftId: String, from: String, sendAt: Long) {
        directSends[outboxId] = sendAt
        scope.launch {
            delay((sendAt - System.currentTimeMillis()).coerceAtLeast(0))
            val mayClaim = { doc: DirectSendDoc? ->
                val input = DirectSendInput(
                    // Only while on screen: a process in the background, or one a notification's reply
                    // started, may be stopped between the claim and the handover.
                    sendNow = directSends[outboxId] == sendAt &&
                        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                    docs = sendDocs(outboxId).size.coerceAtLeast(1),
                    online = online(),
                    gmailRoute = isGmailRoute(from, sendAs.value, ADDRESS_DOMAIN),
                    wroteSendAt = sendAt,
                    doc = doc,
                    now = System.currentTimeMillis(),
                )
                directSendDecision(input) == DirectSendDecision.SEND
            }
            // The doc's fields are checked on the doc as stored, in the claim.
            val seen = outbox.docs.value.firstOrNull { it.id == outboxId }?.let { DirectSendDoc(it.status.name.lowercase(), it.sendAt, emptyList()) }
            if (mayClaim(seen)) {
                val result = sendDirectly(directSendDeps(outboxId, draftId, mayClaim), draftId, from, scope)
                if (result == DirectSendResult.LEFT) Log.w(TAG, "a send from this device is left for the server to settle")
            }
            if (directSends[outboxId] == sendAt) directSends.remove(outboxId)
        }
    }

    private fun directSendDeps(outboxId: String, docDraftId: String, mayClaim: (DirectSendDoc?) -> Boolean) = object : DirectSendDeps {
        override suspend fun readDraft(draftId: String): Pair<String, List<GmailHeader>> {
            val message = checkNotNull(gmail.getDraft(draftId, full = false).message) { "draft has no message" }
            return message.id to message.payload?.headers.orEmpty()
        }

        override suspend fun claim(claim: DeviceClaim): Boolean = outbox.claimDirect(outboxId, docDraftId, claim, mayClaim)

        override suspend fun sendDraft(draftId: String): Pair<String, String> = gmail.sendDraft(draftId).let { it.id to it.threadId }

        override fun recordSent(messageId: String, threadId: String, deviceClaim: String) =
            outbox.recordDirectSent(outboxId, messageId, threadId, deviceClaim)

        override fun recordRefused(message: String, deviceClaim: String) = outbox.recordDirectRefused(outboxId, message, deviceClaim)

        override fun newClaimId(): String = OutboxStore.newId()

        override fun now(): Long = System.currentTimeMillis()
    }

    // Whether the send is still asked for, read without waiting on other draft changes: to stop early.
    private suspend fun stillStands(draft: StoredDraft, pending: PendingSend, rewrite: Boolean): Boolean =
        sendStands(drafts.get(draft.content.localId), pending, rewrite, waitingDoc(pending.outboxId) != null)

    // Each copy's recipient, from its draft's outer Bcc header; a copy whose draft is gone has none.
    private suspend fun copyOwners(copies: List<OutboxDoc>): Map<String, OutboxDoc> =
        copies.mapNotNull { doc ->
            val headers = try {
                gmail.getDraft(doc.draftId, full = false).message?.payload?.headers
            } catch (error: GmailException) {
                if (!isNotFound(error)) throw error
                null
            }
            parseAddressList(headerValue(headers, "Bcc")).firstOrNull()?.let { addressKey(it.email) to doc }
        }.toMap()

    // The composer's draft now holds [saved]; when [clean] it stays dirty only if typed again since [draft] was read.
    // Discarded meanwhile, the saved draft is deleted: at the next flush, or now when nothing is left of it here.
    private suspend fun markUploaded(draft: StoredDraft, saved: GmailDraft, clean: Boolean = true) {
        val localId = draft.content.localId
        val savedId = saved.id
        // Each save gives the draft a new message id; its thread finds Gmail's copy by it.
        val messageId = saved.message?.id ?: draft.content.messageId
        val threadId = saved.message?.threadId?.takeIf(String::isNotEmpty) ?: draft.content.draftThreadId
        val kept = drafts.update(localId) { current ->
            when {
                current == null -> null
                current.deleted -> current.copy(content = current.content.copy(draftId = savedId, messageId = messageId, draftThreadId = threadId))
                else -> current.copy(
                    content = current.content.copy(draftId = savedId, messageId = messageId, draftThreadId = threadId),
                    dirty = current.dirty && (!clean || current.updatedAt != draft.updatedAt),
                )
            }
        }
        if (kept == null) {
            deleteDrafts(listOf(savedId))
        } else if (!kept.deleted) {
            composerState.value?.takeIf { it.localId == localId }?.draftId = savedId
        }
    }

    // A send whose messages can't be built without the key fails rather than going in the clear.
    private suspend fun failLocked(draft: StoredDraft, pending: PendingSend) {
        val keepDoc = outbox.docs.value.any { it.id == pending.outboxId }
        drafts.update(draft.content.localId) {
            it?.copy(pendingSend = null, dirty = true, content = it.content.copy(outboxId = pending.outboxId.takeIf { keepDoc }))
        }
        sendingLocal -= draft.content.localId
        refreshLocal()
        noticeState.value = SendNotice.Failed("Not sent: encrypted mail is locked on this device.")
    }

    /**
     * Build a send's messages, save their drafts, then write their outbox docs. An encrypted send
     * with Bcc becomes several messages (docs/compose-contract.md, "Encrypted sends with Bcc"): the
     * first replaces the composer's draft, each Bcc copy has its own draft and doc. On [rewrite]
     * (an edited scheduled send) a copy whose recipient is still in Bcc is updated in place and its
     * doc kept; removed recipients' copies go. The docs are written only if the send still stands
     * once the drafts are saved ([commitSend]). On a locked device a send fails and an edit waits.
     */
    private suspend fun writeSend(draft: StoredDraft, pending: PendingSend, rewrite: Boolean) {
        val content = draft.content
        val from = content.from.email
        val recipients = recipientsOf(content)
        val outgoing = encryption.outgoing(from, recipients, pending.unencrypted)
        if (outgoing.waitForUnlock) {
            if (rewrite) waitForUnlock() else failLocked(draft, pending)
            return
        }
        val ownKey = encryption.ownPublicKey()
        val oldCopies = sendDocs(pending.outboxId).filter { isCopyId(it.id) }
        if (content.bcc.isEmpty() || outgoing.encryptTo == null || outgoing.signingKey == null || ownKey == null) {
            // A draft Gmail holds as it stands goes as is; an encrypted one is built again, as an earlier save may predate a key.
            val reused = content.draftId.takeIf { !draft.dirty && outgoing.encryptTo == null }
            val saved = if (reused == null) saveWhole(draft, outgoing) else null
            val draftId = reused ?: saved?.id ?: return
            val threadId = saved?.message?.threadId?.takeIf(String::isNotEmpty) ?: content.draftThreadId
            commitSend(draft, pending, rewrite, listOf(pending.outboxId to draftId), oldCopies, created = emptyList(), threadId)
            return
        }
        val keys = encryption.directory.keysFor(recipients)
        val plan = planEncryptedSends(from, content.to, content.cc, content.bcc)
        val owners = if (rewrite || oldCopies.isNotEmpty()) copyOwners(oldCopies) else emptyMap()
        var nextNumber = nextCopyNumbers(oldCopies.map { it.id }, pending.outboxId, 1).single()
        val kept = mutableSetOf<String>()
        val saved = mutableListOf<Pair<String, String>>()
        var main: GmailDraft? = null
        val created = mutableListOf<String>()
        for ((index, planned) in plan.withIndex()) {
            if (index > 0 && !stillStands(draft, pending, rewrite)) {
                deleteDrafts(created)
                main?.let { markUploaded(draft, it, clean = false) }
                return
            }
            val encryptTo = planned.encryptTo.mapNotNull { keys[it] } + ownKey
            val mime = messageFor(content, outgoing, planned.to, planned.cc, planned.bcc, encryptTo, planned.kind == EncryptedSendKind.BCC)
            if (index == 0) {
                val mainDraft = saveDraft(content.draftId, mime, content.threadId)
                main = mainDraft
                // Its id is kept at once so a retry updates it, but it stays dirty until every copy is saved.
                markUploaded(draft, mainDraft, clean = false)
                saved += pending.outboxId to mainDraft.id
            } else {
                val owner = planned.bcc.firstOrNull()?.let { owners[addressKey(it.email)] }?.takeIf { it.id !in kept }
                val docId = owner?.id ?: copyOutboxId(pending.outboxId, nextNumber++)
                kept += docId
                // A new message's copies thread on their own; a reply's stay in its thread.
                val copyDraftId = saveDraft(owner?.draftId, mime, content.threadId).id
                if (copyDraftId != owner?.draftId) created += copyDraftId
                saved += docId to copyDraftId
            }
        }
        val mainThread = main?.message?.threadId?.takeIf(String::isNotEmpty) ?: content.draftThreadId
        val written = commitSend(draft, pending, rewrite, saved, oldCopies.filter { it.id !in kept }, created, mainThread)
        // Not sent after all: the main draft holds the To and Cc message, so it is saved whole again.
        main?.let { markUploaded(draft, it, clean = written) }
    }

    // The draft as one message: encrypted to everyone with Bcc outside when it is encrypted.
    private suspend fun saveWhole(draft: StoredDraft, outgoing: Outgoing): GmailDraft {
        val content = draft.content
        val saved = saveDraft(content.draftId, messageFor(content, outgoing), content.threadId)
        markUploaded(draft, saved)
        return saved
    }

    // An autosaved draft; on a locked device that must encrypt it waits for an unlock.
    private suspend fun upload(draft: StoredDraft) {
        val content = draft.content
        val outgoing = encryption.outgoing(content.from.email, recipientsOf(content))
        if (outgoing.waitForUnlock) {
            waitForUnlock()
            return
        }
        saveWhole(draft, outgoing)
    }
}

// Whether this id (when there is one) is among [ids].
private fun String?.inAny(ids: Set<String>): Boolean = this != null && this in ids
