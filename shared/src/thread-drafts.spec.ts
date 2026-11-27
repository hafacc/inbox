import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  draftSnippet,
  draftsOpening,
  type GmailThreadMessage,
  type LocalThreadDraft,
  type ThreadEntry,
  threadEntries,
} from "./thread-drafts";

type Fixtures = {
  entries: {
    name: string;
    messages: GmailThreadMessage[];
    local: LocalThreadDraft[];
    outbox?: string[];
    expect: ThreadEntry[];
  }[];
  snippet: { name: string; markdown: string; expect: string }[];
  opening: {
    name: string;
    inDrafts: boolean;
    event: string;
    openBefore: string | null;
    openAfter: string | null;
    expect: string | null;
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/thread-draft-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("threadEntries", () => {
  for (const fixture of fixtures.entries) {
    test(fixture.name, () => {
      expect(
        threadEntries(
          fixture.messages,
          fixture.local,
          new Set(fixture.outbox ?? []),
        ),
      ).toEqual(fixture.expect);
    });
  }
});

describe("draftSnippet", () => {
  for (const fixture of fixtures.snippet) {
    test(fixture.name, () => {
      expect(draftSnippet(fixture.markdown)).toBe(fixture.expect);
    });
  }
});

describe("draftsOpening", () => {
  for (const fixture of fixtures.opening) {
    test(fixture.name, () => {
      expect(
        draftsOpening(
          fixture.inDrafts,
          { type: fixture.event },
          fixture.openBefore,
          fixture.openAfter,
        ),
      ).toBe(fixture.expect);
    });
  }
});
