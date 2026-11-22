import { expect, test } from "bun:test";
import {
  inboxSections,
  layoutKeys,
  layoutSections,
} from "@inbox/shared/list-model";
import {
  emptyListState,
  type ListEvent,
  type ListState,
  listStep,
  threadRowKey,
} from "@inbox/shared/list-state";
import { applyChange } from "@inbox/shared/offline-queue";
import type { SnoozeDoc } from "@inbox/shared/thread-snooze";
import type { Todo } from "@inbox/shared/todos";
import { undoChange, undoLabels } from "@inbox/shared/undo";
import {
  type AskedChange,
  expandChanges,
  expandSnoozes,
  joinedRows,
  type OwnSnooze,
  openRowLead,
  ThreadJoins,
  withOpenRow,
} from "./joined-threads";
import type { Row } from "./list-model";
import type { ThreadSummary } from "./thread-summary";

const NOW = new Date(2026, 10, 2, 11, 50).getTime();
const HOUR = 60 * 60 * 1000;

function summary(
  id: string,
  hoursAgo: number,
  extra: Partial<ThreadSummary> = {},
): ThreadSummary {
  return {
    id,
    historyId: "100",
    subject: id,
    snippet: `snippet ${id}`,
    senders: [{ name: id, email: `${id}@x.example.com` }],
    messageCount: 1,
    lastDate: NOW - hoursAgo * HOUR,
    sentDate: 0,
    labelIds: ["INBOX"],
    unread: false,
    messageIds: [`<${id}@x>`],
    firstDate: NOW - hoursAgo * HOUR,
    ...extra,
  };
}

// An encrypted thread and the reply Gmail filed in a thread of its own.
const original = summary("a", 5, { subject: "...", historyId: "100" });
const reply = summary("b", 1, {
  subject: "...",
  historyId: "130",
  labelIds: ["INBOX", "UNREAD"],
  unread: true,
  answers: ["<a@x>"],
  firstDate: NOW - HOUR,
});
const other = summary("c", 3);

const noSnooze = (): OwnSnooze => ({ doc: undefined, status: "none" });

test("a list with a split encrypted reply shows it and its original as one row", () => {
  const rows = joinedRows([reply, other, original], noSnooze);
  expect(rows.map((row) => row.thread.id)).toEqual(["c", "a"]);
  const joined = rows[1]!;
  expect(joined.members).toEqual(["a", "b"]);
  expect(joined.thread).toMatchObject({
    id: "a",
    historyId: "130",
    snippet: "snippet b",
    messageCount: 2,
    lastDate: NOW - HOUR,
    labelIds: ["INBOX", "UNREAD"],
    unread: true,
  });
  expect(rows[0]!.members).toBeUndefined();

  const sections = inboxSections(rows, NOW, []);
  const shown = sections.flatMap((section) =>
    section.entries.flatMap((entry) =>
      entry.kind === "thread" ? [entry.thread.thread.id] : [],
    ),
  );
  expect(shown).toEqual(["a", "c"]);
});

test("members of a joined row stay out of bundles of their own", () => {
  const labeled = (thread: ThreadSummary, hoursAgo: number) => ({
    ...thread,
    lastDate: NOW - hoursAgo * HOUR,
    labelIds: [...thread.labelIds, "Label_1"],
  });
  const rows = joinedRows(
    [
      labeled(original, 5),
      labeled(reply, 1),
      labeled(summary("d", 2), 2),
      labeled(summary("e", 4), 4),
    ],
    noSnooze,
  );
  const sections = inboxSections(rows, NOW, ["Label_1"]);
  const bundled = sections.flatMap((section) =>
    section.entries.flatMap((entry) =>
      entry.kind === "group"
        ? entry.threads.map((row) => row.thread.id)
        : entry.kind === "thread"
          ? [entry.thread.thread.id]
          : [],
    ),
  );
  expect(bundled.sort()).toEqual(["a", "d", "e"]);
});

test("a reply Gmail split off after a snooze ends it", () => {
  const snoozed: SnoozeDoc = {
    snoozeUntil: NOW + HOUR,
    snoozeWatermark: "120",
  };
  const snoozeOf = (id: string): OwnSnooze =>
    id === "a"
      ? { doc: snoozed, status: "snoozed" }
      : { doc: undefined, status: "none" };
  const [row] = joinedRows([original, reply], snoozeOf);
  expect(row!.status).toBe("woken");
  expect(row!.snoozeUntil).toBe(NOW + HOUR);

  const both = (id: string): OwnSnooze =>
    id === "a" || id === "b" ? { doc: snoozed, status: "snoozed" } : noSnooze();
  expect(joinedRows([original, reply], both)[0]!.status).toBe("snoozed");
});

