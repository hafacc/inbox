import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type AccountChange,
  changeRefused,
  enqueueAccount,
  filterMade,
  filtersWithPending,
  isLocalId,
  joinAccountQueues,
  labelMade,
  labelsWithPending,
  type NewAccountChange,
  newLocalId,
  nextAccountChange,
  type QueueLabel,
  relabelChanges,
  resolveLocalId,
  sendableChanges,
  type ThreadLabelChange,
  unsyncedChanges,
  withoutLocalId,
} from "./account-queue";
import type { GmailFilter } from "./filters";

type Queue = AccountChange[];
type Named = { name: string };

type Fixtures = {
  localId: { id: string; expect: boolean }[];
  enqueue: (Named & {
    queue: Queue;
    add: NewAccountChange[];
    expect: Queue;
  })[];
  next: (Named & { queue: Queue; expect: number | null })[];
  join: (Named & { stored: Queue; early: Queue; expect: Queue })[];
  resolve: (Named & {
    queue: Queue;
    localId: string;
    gmailId: string;
    expect: Queue;
  })[];
  without: (Named & { queue: Queue; localId: string; expect: Queue })[];
  labelMade: (Named & {
    queue: Queue;
    seq: number;
    gmailId: string;
    expect: Queue;
  })[];
  filterMade: (Named & {
    queue: Queue;
    seq: number;
    gmailId: string;
    expect: Queue;
  })[];
  refused: (Named & { queue: Queue; seq: number; expect: Queue })[];
  relabel: (Named & {
    changes: ThreadLabelChange[];
    from: string;
    to: string | null;
    expect: ThreadLabelChange[];
    same: number[];
  })[];
  sendable: (Named & { changes: ThreadLabelChange[]; expect: number[] })[];
  labels: (Named & {
    labels: QueueLabel[];
    queue: Queue;
    expect: QueueLabel[];
  })[];
  filters: (Named & {
    filters: GmailFilter[];
    queue: Queue;
    expect: GmailFilter[];
  })[];
  unsynced: (Named & {
    threadChanges: ThreadLabelChange[];
    queue: Queue;
    expect: number;
  })[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/account-queue-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("isLocalId", () => {
  for (const entry of fixtures.localId) {
    test(entry.id, () => expect(isLocalId(entry.id)).toBe(entry.expect));
  }
  test("a new local id is one", () =>
    expect(isLocalId(newLocalId())).toBe(true));
});

describe("enqueueAccount", () => {
  for (const entry of fixtures.enqueue) {
    test(entry.name, () => {
      const queue = entry.add.reduce(enqueueAccount, entry.queue);
      expect(queue).toEqual(entry.expect);
    });
  }
});

describe("nextAccountChange", () => {
  for (const entry of fixtures.next) {
    test(entry.name, () =>
      expect(nextAccountChange(entry.queue)?.seq ?? null).toBe(entry.expect),
    );
  }
});

describe("joinAccountQueues", () => {
  for (const entry of fixtures.join) {
    test(entry.name, () =>
      expect(joinAccountQueues(entry.stored, entry.early)).toEqual(
        entry.expect,
      ),
    );
  }
});

describe("resolveLocalId", () => {
  for (const entry of fixtures.resolve) {
    test(entry.name, () =>
      expect(resolveLocalId(entry.queue, entry.localId, entry.gmailId)).toEqual(
        entry.expect,
      ),
    );
  }
});

describe("withoutLocalId", () => {
  for (const entry of fixtures.without) {
    test(entry.name, () =>
      expect(withoutLocalId(entry.queue, entry.localId)).toEqual(entry.expect),
    );
  }
});

describe("labelMade", () => {
  for (const entry of fixtures.labelMade) {
    test(entry.name, () =>
      expect(labelMade(entry.queue, entry.seq, entry.gmailId)).toEqual(
        entry.expect,
      ),
    );
  }
});

describe("filterMade", () => {
  for (const entry of fixtures.filterMade) {
    test(entry.name, () =>
      expect(filterMade(entry.queue, entry.seq, entry.gmailId)).toEqual(
        entry.expect,
      ),
    );
  }
});

describe("changeRefused", () => {
  for (const entry of fixtures.refused) {
    test(entry.name, () =>
      expect(changeRefused(entry.queue, entry.seq)).toEqual(entry.expect),
    );
  }
});

describe("relabelChanges", () => {
  for (const entry of fixtures.relabel) {
    test(entry.name, () => {
      const relabeled = relabelChanges(entry.changes, entry.from, entry.to);
      expect(relabeled).toEqual(entry.expect);
      for (const index of entry.same) {
        expect(relabeled[index]).toBe(entry.changes[index]!);
      }
    });
  }
});

describe("sendableChanges", () => {
  for (const entry of fixtures.sendable) {
    test(entry.name, () =>
      expect(
        sendableChanges(entry.changes).map((change) => change.seq),
      ).toEqual(entry.expect),
    );
  }
});

describe("labelsWithPending", () => {
  for (const entry of fixtures.labels) {
    test(entry.name, () =>
      expect(labelsWithPending(entry.labels, entry.queue)).toEqual(
        entry.expect,
      ),
    );
  }
});

describe("filtersWithPending", () => {
  for (const entry of fixtures.filters) {
    test(entry.name, () =>
      expect(filtersWithPending(entry.filters, entry.queue)).toEqual(
        entry.expect,
      ),
    );
  }
});

describe("unsyncedChanges", () => {
  for (const entry of fixtures.unsynced) {
    test(entry.name, () =>
      expect(unsyncedChanges(entry.threadChanges, entry.queue)).toBe(
        entry.expect,
      ),
    );
  }
});
