import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  inboxSortDate,
  newerMail,
  type ScannedRecord,
  type SnoozeDoc,
  type SnoozeStatus,
  snoozeArchivedElsewhere,
  snoozeStatus,
} from "./thread-snooze";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/thread-snooze-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  status: {
    name: string;
    doc: SnoozeDoc | null;
    now: number;
    hasNewerMessage: boolean;
    expect: SnoozeStatus;
  }[];
  sortDate: {
    name: string;
    lastDate: number;
    doc: SnoozeDoc | null;
    status: SnoozeStatus;
    expect: number;
  }[];
  archivedElsewhere: {
    name: string;
    doc: SnoozeDoc | null;
    gmailLabelIds: string[];
    gmailHistoryId: string | null;
    now: number;
    hasLocalChange: boolean;
    expect: boolean;
  }[];
  newerMail: {
    name: string;
    watermarks: Record<string, string>;
    records: ScannedRecord[];
    inInbox: string[];
    skippedInbox: string[];
  }[];
};

describe("thread snooze fixtures (must match Android)", () => {
  for (const fixture of fixtures.status) {
    test(`status: ${fixture.name}`, () => {
      expect(
        snoozeStatus(
          fixture.doc ?? undefined,
          fixture.now,
          fixture.hasNewerMessage,
        ),
      ).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.sortDate) {
    test(`sort date: ${fixture.name}`, () => {
      expect(
        inboxSortDate(
          fixture.lastDate,
          fixture.doc ?? undefined,
          fixture.status,
        ),
      ).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.archivedElsewhere) {
    test(`archived elsewhere: ${fixture.name}`, () => {
      expect(
        snoozeArchivedElsewhere(
          fixture.doc ?? undefined,
          fixture.gmailLabelIds,
          fixture.gmailHistoryId ?? undefined,
          fixture.now,
          fixture.hasLocalChange,
        ),
      ).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.newerMail) {
    test(`newer mail: ${fixture.name}`, () => {
      const { inInbox, skippedInbox } = newerMail(
        fixture.records,
        new Map(Object.entries(fixture.watermarks)),
      );
      expect([...inInbox].sort()).toEqual(fixture.inInbox);
      expect([...skippedInbox].sort()).toEqual(fixture.skippedInbox);
    });
  }
});
