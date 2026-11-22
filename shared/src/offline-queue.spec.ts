import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  applyChange,
  compact,
  enqueueAll,
  inReplayOrder,
  type LabelChange,
  type NewLabelChange,
  replay,
} from "./offline-queue";

type FixtureChange = Omit<LabelChange, "id">;

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/offline-queue-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  enqueue: {
    name: string;
    queue: FixtureChange[];
    add: NewLabelChange[];
    seqs: number[];
  }[];
  applyChange: {
    name: string;
    labels: string[];
    add: string[];
    remove: string[];
    expect: string[];
  }[];
  compact: { name: string; queue: FixtureChange[]; expect: FixtureChange[] }[];
  replay: {
    name: string;
    queue: FixtureChange[];
    later: string[];
    queuedWhileSending: Record<string, NewLabelChange[]>;
    sent: string[];
    drained: boolean;
    left: number[];
  }[];
};

function withIds(changes: readonly FixtureChange[]): LabelChange[] {
  return changes.map((change) => ({ ...change, id: `c${change.seq}` }));
}

function withoutIds(changes: readonly LabelChange[]): FixtureChange[] {
  return changes.map(({ id: _id, ...change }) => change);
}

describe("offline queue fixtures (must match Android)", () => {
  for (const fixture of fixtures.enqueue) {
    test(`enqueue: ${fixture.name}`, () => {
      const queue = enqueueAll(withIds(fixture.queue), fixture.add);
      expect(queue.map((change) => change.seq)).toEqual(fixture.seqs);
    });
  }
  for (const fixture of fixtures.applyChange) {
    test(`applyChange: ${fixture.name}`, () => {
      expect(applyChange(fixture.labels, fixture)).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.compact) {
    test(`compact: ${fixture.name}`, () => {
      expect(withoutIds(compact(withIds(fixture.queue)))).toEqual(
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.replay) {
    test(`replay: ${fixture.name}`, async () => {
      let queue = withIds(fixture.queue);
      const sent: string[] = [];
      const queuedFor = new Set<string>();
      const drained = await replay(
        () => queue,
        async (change) => {
          sent.push(change.threadId);
          const meanwhile = fixture.queuedWhileSending[change.threadId];
          if (meanwhile !== undefined && !queuedFor.has(change.threadId)) {
            queuedFor.add(change.threadId);
            queue = enqueueAll(queue, meanwhile);
          }
          if (fixture.later.includes(change.threadId)) return "later";
          queue = queue.filter(
            (entry) =>
              entry.threadId !== change.threadId || entry.seq > change.seq,
          );
          return "settled";
        },
      );
      expect({
        sent,
        drained,
        left: queue.map((change) => change.seq),
      }).toEqual({
        sent: fixture.sent,
        drained: fixture.drained,
        left: fixture.left,
      });
    });
  }
});

test("every change gets its own id, so two tabs' changes never share a key", () => {
  const first = enqueueAll([], [{ threadId: "a", add: [], remove: [] }]);
  const second = enqueueAll([], [{ threadId: "b", add: [], remove: [] }]);
  expect(first[0]!.seq).toBe(second[0]!.seq);
  expect(first[0]!.id).not.toBe(second[0]!.id);
});

test("changes two tabs numbered alike replay in a fixed order, and keep their ids", () => {
  const queue: LabelChange[] = [
    { id: "tab2", seq: 1, threadId: "b", add: [], remove: ["INBOX"] },
    { id: "tab1", seq: 1, threadId: "a", add: [], remove: ["INBOX"] },
  ];
  expect(inReplayOrder(queue).map((change) => change.id)).toEqual([
    "tab1",
    "tab2",
  ]);
  expect(compact(queue).map((change) => change.id)).toEqual(["tab1", "tab2"]);
});

test("replay sends up to its limit at once", async () => {
  const queue = withIds([
    { seq: 1, threadId: "a", add: [], remove: [] },
    { seq: 2, threadId: "b", add: [], remove: [] },
  ]);
  let running = 0;
  let most = 0;
  let left = queue;
  await replay(
    () => left,
    async (change) => {
      running++;
      most = Math.max(most, running);
      await Promise.resolve();
      running--;
      left = left.filter((entry) => entry.threadId !== change.threadId);
      return "settled";
    },
    2,
  );
  expect(most).toBe(2);
});
