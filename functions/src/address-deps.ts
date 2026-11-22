import {
  FieldValue,
  Timestamp,
  type Transaction,
} from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { addressOf, nameOf } from "../../shared/src/address-name.js";
import { gmailForUser } from "./account-access.js";
import {
  auth,
  cloudTaskQueue,
  db,
  type PrivateDoc,
  privateDoc,
  userDoc,
} from "./admin.js";
import {
  CLOUDFLARE_ACCOUNT_ID,
  CLOUDFLARE_API_TOKEN,
  CLOUDFLARE_ZONE_ID,
  tokenSealer,
} from "./config.js";
import {
  type AddressClaim,
  type AddressDeps,
  type AddressStore,
  type CheckTask,
  type ClaimFields,
  decideClaim,
  isBehind,
  type NameRecord,
  retiredNameAmong,
  type SetupState,
  type SetupStatus,
} from "./lib/address.js";
import { emailRouting } from "./lib/cloudflare.js";
import { type HolderKeyStore, holderHasher } from "./lib/holder-hash.js";
import { openVerificationLink } from "./lib/verification-mail.js";

/** Name of the check task function, which is also its queue's name. */
const CHECK_FUNCTION = "addressCheck";

const STATUSES: readonly SetupStatus[] = [
  "pending",
  "verifying",
  "active",
  "releasing",
];

function addressDoc(name: string) {
  return db.collection("addresses").doc(name);
}

/** Field of `addresses/{name}` holding the keyed hash of its holder's uid, kept after retiring. */
const HOLDER_HASH = "holderHash";

/** The KMS-sealed key behind holder hashes; function-only, like `private/**`. */
const holderKeyDoc = db.collection("serverKeys").doc("addressHolder");

function formerNameOf(
  docs: readonly { id: string; data(): Record<string, unknown> }[],
): string | undefined {
  return retiredNameAmong(
    docs.map((entry) => ({ name: entry.id, uid: entry.data().uid })),
  );
}

const holderKeys: HolderKeyStore = {
  async read() {
    const sealed = (await holderKeyDoc.get()).data()?.sealed;
    return typeof sealed === "string" ? sealed : undefined;
  },
  async createIfAbsent(sealed) {
    return db.runTransaction(async (transaction) => {
      const stored = (await transaction.get(holderKeyDoc)).data()?.sealed;
      if (typeof stored === "string") return stored;
      transaction.create(holderKeyDoc, { sealed });
      return sealed;
    });
  },
};

let hasher: ((uid: string) => Promise<string>) | undefined;

function liveHolderHash(uid: string): Promise<string> {
  hasher ??= holderHasher(holderKeys, tokenSealer());
  return hasher(uid);
}

async function formerNameIn(
  transaction: Transaction,
  holderHash: string,
): Promise<string | undefined> {
  const found = await transaction.get(
    db.collection("addresses").where(HOLDER_HASH, "==", holderHash),
  );
  return formerNameOf(found.docs);
}

/** The uid that holds `addresses/{name}`, or undefined. */
export async function addressHolder(name: string): Promise<string | undefined> {
  const holder = (await addressDoc(name).get()).data()?.uid;
  return typeof holder === "string" ? holder : undefined;
}

/** The uid that holds `addresses/{name}` once its setup is active (not pending, verifying or releasing), or undefined. */
export async function activeAddressHolder(
  name: string,
): Promise<string | undefined> {
  const data = (await addressDoc(name).get()).data();
  return typeof data?.uid === "string" && data.status === "active"
    ? data.uid
    : undefined;
}

/** Read `addresses/{name}`, undefined when missing or malformed. */
function claimFromDoc(
  name: string,
  data: Record<string, unknown> | undefined,
): AddressClaim | undefined {
  if (data === undefined) return undefined;
  const {
    uid,
    createdAt,
    destination,
    status,
    destinationId,
    ruleId,
    verificationSentAt,
    releasingAt,
  } = data;
  if (
    typeof uid !== "string" ||
    !(createdAt instanceof Timestamp) ||
    typeof destination !== "string" ||
    !STATUSES.includes(status as SetupStatus)
  ) {
    return undefined;
  }
  return {
    name,
    uid,
    createdAt: createdAt.toMillis(),
    destination,
    status: status as SetupStatus,
    ...(typeof destinationId === "string" ? { destinationId } : {}),
    ...(typeof ruleId === "string" ? { ruleId } : {}),
    ...(verificationSentAt instanceof Timestamp
      ? { verificationSentAt: verificationSentAt.toMillis() }
      : {}),
    ...(releasingAt instanceof Timestamp
      ? { releasingAt: releasingAt.toMillis() }
      : {}),
  };
}

