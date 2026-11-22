import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  afterDraftsListing,
  draftsListingWanted,
  localOnlyDrafts,
  type OfflineDraftParts,
  type OfflineDraftsInput,
  type OfflineLocalDraft,
  offlineDraftOf,
  offlineDraftThreads,
  offlineEditable,
  type SavedDraftRefs,
  savedDraftMessages,
  sentDrafts,
} from "./offline-drafts";
import type {
  GmailDraftRef,
  OutboxDraftDoc,
  SendingDraft,
} from "./outbox-drafts";

type Saved = Record<string, Record<string, string[]>>;

type InputFixture = {
  saved: Saved;
  refs?: GmailDraftRef[];
  docs?: OutboxDraftDoc[];
  sending?: SendingDraft[];
  sent?: GmailDraftRef[];
  local?: OfflineLocalDraft[];
};

type Fixtures = {
  listing: {
    name: string;
    saved: Saved;
    known: SavedDraftRefs;
    listed: GmailDraftRef[];
    wanted: boolean;
    after: SavedDraftRefs;
  }[];
  sent: {
    name: string;
    previous: GmailDraftRef[];
    docs: OutboxDraftDoc[];
    known: GmailDraftRef[];
    saved: Saved;
    expect: GmailDraftRef[];
  }[];
  threads: (InputFixture & {
    name: string;
    expect: string[];
    expectLocal: string[];
  })[];
  open: (InputFixture & {
    name: string;
    target: { messageId?: string; threadIds?: string[] };
    expect: GmailDraftRef | null;
  })[];
  editable: { name: string; parts: OfflineDraftParts; expect: boolean }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/offline-draft-fixtures.json", import.meta.url),
    "utf8",
  ),
);

function input(fixture: InputFixture): OfflineDraftsInput {
  return {
    saved: savedDraftMessages(Object.entries(fixture.saved)),
    refs: fixture.refs ?? [],
    docs: fixture.docs ?? [],
    sending: fixture.sending ?? [],
    sent: fixture.sent ?? [],
    local: fixture.local ?? [],
  };
}

describe("offline drafts (must match Android)", () => {
  for (const fixture of fixtures.listing) {
    test(`listing: ${fixture.name}`, () => {
      const saved = savedDraftMessages(Object.entries(fixture.saved));
      expect(draftsListingWanted(saved, fixture.known)).toBe(fixture.wanted);
      const after = fixture.wanted
        ? afterDraftsListing(fixture.listed, saved)
        : fixture.known;
      expect(after).toEqual(fixture.after);
    });
  }
  for (const fixture of fixtures.sent) {
    test(`sent: ${fixture.name}`, () => {
      expect(
        sentDrafts(
          fixture.previous,
          fixture.docs,
          fixture.known,
          savedDraftMessages(Object.entries(fixture.saved)),
        ),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.threads) {
    test(`threads: ${fixture.name}`, () => {
      expect(offlineDraftThreads(input(fixture))).toEqual(fixture.expect);
      expect(localOnlyDrafts(input(fixture))).toEqual(fixture.expectLocal);
    });
  }
  for (const fixture of fixtures.open) {
    test(`open: ${fixture.name}`, () => {
      expect(offlineDraftOf(input(fixture), fixture.target)).toEqual(
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.editable) {
    test(`editable: ${fixture.name}`, () => {
      expect(offlineEditable(fixture.parts)).toBe(fixture.expect);
    });
  }
});
