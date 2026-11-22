package cc.hafa.inbox.core.mail

// What a thread's summary keeps for joining split encrypted replies (see [joinThreads]), read the
// same way from Gmail's copy and from a saved one, as shared/src/join-keys.ts does; both run
// shared/testdata/join-key-fixtures.json.

/** The headers and date of one message (draft or not) that joining reads. */
data class JoinKeyMessage(
    /** its `Message-ID` header, or null */
    val messageId: String?,
    /** its `In-Reply-To` header, or null */
    val inReplyTo: String?,
    /** its `References` header, or null */
    val references: String?,
    /** its date, epoch ms */
    val date: Long,
)

/** What joining reads of a thread, as its summary keeps it. */
data class JoinKeys(
    /** each message's `Message-ID`, in order, without repeats */
    val messageIds: List<String>,
    /** the ids its messages' `In-Reply-To` and `References` name, in order, without repeats; empty unless hidden-subject */
    val answers: List<String>,
    /** its earliest message's date, epoch ms; 0 with no messages */
    val firstDate: Long,
)

/**
 * The join keys of a thread from all its [messages], drafts included: each one's `Message-ID` (its
 * first `<…>`), and when the thread has the hidden subject ([hiddenSubject]) the ids its replies name.
 */
fun joinKeys(messages: List<JoinKeyMessage>, hiddenSubject: Boolean): JoinKeys = JoinKeys(
    messageIds = messages.flatMap { messageIdsIn(it.messageId).take(1) }.distinct(),
    answers = if (hiddenSubject) messages.flatMap { messageIdsIn(it.inReplyTo) + messageIdsIn(it.references) }.distinct() else emptyList(),
    firstDate = messages.minOfOrNull { it.date } ?: 0L,
)
