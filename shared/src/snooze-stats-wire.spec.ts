import { describe, expect, test } from "bun:test";
import {
  globalPickCounts,
  SHARED_SETS_FIELD,
  sharedFromWire,
  voteDeltas,
} from "./snooze-stats-wire";
import { commit, emptyPartition, rankDeep } from "./snooze-suggest";

describe("globalPickCounts", () => {
  test("none first, then ranks 1..20, missing as 0", () => {
    const counts = globalPickCounts({ none: 4, "1": 7, "20": 2, key: "1" });
    expect(counts).toHaveLength(21);
    expect(counts[0]).toBe(4);
    expect(counts[1]).toBe(7);
    expect(counts[2]).toBe(0);
    expect(counts[20]).toBe(2);
  });

  test("a missing doc is all zeros", () => {
    expect(globalPickCounts(undefined)).toEqual(Array(21).fill(0));
  });
});

describe("shared counts", () => {
  const at = new Date(2026, 8, 22, 10, 0).getTime();
  const day = 24 * 60 * 60 * 1000;
  let block = emptyPartition("d");
  block = commit(block, at, at + day, "custom").next;
  block = commit(block, at + day, at + 3 * day, "custom").next;
  block = commit(block, at + 2 * day, at + 40 * day, "custom").next;

  test("counts saved scaled rank the same as the block they came from", () => {
    const deltas = voteDeltas(emptyPartition("d"), block);
    const shared = sharedFromWire({
      [SHARED_SETS_FIELD]: deltas.sets,
      tod: deltas.tod,
    });
    const now = at + 30 * day;
    const strip = (rows: ReturnType<typeof rankDeep>) =>
      rows.map((row) => [row.keyId, row.time, row.score.toFixed(9)]);
    expect(strip(rankDeep([shared], now))).toEqual(
      strip(rankDeep([block], now)),
    );
  });

  test("counts at or below zero and malformed ids are dropped", () => {
    const shared = sharedFromWire({
      [SHARED_SETS_FIELD]: {
        "D0@0900": 2,
        "D0@1000": 0,
        "D0@1100": -1,
        bogus: 3,
      },
      tod: { "0900": 1, "0901": 1 },
    });
    expect(Object.keys(shared.sets)).toEqual(["D0@0900"]);
    expect(Object.keys(shared.tod)).toEqual(["0900"]);
  });

  test("set counts under sets are not read", () => {
    const shared = sharedFromWire({ sets: { "D0@0900": 2 } });
    expect(shared.sets).toEqual({});
  });
});
