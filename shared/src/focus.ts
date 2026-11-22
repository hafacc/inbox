import { PINNED_LABEL } from "./bundles";

/** Gmail's system label for threads it ranks important; it can be added and removed like any label. */
export const IMPORTANT_LABEL = "IMPORTANT";

/** The Gmail query naming pinned threads, added to the view's own query in focus mode. */
export const FOCUS_QUERY = "is:starred";

/** The labels to add and remove to mark a thread important or not; both empty when it already is. */
export type ImportantChange = {
  /** label ids added */
  add: string[];
  /** label ids removed */
  remove: string[];
};

/** Whether a thread carrying `labelIds` is important. */
export function isImportant(labelIds: readonly string[]): boolean {
  return labelIds.includes(IMPORTANT_LABEL);
}

/** The label change that marks a thread important (`important` true) or not important. */
export function importantChange(
  labelIds: readonly string[],
  important: boolean,
): ImportantChange {
  if (isImportant(labelIds) === important) return { add: [], remove: [] };
  else if (important) return { add: [IMPORTANT_LABEL], remove: [] };
  else return { add: [], remove: [IMPORTANT_LABEL] };
}

/** A list entry as focus mode sees it. */
export type FocusEntry =
  | {
      /** a thread on its own row */
      kind: "thread";
      /** thread id */
      id: string;
      /** its labels */
      labelIds: readonly string[];
    }
  | {
      /** a todo */
      kind: "todo";
      /** whether it is pinned */
      pinned: boolean;
    }
  | {
      /** a bundle */
      kind: "bundle";
      /** the threads it holds */
      threadIds: readonly string[];
    };

/** Sections with focus mode applied, and how many entries it hid. */
export type FocusResult<Section> = {
  /** the sections still showing something */
  sections: Section[];
  /** rows hidden for not being pinned: threads, todos and bundles, one each */
  hidden: number;
};

// Whether focus mode shows an entry: pinned, or holding the open thread so unpinning it doesn't close it.
function shown(
  entry: FocusEntry,
  openId: string | null,
  heldPins: ReadonlyMap<string, boolean>,
): boolean {
  if (entry.kind === "thread") {
    return (
      entry.id === openId ||
      entry.labelIds.includes(PINNED_LABEL) ||
      heldPins.get(entry.id) === true
    );
  } else if (entry.kind === "todo") {
    return entry.pinned;
  } else {
    return openId !== null && entry.threadIds.includes(openId);
  }
}

/**
 * Apply focus mode to a list's sections.
 *
 * Only pinned threads (Gmail's star) and pinned todos show, plus the open thread, alone or in its
 * bundle, so unpinning it doesn't close it, and the focused thread while `heldPins` (`HeldPlaces`'
 * `pins`) holds it pinned, so unpinning it doesn't hide it until focus leaves. Pinned threads never
 * sit in bundles, so bundles are hidden. Sections left with nothing are dropped.
 */
export function focusSections<
  Entry,
  Section extends { entries: readonly Entry[] },
>(
  sections: readonly Section[],
  entryOf: (entry: Entry) => FocusEntry,
  openId: string | null,
  heldPins: ReadonlyMap<string, boolean> = new Map(),
): FocusResult<Section> {
  let hidden = 0;
  const kept: Section[] = [];
  for (const section of sections) {
    const entries = section.entries.filter((entry) => {
      if (shown(entryOf(entry), openId, heldPins)) {
        return true;
      } else {
        hidden += 1;
        return false;
      }
    });
    if (entries.length > 0) kept.push({ ...section, entries });
  }
  return { sections: kept, hidden };
}

/** The Gmail query focus mode lists the inbox with: the view's own `query` and pinned threads only. */
export function focusQuery(query: string): string {
  const own = query.trim();
  return own === "" ? FOCUS_QUERY : `${own} ${FOCUS_QUERY}`;
}
