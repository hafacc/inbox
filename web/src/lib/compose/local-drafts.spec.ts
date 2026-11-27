import { expect, test } from "bun:test";
import type { LocalDraftState } from "@inbox/shared/thread-drafts";
import { blankSnapshot } from "./compose-model";
import {
  closeThrowsAway,
  composersOf,
  deletedDraftOf,
  emptyComposers,
  localDraftOf,
  unsyncedDrafts,
} from "./local-drafts";

test("a composer's contents make its local draft", () => {
  const snapshot = {
    ...blankSnapshot("c1", "test@hafa.cc"),
    threadId: "t1",
    messageId: "m9",
    draftThreadId: "t1",
    markdown: "Thanks",
    bcc: [{ name: "", email: "sam@example.com" }],
    subject: "Lunch",
  };
  expect(localDraftOf(snapshot, "sending", 42)).toEqual({
    localId: "c1",
    messageId: "m9",
    state: "sending",
    updatedAt: 42,
    threadId: "t1",
    draftThreadId: "t1",
    from: "test@hafa.cc",
    to: [],
    cc: [],
    bcc: [{ name: "", email: "sam@example.com" }],
    subject: "Lunch",
    snippet: "Thanks",
  });
});

test("queued saves and undeleted discards count as unsynced drafts, each once", () => {
  const draft = (localId: string, state: LocalDraftState) =>
    localDraftOf(blankSnapshot(localId, "test@hafa.cc"), state, 0);
  expect(unsyncedDrafts([], [])).toBe(0);
  expect(
    unsyncedDrafts(
      ["c1", "c2"],
      [
        draft("c1", "deleted"),
        draft("c3", "deleted"),
        draft("c4", "draft"),
        draft("c5", "sending"),
      ],
    ),
  ).toBe(3);
});

test("a pending delete kept on the device is a deleted local draft that counts as unsynced", () => {
  const deleted = deletedDraftOf({
    localId: "c1",
    draftId: "d1",
    messageId: "m1",
    threadId: "t1",
    deleted: true,
  });
  expect(deleted).toEqual({
    localId: "c1",
    messageId: "m1",
    state: "deleted",
    updatedAt: 0,
    threadId: "t1",
    from: "",
    to: [],
    cc: [],
    bcc: [],
    subject: "",
    snippet: "",
  });
  expect(unsyncedDrafts([], [deleted])).toBe(1);
});

test("closing a blank message Gmail has no draft of throws it away, a queued save with it", () => {
  const blank = blankSnapshot("c1", "test@hafa.cc");
  expect(closeThrowsAway(blank, null)).toBe(true);
  // Gmail has it (perhaps only through a save that landed after the composer last heard): kept.
  expect(closeThrowsAway(blank, "d1")).toBe(false);
  expect(closeThrowsAway({ ...blank, subject: "Lunch" }, null)).toBe(false);
});

test("blank kept composers with no Gmail draft, queued save or open composer are forgotten", () => {
  const blank = blankSnapshot("c1", "test@hafa.cc");
  const kept: [string, typeof blank][] = [
    ["c1", blank],
    ["c2", { ...blank, localId: "c2", draftId: "d2" }],
    ["c3", { ...blank, localId: "c3", markdown: "Hi" }],
    ["c4", { ...blank, localId: "c4" }],
    ["c5", { ...blank, localId: "c5" }],
  ];
  expect(
    emptyComposers(
      kept,
      (localId) => localId === "c4",
      (localId) => localId === "c5",
    ),
  ).toEqual(["c1"]);
});

test("Gmail's ids reach every composer of a draft, one put away too, each once", () => {
  const open = { localId: "c1", where: "open" };
  const closed = { localId: "c1", where: "closed" };
  const other = { localId: "c2", where: "open" };
  expect(composersOf("c1", [open, null, other, closed, open])).toEqual([
    open,
    closed,
  ]);
  expect(composersOf("c3", [open, null])).toEqual([]);
});
