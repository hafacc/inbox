import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  actionRemoves,
  type FilteredThread,
  type FilteredView,
  inboxAction,
  inboxPlan,
  keepsThread,
  viewFilter,
} from "./view-filter";

type Fixtures = {
  labels: Record<string, string>;
  keeps: {
    name: string;
    view: FilteredView;
    thread: FilteredThread;
    expect: boolean;
  }[];
  inboxAction: {
    name: string;
    thread: FilteredThread;
    expect: "done" | "inbox" | null;
  }[];
  inboxPlan: {
    name: string;
    threads: (FilteredThread & { id: string })[];
    expect: { done: string[]; inbox: string[] };
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/view-filter-fixtures.json", import.meta.url),
    "utf8",
  ),
);

const labelIdOf = (name: string) => fixtures.labels[name] ?? null;

describe("view filter fixtures (must match Android)", () => {
  for (const { name, view, thread, expect: want } of fixtures.keeps) {
    test(`keeps: ${name}`, () => {
      expect(keepsThread(viewFilter(view, labelIdOf), thread)).toBe(want);
    });
  }
  for (const { name, thread, expect: want } of fixtures.inboxAction) {
    test(`inbox action: ${name}`, () => {
      expect(inboxAction(thread)).toBe(want);
    });
  }
  for (const { name, threads, expect: want } of fixtures.inboxPlan) {
    test(`inbox plan: ${name}`, () => {
      expect(inboxPlan(threads)).toEqual(want);
    });
  }
});

describe("actionRemoves", () => {
  test("Done takes a row out of the inbox but not out of its label", () => {
    const before = { labelIds: ["INBOX", "Label_1"], snoozed: false };
    const after = { labelIds: ["Label_1"], snoozed: false };
    expect(actionRemoves(viewFilter({ kind: "inbox" }), before, after)).toBe(
      true,
    );
    expect(
      actionRemoves(
        viewFilter({ kind: "label", labelId: "Label_1" }),
        before,
        after,
      ),
    ).toBe(false);
  });
});
