// A send made on the device gets its outbox doc only once Gmail has saved its draft, so offline it
// has none yet. Until then the Outbox lists it as held on the device, "Waiting for a connection",
// and counts it; whether the Outbox is listed in the menu treats it as a `waiting` doc due when
// it was meant to go (send-suggest.ts outboxShownAt). Web uses it directly; Android ports it to
// :core (OutboxHeld.kt). Both run shared/testdata/outbox-held-fixtures.json.

/** A send queued on the device, as this rule sees it. */
export type QueuedSend = {
  /** the draft's id on this device */
  localId: string;
  /** the outbox doc it writes or rewrites, once it has an id */
  outboxId: string | null;
  /** whether this device has written its outbox doc already */
  docWritten: boolean;
};

/** What a held send's row says in place of its time or status. */
export const HELD_SEND_STATUS = "Waiting for a connection";

/**
 * The local ids of queued sends the Outbox lists as held on the device: those with no outbox doc
 * yet, neither written by this device nor among the docs (`docIds`) it has heard of.
 */
export function heldSendIds(
  sends: Iterable<QueuedSend>,
  docIds: Iterable<string>,
): string[] {
  const known = new Set(docIds);
  const held: string[] = [];
  for (const send of sends) {
    if (send.docWritten) continue;
    if (send.outboxId !== null && known.has(send.outboxId)) continue;
    held.push(send.localId);
  }
  return held;
}
