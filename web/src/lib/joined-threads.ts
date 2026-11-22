// Gmail threads a list shows as one row (an encrypted reply Gmail filed in a thread of its own; see
// `@inbox/shared/thread-joins`), and the writes on such a row reaching each of them.

import { threadRowKey } from "@inbox/shared/list-state";
import type { NewLabelChange } from "@inbox/shared/offline-queue";
import {
  type JoinThread,
  joinedSnooze,
  joinSummaries,
  joinThreads,
} from "@inbox/shared/thread-joins";
import {
  inboxSortDate,
  type SnoozeDoc,
  type SnoozeStatus,
} from "@inbox/shared/thread-snooze";
import type { Row } from "./list-model";
import { isInInbox, type ThreadSummary } from "./thread-summary";

/** One Gmail thread's own snooze: its doc and status. */
export type OwnSnooze = { doc: SnoozeDoc | undefined; status: SnoozeStatus };

/** A label change as asked, before it is queued. */
export type AskedChange = NewLabelChange;

/** The row of a thread shown alone. */
export function threadRow(thread: ThreadSummary, snooze: OwnSnooze): Row {
  return {
    thread,
    status: snooze.status,
    snoozeUntil: snooze.doc?.snoozeUntil ?? null,
    sortDate: inboxSortDate(thread.lastDate, snooze.doc, snooze.status),
  };
}

function joinInput(thread: ThreadSummary): JoinThread {
  return {
    id: thread.id,
    hiddenSubject: thread.answers !== undefined,
    messageIds: thread.messageIds,
    answers: thread.answers ?? [],
    firstDate: thread.firstDate,
  };
}

function joinedRow(
  members: readonly ThreadSummary[],
  snoozeOf: (id: string) => OwnSnooze,
): Row {
  const thread = joinSummaries(members);
  const snooze = joinedSnooze(
    members.map((member) => {
      const { doc, status } = snoozeOf(member.id);
      return {
        status,
        snoozeUntil: doc?.snoozeUntil ?? null,
        watermark: doc?.snoozeWatermark ?? null,
        historyId: member.historyId,
        inInbox: isInInbox(member),
      };
    }),
  );
  return {
    thread,
    status: snooze.status,
    snoozeUntil: snooze.snoozeUntil,
    sortDate: inboxSortDate(
      thread.lastDate,
      { snoozeUntil: snooze.snoozeUntil, snoozeWatermark: null },
      snooze.status,
    ),
    members: members.map((member) => member.id),
  };
}

/**
 * The rows of one list's loaded threads, each thread given once: threads Gmail split apart as one
 * row under the oldest (`joinThreads`), with their summaries combined (`joinSummaries`) and one
 * snooze over them all (`joinedSnooze`); every other thread as a row of its own.
 */
export function joinedRows(
  threads: readonly ThreadSummary[],
  snoozeOf: (id: string) => OwnSnooze,
): Row[] {
  const groups = joinThreads(threads.map(joinInput));
  const groupOf = new Map<string, readonly string[]>();
  for (const group of groups) {
    for (const id of group) groupOf.set(id, group);
  }
  const byId = new Map(threads.map((thread) => [thread.id, thread]));
  return threads.flatMap((thread): Row[] => {
    const group = groupOf.get(thread.id);
    if (group === undefined) {
      return [threadRow(thread, snoozeOf(thread.id))];
    } else if (group[0] === thread.id) {
      return [
        joinedRow(
          group.map((id) => byId.get(id)!),
          snoozeOf,
        ),
      ];
    } else {
      return [];
    }
  });
}

function rowThreads(row: Row): readonly string[] {
  return row.members ?? [row.thread.id];
}

/**
 * `rows` with the open row shown whole, joined or not, though it left the list: `open` is the
 * Gmail threads it stood for when opened (lead first), joined again with any row now holding one of
 * them, from the summaries `summaryOf` gives.
 */
