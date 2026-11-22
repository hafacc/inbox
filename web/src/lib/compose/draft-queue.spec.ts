import { expect, test } from "bun:test";
import { afterSave, draftGone, opFor, queueSave } from "./draft-queue";

const BASE = {
  localId: "l1",
  draftId: null,
  threadId: null,
  from: "a@x.example.net",
  raw: "r1",
  send: null,
};

test("the first save creates; once there's an id, saves update", () => {
  const first = queueSave(undefined, BASE);
  expect(first.version).toBe(1);
  expect(opFor(first).kind).toBe("create");
  expect(opFor({ ...first, draftId: "d1" })).toEqual({
    kind: "update",
    job: { ...first, draftId: "d1" },
    draftId: "d1",
  });
});

test("a newer save keeps the known draft id and a send already asked for", () => {
  const queued = {
    ...queueSave(undefined, BASE),
    draftId: "d1",
    send: { kind: "now" as const, outboxId: null, queuedAt: 1 },
  };
  const next = queueSave(queued, { ...BASE, raw: "r2" });
  expect(next).toMatchObject({
    draftId: "d1",
    raw: "r2",
    send: { kind: "now" },
    version: 2,
  });
});

test("a save that finished is done unless a newer one was queued meanwhile", () => {
  const job = queueSave(undefined, BASE);
  expect(afterSave(job, job.version, "d1")).toBeNull();
  const newer = queueSave(job, { ...BASE, raw: "r2" });
  expect(afterSave(newer, job.version, "d1")).toEqual({
    ...newer,
    draftId: "d1",
  });
});

test("only a 404 means the draft is gone", () => {
  expect(draftGone(404)).toBe(true);
  expect(draftGone(400)).toBe(false);
});
