import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  commit,
  emptyPartition,
  type OutboxTiming,
  outboxShownAt,
  SEND_SHOWN_ROWS,
  seededRandom,
  sendRows,
  sendStatsPath,
  shiftMinutes,
  unvariedSendTimes,
  type VarySetting,
  varySendTimes,
  varySetting,
} from "./send-suggest";
import type { DevicePartition, RowIcon } from "./snooze-suggest";

// A fixture's partition: its fields as saved, missing ones empty.
function partitionOf(deviceId: string, raw: unknown): DevicePartition {
  const {
    sets = {},
    tod = {},
    lastCustom = null,
    picks = {},
  } = raw as Partial<DevicePartition>;
  return { deviceId, sets, tod, lastCustom, picks };
}

type Fixtures = {
  rows: {
    name: string;
    now: string;
    partitions: unknown[];
    expect: {
      keyId: string;
      timeLocal: string;
      text: string;
      icon: RowIcon;
      learned: boolean;
    }[];
  }[];
  shift: { unit: number; maxMinutes: number; minutes: number }[];
  random: { seed: number; first: number[] }[];
  vary: {
    name: string;
    now: string;
    seed: number;
    maxMinutes: number;
    timesLocal: string[];
    expect: { chosenAtLocal: string; sendAtLocal: string }[];
  }[];
  setting: {
    settings: Record<string, unknown> | null;
    expect: VarySetting;
  }[];
  outboxShown: {
    name: string;
    nowMs: number;
    docs: OutboxTiming[];
    expect: number | null;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/send-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

function epoch(local: string): number {
  return new Date(local).getTime();
}

describe("send fixtures (must match Android)", () => {
  for (const fixture of fixtures.outboxShown) {
    test(`outboxShownAt: ${fixture.name}`, () => {
      expect(outboxShownAt(fixture.docs, fixture.nowMs)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.rows) {
    test(`rows: ${fixture.name}`, () => {
      const partitions = fixture.partitions.map((raw, index) =>
        partitionOf(`p${index}`, raw),
      );
      const rows = sendRows(partitions, epoch(fixture.now));
      expect(
        rows.map((row) => ({
          keyId: row.keyId,
          time: row.time,
          text: row.text,
          icon: row.icon,
          learned: row.learned,
        })),
      ).toEqual(
        fixture.expect.map(({ timeLocal, ...rest }) => ({
          ...rest,
          time: epoch(timeLocal),
        })),
      );
    });
  }

  for (const fixture of fixtures.shift) {
    test(`shift: ${fixture.unit} within ${fixture.maxMinutes}`, () => {
      expect(shiftMinutes(fixture.unit, fixture.maxMinutes)).toBe(
        fixture.minutes,
      );
    });
  }

  for (const fixture of fixtures.random) {
    test(`random: seed ${fixture.seed}`, () => {
      const random = seededRandom(fixture.seed);
      expect(fixture.first.map(() => random())).toEqual(fixture.first);
    });
  }

  for (const fixture of fixtures.vary) {
    test(`vary: ${fixture.name}`, () => {
      const shifted = varySendTimes(
        fixture.timesLocal.map(epoch),
        epoch(fixture.now),
        seededRandom(fixture.seed),
        fixture.maxMinutes,
      );
      expect(shifted).toEqual(
        fixture.expect.map((entry) => ({
          chosenAt: epoch(entry.chosenAtLocal),
          sendAt: epoch(entry.sendAtLocal),
        })),
      );
    });
  }

  for (const fixture of fixtures.setting) {
    test(`setting: ${JSON.stringify(fixture.settings)}`, () => {
      expect(varySetting(fixture.settings)).toEqual(fixture.expect);
    });
  }
});

describe("varySendTimes", () => {
  for (const maxMinutes of [1, 10, 30]) {
    test(`shifts by whole minutes within ${maxMinutes}, never zero`, () => {
      const random = seededRandom(99);
      const now = epoch("2026-09-22T10:00:00");
      const time = epoch("2026-09-23T08:00:00");
      const seen = new Set<number>();
      for (let draw = 0; draw < 2000; draw++) {
        const [shifted] = varySendTimes([time], now, random, maxMinutes);
        const minutes = (shifted!.sendAt - time) / 60_000;
        expect(Number.isInteger(minutes)).toBe(true);
        expect(minutes).not.toBe(0);
        expect(Math.abs(minutes)).toBeLessThanOrEqual(maxMinutes);
        seen.add(minutes);
      }
      expect(seen.size).toBe(2 * maxMinutes);
    });
  }

  test("defaults to a ten-minute maximum", () => {
    expect(shiftMinutes(0)).toBe(-10);
    expect(shiftMinutes(0.9999)).toBe(10);
  });

  test("unvaried keeps the time", () => {
    expect(unvariedSendTimes([5])).toEqual([{ chosenAt: 5, sendAt: 5 }]);
  });
});

describe("sendRows", () => {
  test("learning a pick turns its default into a learned row", () => {
    const now = epoch("2026-09-22T10:00:00");
    const [first] = sendRows([emptyPartition("p")], now);
    const { next } = commit(
      emptyPartition("p"),
      now,
      first!.time,
      "suggestion",
      first!.keyId,
    );
    const rows = sendRows([next], now);
    expect(rows.length).toBeLessThanOrEqual(SEND_SHOWN_ROWS);
    expect(rows.find((row) => row.time === first!.time)?.learned).toBe(true);
  });

  test("stats live beside snoozeStats", () => {
    expect(sendStatsPath("u")).toEqual(["users", "u", "meta", "sendStats"]);
  });
});
