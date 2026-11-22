// The queue of label and filter changes waiting for Gmail. Pure; web uses it directly, Android
// ports it to :core (account-queue-fixtures.json). Sending, storage and ids Gmail makes stay with
// each platform.

import type {
  GmailFilter,
  GmailFilterAction,
  GmailFilterCriteria,
} from "./filters";

/** Start of an id made on the device for a label or filter Gmail hasn't made yet. */
export const LOCAL_ID_PREFIX = "local:";

/** Whether an id stands in for one Gmail hasn't given yet. */
export function isLocalId(id: string): boolean {
  return id.startsWith(LOCAL_ID_PREFIX);
}

/** A new id to stand in for a label or filter until Gmail makes it. */
export function newLocalId(): string {
  return `${LOCAL_ID_PREFIX}${crypto.randomUUID()}`;
}

/** A label's colors as Gmail takes them. */
export type LabelColorInput = { textColor: string; backgroundColor: string };

/** A Gmail label as the queue shows it. */
export type QueueLabel = {
  /** label id */
  id: string;
  /** full name; `/` separates nesting */
  name: string;
  /** Gmail's own or the user's */
  type: "system" | "user";
  /** whether the label list shows it */
  labelListVisibility?: "labelShow" | "labelShowIfUnread" | "labelHide";
  /** its colors */
  color?: { textColor?: string; backgroundColor?: string };
  /** threads with the label */
  threadsTotal?: number;
  /** unread threads with the label */
  threadsUnread?: number;
};

/** A thread's label change waiting to be sent, as the queue reads it. */
export type ThreadLabelChange = {
  /** position in the order the user made changes */
  seq: number;
  /** Gmail thread id */
  threadId: string;
  /** label ids to add */
  add: string[];
  /** label ids to remove */
  remove: string[];
};

/**
 * A change to the account's labels or filters waiting to be sent, in the order the user made it.
 * A label or filter made on the device has a local id until Gmail makes it; later changes name it
 * by that id and are rewritten to Gmail's once it comes back.
 */
export type AccountChange =
  | {
      seq: number;
      kind: "createLabel";
      labelId: string;
      name: string;
      color?: LabelColorInput;
    }
  | {
      seq: number;
      kind: "patchLabel";
      labelId: string;
      name?: string;
      color?: LabelColorInput;
    }
  | {
      seq: number;
      kind: "createFilter";
      filterId: string;
      criteria: GmailFilterCriteria;
      action: GmailFilterAction;
      // the filter it takes the place of, deleted once this one is made
      replacing?: string;
    }
  | { seq: number; kind: "deleteFilter"; filterId: string }
  | {
      seq: number;
      kind: "labelMatching";
      query: string;
      add: string[];
      remove: string[];
    };

/** An account change before it is numbered. */
export type NewAccountChange = Unnumbered<AccountChange>;

type Unnumbered<Change> = Change extends unknown ? Omit<Change, "seq"> : never;

/** Append a change, numbered after every change already queued. */
export function enqueueAccount(
  queue: readonly AccountChange[],
  change: NewAccountChange,
): AccountChange[] {
  const highest = queue.reduce((seq, entry) => Math.max(seq, entry.seq), 0);
  return [...queue, { ...change, seq: highest + 1 } as AccountChange];
}

/** The stored queue, then the changes made before it was read, numbered after it. */
export function joinAccountQueues(
  stored: readonly AccountChange[],
  early: readonly AccountChange[],
): AccountChange[] {
  return [...early]
    .sort((left, right) => left.seq - right.seq)
    .reduce<AccountChange[]>(
      (queue, { seq: _seq, ...change }) =>
        enqueueAccount(queue, change as NewAccountChange),
      [...stored],
    );
}

/** The change to send next: the earliest made. */
export function nextAccountChange(
  queue: readonly AccountChange[],
): AccountChange | undefined {
  return queue.reduce<AccountChange | undefined>(
    (first, entry) =>
      first === undefined || entry.seq < first.seq ? entry : first,
    undefined,
  );
}

