// Taking back the last action on threads: which labels undo puts back, whether it puts back the
// snooze, merging label changes made one after another, and what the undo pill says. Web uses it
// directly; Android ports it to :core (Undo.kt). Both run shared/testdata/undo-fixtures.json.

/** What an undoable action did. */
export type UndoKind =
  | "done"
  | "snooze"
  | "pin"
  | "spam"
  | "notSpam"
  | "trash"
  | "delete"
  | "move"
  | "label"
  | "read"
  | "unread";

// The labels done, snooze, pin, spam and trash touch.
const UNDOABLE_LABELS: readonly string[] = [
  "INBOX",
  "STARRED",
  "SPAM",
  "TRASH",
];

const UNREAD = "UNREAD";

/** The labels undo puts back for an action that changed `touched` (label changes and moves). */
export function undoLabels(
  kind: UndoKind,
  touched: readonly string[],
): string[] {
  switch (kind) {
    case "label":
    case "move":
      return [...new Set(touched)];
    case "read":
    case "unread":
      return [UNREAD];
    default:
      return [...UNDOABLE_LABELS];
  }
}

/** The change that takes a thread's labels from `current` back to `before`, for the labels in `restore`. */
export function undoChange(
  before: readonly string[],
  current: readonly string[],
  restore: readonly string[],
): { add: string[]; remove: string[] } {
  return {
    add: restore.filter(
      (label) => before.includes(label) && !current.includes(label),
    ),
    remove: restore.filter(
      (label) => !before.includes(label) && current.includes(label),
    ),
  };
}

/** Whether undoing the action writes each thread's snooze back as it was. */
export function restoresSnooze(kind: UndoKind): boolean {
  return (
    kind === "done" || kind === "snooze" || kind === "spam" || kind === "trash"
  );
}

/**
 * The threads a label change's undo holds when it follows another label change still on offer:
 * each thread as it was before the earlier one, then the threads only the later one touched.
 */
export function mergeUndoItems<Item extends { id: string }>(
  earlier: readonly Item[],
  later: readonly Item[],
): Item[] {
  const seen = new Set(earlier.map((item) => item.id));
  return [...earlier, ...later.filter((item) => !seen.has(item.id))];
}

/** What the undo pill says about an action on `count` threads and todos. */
export function undoText(
  kind: UndoKind,
  count: number,
  pinned: boolean,
): string {
  const one = count === 1;
  switch (kind) {
    case "snooze":
      return one ? "Snoozed" : `${count} snoozed`;
    case "pin":
      if (pinned) return one ? "Pinned" : `${count} pinned`;
      else return one ? "Unpinned" : `${count} unpinned`;
    case "delete":
      return one ? "Deleted" : `${count} deleted`;
    case "move":
      return one ? "Moved" : `${count} moved`;
    case "spam":
      return one ? "Marked as spam" : `${count} marked as spam`;
    case "notSpam":
      return one ? "Moved to Inbox" : `${count} moved to Inbox`;
    case "trash":
      return one ? "Moved to trash" : `${count} moved to trash`;
    case "label":
      return one ? "Labels changed" : `Labels changed on ${count}`;
    case "read":
      return one ? "Marked as read" : `${count} marked as read`;
    case "unread":
      return one ? "Marked as unread" : `${count} marked as unread`;
    default:
      return one ? "Done" : `${count} done`;
  }
}
