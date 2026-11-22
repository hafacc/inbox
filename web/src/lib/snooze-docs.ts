import { epochToIso, isLocalIso, isoToEpoch } from "@inbox/shared/local-time";
import type { SnoozeDoc } from "@inbox/shared/thread-snooze";
import {
  collection,
  deleteDoc,
  doc,
  type Firestore,
  setDoc,
} from "firebase/firestore";
import { watchFirestore } from "./firestore-watch";

type ThreadDocData = {
  // A local wall time with no zone.
  snoozeUntil?: string | null;
  snoozeWatermark?: string | null;
};

/** Follow `users/{uid}/threads`, calling back with every thread's snooze. */
export function watchSnoozeDocs(
  database: Firestore,
  uid: string,
  onChange: (docs: Map<string, SnoozeDoc>) => void,
): () => void {
  return watchFirestore(
    collection(database, "users", uid, "threads"),
    (snapshot) => {
      const docs = new Map<string, SnoozeDoc>();
      for (const entry of snapshot.docs) {
        const { snoozeUntil, snoozeWatermark } = entry.data() as ThreadDocData;
        docs.set(entry.id, {
          snoozeUntil: isLocalIso(snoozeUntil) ? isoToEpoch(snoozeUntil) : null,
          snoozeWatermark: snoozeWatermark ?? null,
        });
      }
      onChange(docs);
    },
    "snooze",
  ).stop;
}

/** Save a thread's snooze as this device's wall time; Firestore queues the write while offline. */
export function writeSnoozeDoc(
  database: Firestore,
  uid: string,
  threadId: string,
  snooze: SnoozeDoc,
): Promise<void> {
  return setDoc(doc(database, "users", uid, "threads", threadId), {
    snoozeUntil:
      snooze.snoozeUntil === null ? null : epochToIso(snooze.snoozeUntil),
    snoozeWatermark: snooze.snoozeWatermark,
  });
}

/** Forget a thread's snooze. */
export function deleteSnoozeDoc(
  database: Firestore,
  uid: string,
  threadId: string,
): Promise<void> {
  return deleteDoc(doc(database, "users", uid, "threads", threadId));
}
