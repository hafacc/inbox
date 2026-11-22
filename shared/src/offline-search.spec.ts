import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type FreeTerms,
  freeTerms,
  matchesOffline,
  type OfflineThread,
} from "./offline-search";
import { parseQuery } from "./search";

type Fixtures = {
  terms: { name: string; free: string; expect: FreeTerms }[];
  match: {
    name: string;
    thread: OfflineThread;
    labels?: { id: string; name: string }[];
    expect: string[];
    reject: string[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/offline-search-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

const LABELS = [
  { id: "Label_1", name: "Home Stuff" },
  { id: "Label_2", name: "Work/Projects" },
];

describe("terms", () => {
  for (const fixture of fixtures.terms) {
    test(fixture.name, () => {
      expect(freeTerms(fixture.free)).toEqual(fixture.expect);
    });
  }
});

describe("match", () => {
  for (const fixture of fixtures.match) {
    test(fixture.name, () => {
      const matches = (query: string) => {
        const { chips, free } = parseQuery(query, null);
        return matchesOffline(
          fixture.thread,
          chips,
          freeTerms(free),
          fixture.labels ?? LABELS,
        );
      };
      expect(fixture.expect.filter((query) => !matches(query))).toEqual([]);
      expect(fixture.reject.filter(matches)).toEqual([]);
    });
  }
});
