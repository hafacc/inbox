import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  addressNoticeState,
  addressTrouble,
  type SetupState,
} from "./address-notice";

type Fixtures = {
  cases: {
    name: string;
    setup: SetupState | null;
    now: number;
    expect: "error" | "stalled" | null;
    notice: "working" | "error" | null;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/address-notice-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

for (const fixture of fixtures.cases) {
  test(`addressTrouble: ${fixture.name} (must match Android)`, () => {
    expect(addressTrouble(fixture.setup, fixture.now)).toBe(fixture.expect);
    expect(addressNoticeState(fixture.setup, fixture.now)).toBe(fixture.notice);
  });
}
