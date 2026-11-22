package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.gmail.GmailException
import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailSendAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Port of shared/src/direct-send.ts: a send now that is still on screen and online when its undo
// window ends goes from the device (`drafts.send`) instead of waiting for the server's Cloud Task;
// the server stays the backup. Both run shared/testdata/direct-send-fixtures.json.

/** The fields clients write on an outbox doc; any other field is the server's attempt record. */
val CLIENT_OUTBOX_FIELDS: List<String> = listOf("draftId", "from", "status", "sendAt", "chosenAt", "threadId")

/**
 * How long past its `sendAt` a device still sends a send now itself; later, the server has it. The
 * rules refuse a claim more than 60 s past `sendAt` by the server's clock, which leaves room for a
 * device clock up to 30 s behind.
 */
const val DIRECT_SEND_LATE_MS = 30_000L

/**
 * Longest a device lets pass between starting its claim and handing the draft to Gmail; past it,
 * the claim stays as it is and the server's takeover settles the send once the lease ends.
 */
const val DIRECT_HANDOVER_MAX_MS = 60_000L

/**
 * Longest a device counts on Gmail's answer to its `drafts.send`; past it, the server's takeover
 * settles the send once the lease ends, unless the answer still comes first: the device records it
 * under its claim (never sending again), which the rules accept only while that claim stands.
 */
const val DIRECT_SEND_TIMEOUT_MS = 30_000L

/**
 * Whether [from] sends through Gmail, by the server's own check: not on [resendDomain], and the
 * Gmail address or a send-as alias Gmail has verified.
 */
fun isGmailRoute(from: String, sendAs: List<GmailSendAs>, resendDomain: String): Boolean {
    val address = addressKey(from)
    return address.split("@").getOrNull(1) != resendDomain &&
        sendAs.any { it.sendAsEmail.lowercase() == address && it.usable }
}

/** An outbox doc as [directSendDecision] reads it. */
data class DirectSendDoc(
    /** its status, as stored */
    val status: String,
    /** its send time, epoch milliseconds */
    val sendAt: Long,
    /** the names of every field it has */
    val fields: Collection<String>,
)

/** What a device knows when a send now's undo window ends. */
data class DirectSendInput(
    /** whether this device pressed Send (not a scheduled time) and the send wasn't undone */
    val sendNow: Boolean,
    /** the outbox docs of the send: 1, or more for an encrypted send with Bcc copies */
    val docs: Int,
    /** whether the device is online */
    val online: Boolean,
    /** whether its From sends through Gmail ([isGmailRoute]) */
    val gmailRoute: Boolean,
    /** the `sendAt` this device wrote */
    val wroteSendAt: Long,
    /** the doc as read now, or null when it is gone */
    val doc: DirectSendDoc?,
    /** the device clock, epoch milliseconds */
    val now: Long,
)

/** Whether the device sends it ([SEND]), or why the server's task does. */
enum class DirectSendDecision(
    /** the name the fixtures use */
    val wire: String,
) {
    /** the device sends it */
    SEND("send"),

    /** a scheduled send, or one undone or written elsewhere */
    SCHEDULED("scheduled"),

    /** an encrypted send with Bcc copies */
    COPIES("copies"),

    /** no connection */
    OFFLINE("offline"),

    /** from @hafa.cc, or an address Gmail hasn't verified */
    NOT_GMAIL("not-gmail"),

    /** the doc is gone (undone) */
    NO_DOC("no-doc"),

    /** no longer `waiting`: the server, or another device, has it */
    TAKEN("taken"),

    /** rescheduled since */
    MOVED("moved"),

    /** it carries an attempt record of the server's */
    SERVER_RECORD("server-record"),

    /** its time hasn't come by this device's clock */
    EARLY("early"),

    /** past [DIRECT_SEND_LATE_MS]: the server's task is on it */
    LATE("late"),
}

/**
 * Whether this device sends a send now itself when its undo window ends: one doc, online, from a
 * Gmail address, still `waiting` at the time this device wrote, with only the fields clients write,
 * and no more than [DIRECT_SEND_LATE_MS] late. Checked on the doc as last seen, and again on the
 * doc as read in the claim's transaction.
 */
