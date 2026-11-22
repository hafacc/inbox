package cc.hafa.inbox.core.notify

/** Why a notification was posted: new mail, or a snooze that ended. */
enum class PushKind {
    /** new mail */
    MESSAGE,

    /** a snooze ended, posted by the phone itself (see [snoozeEndedPayload]) */
    SNOOZE_ENDED,
}

/** The FCM data a notification carries; see `functions/src/lib/notification.ts`. */
data class PushPayload(
    /** why it was sent */
    val kind: PushKind,
    /** Gmail thread id */
    val threadId: String,
    /** Gmail message id */
    val messageId: String,
    /** sender's display name */
    val sender: String,
    /** sender's address */
    val senderAddress: String,
    /** message subject */
    val subject: String,
    /** start of the message text */
    val snippet: String,
    /** the account it is for */
    val account: String,
    /** token that lets Done run without opening the app; empty when none */
    val actionToken: String,
    /** when the message arrived, epoch milliseconds as text */
    val sentAt: String,
    /** whether the message is encrypted: subject and snippet are then empty until the phone decrypts it */
    val encrypted: Boolean = false,
)

/** A notification ready to show. */
data class NotificationContent(
    /** notification title */
    val title: String,
    /** notification text */
    val body: String,
    /** tag shared by notifications about one thread, so they replace each other */
    val tag: String,
    /** epoch milliseconds, or null when the push didn't say */
    val timestamp: Long?,
    /** whether Done can run without opening the app */
    val canDone: Boolean,
)

/** The tag of a thread's notification. */
fun notificationTag(threadId: String): String = "thread:$threadId"

/** Read an FCM data map. Null when it isn't ours. */
fun parsePush(data: Map<String, String>): PushPayload? {
    val kind = when (data["kind"]) {
        "message" -> PushKind.MESSAGE
        else -> return null
    }
    val threadId = data["threadId"].orEmpty()
    if (threadId.isEmpty()) return null
    fun field(key: String) = data[key].orEmpty()
    return PushPayload(
        kind = kind,
        threadId = threadId,
        messageId = field("messageId"),
        sender = field("sender"),
        senderAddress = field("senderAddress"),
        subject = field("subject"),
        snippet = field("snippet"),
        account = field("account"),
        actionToken = field("actionToken"),
        sentAt = field("sentAt"),
        encrypted = data["encrypted"] == "true",
    )
}

/**
 * Whether a push is for the account signed in here ([signedInEmail], null when signed out): pushes
 * for another account (one signed out of, or a token the server hasn't dropped yet) aren't shown.
 */
fun isForAccount(payload: PushPayload, signedInEmail: String?): Boolean = isForAccount(payload.account, signedInEmail)

/** Whether a push naming [account] is for the account signed in here ([signedInEmail], null when signed out). */
fun isForAccount(account: String, signedInEmail: String?): Boolean =
    signedInEmail != null && account.isNotBlank() && account.trim().equals(signedInEmail.trim(), ignoreCase = true)

/** Build the notification for a push. */
fun notificationContent(payload: PushPayload): NotificationContent {
    val sender = payload.sender.ifEmpty { payload.senderAddress.ifEmpty { "Someone" } }
    val sealed = payload.encrypted && payload.subject.isEmpty() && payload.snippet.isEmpty()
    val subject = payload.subject.ifEmpty { if (sealed) "Encrypted message" else "(no subject)" }
    val (title, body) = when {
        payload.kind == PushKind.SNOOZE_ENDED -> subject to "Back from snooze · $sender"
        sealed -> sender to "Encrypted message from $sender"
        else -> sender to listOf(payload.subject, payload.snippet).filter { it.isNotEmpty() }.joinToString("\n")
    }
    return NotificationContent(
        title = title,
        body = body,
        tag = notificationTag(payload.threadId),
        timestamp = payload.sentAt.toLongOrNull()?.takeIf { it > 0 },
        canDone = payload.actionToken.isNotEmpty(),
    )
}
