import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type QueuedLabelChange,
  type QueuedRow,
  withQueued,
} from "./queued-rows";

type Fixtures = {
  cases: {
    name: string;
    row: QueuedRow;
    queue: QueuedLabelChange[];
    expect: QueuedRow;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/queued-row-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

for (const fixture of fixtures.cases) {
  test(`withQueued: ${fixture.name} (must match Android)`, () => {
    expect(withQueued(fixture.row, fixture.queue)).toEqual(fixture.expect);
  });
}

function change(
  labels: Partial<Pick<QueuedLabelChange, "add" | "remove">>,
): QueuedLabelChange {
  return { id: "c1", seq: 1, threadId: "t1", add: [], remove: [], ...labels };
}

test("a row no queued change moves comes back as it was", () => {
  const row = { id: "t1", labelIds: ["INBOX"], unread: false, extra: 1 };
  expect(withQueued(row, [])).toBe(row);
  expect(withQueued(row, [change({ add: ["INBOX"] })])).toBe(row);
  expect(withQueued(row, [change({ add: ["STARRED"] })]).extra).toBe(1);
});

test("changes two tabs numbered alike apply by id", () => {
  const row = { id: "t1", labelIds: ["INBOX"], unread: false };
  expect(
    withQueued(row, [
      { ...change({ remove: ["INBOX"] }), id: "b" },
      { ...change({ add: ["INBOX"] }), id: "a" },
    ]).labelIds,
  ).toEqual([]);
});
