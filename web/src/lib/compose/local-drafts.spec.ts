import { expect, test } from "bun:test";
import type { LocalDraftState } from "@inbox/shared/thread-drafts";
import { blankSnapshot } from "./compose-model";
import { deletedDraftOf, localDraftOf, unsyncedDrafts } from "./local-drafts";

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
