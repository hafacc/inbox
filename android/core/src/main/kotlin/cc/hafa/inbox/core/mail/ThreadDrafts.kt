package cc.hafa.inbox.core.mail

/** A message of a Gmail thread as the draft rule sees it. */
data class GmailThreadMessage(
    /** Gmail message id */
    val id: String,
    /** whether Gmail has it as a draft (the `DRAFT` label) */
    val draft: Boolean,
)

/** Where a draft this device holds stands. */
enum class LocalDraftState {
    /** being written, or kept to finish later */
    DRAFT,

    /** thrown away; Gmail's copy may not be deleted yet */
    DELETED,

    /** sent from this device; Gmail hasn't shown the sent message yet */
    SENDING,
}

/** A draft of the thread this device holds. */
data class LocalThreadDraft(
    /** the draft's id on this device */
    val localId: String,
    /** Gmail message id of the draft as last saved there; null before it reaches Gmail */
    val messageId: String?,
    /** where it stands */
    val state: LocalDraftState,
    /** when it last changed, epoch milliseconds */
    val updatedAt: Long,
)

/** One entry of a thread as it shows. */
sealed interface ThreadEntry {
    /** A sent or received message, Gmail's copy. */
    data class Message(
        /** Gmail message id */
        val id: String,
    ) : ThreadEntry

    /** A draft only Gmail has. */
    data class Draft(
        /** Gmail message id */
        val id: String,
    ) : ThreadEntry

    /** A draft (or a message being sent) as this device holds it. */
    data class Local(
        /** the draft's id on this device */
        val localId: String,
        /** whether it is being sent */
        val sending: Boolean,
    ) : ThreadEntry
}

/**
 * The entries a thread shows, in order, from Gmail's messages and this device's drafts.
 *
 * Gmail's messages keep their order ([messages] oldest first). A Gmail draft that a local draft
 * saved (same message id) shows the local one in its place, or nothing when it was deleted. A sent
 * message Gmail has is shown as Gmail's, whatever the device holds for it. Local drafts Gmail
 * doesn't have yet (not deleted) go at the end, oldest change first. A draft in the Outbox (its
 * Gmail message id in [outbox]) shows nowhere here, Gmail's copy or the device's, unless the device
 * holds it as sending. Matches web's `shared/src/thread-drafts.ts`.
 */
fun threadEntries(messages: List<GmailThreadMessage>, local: List<LocalThreadDraft>, outbox: Set<String> = emptySet()): List<ThreadEntry> {
    val byMessage = local.filter { it.messageId != null }.associateBy { it.messageId }
    val placed = mutableSetOf<String>()
    val entries = mutableListOf<ThreadEntry>()
    for (message in messages) {
        val held = byMessage[message.id]
        if (held != null) placed += held.localId
        when {
            !message.draft -> entries += ThreadEntry.Message(message.id)
            held == null -> if (message.id !in outbox) entries += ThreadEntry.Draft(message.id)
            shows(held, outbox) -> entries += localEntry(held)
        }
    }
    local
        .filter { it.localId !in placed && shows(it, outbox) }
        .sortedWith(compareBy<LocalThreadDraft> { it.updatedAt }.thenBy { it.localId })
        .forEach { entries += localEntry(it) }
    return entries
}

private fun shows(draft: LocalThreadDraft, outbox: Set<String>): Boolean = when (draft.state) {
    LocalDraftState.DELETED -> false
    LocalDraftState.SENDING -> true
    LocalDraftState.DRAFT -> draft.messageId == null || draft.messageId !in outbox
}

private fun localEntry(draft: LocalThreadDraft): ThreadEntry =
    ThreadEntry.Local(draft.localId, draft.state == LocalDraftState.SENDING)

private const val SNIPPET_LENGTH = 200
private val IMAGE_RE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val LINK_RE = Regex("""\[([^\]]*)]\([^)]*\)""")
private val MARK_RE = Regex("""[*_~`#>\\]""")
private val SPACE_RE = Regex("""\s+""")

/** A draft's first words for its line in the thread: its markdown body as plain text. */
fun draftSnippet(markdown: String): String =
    markdown
        .replace(IMAGE_RE, " ")
        .replace(LINK_RE, "$1")
        .replace(MARK_RE, "")
        .replace(SPACE_RE, " ")
        .trim()
        .take(SNIPPET_LENGTH)