export function withOpenRow(
  rows: readonly Row[],
  open: readonly string[],
  summaryOf: (id: string) => ThreadSummary | undefined,
  snoozeOf: (id: string) => OwnSnooze,
): readonly Row[] {
  const held = new Set(open);
  const overlapping = rows.filter((row) =>
    rowThreads(row).some((id) => held.has(id)),
  );
  const [shown] = overlapping;
  if (
    overlapping.length === 1 &&
    shown!.thread.id === open[0] &&
    open.every((id) => rowThreads(shown!).includes(id))
  ) {
    return rows;
  }
  const ids = new Set([...open, ...overlapping.flatMap(rowThreads)]);
  const threads = [...ids].flatMap((id) => {
    const thread = summaryOf(id);
    return thread ? [thread] : [];
  });
  if (threads.length === 0) return rows;
  return [
    ...rows.filter((row) => !overlapping.includes(row)),
    ...joinedRows(threads, snoozeOf),
  ];
}

/** Which Gmail threads each joined row of a list stands for. */
export class ThreadJoins {
  readonly #rows = new Map<string, Row>();
  readonly #leads = new Map<string, string>();

  /** Read the joins off a list's rows. */
  constructor(rows: Iterable<Row>) {
    for (const row of rows) {
      if (row.members === undefined) continue;
      this.#rows.set(row.thread.id, row);
      for (const member of row.members) this.#leads.set(member, row.thread.id);
    }
  }

  /** The joined row a lead id names, or undefined for any other thread. */
  joinedRow(id: string): Row | undefined {
    return this.#rows.get(id);
  }

  /** The Gmail threads row `id` stands for, lead first: itself alone unless it leads a joined row. */
  membersOf(id: string): readonly string[] {
    return this.#rows.get(id)?.members ?? [id];
  }

  /** The id of the row a Gmail thread shows in: its lead's, else its own. */
  rowOf(id: string): string {
    return this.#leads.get(id) ?? id;
  }
}

/**
 * The thread an open thread is to become once a layout (whose row keys are `listed`) joined it
 * into another row, as when a reply opened from a notification before its original loaded: that
 * row's lead, else null.
 */
export function openRowLead(
  open: string | null,
  joins: ThreadJoins,
  listed: ReadonlySet<string>,
): string | null {
  if (open === null) return null;
  const lead = joins.rowOf(open);
  return lead !== open &&
    !listed.has(threadRowKey(open)) &&
    listed.has(threadRowKey(lead))
    ? lead
    : null;
}

/**
 * Label changes as Gmail gets them: a change asked of a joined row's lead goes to each member the
 * call doesn't name itself, narrowed to the labels it changes there (per `labelsOf`), and changes
 * that change nothing are left out.
 */
export function expandChanges(
  changes: readonly AskedChange[],
  joins: ThreadJoins,
  labelsOf: (id: string) => readonly string[] | undefined,
): AskedChange[] {
  const named = new Set(changes.map((change) => change.threadId));
  return changes
    .flatMap((change) => [
      change,
      ...joins
        .membersOf(change.threadId)
        .filter((member) => !named.has(member))
        .map((member) => {
          const labels = labelsOf(member);
          return labels === undefined
            ? { ...change, threadId: member }
            : {
                threadId: member,
                add: change.add.filter((label) => !labels.includes(label)),
                remove: change.remove.filter((label) => labels.includes(label)),
              };
        }),
    ])
    .filter((change) => change.add.length > 0 || change.remove.length > 0);
}

/**
 * Snooze writes as Firestore gets them: one asked of a joined row's lead goes to each member the
 * call doesn't name itself, a snooze there marked at that member's own history (per `historyIdOf`).
 */
export function expandSnoozes(
  entries: readonly (readonly [string, SnoozeDoc | null])[],
  joins: ThreadJoins,
  historyIdOf: (id: string) => string | undefined,
): [string, SnoozeDoc | null][] {
  const named = new Set(entries.map(([id]) => id));
  return entries.flatMap(([id, snooze]): [string, SnoozeDoc | null][] => [
    [id, snooze],
    ...joins
      .membersOf(id)
      .filter((member) => !named.has(member))
      .map((member): [string, SnoozeDoc | null] => [
        member,
        snooze === null
          ? null
          : {
              ...snooze,
              snoozeWatermark: historyIdOf(member) ?? snooze.snoozeWatermark,
            },
      ]),
  ]);
}
