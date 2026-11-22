import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import reserved from "../address/reserved-names.json" with { type: "json" };
import {
  addressOf,
  checkName,
  type NameCheck,
  nameOf,
  reasonText,
} from "./address-name";

type Fixtures = {
  checks: { name: string; input: string; expect: NameCheck }[];
  refusals: {
    reason: string | null;
    address: string | null;
    text: string | null;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/address-name-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

for (const fixture of fixtures.checks) {
  test(`checkName: ${fixture.name} (must match Android and the server)`, () => {
    expect(checkName(fixture.input)).toEqual(fixture.expect);
  });
}

for (const fixture of fixtures.refusals) {
  test(`reasonText: ${fixture.reason} ${fixture.address ?? ""} (must match Android)`, () => {
    expect(reasonText(fixture.reason, fixture.address)).toBe(fixture.text);
  });
}

test("every reserved name is refused, with or without its punctuation", () => {
  for (const name of [
    ...reserved.roles,
    ...reserved.subdomains,
    ...reserved.apps,
  ]) {
    expect(checkName(name)).toEqual({ ok: false, problem: "reserved" });
    const dotted = `${name.slice(0, 1)}.${name.slice(1)}`;
    expect(checkName(dotted)).toEqual({ ok: false, problem: "reserved" });
  }
});

test("addresses and names convert both ways", () => {
  expect(addressOf("test")).toBe("test@hafa.cc");
  expect(nameOf("test@hafa.cc")).toBe("test");
  expect(nameOf("ana")).toBe("ana");
});
