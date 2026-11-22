import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  rowHoldActions,
  type ThreadAction,
  type ThreadActionPlaces,
  type ThreadActionState,
  threadActionPlaces,
  threadActions,
} from "./view-filter";

type Fixtures = {
  cases: {
    name: string;
    thread: ThreadActionState;
    expect: ThreadAction[];
    places: ThreadActionPlaces;
  }[];
  rows: {
    name: string;
    actions: ThreadAction[];
    expect: ThreadAction[];
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/thread-action-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("threadActions", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      expect(threadActions(fixture.thread)).toEqual(fixture.expect);
    });
  }
});

describe("threadActionPlaces", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      expect(threadActionPlaces(fixture.expect)).toEqual(fixture.places);
    });
  }
});

describe("rowHoldActions", () => {
  for (const fixture of fixtures.rows) {
    test(fixture.name, () => {
      expect(rowHoldActions(fixture.actions)).toEqual(fixture.expect);
    });
  }
});