test("Done on a joined row and its undo reach every member", () => {
  const joins = new ThreadJoins(joinedRows([original, reply], noSnooze));
  const labels = new Map([
    ["a", ["INBOX", "Label_2"]],
    ["b", ["INBOX", "UNREAD"]],
  ]);
  const labelsOf = (id: string) => labels.get(id);
  const apply = (changes: readonly AskedChange[]) => {
    for (const change of changes) {
      labels.set(
        change.threadId,
        applyChange(labels.get(change.threadId)!, change),
      );
    }
  };
  // Undo holds each member as it was.
  const before = new Map(labels);
  const done = expandChanges(
    [{ threadId: "a", add: [], remove: ["INBOX"] }],
    joins,
    labelsOf,
  );
  expect(done.map((change) => change.threadId)).toEqual(["a", "b"]);
  apply(done);
  expect(labels.get("a")).toEqual(["Label_2"]);
  expect(labels.get("b")).toEqual(["UNREAD"]);

  const restore = undoLabels("done", []);
  const undo = expandChanges(
    [...before].map(([threadId, was]) => ({
      threadId,
      ...undoChange(was, labels.get(threadId)!, restore),
    })),
    joins,
    labelsOf,
  );
  apply(undo);
  expect(labels.get("a")).toContain("INBOX");
  expect(labels.get("b")).toContain("INBOX");
});

test("a member named on its own keeps its own change, and no-ops are left out", () => {
  const joins = new ThreadJoins(joinedRows([original, reply], noSnooze));
  const labelsOf = (id: string) =>
    id === "a" ? ["INBOX", "STARRED"] : ["INBOX"];
  expect(
    expandChanges(
      [
        { threadId: "a", add: [], remove: ["STARRED"] },
        { threadId: "b", add: [], remove: [] },
      ],
      joins,
      labelsOf,
    ),
  ).toEqual([{ threadId: "a", add: [], remove: ["STARRED"] }]);
  expect(joins.rowOf("b")).toBe("a");
  expect(joins.membersOf("c")).toEqual(["c"]);
});

test("snoozing a joined row snoozes each member at its own history", () => {
  const joins = new ThreadJoins(joinedRows([original, reply], noSnooze));
  const historyIds = new Map([
    ["a", "100"],
    ["b", "130"],
  ]);
  expect(
    expandSnoozes(
      [["a", { snoozeUntil: NOW + HOUR, snoozeWatermark: "100" }]],
      joins,
      (id) => historyIds.get(id),
    ),
  ).toEqual([
    ["a", { snoozeUntil: NOW + HOUR, snoozeWatermark: "100" }],
    ["b", { snoozeUntil: NOW + HOUR, snoozeWatermark: "130" }],
  ]);
  expect(expandSnoozes([["a", null]], joins, () => undefined)).toEqual([
    ["a", null],
    ["b", null],
  ]);
});

test("an open joined row stays joined when its oldest thread leaves the list", () => {
  const summaries = new Map(
    [original, reply, other].map((thread) => [thread.id, thread]),
  );
  // The original left the list; only the reply and another thread are loaded.
  const rows = joinedRows([reply, other], noSnooze);
  expect(rows.map((row) => row.thread.id)).toEqual(["b", "c"]);
  const shown = withOpenRow(
    rows,
    ["a", "b"],
    (id) => summaries.get(id),
    noSnooze,
  );
  expect(shown.map((row) => [row.thread.id, row.members])).toEqual([
    ["c", undefined],
    ["a", ["a", "b"]],
  ]);
  const whole = joinedRows([original, reply, other], noSnooze);
  expect(
    withOpenRow(whole, ["a", "b"], (id) => summaries.get(id), noSnooze),
  ).toBe(whole);
});

// The inbox list's layout event for `rows`.
function layoutOf(rows: readonly Row[]): ListEvent<Row, Todo> {
  return {
    type: "layout",
    sections: layoutSections(inboxSections(rows, NOW, []), () => ({
      more: false,
      loading: false,
    })),
  };
}

test("a reply opened before its original loaded opens as the joined row once it lands", () => {
  const summaries = new Map(
    [original, reply, other].map((thread) => [thread.id, thread]),
  );
  let list: ListState<Row, Todo> = listStep(
    listStep(emptyListState(), layoutOf(joinedRows([other], noSnooze))),
    { type: "openThread", id: "b" },
  );
  expect(list.open).toBe("b");
  // The refresh brings the reply and its original, which join as one row led by the original.
  const rows = withOpenRow(
    joinedRows([original, reply, other], noSnooze),
    ["b"],
    (id) => summaries.get(id),
    noSnooze,
  );
  list = listStep(list, layoutOf(rows));
  const lead = openRowLead(
    list.open,
    new ThreadJoins(rows),
    layoutKeys(list.layout),
  );
  expect(lead).toBe("a");
  list = listStep(list, { type: "openThread", id: lead! });
  expect(list.open).toBe("a");
  expect(list.rows.some((row) => row.key === threadRowKey("a"))).toBe(true);
  expect(
    openRowLead(list.open, new ThreadJoins(rows), layoutKeys(list.layout)),
  ).toBeNull();
});

test("Done from a notification on a joined row's reply takes the row out", () => {
  const rows = joinedRows([original, reply, other], noSnooze);
  let list: ListState<Row, Todo> = listStep(emptyListState(), layoutOf(rows));
  const key = threadRowKey(new ThreadJoins(rows).rowOf("b"));
  expect(key).toBe(threadRowKey("a"));
  list = listStep(list, {
    type: "act",
    removes: [key],
    from: "notification",
    undoable: false,
    clearSelection: false,
  });
  expect(list.rows.some((row) => row.key === key)).toBe(false);
});
