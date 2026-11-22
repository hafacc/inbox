import { logger } from "firebase-functions/v2";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { nameOf } from "../../shared/src/address-name.js";
import { addressHolder } from "./address-deps.js";
import { db, userDoc } from "./admin.js";
import {
  CLOUDFLARE_ACCOUNT_ID,
  CLOUDFLARE_API_TOKEN,
  CLOUDFLARE_KV_NAMESPACE_ID,
  isStaleEvent,
} from "./config.js";
import {
  type AddressHolder,
  type KeyStore,
  publicationOf,
  reconcileKeys,
  syncUserKey,
  workersKv,
} from "./lib/wkd-keys.js";

/** Workers KV of published keys, or undefined until it is set up; needs `CLOUDFLARE_API_TOKEN`. */
export function keyStore(): KeyStore | undefined {
  const apiToken = CLOUDFLARE_API_TOKEN.value();
  const accountId = CLOUDFLARE_ACCOUNT_ID.value();
  const namespaceId = CLOUDFLARE_KV_NAMESPACE_ID.value();
  if (apiToken === "" || accountId === "" || namespaceId === "") {
    return undefined;
  } else {
    return workersKv({ apiToken, accountId, namespaceId });
  }
}

let reportedUnconfigured = false;

/** Who holds `name@hafa.cc` as seen from `uid`, given the uid holding `addresses/{name}`. */
async function holderOf(
  uid: string,
  address: string,
  uidHolding: (name: string) => Promise<string | undefined>,
): Promise<AddressHolder> {
  const name = nameOf(address).toLowerCase();
  const holder = name === "" ? undefined : await uidHolding(name);
  if (holder === undefined) {
    return "none";
  } else {
    return holder === uid ? "self" : "other";
  }
}

/** Whether a write left the published address and key as they were. */
function samePublication(
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
): boolean {
  const old = publicationOf(before);
  const next = publicationOf(after);
  return old?.address === next?.address && old?.publicKey === next?.publicKey;
}

/**
 * Publishes a user's OpenPGP public key for WKD when `users/{uid}.pgp.publicKey`, the address or
 * its setup status changes: the key goes to Workers KV (read by the Key Worker at
 * openpgpkey.hafa.cc) while the user holds an active @hafa.cc address, and is removed when not.
 * Only the public key is ever read. It works from `users/{uid}` as it is now, not the event's copy,
 * and checks `addresses/{name}` before each change, so a retry of an old write can't publish a
 * replaced key or remove the key of whoever holds the address now.
 */
export const publishKey = onDocumentWritten(
  { document: "users/{uid}", secrets: [CLOUDFLARE_API_TOKEN], retry: true },
  async (event) => {
    const change = event.data;
    if (change === undefined || isStaleEvent(event.time)) return;
    const before = change.before.data();
    if (samePublication(before, change.after.data())) return;
    const store = keyStore();
    if (store === undefined) {
      if (!reportedUnconfigured) {
        logger.info("Workers KV isn't set up; keys aren't published");
        reportedUnconfigured = true;
      }
      return;
    }
    const { uid } = event.params;
    const current = (await userDoc(uid).get()).data();
    const result = await syncUserKey(store, before, current, (address) =>
      holderOf(uid, address, addressHolder),
    );
    if (result.published.length > 0 || result.removed.length > 0) {
      logger.info("WKD keys updated", { uid, ...result });
    }
  },
);

/**
 * Daily full sync of published keys, for writes that were missed or made before KV was set up. It
 * follows the same rule as {@link publishKey}: a key is published only while its user holds the
 * address.
 */
export const syncPublishedKeys = onSchedule(
  {
    schedule: "every day 05:00",
    timeZone: "UTC",
    secrets: [CLOUDFLARE_API_TOKEN],
    timeoutSeconds: 540,
  },
  async () => {
    const store = keyStore();
    if (store === undefined) return;
    const [users, claims] = await Promise.all([
      db
        .collection("users")
        .where("addressSetup.status", "==", "active")
        .select("address", "addressSetup.status", "pgp.publicKey")
        .get(),
      db.collection("addresses").select("uid").get(),
    ]);
    const holders = new Map<string, string>();
    for (const claim of claims.docs) {
      const uid: unknown = claim.get("uid");
      if (typeof uid === "string") holders.set(claim.id, uid);
    }
    const result = await reconcileKeys(
      store,
      users.docs.map((doc) => ({ uid: doc.id, data: doc.data() })),
      (uid, address) =>
        holderOf(uid, address, async (name) => holders.get(name)),
    );
    logger.info("WKD keys synced", {
      published: result.published.length,
      unchanged: result.unchanged,
      removed: result.removed,
    });
  },
);