/** Whether `addresses/{name}` still holds this very claim (same holder, same claim time). */
function sameClaim(
  current: AddressClaim | undefined,
  claim: AddressClaim,
): current is AddressClaim {
  return current?.uid === claim.uid && current.createdAt === claim.createdAt;
}

function setupField(setup: SetupState) {
  return {
    status: setup.status,
    error: setup.error ?? null,
    updatedAt: FieldValue.serverTimestamp(),
  };
}

function claimFieldsForDoc(fields: ClaimFields): Record<string, unknown> {
  return {
    ...(fields.status === undefined ? {} : { status: fields.status }),
    ...(fields.destinationId === undefined
      ? {}
      : { destinationId: fields.destinationId }),
    ...(fields.ruleId === undefined ? {} : { ruleId: fields.ruleId }),
    ...(fields.verificationSentAt === undefined
      ? {}
      : {
          verificationSentAt: Timestamp.fromMillis(fields.verificationSentAt),
        }),
  };
}

async function readClaim(
  transaction: Transaction,
  name: string,
): Promise<AddressClaim | undefined> {
  return claimFromDoc(
    name,
    (await transaction.get(addressDoc(name))).data() as
      | Record<string, unknown>
      | undefined,
  );
}

// Any doc that isn't a readable claim still blocks the name, so a malformed one never frees it.
async function readRecord(
  transaction: Transaction,
  name: string,
): Promise<NameRecord> {
  const found = await transaction.get(addressDoc(name));
  if (!found.exists) {
    return undefined;
  } else {
    return (
      claimFromDoc(name, found.data() as Record<string, unknown>) ?? "retired"
    );
  }
}

async function claimNameOf(
  transaction: Transaction,
  uid: string,
): Promise<string | undefined> {
  const address = (await transaction.get(userDoc(uid))).data()?.address;
  if (typeof address !== "string") {
    return undefined;
  } else {
    return nameOf(address);
  }
}

