package cc.hafa.inbox.core.mail

// How a device's saved copy syncs with Gmail: from its own history id when it has one, otherwise
// by a full rebuild, and when that id moves on. A port of shared/src/offline-sync.ts; both run
// shared/testdata/offline-sync-fixtures.json.

/** How a sync brings the saved copy up to date. */
enum class SyncMode {
    /** `history.list` from the saved history id */
    INCREMENTAL,

    /** list every thread the saved set holds and read those that changed */
    REBUILD,
}

/** How reading every page of `history.list` went. */
enum class HistoryRead {
    /** every page came back */
    COMPLETE,

    /** Gmail answered 404: the id is too old, so only a rebuild catches up */
    EXPIRED,

    /** a page failed otherwise (offline, a limit): try again at the next sync */
    FAILED,
}

/** What follows reading history. */
enum class HistoryNext {
    /** apply what it says */
    APPLY,

    /** rebuild the saved copy */
    REBUILD,

    /** stop until the next sync */
    STOP,
}

/**
 * How the next sync runs: from the saved history id once a rebuild filled the saved copy, a
 * rebuild before that (the first fill, or one cut short) or with no id.
 */
fun syncMode(historyId: String?, filled: Boolean): SyncMode =
    if (historyId != null && filled) SyncMode.INCREMENTAL else SyncMode.REBUILD

/** What follows reading history: apply what it says, rebuild, or stop until the next sync. */
fun afterHistory(read: HistoryRead): HistoryNext = when (read) {
    HistoryRead.COMPLETE -> HistoryNext.APPLY
    HistoryRead.EXPIRED -> HistoryNext.REBUILD
    HistoryRead.FAILED -> HistoryNext.STOP
}

/** How a sync went. */
data class SyncRun(
    /** how it ran */
    val mode: SyncMode,
    /** every history page, or every list page of a rebuild, came back */
    val listed: Boolean,
    /** every thread read it asked for came back (one Gmail no longer has counts as read) */
    val read: Boolean,
    /** the history id it brings the copy to; null when that read failed */
    val reached: String?,
)

/** The saved copy's state after a sync. */
data class SyncState(
    /** history id the next sync reads from */
    val historyId: String?,
    /** whether a rebuild completed, so lists are built from the saved copy */
    val filled: Boolean,
)

/**
 * The state after [run], from the state before it. The history id moves on only after a sync that
 * came back whole, so a failed one is tried again from where it started; a rebuild that came back
 * whole fills the copy. A rebuild cut short leaves the copy as filled as it was.
 */
fun syncAfter(before: SyncState, run: SyncRun): SyncState =
    if (run.listed && run.read && run.reached != null) {
        SyncState(run.reached, before.filled || run.mode == SyncMode.REBUILD)
    } else {
        before
    }

/** Whether a rebuild may drop saved threads it didn't list: only once every list page came back. */
fun dropsUnlisted(mode: SyncMode, listed: Boolean): Boolean = mode == SyncMode.REBUILD && listed
