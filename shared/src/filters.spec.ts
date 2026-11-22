import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  actionFromGmail,
  actionToGmail,
  conditionsToCriteria,
  criteriaToConditions,
  criteriaToSearch,
  editorToFilter,
  type FilterActions,
  type FilterConditions,
  type FilterRow,
  filterIsWhole,
  filterToEditor,
  type GmailFilterAction,
  type GmailFilterCriteria,
  labelChanges,
  parseSize,
  rowError,
} from "./filters";

type Fixtures = {
  rowError: { row: FilterRow; expect: string | null }[];
  size: { value: string; bytes: number | null }[];
  build: {
    name: string;
    conditions: FilterConditions;
    expect: GmailFilterCriteria;
  }[];
  parse: {
    name: string;
    criteria: GmailFilterCriteria;
    expect: FilterConditions;
  }[];
  search: { criteria: GmailFilterCriteria; expect: string }[];
  actions: {
    name: string;
    gmail: GmailFilterAction;
    actions: FilterActions;
    toGmail: GmailFilterAction;
    existing: { add: string[]; remove: string[] };
  }[];
  whole: {
    name: string;
    conditions: FilterConditions;
    actions: FilterActions;
    expect: boolean;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/filter-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("filter fixtures (must match Android)", () => {
  for (const fixture of fixtures.rowError) {
    test(`rowError: ${JSON.stringify(fixture.row)}`, () => {
      expect(rowError(fixture.row)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.size) {
    test(`size: ${fixture.value}`, () => {
      expect(parseSize(fixture.value)).toBe(fixture.bytes);
    });
  }
  for (const fixture of fixtures.build) {
    test(`build: ${fixture.name}`, () => {
      const criteria = conditionsToCriteria(fixture.conditions);
      expect(criteria).toEqual(fixture.expect);
      expect(Object.keys(criteria)).toEqual(Object.keys(fixture.expect));
    });
  }
  for (const fixture of fixtures.parse) {
    test(`parse: ${fixture.name}`, () => {
      expect(criteriaToConditions(fixture.criteria)).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.search) {
    test(`search: ${fixture.expect}`, () => {
      expect(criteriaToSearch(fixture.criteria)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.actions) {
    test(`actions: ${fixture.name}`, () => {
      expect(actionFromGmail(fixture.gmail)).toEqual(fixture.actions);
      expect(actionToGmail(fixture.actions)).toEqual(fixture.toGmail);
      expect(labelChanges(fixture.actions)).toEqual(fixture.existing);
    });
  }
  for (const fixture of fixtures.whole) {
    test(`whole: ${fixture.name}`, () => {
      expect(
        filterIsWhole({
          conditions: fixture.conditions,
          actions: fixture.actions,
        }),
      ).toBe(fixture.expect);
    });
  }
});

test("built filters load back as rows that save and load the same", () => {
  for (const fixture of fixtures.build) {
    const loaded = criteriaToConditions(
      conditionsToCriteria(fixture.conditions),
    );
    expect(loaded.kind).toBe("rows");
    expect(criteriaToConditions(conditionsToCriteria(loaded))).toEqual(loaded);
  }
});

test("a whole filter round-trips through the editor", () => {
  const filter = {
    criteria: { from: "news@example.com", query: "weekly" },
    action: { addLabelIds: ["Label_1"], removeLabelIds: ["INBOX"] },
  };
  expect(editorToFilter(filterToEditor(filter))).toEqual(filter);
});
