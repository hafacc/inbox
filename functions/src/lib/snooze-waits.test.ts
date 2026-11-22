import { describe, expect, it } from "bun:test";
import {
  orphanWaits,
  phonesToPing,
  readWaits,
  type SnoozeWait,
  todoItemId,
  todoSnoozeUntil,
} from "./snooze-waits.js";

const at = (millis: number) => ({ toMillis: () => millis });

describe("readWaits", () => {
  it("reads each phone's entry and skips malformed ones", () => {
    expect(
      readWaits({
        waits: {
          phone1: {
            itemId: "t1",
            snoozeUntil: "2027-01-15T08:00:00",
            updatedAt: at(5),
          },
          phone2: { updatedAt: at(6) },
          phone3: { itemId: "t1" },
          phone4: "x",
        },
      }),
    ).toEqual(
      new Map<string, SnoozeWait>([
        [
          "phone1",
          { itemId: "t1", snoozeUntil: "2027-01-15T08:00:00", updatedAt: 5 },
        ],
        ["phone2", { updatedAt: 6 }],
      ]),
    );
    expect(readWaits(undefined)).toEqual(new Map());
  });
});

describe("phonesToPing", () => {
  const waits = new Map<string, SnoozeWait>([
    [
      "waiting",
      { itemId: "t1", snoozeUntil: "2027-01-15T08:00:00", updatedAt: 100 },
    ],
    ["idle", { updatedAt: 100 }],
  ]);

  it("pings a phone whose awaited snooze moved or went", () => {
    expect(phonesToPing(waits, "t1", "2027-01-15T09:00:00", 50)).toEqual([
      "waiting",
    ]);
    expect(phonesToPing(waits, "t1", null, 200)).toEqual(["waiting"]);
    expect(phonesToPing(waits, "t1", "2027-01-15T08:00:00", 200)).toEqual([
      "idle",
    ]);
  });

  it("pings for an earlier snooze, or any for an idle phone, written after the phone looked", () => {
    expect(phonesToPing(waits, "t2", "2027-01-15T07:00:00", 200)).toEqual([
      "waiting",
      "idle",
    ]);
    expect(phonesToPing(waits, "t2", "2027-01-15T09:00:00", 200)).toEqual([
      "idle",
    ]);
    expect(phonesToPing(waits, "t2", "2027-01-15T07:00:00", 100)).toEqual([]);
    expect(phonesToPing(waits, "t2", null, 200)).toEqual([]);
  });
});

describe("todoSnoozeUntil", () => {
  it("is the snooze of a live todo not done", () => {
    expect(
      todoSnoozeUntil({
        snoozeUntil: "2027-01-15T08:00:00",
        state: "ACTIVE",
        deleted: false,
      }),
    ).toBe("2027-01-15T08:00:00");
  });

  it("is null once done, deleted, gone or never snoozed", () => {
    const until = "2027-01-15T08:00:00";
    expect(todoSnoozeUntil({ snoozeUntil: until, state: "DONE" })).toBeNull();
    expect(todoSnoozeUntil({ snoozeUntil: until, deleted: true })).toBeNull();
    expect(todoSnoozeUntil(undefined)).toBeNull();
    expect(todoSnoozeUntil({ snoozeUntil: null, state: "ACTIVE" })).toBeNull();
  });

  it("names todos apart from threads", () => {
    expect(todoItemId("abc")).toBe("todo:abc");
  });
});

describe("orphanWaits", () => {
  it("names the phones whose device doc is gone", () => {
    const waits = new Map([
      ["kept", { updatedAt: 1 }],
      [
        "gone",
        { itemId: "t1", snoozeUntil: "2026-11-10T08:00:00", updatedAt: 2 },
      ],
    ]);
    expect(orphanWaits(waits, ["kept", "web-only"])).toEqual(["gone"]);
    expect(orphanWaits(waits, ["kept", "gone"])).toEqual([]);
  });
});
