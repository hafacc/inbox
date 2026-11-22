// Folding a list's threads into one row per big user label. Android :core mail/LabelGroups.kt is the
// same; both run shared/testdata/label-group-fixtures.json (through the list model).

import { MIN_BUNDLE_SIZE } from "./bundles";

/** Fewest threads sharing a user label before they fold into one row. */
export const MIN_GROUP_SIZE = MIN_BUNDLE_SIZE;

/** A list entry: one thread, or the threads of one user label folded together. */
export type ListEntry<Thread> =
  | {
      /** one thread */
      kind: "thread";
      /** the thread */
      thread: Thread;
    }
  | {
      /** threads of one label, folded */
      kind: "group";
      /** the label */
      labelId: string;
      /** its threads, newest first */
      threads: Thread[];
    };

/**
 * Fold threads, newest first, into label groups.
 *
 * A user label with at least `minSize` of the threads becomes one group, placed where its
 * newest thread was. A thread with several such labels joins the label with the most threads (ties:
 * the earlier label in `userLabelIds`). A label left with fewer than `minSize` threads after that
 * gives its threads back as single rows, and the choice is redone without it. A `kept` label (an
 * open bundle) stays a group however few threads it has, and takes any thread carrying it first.
 */
export function groupByLabel<Thread>(
  threads: readonly Thread[],
  labelsOf: (thread: Thread) => readonly string[],
  userLabelIds: readonly string[],
  minSize = MIN_GROUP_SIZE,
  kept: ReadonlySet<string> = new Set(),
): ListEntry<Thread>[] {
  const order = new Map(userLabelIds.map((id, index) => [id, index]));
  let candidates = new Set(userLabelIds);
  let assignment = new Map<Thread, string>();
  for (;;) {
    const counts = new Map<string, number>();
    for (const thread of threads) {
      for (const label of labelsOf(thread)) {
        if (candidates.has(label)) {
          counts.set(label, (counts.get(label) ?? 0) + 1);
        }
      }
    }
    const eligible = new Set(
      [...counts]
        .filter(([id, count]) => count >= minSize || kept.has(id))
        .map(([id]) => id),
    );
    assignment = new Map();
    const sizes = new Map<string, number>();
    for (const thread of threads) {
      let best: string | null = null;
      for (const label of labelsOf(thread)) {
        if (!eligible.has(label)) continue;
        if (
          best === null ||
          (kept.has(label) && !kept.has(best)) ||
          (kept.has(label) === kept.has(best) &&
            (counts.get(label)! > counts.get(best)! ||
              (counts.get(label) === counts.get(best) &&
                order.get(label)! < order.get(best)!)))
        ) {
          best = label;
        }
      }
      if (best !== null) {
        assignment.set(thread, best);
        sizes.set(best, (sizes.get(best) ?? 0) + 1);
      }
    }
    const tooSmall = [...sizes]
      .filter(([id, size]) => size < minSize && !kept.has(id))
      .map(([id]) => id);
    if (tooSmall.length === 0) break;
    candidates = new Set([...eligible].filter((id) => !tooSmall.includes(id)));
  }

  const out: ListEntry<Thread>[] = [];
  const groups = new Map<string, Thread[]>();
  for (const thread of threads) {
    const label = assignment.get(thread);
    if (label === undefined) {
      out.push({ kind: "thread", thread });
    } else {
      const members = groups.get(label);
      if (members) {
        members.push(thread);
      } else {
        const created = [thread];
        groups.set(label, created);
        out.push({ kind: "group", labelId: label, threads: created });
      }
    }
  }
  return out;
}