/** The address claims on Firestore. */
export const firestoreAddresses: AddressStore = {
  async accountEmail(uid) {
    const data = (await privateDoc(uid).get()).data() as PrivateDoc | undefined;
    return data?.email;
  },
  async create(uid, name, destination, now, holderHash) {
    return db.runTransaction(async (transaction) => {
      const held = await claimNameOf(transaction, uid);
      const claims = await transaction.get(
        db.collection("addresses").where("uid", "==", uid),
      );
      const record = await readRecord(transaction, name);
      const former = await formerNameIn(transaction, holderHash);
      const owned = [
        ...(held === undefined ? [] : [held]),
        ...claims.docs.map((entry) => entry.id),
      ];
      const decision = decideClaim(uid, name, record, owned, former);
      if (decision.kind !== "free") return decision;
      const claim: AddressClaim = {
        name,
        uid,
        createdAt: now,
        destination,
        status: "pending",
      };
      // Replaces the retired doc when the account takes its old name back.
      transaction.set(addressDoc(name), {
        uid,
        createdAt: Timestamp.fromMillis(now),
        destination,
        status: "pending",
        [HOLDER_HASH]: holderHash,
      });
      transaction.set(
        userDoc(uid),
        {
          address: addressOf(name),
          addressSetup: setupField({ status: "pending" }),
        },
        { merge: true },
      );
      return { kind: "created" as const, claim };
    });
  },
  async formerName(holderHash) {
    const found = await db
      .collection("addresses")
      .where(HOLDER_HASH, "==", holderHash)
      .get();
    return formerNameOf(found.docs);
  },
  async claim(name) {
    return claimFromDoc(
      name,
      (await addressDoc(name).get()).data() as
        | Record<string, unknown>
        | undefined,
    );
  },
  async claimOf(uid) {
    const address = (await userDoc(uid).get()).data()?.address;
    if (typeof address !== "string") return undefined;
    const claim = await this.claim(nameOf(address));
    return claim?.uid === uid ? claim : undefined;
  },
  async verifyingFor(destination) {
    const found = await db
      .collection("addresses")
      .where("destination", "==", destination)
      .where("status", "==", "verifying")
      .limit(1)
      .get();
    const entry = found.docs[0];
    return entry === undefined
      ? undefined
      : claimFromDoc(entry.id, entry.data());
  },
  async unfinished() {
    const found = await db
      .collection("addresses")
      .where("status", "in", ["pending", "verifying"])
      .get();
    return found.docs
      .map((entry) => claimFromDoc(entry.id, entry.data()))
      .filter((claim) => claim !== undefined);
  },
  async claimsUsing(destination) {
    const found = await db
      .collection("addresses")
      .where("destination", "==", destination)
      .get();
    return found.docs.map((entry) => entry.id);
  },
  async update(claim, fields, setup) {
    return db.runTransaction(async (transaction) => {
      const current = await readClaim(transaction, claim.name);
      if (!sameClaim(current, claim) || current.status === "releasing") {
        return "gone" as const;
      }
      if (isBehind(current.status, setup.status)) return "behind" as const;
      const docFields = claimFieldsForDoc(fields);
      // Firestore refuses an update with no fields; a save can change only the setup progress.
      if (Object.keys(docFields).length > 0) {
        transaction.update(addressDoc(claim.name), docFields);
      }
      transaction.set(
        userDoc(claim.uid),
        { address: addressOf(claim.name), addressSetup: setupField(setup) },
        { merge: true },
      );
      return "saved" as const;
    });
  },
  async beginRelease(uid, now) {
    return db.runTransaction(async (transaction) => {
      const name = await claimNameOf(transaction, uid);
      if (name === undefined) return undefined;
      const claim = await readClaim(transaction, name);
      if (claim?.uid !== uid) {
        transaction.set(
          userDoc(uid),
          { address: null, addressSetup: null },
          { merge: true },
        );
        return undefined;
      }
      transaction.update(addressDoc(name), {
        status: "releasing",
        releasingAt: Timestamp.fromMillis(now),
      });
      transaction.set(
        userDoc(uid),
        { addressSetup: setupField({ status: "releasing" }) },
        { merge: true },
      );
      return { ...claim, status: "releasing" as const };
    });
  },
  async releasing() {
    const found = await db
      .collection("addresses")
      .where("status", "==", "releasing")
      .get();
    return found.docs
      .map((entry) => claimFromDoc(entry.id, entry.data()))
      .filter((claim) => claim !== undefined);
  },
  async rollBack(claim) {
    return db.runTransaction(async (transaction) => {
      const current = await readClaim(transaction, claim.name);
      if (
        !sameClaim(current, claim) ||
        current.status !== "releasing" ||
        current.releasingAt !== claim.releasingAt
      ) {
        return false;
      }
      transaction.update(addressDoc(claim.name), {
        status: "pending",
        releasingAt: FieldValue.delete(),
      });
      transaction.set(
        userDoc(claim.uid),
        {
          address: addressOf(claim.name),
          addressSetup: setupField({ status: "pending" }),
        },
        { merge: true },
      );
      return true;
    });
  },
  async retire(claim, holderHash) {
    await db.runTransaction(async (transaction) => {
      const current = await readClaim(transaction, claim.name);
      if (current?.uid === claim.uid && current.createdAt === claim.createdAt) {
        // The id keeps the name taken; the hash lets only the same account take it back.
        transaction.set(addressDoc(claim.name), { [HOLDER_HASH]: holderHash });
      }
      transaction.set(
        userDoc(claim.uid),
        { address: null, addressSetup: null },
        { merge: true },
      );
    });
  },
};

/** The `addressCheck` Cloud Tasks queue through the Admin SDK. */
export const checkQueue = cloudTaskQueue<CheckTask>(CHECK_FUNCTION);

/**
 * The production wiring of the address handlers; call only inside a handler that declares the
 * `CLOUDFLARE_API_TOKEN` and `GOOGLE_CLIENT_SECRET` secrets.
 */
export function liveAddressDeps(): AddressDeps {
  return {
    store: firestoreAddresses,
    routing() {
      const config = {
        apiToken: CLOUDFLARE_API_TOKEN.value(),
        accountId: CLOUDFLARE_ACCOUNT_ID.value(),
        zoneId: CLOUDFLARE_ZONE_ID.value(),
      };
      return config.apiToken === "" ||
        config.accountId === "" ||
        config.zoneId === ""
        ? undefined
        : emailRouting(config);
    },
    mailboxFor: async (uid) => (await gmailForUser(uid))?.gmail,
    openLink: (link) => openVerificationLink(link),
    queue: checkQueue,
    holderHash: liveHolderHash,
    async userExists(uid) {
      try {
        await auth.getUser(uid);
        return true;
      } catch (error) {
        if ((error as { code?: string }).code === "auth/user-not-found") {
          return false;
        }
        throw error;
      }
    },
    now: Date.now,
    log: logger,
  };
}
