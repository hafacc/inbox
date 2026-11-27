import {
  negated,
  SHARED_SETS_FIELD,
  sharedFromWire,
  type VoteDeltas,
  voteDeltas,
} from "@inbox/shared/snooze-stats-wire";
import {
  type CommitUndoSnapshot,
  commit as commitPure,
  type DevicePartition,
  emptyPartition,
  type SnoozeSource,
  undoCommit as undoCommitPure,
} from "@inbox/shared/snooze-suggest";
import { doc, type Firestore, increment, setDoc } from "firebase/firestore";
import { type FirestoreWatch, watchFirestore } from "./firestore-watch";

/** What undoing one snooze needs: the engine's snapshot and the amounts the commit added. */
export type SnoozeUndo = {
  snapshot: CommitUndoSnapshot;
  deltas: VoteDeltas;
  custom: boolean;
};

/** Firestore path of the learned snooze counts: `users/{uid}/meta/snoozeStats`. */
export function snoozeStatsPath(uid: string): [string, ...string[]] {
  return ["users", uid, "meta", "snoozeStats"];
}

function increments(deltas: Record<string, number>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(deltas).map(([id, amount]) => [id, increment(amount)]),
  );
}

/**
 * The account's learned snooze counts, changed only by adding amounts so devices never overwrite
 * each other. Schedule send keeps its own counts in the same format at another path.
 */
export class SnoozeStatsStore {
  #db: Firestore;
  #path: [string, ...string[]];
  #shared: DevicePartition = emptyPartition("shared");
  #listener: (partition: DevicePartition) => void;
  #watch: FirestoreWatch;

  constructor(
    database: Firestore,
    uid: string,
    listener: (partition: DevicePartition) => void,
    path: [string, ...string[]] = snoozeStatsPath(uid),
  ) {
    this.#db = database;
    this.#path = path;
    this.#listener = listener;
    this.#watch = watchFirestore(
      this.#ref(),
      (snapshot) => this.#set(sharedFromWire(snapshot.data())),
      `stats ${this.#path.join("/")}`,
    );
  }

  #ref() {
    const [first, ...rest] = this.#path;
    return doc(this.#db, first, ...rest);
  }

  #set(next: DevicePartition): void {
    this.#shared = next;
    this.#listener(next);
  }

  /** Count a snooze to `target` made at `at`. */
  commit(
    at: number,
    target: number,
    source: SnoozeSource,
    pickedKey: string | null,
  ): SnoozeUndo {
    const result = commitPure(this.#shared, at, target, source, pickedKey);
    const deltas = voteDeltas(this.#shared, result.next);
    const custom = source === "custom";
    this.#set(result.next);
    this.#write(deltas, custom ? { lastCustom: result.next.lastCustom } : {});
    return { snapshot: result.undoSnapshot, deltas, custom };
  }

  /** Take back a commit. */
  undo(undo: SnoozeUndo): void {
    this.#set(undoCommitPure(this.#shared, undo.snapshot));
    this.#write(
      negated(undo.deltas),
      undo.custom ? { lastCustom: undo.snapshot.prevLastCustom } : {},
    );
  }

  #write(deltas: VoteDeltas, extra: Record<string, unknown>): void {
    const fields: Record<string, unknown> = { ...extra };
    if (Object.keys(deltas.sets).length) {
      fields[SHARED_SETS_FIELD] = increments(deltas.sets);
    }
    if (Object.keys(deltas.tod).length) fields.tod = increments(deltas.tod);
    if (Object.keys(fields).length === 0) return;
    void setDoc(this.#ref(), fields, { merge: true }).catch((error) =>
      console.error("stats write failed", this.#path, error),
    );
  }

  /** Retry the listener now instead of waiting out the backoff. */
  reattach(): void {
    this.#watch.retryNow();
  }

  /** Stop listening. */
  dispose(): void {
    this.#watch.stop();
  }
}
