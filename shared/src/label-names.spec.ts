import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  compareLabelNames,
  labelNameError,
  leafName,
  type NamedLabel,
  nestedName,
  parentName,
  renames,
} from "./label-names";

type Fixtures = {
  labels: NamedLabel[];
  sort: { names: string[]; expect: string[] };
  parts: { name: string; parent: string | null; leaf: string }[];
  nested: { parent: string | null; leaf: string; expect: string }[];
  renames: {
    name: string;
    id: string;
    newName: string;
    expect: { id: string; name: string }[];
  }[];
  errors: {
    name: string;
    text: string;
    exceptId: string | null;
    expect: string | null;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/label-name-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

test("sort", () => {
  expect([...fixtures.sort.names].sort(compareLabelNames)).toEqual(
    fixtures.sort.expect,
  );
});

test("parts", () => {
  for (const fixture of fixtures.parts) {
    expect([parentName(fixture.name), leafName(fixture.name)]).toEqual([
      fixture.parent,
      fixture.leaf,
    ]);
  }
});

test("nested", () => {
  for (const fixture of fixtures.nested) {
    expect(nestedName(fixture.parent, fixture.leaf)).toBe(fixture.expect);
  }
});

describe("renames", () => {
  for (const fixture of fixtures.renames) {
    test(fixture.name, () => {
      expect(renames(fixtures.labels, fixture.id, fixture.newName)).toEqual(
        fixture.expect,
      );
    });
  }
});

describe("errors", () => {
  for (const fixture of fixtures.errors) {
    test(fixture.name, () => {
      expect(
        labelNameError(fixtures.labels, fixture.text, fixture.exceptId),
      ).toBe(fixture.expect);
    });
  }
});
