import {
  collection,
  deleteDoc,
  doc,
  type Firestore,
  setDoc,
} from "firebase/firestore";
import { watchFirestore } from "./firestore-watch";

/** Follow `users/{uid}/labelPrefs`: each label's stored settings, by label id. */
export function watchLabelPrefs(
  database: Firestore,
  uid: string,
  onChange: (docs: Map<string, unknown>) => void,
): () => void {
  return watchFirestore(
    collection(database, "users", uid, "labelPrefs"),
    (snapshot) => {
      onChange(new Map(snapshot.docs.map((entry) => [entry.id, entry.data()])));
    },
    "label settings",
  ).stop;
}

/** One label's stored settings; only the fields given change. */
export type LabelPrefsPatch = {
  /** listed in the sidebar */
  sidebar?: boolean;
  /** folded into inbox bundles */
  bundle?: boolean;
  /** icon id, or "none" for the colored circle */
  icon?: string;
};

/** Change a label's settings; Firestore queues the write while offline. */
export function writeLabelPrefs(
  database: Firestore,
  uid: string,
  labelId: string,
  patch: LabelPrefsPatch,
): Promise<void> {
  return setDoc(doc(database, "users", uid, "labelPrefs", labelId), patch, {
    merge: true,
  });
}

/** Drop a label's settings; Firestore queues the delete while offline. */
export function deleteLabelPrefs(
  database: Firestore,
  uid: string,
  labelId: string,
): Promise<void> {
  return deleteDoc(doc(database, "users", uid, "labelPrefs", labelId));
}
