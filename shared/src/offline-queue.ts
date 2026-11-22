// Gmail label changes made offline or not yet sent, kept in the order they were made and sent one
// request per thread. Android :core mail/OfflineQueue.kt is the same; both run
// shared/testdata/offline-queue-fixtures.json.

/** A Gmail label change waiting to be sent, in the order the user made it. */
export type LabelChange = {
  /**
   * unique id, its key where the queue is stored: two tabs share one store and number their
   * changes from the same highest `seq`, so `seq` alone can repeat
   */
  id: string;
  /** position in the order the user made changes */
  seq: number;
  /** Gmail thread id */
  threadId: string;
  /** label ids to add */
  add: string[];
  /** label ids to remove */
  remove: string[];
};

/** A change as the user made it, before the queue numbers it. */
export type NewLabelChange = Omit<LabelChange, "id" | "seq">;

function randomId(): string {
  return crypto.randomUUID();
}

/** Append a change, numbered after every change already queued. */
export function enqueue(
  queue: readonly LabelChange[],
  change: NewLabelChange,
  newId: () => string = randomId,
): LabelChange[] {
  return enqueueAll(queue, [change], newId);
}

/** Append several changes in order, numbered after every change already queued, each with a new id. */
export function enqueueAll(
  queue: readonly LabelChange[],
  changes: readonly NewLabelChange[],
  newId: () => string = randomId,
): LabelChange[] {
  const highest = queue.reduce((seq, entry) => Math.max(seq, entry.seq), 0);
  return [
    ...queue,
    ...changes.map((change, index) => ({
      ...change,
      id: newId(),
      seq: highest + index + 1,
    })),
  ];
}

/** The queue in the order to replay it; changes two tabs numbered alike go by id. */
export function inReplayOrder<Change extends Pick<LabelChange, "id" | "seq">>(
  queue: readonly Change[],
): Change[] {
  return [...queue].sort(
    (left, right) =>
      left.seq - right.seq ||
      (left.id < right.id ? -1 : left.id > right.id ? 1 : 0),
  );
}

/** Labels after a change; a label both added and removed ends up added. */
export function applyChange(
  labelIds: readonly string[],
  change: Pick<LabelChange, "add" | "remove">,
): string[] {
  const out = new Set(labelIds);
  for (const label of change.remove) out.delete(label);
  for (const label of change.add) out.add(label);
  return [...out].sort();
}

/**
 * Merge each thread's queued changes into one net change, so replay sends one request per thread.
 *
 * Changes to different threads don't interact, so each merged change takes the place (and the id)
 * of that thread's last change.
 */
export function compact(queue: readonly LabelChange[]): LabelChange[] {
  const byThread = new Map<
    string,
    { id: string; seq: number; add: Set<string>; remove: Set<string> }
  >();
  for (const change of inReplayOrder(queue)) {
    const net = byThread.get(change.threadId) ?? {
      id: change.id,
      seq: change.seq,
      add: new Set<string>(),
      remove: new Set<string>(),
    };
    for (const label of change.remove) {
      net.add.delete(label);
      net.remove.add(label);
    }
    for (const label of change.add) {
      net.remove.delete(label);
      net.add.add(label);
    }
    net.id = change.id;
    net.seq = change.seq;
    byThread.set(change.threadId, net);
  }
  const merged: LabelChange[] = [];
  for (const [threadId, net] of byThread) {
    merged.push({
      id: net.id,
      seq: net.seq,
      threadId,
      add: [...net.add].sort(),
      remove: [...net.remove].sort(),
    });
  }
  return inReplayOrder(merged);
}

/**
 * Send the queue in replay order (compacted) until it is empty or a change has to wait, also
 * sending changes queued while it runs. Compacting leaves one change per thread, and threads don't
 * interact, so up to `limit` are sent at once. `send` settles one change and takes it off the
 * queue, or answers "later" to stop starting more; resolves whether the queue emptied.
 */
export async function replay(
  queue: () => readonly LabelChange[],
  send: (change: LabelChange) => Promise<"settled" | "later">,
  limit = 1,
): Promise<boolean> {
  for (;;) {
    const pending = compact(queue());
    if (pending.length === 0) return true;
    let waiting = false;
    let next = 0;
    const worker = async () => {
      while (!waiting && next < pending.length) {
        const change = pending[next++]!;
        if ((await send(change)) === "later") waiting = true;
      }
    };
    await Promise.all(
      Array.from({ length: Math.min(limit, pending.length) }, worker),
    );
    if (waiting) return false;
  }
}
