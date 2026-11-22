import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  between,
  idKey,
  positionAbove,
  positionBelow,
  positionBetween,
  readPosition,
  timeKey,
} from "./position";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/position-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  timeKey: { epoch: number; key: string }[];
  idKey: { id: string; key: string }[];
  readPosition: { in: unknown; out: string | null }[];
  between: { lo: string; hi: string; out: string | null }[];
  positionBetween: { lo: string; hi: string; id: string; out: string | null }[];
  positionAbove: { lo: string; id: string; out: string | null }[];
  positionBelow: { hi: string; id: string; out: string | null }[];
};

describe("position fixtures", () => {
  test("timeKey", () => {
    for (const { epoch, key } of fixtures.timeKey) {
      expect([epoch, timeKey(epoch)]).toEqual([epoch, key]);
    }
  });
  test("idKey", () => {
    for (const { id, key } of fixtures.idKey) {
      expect([id, idKey(id)]).toEqual([id, key]);
    }
  });
  test("readPosition", () => {
    for (const fixture of fixtures.readPosition) {
      expect([fixture.in, readPosition(fixture.in)]).toEqual([
        fixture.in,
        fixture.out,
      ]);
    }
  });
  test("between", () => {
    for (const { lo, hi, out } of fixtures.between) {
      expect([lo, hi, between(lo, hi)]).toEqual([lo, hi, out]);
    }
  });
  test("positionBetween", () => {
    for (const { lo, hi, id, out } of fixtures.positionBetween) {
      expect([lo, hi, positionBetween(lo, hi, id)]).toEqual([lo, hi, out]);
    }
  });
  test("positionAbove", () => {
    for (const { lo, id, out } of fixtures.positionAbove) {
      expect([lo, positionAbove(lo, id)]).toEqual([lo, out]);
    }
  });
  test("positionBelow", () => {
    for (const { hi, id, out } of fixtures.positionBelow) {
      expect([hi, positionBelow(hi, id)]).toEqual([hi, out]);
    }
  });
});