fun directSendDecision(input: DirectSendInput): DirectSendDecision {
    val doc = input.doc
    return when {
        !input.sendNow -> DirectSendDecision.SCHEDULED
        input.docs != 1 -> DirectSendDecision.COPIES
        !input.online -> DirectSendDecision.OFFLINE
        !input.gmailRoute -> DirectSendDecision.NOT_GMAIL
        doc == null -> DirectSendDecision.NO_DOC
        doc.status != "waiting" -> DirectSendDecision.TAKEN
        doc.sendAt != input.wroteSendAt -> DirectSendDecision.MOVED
        !CLIENT_OUTBOX_FIELDS.containsAll(doc.fields) -> DirectSendDecision.SERVER_RECORD
        input.now < doc.sendAt -> DirectSendDecision.EARLY
        input.now > doc.sendAt + DIRECT_SEND_LATE_MS -> DirectSendDecision.LATE
        else -> DirectSendDecision.SEND
    }
}

// A display name of plain words or quoted strings, then one address in angle brackets.
private val NAMED_MAILBOX = Regex("""^(?:"(?:[^"\\\r\n]|\\.)*"|[^"<>@,;:()\r\n])*<([^<>\s@]+@[^<>\s@]+)>$""")
private val BARE_MAILBOX = Regex("""^[^"<>@,;:()\s]+@[^"<>@,;:()\s]+$""")

/**
 * The draft's sender, lower-cased, when its headers hold exactly one `From` naming one plain
 * mailbox; null for anything else (two headers, a group, two mailboxes, comments), which the device
 * leaves to the server's full parse.
 */
fun draftSender(headers: List<GmailHeader>): String? {
    val field = headers.filter { it.name.equals("from", ignoreCase = true) }.singleOrNull() ?: return null
    val value = field.value.trim()
    val address = NAMED_MAILBOX.find(value)?.groupValues?.get(1) ?: value.takeIf { BARE_MAILBOX.matches(it) }
    return address?.lowercase()
}

/** What a device does after its own `drafts.send` failed. */
enum class DirectSendFailure {
    /** a refusal retrying can't fix: recorded as `failed` with `rejected`, as the server records it */
    REJECTED,

    /** anything else: the doc stays `sending` and the server's takeover settles it */
    LEAVE,
}

private val RATE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded")

/**
 * What a device does after its own `drafts.send` failed with HTTP [status] and Google's [reason]
 * (a null [status] for no answer), reading the error as the server does: [DirectSendFailure.REJECTED]
 * for a refusal retrying can't fix, [DirectSendFailure.LEAVE] for no answer, a limit, a server
 * error, an expired token or a draft gone (whose send the server looks up). The device never retries.
 */
@Suppress("MagicNumber")
fun directSendFailure(status: Int?, reason: String?): DirectSendFailure {
    val passing = status == null ||
        status == 401 ||
        status == 404 ||
        status == 408 ||
        status == 429 ||
        status >= 500 ||
        (status == 403 && reason in RATE_REASONS)
    return if (status != null && status >= 400 && !passing) DirectSendFailure.REJECTED else DirectSendFailure.LEAVE
}

/** What the device records when it claims a send now (the server's attempt record). */
data class DeviceClaim(
    /** the draft's Gmail message id, which the sent message keeps */
    val draftMessageId: String,
    /** the draft's `Message-ID` header, when it has one */
    val messageIdHeader: String?,
    /** a random id of this claim */
    val deviceClaim: String,
)

/** What sending a send now from the device talks to. */
interface DirectSendDeps {
    /** The draft's Gmail message id and headers (`drafts.get`, metadata). */
    suspend fun readDraft(draftId: String): Pair<String, List<GmailHeader>>

    /**
     * Claim the outbox doc in a transaction, deciding again on the doc as stored
     * ([directSendDecision]); false when it isn't this device's to send. May throw.
     */
    suspend fun claim(claim: DeviceClaim): Boolean

    /** `drafts.send`: the sent message's id and thread id. */
    suspend fun sendDraft(draftId: String): Pair<String, String>

