import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type BookEntry,
  contactWords,
  mergeBook,
  searchContacts,
} from "./contact-match";

type Fixtures = {
  merge: { name: string; entries: BookEntry[]; expect: BookEntry[] }[];
  words: { text: string; expect: string[] }[];
  book: BookEntry[];
  search: {
    name: string;
    query: string;
    exclude?: string[];
    limit?: number;
    expect: string[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/contact-match-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("merge", () => {
  for (const fixture of fixtures.merge) {
    test(fixture.name, () => {
      expect(mergeBook(fixture.entries)).toEqual(fixture.expect);
    });
  }
});

test("words", () => {
  for (const fixture of fixtures.words) {
    expect(contactWords(fixture.text)).toEqual(fixture.expect);
  }
});

describe("search", () => {
  for (const fixture of fixtures.search) {
    test(fixture.name, () => {
      expect(
        searchContacts(
          fixtures.book,
          fixture.query,
          fixture.exclude ?? [],
          fixture.limit ?? 6,
        ).map((entry) => entry.email),
      ).toEqual(fixture.expect);
    });
  }
});
