// How a device's saved copy syncs with Gmail: from its own history id when it has one, otherwise
// by a full rebuild, and when that id moves on. Android :core mail/OfflineSync.kt is the same; both
// run shared/testdata/offline-sync-fixtures.json.

/** How a sync brings the saved copy up to date. */
export type SyncMode =
  /** `history.list` from the saved history id */
  | "incremental"
  /** list every thread the saved set holds and read those that changed */
  | "rebuild";

/**
 * How the next sync runs: from the saved history id once a rebuild filled the saved copy, a
 * rebuild before that (the first fill, or one cut short) or with no id.
 */
export function syncMode(historyId: string | null, filled: boolean): SyncMode {
  return historyId !== null && filled ? "incremental" : "rebuild";
}

/** How reading every page of `history.list` went. */
export type HistoryRead =
  /** every page came back */
  | "complete"
  /** Gmail answered 404: the id is too old, so only a rebuild catches up */
  | "expired"
  /** a page failed otherwise (offline, a limit): try again at the next sync */
  | "failed";

/** What follows reading history: apply what it says, rebuild, or stop until the next sync. */
export function afterHistory(read: HistoryRead): "apply" | "rebuild" | "stop" {
  return read === "complete"
    ? "apply"
    : read === "expired"
      ? "rebuild"
      : "stop";
}

/** How a sync went. */
export type SyncRun = {
  /** how it ran */
  mode: SyncMode;
  /** every history page, or every list page of a rebuild, came back */
  listed: boolean;
  /** every thread read it asked for came back (one Gmail no longer has counts as read) */
  read: boolean;
  /**
   * the history id it brings the copy to: the last history page's, or for a rebuild the profile's
   * read before listing; null when that read failed
   */
  reached: string | null;
};

/** The saved copy's state after a sync. */
export type SyncState = {
  /** history id the next sync reads from */
  historyId: string | null;
  /** whether a rebuild completed, so lists are built from the saved copy */
  filled: boolean;
};

/**
 * The state after `run`, from the state before it. The history id moves on only after a sync that
 * came back whole, so a failed one is tried again from where it started; a rebuild that came back
 * whole fills the copy. A rebuild cut short leaves the copy as filled as it was.
 */
export function syncAfter(before: SyncState, run: SyncRun): SyncState {
  const whole = run.listed && run.read && run.reached !== null;
  if (!whole) return before;
  return {
    historyId: run.reached,
    filled: before.filled || run.mode === "rebuild",
  };
}

/** Whether a rebuild may drop saved threads it didn't list: only once every list page came back. */
export function dropsUnlisted(run: Pick<SyncRun, "mode" | "listed">): boolean {
  return run.mode === "rebuild" && run.listed;
}
