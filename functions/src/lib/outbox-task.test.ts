import { describe, expect, it } from "bun:test";
import {
  outboxFromDoc,
  parseSendTask,
  pendingSendTasks,
  planOutboxWrite,
  type SendTask,
  sendHopTime,
  sendTaskId,
} from "./outbox-task.js";
import { MAX_TASK_DELAY_MS } from "./task-legs.js";
import { millisOf } from "./util.js";

const DAY = 24 * 60 * 60 * 1000;

function task(
  sendAt: number,
  writtenAt: number,
  hop = 1,
  attempt = 0,
): SendTask {
  return { uid: "u", outboxId: "o", sendAt, writtenAt, hop, attempt };
}

describe("sendTaskId", () => {
  it("is stable and differs per leg, write and attempt", () => {
    expect(sendTaskId(task(10, 1))).toBe(sendTaskId(task(10, 1)));
    expect(sendTaskId(task(10, 1))).not.toBe(sendTaskId(task(10, 2)));
    expect(sendTaskId(task(10, 1))).not.toBe(sendTaskId(task(10, 1, 2)));
    expect(sendTaskId(task(10, 1))).toMatch(/^[0-9a-f]{40}$/);
  });

  it("keeps a first attempt's id and sets retries and lease checks apart", () => {
    const first = sendTaskId(task(10, 1));
    const retry = sendTaskId(task(10, 1, 1, 1));
    expect(retry).not.toBe(first);
    expect(sendTaskId(task(10, 1, 1, 2))).not.toBe(retry);
    expect(sendTaskId(task(10, 1, 1, 1), "lease:5")).not.toBe(retry);
    expect(sendTaskId(task(10, 1, 1, 1))).toBe(retry);
  });
});

describe("sendHopTime", () => {
  it("splits a send far out into legs within the Cloud Tasks limit", () => {
    expect(sendHopTime(task(5 * DAY, 0))).toBe(5 * DAY);
    expect(sendHopTime(task(40 * DAY, 0, 1))).toBe(MAX_TASK_DELAY_MS);
    expect(sendHopTime(task(40 * DAY, 0, 2))).toBe(40 * DAY);
  });
});

describe("pendingSendTasks", () => {
  it("covers the leg queued now and its neighbors", () => {
    const hops = pendingSendTasks(
      "u",
      "o",
      { sendAt: 100 * DAY, writtenAt: 0 },
      40 * DAY,
    ).map((entry) => entry.hop);
    expect(hops).toEqual([1, 2, 3]);
  });
});

describe("planOutboxWrite", () => {
  const at = (sendAt: number, writtenAt = 1) => ({ sendAt, writtenAt });

  it("schedules a new waiting entry and nothing for a deleted one", () => {
    expect(
      planOutboxWrite("u", "o", null, { status: "waiting", waiting: at(10) }, 0)
        .schedule,
    ).toEqual(task(10, 1));
    expect(
      planOutboxWrite(
        "u",
        "o",
        { status: "waiting", waiting: at(10) },
        null,
        0,
      ),
    ).toEqual({ cancel: [task(10, 1)], schedule: null });
  });

  it("leaves an unchanged time alone", () => {
    expect(
      planOutboxWrite(
        "u",
        "o",
        { status: "waiting", waiting: at(10, 1) },
        { status: "waiting", waiting: at(10, 2) },
        0,
      ),
    ).toEqual({ cancel: [], schedule: null });
  });

  it("schedules a blocked or failed entry turned back into waiting", () => {
    for (const status of ["blocked", "failed"] as const) {
      expect(
        planOutboxWrite(
          "u",
          "o",
          { status, waiting: at(10, 1) },
          { status: "waiting", waiting: at(10, 2) },
          0,
        ),
      ).toEqual({ cancel: [], schedule: task(10, 2) });
    }
  });

  it("needs no task when an attempt blocks", () => {
    expect(
      planOutboxWrite(
        "u",
        "o",
        { status: "sending", waiting: at(10, 1) },
        { status: "blocked", waiting: at(10, 2) },
        0,
      ),
    ).toEqual({ cancel: [], schedule: null });
  });
});

describe("outboxFromDoc", () => {
  it("reads Timestamps", () => {
    const stamp = { toMillis: () => 42 };
    expect(
      outboxFromDoc({
        status: "waiting",
        draftId: "d",
        from: "a@b.c",
        sendAt: stamp,
        chosenAt: stamp,
      }),
    ).toEqual({
      status: "waiting",
      draftId: "d",
      from: "a@b.c",
      sendAt: 42,
      claimedAt: null,
      via: null,
      sentAt: null,
      messageIdHeader: null,
      filed: false,
      attempts: 0,
      firstAttemptAt: null,
      nextAttemptAt: null,
      inFlightAt: null,
      uncertainSince: null,
      draftMessageId: null,
      messageDate: null,
      idempotencyKey: null,
      blockedOn: null,
      errorCode: null,
      deviceClaim: null,
    });
    expect(
      outboxFromDoc({
        status: "retrying",
        draftId: "d",
        from: "a@b.c",
        sendAt: { toMillis: () => 1 },
        attempts: 3,
        nextAttemptAt: stamp,
        idempotencyKey: "k",
      }),
    ).toMatchObject({
      status: "retrying",
      attempts: 3,
      nextAttemptAt: 42,
      idempotencyKey: "k",
    });
    expect(
      outboxFromDoc({
        status: "blocked",
        draftId: "d",
        from: "a@b.c",
        sendAt: { toMillis: () => 1 },
        blockedOn: "sign-in",
      }),
    ).toMatchObject({ status: "blocked", blockedOn: "sign-in" });
    expect(
      outboxFromDoc({
        status: "failed",
        draftId: "d",
        from: "a@b.c",
        sendAt: { toMillis: () => 1 },
        error: { code: "uncertain", message: "x" },
      }),
    ).toMatchObject({ errorCode: "uncertain", blockedOn: null });
    expect(millisOf(7)).toBeUndefined();
    expect(millisOf("7")).toBeUndefined();
  });

  it("rejects malformed docs", () => {
    expect(
      outboxFromDoc({ status: "waiting", draftId: "d", from: "a", sendAt: 1 }),
    ).toBeUndefined();
    expect(outboxFromDoc(undefined)).toBeUndefined();
    expect(
      outboxFromDoc({
        status: "queued",
        draftId: "d",
        from: "a",
        sendAt: { toMillis: () => 1 },
      }),
    ).toBeUndefined();
    expect(
      outboxFromDoc({
        status: "waiting",
        draftId: "",
        from: "a",
        sendAt: { toMillis: () => 1 },
      }),
    ).toBeUndefined();
    expect(
      outboxFromDoc({ status: "waiting", draftId: "d", from: "a" }),
    ).toBeUndefined();
  });
});

describe("parseSendTask", () => {
  it("accepts a well-formed payload and rejects others", () => {
    expect(parseSendTask(task(10, 1))).toEqual(task(10, 1));
    expect(parseSendTask(task(10, 1, 1, 4))).toEqual(task(10, 1, 1, 4));
    const { attempt: _attempt, ...withoutAttempt } = task(10, 1);
    expect(parseSendTask(withoutAttempt)).toBeUndefined();
    expect(parseSendTask({ ...task(10, 1), attempt: -1 })).toBeUndefined();
    expect(parseSendTask({ ...task(10, 1), hop: 0 })).toBeUndefined();
    expect(parseSendTask({ ...task(10, 1), outboxId: "" })).toBeUndefined();
    expect(parseSendTask(null)).toBeUndefined();
  });
});