function swapId(ids: readonly string[], from: string, to: string): string[] {
  return ids.map((id) => (id === from ? to : id));
}

function usesLabel(action: GmailFilterAction, labelId: string): boolean {
  return (
    (action.addLabelIds?.includes(labelId) ?? false) ||
    (action.removeLabelIds?.includes(labelId) ?? false)
  );
}

function resolveOne(
  change: AccountChange,
  localId: string,
  gmailId: string,
): AccountChange {
  if (change.kind === "patchLabel" && change.labelId === localId) {
    return { ...change, labelId: gmailId };
  } else if (change.kind === "deleteFilter" && change.filterId === localId) {
    return { ...change, filterId: gmailId };
  } else if (
    change.kind === "createFilter" &&
    (change.replacing === localId || usesLabel(change.action, localId))
  ) {
    const { addLabelIds, removeLabelIds } = change.action;
    return {
      ...change,
      action: {
        ...change.action,
        ...(addLabelIds
          ? { addLabelIds: swapId(addLabelIds, localId, gmailId) }
          : {}),
        ...(removeLabelIds
          ? { removeLabelIds: swapId(removeLabelIds, localId, gmailId) }
          : {}),
      },
      ...(change.replacing === localId ? { replacing: gmailId } : {}),
    };
  } else if (
    change.kind === "labelMatching" &&
    (change.add.includes(localId) || change.remove.includes(localId))
  ) {
    return {
      ...change,
      add: swapId(change.add, localId, gmailId),
      remove: swapId(change.remove, localId, gmailId),
    };
  } else {
    return change;
  }
}

/** Name Gmail's id wherever later changes name a label or filter by its local id. */
export function resolveLocalId(
  queue: readonly AccountChange[],
  localId: string,
  gmailId: string,
): AccountChange[] {
  return queue.map((change) => resolveOne(change, localId, gmailId));
}

function withoutOne(change: AccountChange, localId: string): AccountChange[] {
  if (
    (change.kind === "patchLabel" && change.labelId === localId) ||
    (change.kind === "deleteFilter" && change.filterId === localId) ||
    (change.kind === "createFilter" && usesLabel(change.action, localId)) ||
    (change.kind === "labelMatching" &&
      (change.add.includes(localId) || change.remove.includes(localId)))
  ) {
    return [];
  } else if (change.kind === "createFilter" && change.replacing === localId) {
    const { replacing: _replaced, ...rest } = change;
    return [rest];
  } else {
    return [change];
  }
}

/**
 * Forget a label or filter Gmail refused to make: changes to it go, and so do a filter or a labeling
 * of matching mail that would use the label; a filter that was to replace it replaces nothing.
 */
export function withoutLocalId(
  queue: readonly AccountChange[],
  localId: string,
): AccountChange[] {
  return queue.flatMap((change) => withoutOne(change, localId));
}

/** The queue once the change numbered `seq` reached Gmail. */
export function changeSent(
  queue: readonly AccountChange[],
  seq: number,
): AccountChange[] {
  return queue.filter((entry) => entry.seq !== seq);
}

/** The queue once Gmail made the label the change numbered `seq` made, as `gmailId`. */
export function labelMade(
  queue: readonly AccountChange[],
  seq: number,
  gmailId: string,
): AccountChange[] {
  const change = queue.find((entry) => entry.seq === seq);
  if (change?.kind !== "createLabel") return [...queue];
  else return resolveLocalId(changeSent(queue, seq), change.labelId, gmailId);
}

/**
 * The queue once Gmail made the filter the change numbered `seq` made, as `gmailId`. An edit's old
 * filter is deleted next, in that change's place, so a retry doesn't make the new one twice; Gmail
 * naming the old filter itself (an edit that changed nothing) leaves nothing to delete.
 */
