import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  arrivalBundleLabels,
  type BundleCounts,
  type BundleHistoryRecord,
  type BundleList,
  type BundleSupply,
  bundleCounts,
  bundledOnArrival,
  bundleSweepIds,
  bundlesToFetch,
  bundlesToRemember,
  bundlesToTopUp,
  type InboxPageRequest,
  inboxExclusion,
  inboxNextPage,
  inboxThreadIds,
  inInbox,
  mergeFirstPage,
  type QueryLabel,
  type SweptThread,
  touchedBundles,
} from "./bundles";

type Fixtures = {
  ids: {
    name: string;
    inboxIds: string[];
    bundles: BundleList[];
    labels: Record<string, string[]>;
    expect: string[];
  }[];
  fetch: {
    name: string;
    bundleLabels: string[];
    inboxLabels: string[][];
    fetched: string[];
    remembered: string[];
    expect: string[];
  }[];
  remember: { name: string; bundles: BundleList[]; expect: string[] }[];
  counts: {
    name: string;
    threads: number;
    unread: number;
    more: boolean;
    expect: BundleCounts;
  }[];
  exclusion: {
    name: string;
    labels: QueryLabel[];
    known?: QueryLabel[];
    expect: string;
  }[];
  merge: {
    name: string;
    existing: string[];
    page: string[];
    expect: string[] | null;
  }[];
  nextPage: {
    name: string;
    query: string;
    pageToken: string | null;
    tokenQuery: string;
    expect: InboxPageRequest;
  }[];
  touched: {
    name: string;
    bundleLabels: string[];
    records: BundleHistoryRecord[];
    expect: string[];
  }[];
  inbox: { name: string; labels: string[]; expect: boolean }[];
  topUp: { name: string; bundles: BundleSupply[]; expect: string[] }[];
  sweep: {
    name: string;
    shown: SweptThread[];
    listed: string[];
    pinned: string[];
    expect: string[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/bundle-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("bundle fixtures (must match Android)", () => {
  for (const fixture of fixtures.ids) {
    test(`inboxThreadIds: ${fixture.name}`, () => {
      expect(
        inboxThreadIds(
          fixture.inboxIds,
          fixture.bundles,
          (id) => fixture.labels[id],
        ),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.fetch) {
    test(`bundlesToFetch: ${fixture.name}`, () => {
      expect(
        bundlesToFetch(
          fixture.bundleLabels,
          fixture.inboxLabels,
          new Set(fixture.fetched),
          new Set(fixture.remembered),
        ),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.inbox) {
    test(`inInbox: ${fixture.name}`, () => {
      expect(inInbox(fixture.labels)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.topUp) {
    test(`bundlesToTopUp: ${fixture.name}`, () => {
      expect(bundlesToTopUp(fixture.bundles)).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.sweep) {
    test(`bundleSweepIds: ${fixture.name}`, () => {
      expect(
        bundleSweepIds(fixture.shown, fixture.listed, fixture.pinned),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.remember) {
    test(`bundlesToRemember: ${fixture.name}`, () => {
      expect(bundlesToRemember(fixture.bundles)).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.counts) {
    test(`bundleCounts: ${fixture.name}`, () => {
      expect(
        bundleCounts(fixture.threads, fixture.unread, fixture.more),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.exclusion) {
    test(`inboxExclusion: ${fixture.name}`, () => {
      expect(inboxExclusion(fixture.labels, fixture.known)).toBe(
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.merge) {
    test(`mergeFirstPage: ${fixture.name}`, () => {
      expect(mergeFirstPage(fixture.existing, fixture.page)).toEqual(
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.nextPage) {
    test(`inboxNextPage: ${fixture.name}`, () => {
      expect(
        inboxNextPage(fixture.query, fixture.pageToken, fixture.tokenQuery),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.touched) {
    test(`touchedBundles: ${fixture.name}`, () => {
      expect(touchedBundles(fixture.records, fixture.bundleLabels)).toEqual(
        fixture.expect,
      );
    });
  }
});

type ArrivalFixtures = {
  cases: {
    name: string;
    labels: string[];
    bundleOff: string[];
    hasTodo?: boolean;
    expect: { labels: string[]; bundled: boolean };
  }[];
};

const arrival = JSON.parse(
  readFileSync(
    new URL("../testdata/arrival-bundle-fixtures.json", import.meta.url),
    "utf8",
  ),
) as ArrivalFixtures;

describe("arrival-bundle fixtures", () => {
  for (const fixture of arrival.cases) {
    test(fixture.name, () => {
      expect(
        arrivalBundleLabels(fixture.labels, fixture.bundleOff, fixture.hasTodo),
      ).toEqual(fixture.expect.labels);
      expect(
        bundledOnArrival(fixture.labels, fixture.bundleOff, fixture.hasTodo),
      ).toBe(fixture.expect.bundled);
    });
  }
});
