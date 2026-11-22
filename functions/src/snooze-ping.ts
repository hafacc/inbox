import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { userDoc } from "./admin.js";
import { isStaleEvent } from "./config.js";
import { sendFcm } from "./deps.js";
import { staleTokens } from "./lib/notification.js";
import {
  dropWaits,
  orphanWaits,
  phonesToPing,
  readWaits,
  todoItemId,
  todoSnoozeUntil,
} from "./lib/snooze-waits.js";

function snoozeUntilOf(
  data: { snoozeUntil?: unknown } | undefined,
): string | null {
  return typeof data?.snoozeUntil === "string" ? data.snoozeUntil : null;
}

// Reads the one `meta/snoozeWaits` doc, pings the phones `phonesToPing` names, and drops the entries
// of phones whose device doc is gone or whose token FCM refuses, so the 10 entries the rules allow
// aren't held by phones that left.
async function ping(
  uid: string,
  itemId: string,
  snoozeUntil: string | null,
  time: string,
): Promise<void> {
  const waitsRef = userDoc(uid).collection("meta").doc("snoozeWaits");
  const waits = readWaits((await waitsRef.get()).data());
  if (waits.size === 0) return;
  const devices = await userDoc(uid).collection("devices").get();
  const gone = orphanWaits(
    waits,
    devices.docs.map((entry) => entry.id),
  );
  for (const token of gone) waits.delete(token);
  const tokens = phonesToPing(waits, itemId, snoozeUntil, Date.parse(time));
  const stale =
    tokens.length === 0
      ? []
      : staleTokens(
          tokens,
          await sendFcm(tokens, { kind: "snooze" }, "normal"),
        );
  await dropWaits(waitsRef, [...gone, ...stale]);
}

/**
 * Pings the phones a thread's snooze change concerns (`phonesToPing`), so each sets its snooze alarm
 * again; it relays only, with no time math and nothing scheduled. Exits before any read unless
 * `snoozeUntil` changed, then reads the one `meta/snoozeWaits` doc. Not retried: a lost ping is
 * caught at the phone's next check.
 */
export const snoozePing = onDocumentWritten(
  "users/{uid}/threads/{threadId}",
  async (event) => {
    const change = event.data;
    if (change === undefined || isStaleEvent(event.time)) return;
    const snoozeUntil = snoozeUntilOf(change.after.data());
    if (snoozeUntil === snoozeUntilOf(change.before.data())) return;
    await ping(
      event.params.uid,
      event.params.threadId,
      snoozeUntil,
      event.time,
    );
  },
);

/**
 * {@link snoozePing} for todos: a todo's snooze as phones wait on it (none once done or deleted)
 * changed, so the phones it concerns set their alarms again. Its item id is `todo:` and its id.
 */
export const todoSnoozePing = onDocumentWritten(
  "users/{uid}/todos/{todoId}",
  async (event) => {
    const change = event.data;
    if (change === undefined || isStaleEvent(event.time)) return;
    const snoozeUntil = todoSnoozeUntil(change.after.data());
    if (snoozeUntil === todoSnoozeUntil(change.before.data())) return;
    await ping(
      event.params.uid,
      todoItemId(event.params.todoId),
      snoozeUntil,
      event.time,
    );
  },
);
