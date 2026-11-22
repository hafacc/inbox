import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { epochToIso, isLocalIso, isoToEpoch } from "./local-time";

type Fixtures = {
  zone: string;
  toIso: { name: string; epoch: number; iso: string }[];
  toEpoch: { name: string; iso: string; epoch: number }[];
  isLocalIso: { value: string; valid: boolean }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/local-time-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

test("runs in the fixtures' zone", () => {
  expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(fixtures.zone);
});

describe("epochToIso", () => {
  for (const fixture of fixtures.toIso) {
    test(fixture.name, () => {
      expect(epochToIso(fixture.epoch)).toBe(fixture.iso);
    });
  }
});

describe("isoToEpoch", () => {
  for (const fixture of fixtures.toEpoch) {
    test(fixture.name, () => {
      expect(isoToEpoch(fixture.iso)).toBe(fixture.epoch);
    });
  }
});

test("isLocalIso", () => {
  for (const { value, valid } of fixtures.isLocalIso) {
    expect([value, isLocalIso(value)]).toEqual([value, valid]);
  }
});
