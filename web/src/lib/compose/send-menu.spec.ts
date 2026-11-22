import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { sendRows } from "@inbox/shared/send-suggest";
import { sendMenuItems } from "./send-menu";

type VaryFixture = {
  name: string;
  now: string;
  seed: number;
  maxMinutes: number;
  timesLocal: string[];
  expect: { chosenAtLocal: string; sendAtLocal: string }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../../../shared/testdata/send-fixtures.json", import.meta.url),
    "utf8",
  ),
) as { vary: VaryFixture[] };

function epoch(local: string): number {
  return new Date(local).getTime();
}

describe("send fixtures through the menu (must match Android)", () => {
  for (const fixture of fixtures.vary) {
    test(`vary: ${fixture.name}`, () => {
      const rows = fixture.timesLocal.map((local, index) => ({
        time: epoch(local),
        text: `row ${index}`,
        icon: "tomorrow" as const,
        keyId: `k${index}`,
        score: 0,
        learned: false,
      }));
      const items = sendMenuItems(
        rows,
        epoch(fixture.now),
        { enabled: true, maxMinutes: fixture.maxMinutes },
        fixture.seed,
      );
      expect(
        items.map((item) => ({ chosenAt: item.chosenAt, sendAt: item.sendAt })),
      ).toEqual(
        fixture.expect.map((entry) => ({
          chosenAt: epoch(entry.chosenAtLocal),
          sendAt: epoch(entry.sendAtLocal),
        })),
      );
    });
  }
});

test("off, the menu shows the suggestions as they are", () => {
  const now = epoch("2026-09-22T10:00:00");
  const items = sendMenuItems(
    sendRows([], now),
    now,
    { enabled: false, maxMinutes: 10 },
    1,
  );
  expect(items.map((item) => [item.keyId, item.text])).toEqual([
    ["D0@1300", "This afternoon"],
    ["D0@0800", "Tomorrow morning"],
    ["Wd1@0800", "This Monday morning"],
  ]);
  for (const item of items) expect(item.sendAt).toBe(item.chosenAt);
});

test("on, the same seed gives the same times and every shift is a whole minute", () => {
  const now = epoch("2026-09-22T10:00:00");
  const rows = sendRows([], now);
  const vary = { enabled: true, maxMinutes: 10 };
  const first = sendMenuItems(rows, now, vary, 99);
  expect(sendMenuItems(rows, now, vary, 99)).toEqual(first);
  for (const item of first) {
    const minutes = (item.sendAt - item.chosenAt) / 60_000;
    expect(Number.isInteger(minutes)).toBe(true);
    expect(minutes).not.toBe(0);
    expect(Math.abs(minutes)).toBeLessThanOrEqual(10);
  }
});
