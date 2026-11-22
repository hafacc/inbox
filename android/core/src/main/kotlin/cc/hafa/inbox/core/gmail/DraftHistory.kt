package cc.hafa.inbox.core.gmail

// Gmail's label on draft messages.
private const val DRAFT = "DRAFT"

/**
 * Whether Gmail history [records] may have changed the account's drafts: a draft message added or
 * deleted (a save replaces the draft's message), or the draft label added to or taken off one. A
 * deleted message without its labels may have been a draft, so it counts. The same as web's
 * `touchesDrafts`.
 */
fun touchesDrafts(records: List<GmailHistoryRecord>): Boolean = records.any { record ->
    record.messagesAdded.orEmpty().any { DRAFT in it.message.labelIds.orEmpty() } ||
        record.messagesDeleted.orEmpty().any { it.message.labelIds?.contains(DRAFT) ?: true } ||
        (record.labelsAdded.orEmpty() + record.labelsRemoved.orEmpty()).any { DRAFT in it.labelIds.orEmpty() }
}
