import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type DatedMessage,
  newestThreadsFirst,
  type ThreadDates,
  threadDates,
} from "./thread-dates";

type DatedThread = { id: string; date: number };

type Fixtures = {
  dates: { name: string; messages: DatedMessage[]; expect: ThreadDates }[];
  order: { name: string; threads: DatedThread[]; expect: DatedThread[] }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/thread-date-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("threadDates", () => {
  for (const fixture of fixtures.dates) {
    test(fixture.name, () => {
      expect(threadDates(fixture.messages)).toEqual(fixture.expect);
    });
  }
});

describe("newestThreadsFirst", () => {
  for (const fixture of fixtures.order) {
    test(fixture.name, () => {
      expect(
        newestThreadsFirst(
          fixture.threads,
          (thread) => thread.id,
          (thread) => thread.date,
        ),
      ).toEqual(fixture.expect);
    });
  }
});
