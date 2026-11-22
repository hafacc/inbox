import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  answeredSearch,
  type JoinSnoozeMember,
  type JoinSnoozeStatus,
  type JoinSummary,
  type JoinThread,
  joinedSnooze,
  joinSummaries,
  joinThreads,
} from "./thread-joins";

type Fixtures = {
  answered: { name: string; answers: string[]; search: string | null }[];
  joins: { name: string; threads: JoinThread[]; expect: string[][] }[];
  summaries: { name: string; members: JoinSummary[]; expect: JoinSummary }[];
  snooze: {
    name: string;
    members: JoinSnoozeMember[];
    expect: { status: JoinSnoozeStatus; snoozeUntil: number | null };
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/thread-join-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("joinThreads", () => {
  for (const fixture of fixtures.joins) {
    test(fixture.name, () => {
      expect(joinThreads(fixture.threads)).toEqual(fixture.expect);
    });
  }
});

describe("joinSummaries", () => {
  for (const fixture of fixtures.summaries) {
    test(fixture.name, () => {
      expect(joinSummaries(fixture.members)).toEqual(fixture.expect);
    });
  }
});

describe("joinedSnooze", () => {
  for (const fixture of fixtures.snooze) {
    test(fixture.name, () => {
      expect(joinedSnooze(fixture.members)).toEqual(fixture.expect);
    });
  }
});

describe("answeredSearch (must match Android and the server)", () => {
  for (const fixture of fixtures.answered) {
    test(fixture.name, () => {
      expect(answeredSearch(fixture.answers)).toBe(fixture.search);
    });
  }
});