export function filterMade(
  queue: readonly AccountChange[],
  seq: number,
  gmailId: string,
): AccountChange[] {
  const change = queue.find((entry) => entry.seq === seq);
  if (change?.kind !== "createFilter") return [...queue];
  const { replacing } = change;
  const next =
    replacing === undefined || replacing === gmailId
      ? changeSent(queue, seq)
      : queue.map(
          (entry): AccountChange =>
            entry.seq === seq
              ? { seq, kind: "deleteFilter", filterId: replacing }
              : entry,
        );
  return resolveLocalId(next, change.filterId, gmailId);
}

/** The queue once Gmail refused the change numbered `seq` for good: it goes, with what depended on it. */
export function changeRefused(
  queue: readonly AccountChange[],
  seq: number,
): AccountChange[] {
  const change = queue.find((entry) => entry.seq === seq);
  const rest = changeSent(queue, seq);
  if (change?.kind === "createLabel") {
    return withoutLocalId(rest, change.labelId);
  } else if (change?.kind === "createFilter") {
    return withoutLocalId(rest, change.filterId);
  } else {
    return rest;
  }
}

/**
 * Rename a label in queued thread changes, or drop it with `to` null. Unchanged entries are the
 * same objects, so a caller can tell which to save again.
 */
export function relabelChanges<Change extends ThreadLabelChange>(
  changes: readonly Change[],
  from: string,
  to: string | null,
): Change[] {
  const swap = (ids: string[]) =>
    to === null
      ? ids.filter((id) => id !== from)
      : ids.map((id) => (id === from ? to : id));
  return changes.map((change) =>
    change.add.includes(from) || change.remove.includes(from)
      ? { ...change, add: swap(change.add), remove: swap(change.remove) }
      : change,
  );
}

/**
 * Thread changes that can go to Gmail now: those of threads none of whose queued changes names a
 * label Gmail hasn't made yet (a thread's changes go together, in order).
 */
export function sendableChanges<Change extends ThreadLabelChange>(
  changes: readonly Change[],
): Change[] {
  const waiting = new Set(
    changes
      .filter((change) => [...change.add, ...change.remove].some(isLocalId))
      .map((change) => change.threadId),
  );
  return changes.filter((change) => !waiting.has(change.threadId));
}

/** Gmail's labels as the device shows them: queued new labels added, queued renames and colors applied. */
export function labelsWithPending(
  labels: readonly QueueLabel[],
  queue: readonly AccountChange[],
): QueueLabel[] {
  let shown = [...labels];
  for (const change of [...queue].sort((left, right) => left.seq - right.seq)) {
    if (change.kind === "createLabel") {
      if (shown.some((label) => label.id === change.labelId)) continue;
      shown.push({
        id: change.labelId,
        name: change.name,
        type: "user",
        labelListVisibility: "labelShow",
        ...(change.color ? { color: change.color } : {}),
      });
    } else if (change.kind === "patchLabel") {
      shown = shown.map((label) =>
        label.id === change.labelId
          ? {
              ...label,
              ...(change.name === undefined ? {} : { name: change.name }),
              ...(change.color ? { color: change.color } : {}),
            }
          : label,
      );
    }
  }
  return shown;
}

/** Gmail's filters as the device shows them: queued new ones added, queued deletes and replaced ones gone. */
export function filtersWithPending(
  filters: readonly GmailFilter[],
  queue: readonly AccountChange[],
): GmailFilter[] {
  let shown = [...filters];
  for (const change of [...queue].sort((left, right) => left.seq - right.seq)) {
    if (change.kind === "createFilter") {
      shown = shown.filter(
        (filter) =>
          filter.id !== change.replacing && filter.id !== change.filterId,
      );
      shown.push({
        id: change.filterId,
        criteria: change.criteria,
        action: change.action,
      });
    } else if (change.kind === "deleteFilter") {
      shown = shown.filter((filter) => filter.id !== change.filterId);
    }
  }
  return shown;
}

/** How many changes haven't reached Gmail: label changes on threads and label or filter edits. */
export function unsyncedChanges(
  queue: readonly ThreadLabelChange[],
  accountQueue: readonly AccountChange[],
): number {
  return queue.length + accountQueue.length;
}