    /** Record the doc this device claimed under [deviceClaim] as sent (queued by Firestore while offline). */
    fun recordSent(messageId: String, threadId: String, deviceClaim: String)

    /** Record the doc this device claimed under [deviceClaim] as refused (`failed`, `rejected`). */
    fun recordRefused(message: String, deviceClaim: String)

    /** A fresh claim id. */
    fun newClaimId(): String

    /** The clock, epoch milliseconds. */
    fun now(): Long
}

/** How a send now from the device ended. */
enum class DirectSendResult {
    /** sent and recorded */
    SENT,

    /** Gmail refused it for good; recorded as failed */
    REFUSED,

    /** not claimed: the server's task sends it as usual */
    SERVER,

    /**
     * claimed, but its outcome is the server's to settle once the lease ends, unless Gmail's
     * answer comes after [DIRECT_SEND_TIMEOUT_MS] and is recorded first
     */
    LEFT,
}

// The rules' limit on a refusal's message.
private const val REFUSAL_TEXT_MAX = 2000

// Gmail's answer to `drafts.send`: the sent message's id and thread id, or what it failed with.
private typealias SendAnswer = Result<Pair<String, String>>

// Records Gmail's answer under the claim id as the server would, however late it came.
private fun recordAnswer(deps: DirectSendDeps, answer: SendAnswer, deviceClaim: String): DirectSendResult {
    val error = answer.exceptionOrNull()
    val gmailError = error as? GmailException
    return if (error == null) {
        val (messageId, threadId) = answer.getOrThrow()
        deps.recordSent(messageId, threadId, deviceClaim)
        DirectSendResult.SENT
    } else if (directSendFailure(gmailError?.status, gmailError?.reason) == DirectSendFailure.REJECTED) {
        deps.recordRefused((error.message ?: error.toString()).take(REFUSAL_TEXT_MAX), deviceClaim)
        DirectSendResult.REFUSED
    } else {
        DirectSendResult.LEFT
    }
}

/**
 * Send a send now from the device once its undo window has ended and [directSendDecision] said so:
 * read the draft's headers and check its From is the doc's, as the server does; claim the doc
 * (`sending`, with the handover recorded); hand the draft to Gmail unless the claim took longer
 * than [DIRECT_HANDOVER_MAX_MS]; then record the result under its claim id as the server would.
 * Nothing is retried: a failure other than a clean refusal leaves the doc `sending` for the
 * server's takeover, which looks the message up before sending, so it never goes twice. With no
 * answer within [DIRECT_SEND_TIMEOUT_MS], it returns [DirectSendResult.LEFT] but keeps the call
 * going in [lateScope] and still records Gmail's answer when it comes, which the rules accept only
 * while this device's claim stands.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
suspend fun sendDirectly(
    deps: DirectSendDeps,
    draftId: String,
    from: String,
    lateScope: CoroutineScope,
): DirectSendResult {
    val (draftMessageId, headers) = try {
        deps.readDraft(draftId)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        return DirectSendResult.SERVER
    }
    if (draftSender(headers) != addressKey(from)) return DirectSendResult.SERVER
    val messageIdHeader = headers.firstOrNull { it.name.equals("message-id", ignoreCase = true) }?.value
    val started = deps.now()
    val deviceClaim = deps.newClaimId()
    val claimed = try {
        deps.claim(DeviceClaim(draftMessageId, messageIdHeader, deviceClaim))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }
    if (!claimed) return DirectSendResult.SERVER
    if (deps.now() - started > DIRECT_HANDOVER_MAX_MS) return DirectSendResult.LEFT
    val answer = lateScope.async {
        try {
            SendAnswer.success(deps.sendDraft(draftId))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SendAnswer.failure(error)
        }
    }
    val onTime = withTimeoutOrNull(DIRECT_SEND_TIMEOUT_MS) { answer.await() }
    return if (onTime == null) {
        lateScope.launch { recordAnswer(deps, answer.await(), deviceClaim) }
        DirectSendResult.LEFT
    } else {
        recordAnswer(deps, onTime, deviceClaim)
    }
}
