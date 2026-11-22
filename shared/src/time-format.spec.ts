import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  formatClock,
  formatListDate,
  formatMessageDate,
  formatPickedTime,
  formatSize,
  formatWakeTime,
  lapsedMarker,
} from "./time-format";

type DateCase = { now: string; date: string; expect: string };

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/time-format-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  listDate: DateCase[];
  messageDate: DateCase[];
  wakeTime: DateCase[];
  lapsedMarker: DateCase[];
  size: { bytes: number; expect: string }[];
};

const formats: Record<
  "listDate" | "messageDate" | "wakeTime" | "lapsedMarker",
  (epoch: number, now: number) => string
> = {
  listDate: formatListDate,
  messageDate: formatMessageDate,
  wakeTime: formatWakeTime,
  lapsedMarker,
};

describe("time format fixtures (must match Android)", () => {
  for (const [section, format] of Object.entries(formats)) {
    for (const fixture of fixtures[section as keyof typeof formats]) {
      test(`${section} ${fixture.date} at ${fixture.now}`, () => {
        expect(
          format(
            new Date(fixture.date).getTime(),
            new Date(fixture.now).getTime(),
          ),
        ).toBe(fixture.expect);
      });
    }
  }
  for (const fixture of fixtures.size) {
    test(`size ${fixture.bytes}`, () => {
      expect(formatSize(fixture.bytes)).toBe(fixture.expect);
    });
  }
});

test("clock times are 24-hour", () => {
  expect(formatClock(new Date(2026, 10, 2, 19, 5).getTime())).toBe("19:05");
  expect(formatClock(new Date(2026, 10, 2, 0, 0).getTime())).toBe("00:00");
});

test("the picker's summary names the day and the 24-hour time", () => {
  expect(formatPickedTime(new Date(2026, 10, 7, 10).getTime())).toBe(
    "Sat, Nov 7 at 10:00",
  );
  expect(formatPickedTime(new Date(2026, 10, 7, 21, 30).getTime())).toBe(
    "Sat, Nov 7 at 21:30",
  );
});
