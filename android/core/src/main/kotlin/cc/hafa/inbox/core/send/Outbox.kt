package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.mail.formatClock
import java.time.ZoneId

/** Where an outbox doc is in its life (docs/compose-contract.md). */
enum class OutboxStatus {
    /** waiting for its send time */
    WAITING,

    /** an attempt is under way */
    SENDING,

    /** an attempt failed and the server tries again */
    RETRYING,

    /** waiting for the user to fix something */
    BLOCKED,

    /** sent */
    SENT,

    /** failed for good */
    FAILED,
    ;

    companion object {
        /** The status a doc's `status` field names; unknown values read as waiting. */
        fun of(value: String?): OutboxStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WAITING
    }
}

/** How the server sent a message. */
enum class SendRoute(
    /** the value of the doc's `via` field */
    val wire: String,
) {
    /** through the Gmail API */
    GMAIL("gmail"),

    /** through Resend, for @hafa.cc addresses */
    RESEND("resend"),
    ;

    companion object {
        /** The route a doc's `via` field names; null when absent or unknown. */
        fun of(value: String?): SendRoute? = entries.firstOrNull { it.wire == value }
    }
}

/** What a blocked send waits for. */
enum class BlockedOn(
    /** the value of the doc's `blockedOn` field */
    val wire: String,
) {
    /** the user signing in again */
    SIGN_IN("sign-in"),

    /** sending from @hafa.cc being set up */
    RESEND("resend"),
    ;

    companion object {
        /** What a doc's `blockedOn` field names; null when absent or unknown. */
        fun of(value: String?): BlockedOn? = entries.firstOrNull { it.wire == value }
    }
}

/** `users/{uid}/outbox/{id}`: one undo or scheduled send, as the client reads it. */
data class OutboxDoc(
    /** document id */
    val id: String,
    /** Gmail draft id */
    val draftId: String,
    /** the draft's From address */
    val from: String,
    /** where it is */
    val status: OutboxStatus,
    /** when it goes, epoch milliseconds */
    val sendAt: Long,
    /** the unshifted suggestion picked, or null */
    val chosenAt: Long?,
    /** the draft's thread, or null for a new conversation */
    val threadId: String?,
    /** server's error code when failed */
    val errorCode: String? = null,
    /** server's readable error when failed */
    val errorMessage: String? = null,
    /** when it went, epoch milliseconds */
    val sentAt: Long? = null,
    /** how it went, once the server chose */
    val via: SendRoute? = null,
    /** Resend only: whether the Sent copy is in Gmail yet */
    val filed: Boolean? = null,
    /** when the server tries again while retrying, epoch milliseconds */
    val nextAttemptAt: Long? = null,
    /** why the last attempt didn't go: `lastError.code` */
    val lastErrorCode: String? = null,
    /** why the last attempt didn't go, readable: `lastError.message` */
    val lastErrorMessage: String? = null,
    /** what a blocked send waits for */
    val blockedOn: BlockedOn? = null,
)

/** Whether the message is on its way: an attempt under way or another coming. Its draft mustn't change. */
val OutboxDoc.inFlight: Boolean get() = status == OutboxStatus.SENDING || status == OutboxStatus.RETRYING

/** How long a sent doc stays, so every open device can show the result, before the client deletes it. */
const val SENT_KEEP_MS = 2 * 60_000L

/** Whether a client should delete a sent doc now: two minutes after sending, and for Resend once filed in Sent. */
fun readyToDelete(doc: OutboxDoc, now: Long): Boolean =
    doc.status == OutboxStatus.SENT &&
        (doc.sentAt ?: 0L) <= now - SENT_KEEP_MS &&
        (doc.via != SendRoute.RESEND || doc.filed == true)

/** What to tell the user about a failed send: the server's reason plus what they can do. */
fun failureText(code: String?, message: String?): String {
    val advice = when (code) {
        "draft-missing" -> "The draft was deleted before it could go."
        "from-mismatch" -> "The draft's From address changed."
        "not-owned" -> "This app can't send from that address."
        "uncertain" -> "It may have been delivered. Sending again sends it again."
        "unavailable" -> "It kept failing, so it stopped trying."
        else -> null
    }
    val reason = message?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(reason, advice).distinct().joinToString(" ").ifEmpty { "It didn't send." }
}

/** Whether sending a failed message again can help: not when the draft is gone or the address isn't the user's. */
fun canSendAgain(code: String?): Boolean = code != "draft-missing" && code != "not-owned"

/** Whether the user can send it again by hand: failed, or blocked waiting for a fix. */
val OutboxDoc.canSendAgain: Boolean
    get() = status == OutboxStatus.BLOCKED || (status == OutboxStatus.FAILED && canSendAgain(errorCode))

/** What a blocked send waits for, in plain words; it goes by itself once that is fixed. */
fun blockedText(blockedOn: BlockedOn?): String = when (blockedOn) {
    BlockedOn.SIGN_IN -> "Waiting for you to sign in again"
    BlockedOn.RESEND -> "Sending from @hafa.cc isn't set up yet"
    null -> "Waiting until it can be sent"
}

/** The line for a send the server is retrying: not sent yet, and when it tries next (24-hour). */
fun retryingText(nextAttemptAt: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
    if (nextAttemptAt == null) {
        "Not sent yet · trying again soon"
    } else {
        "Not sent yet · trying again at ${formatClock(nextAttemptAt, zone)}"
    }

/** The quiet line under a retrying send: why the last attempt didn't go. */
fun retryingDetail(code: String?, message: String?): String? {
    val advice = when (code) {
        "rate-limited" -> "Sending limit reached."
        else -> null
    }
    val reason = message?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(reason, advice).distinct().joinToString(" ").ifEmpty { null }
}

/** Whether canceling may come too late: a retrying send whose last attempt's outcome was unknown. */
fun mayHaveGone(doc: OutboxDoc): Boolean = doc.status == OutboxStatus.RETRYING && doc.lastErrorCode == "network"

/** How long after its send time a message may stay unsent before the Outbox is listed. */
const val OUTBOX_GRACE_MS = 20_000L

/**
 * When the Outbox should be listed in the menu: [now] if it should be now, the later moment it will
 * be if nothing changes, or null while nothing is held up. Failed, blocked, retrying and scheduled
 * sends list it at once; anything else once it is [OUTBOX_GRACE_MS] past its send time.
 */
fun outboxShownAt(docs: Iterable<OutboxDoc>, now: Long): Long? =
    docs.filter { it.status != OutboxStatus.SENT }.minOfOrNull { doc ->
        val heldUp = doc.status == OutboxStatus.FAILED || doc.status == OutboxStatus.BLOCKED ||
            doc.status == OutboxStatus.RETRYING || doc.sendAt - now > SendSuggestions.UNDO_SEND_DELAY_MS
        if (heldUp) now else maxOf(now, doc.sendAt + OUTBOX_GRACE_MS)
    }
