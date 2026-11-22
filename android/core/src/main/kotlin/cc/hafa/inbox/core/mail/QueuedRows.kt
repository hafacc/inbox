package cc.hafa.inbox.core.mail

// Port of shared/src/queued-rows.ts: label changes still queued for Gmail laid over a row Gmail just
// sent, so a fresh copy doesn't undo them on screen. Both run shared/testdata/queued-row-fixtures.json.

/** A row's unread flag after a change: only a change to UNREAD itself moves it, and adding wins. */
fun unreadAfter(unread: Boolean, add: List<String>, remove: List<String>): Boolean = when {
    Labels.UNREAD in add -> true
    Labels.UNREAD in remove -> false
    else -> unread
}

/**
 * This row with every change in [queue] for its thread applied in replay order (by seq, whatever
 * order [queue] holds them in); this row itself when none moves it.
 */
fun ThreadSummary.withQueued(queue: List<LabelChange>): ThreadSummary {
    var labels = labelIds
    var unreadNow = unread
    for (change in queue.sortedBy { it.seq }) {
        if (change.threadId != id) continue
        labels = applyChange(labels, change.add, change.remove)
        unreadNow = unreadAfter(unreadNow, change.add, change.remove)
    }
    return if (labels == labelIds && unreadNow == unread) this else copy(labelIds = labels, unread = unreadNow)
}
