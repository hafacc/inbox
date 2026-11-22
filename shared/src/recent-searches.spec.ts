import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { addRecentSearch, removeRecentSearch } from "./recent-searches";

type Case = { name: string; recent: string[]; query: string; expect: string[] };

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/recent-search-fixtures.json", import.meta.url),
    "utf8",
  ),
) as { add: Case[]; remove: Case[] };

describe("addRecentSearch", () => {
  for (const entry of fixtures.add) {
    test(entry.name, () => {
      expect(addRecentSearch(entry.recent, entry.query)).toEqual(entry.expect);
    });
  }
});

describe("removeRecentSearch", () => {
  for (const entry of fixtures.remove) {
    test(entry.name, () => {
      expect(removeRecentSearch(entry.recent, entry.query)).toEqual(
        entry.expect,
      );
    });
  }
});
