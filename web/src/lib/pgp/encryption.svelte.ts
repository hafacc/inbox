import type { DecryptedMessage, KeyPair } from "@inbox/shared/pgp";
import {
  type DocumentData,
  deleteField,
  doc,
  type Firestore,
  Timestamp,
  updateDoc,
} from "firebase/firestore";
import type { Idb } from "../idb";
import { PARKED_WRAPPING_KEY } from "../parked";
import {
  DEVICE_RECORD_KEY,
  type DeviceKeyRecord,
  makeDeviceRecord,
  privateKeyOf,
} from "./device-key";
import { KeyDirectory, networkFetchers } from "./key-cache";
import { loadPgp } from "./load";
import {
  createPasskey,
  type PasskeyUser,
  rpIdFor,
  type SealedCopy,
  unlockWithPasskey,
} from "./passkey";
import {
  type PgpDoc,
  passkeyName,
  withoutPasskey,
  withPasskey,
} from "./pgp-doc";
import {
  loadSubjects,
  type SubjectSlot,
  saveSubjects,
} from "./sealed-subjects";

const SUBJECTS_KEY = "pgpSubjects";
const SUBJECTS_SAVE_MS = 1_000;

/** The unsealed key doesn't belong to the account's public key. */
export class KeyMismatch extends Error {
  constructor() {
    super("That key isn't this account's key.");
    this.name = "KeyMismatch";
  }
}

function firestorePgp(value: PgpDoc): DocumentData {
  return {
    publicKey: value.publicKey,
    sealed: value.sealed.map((entry) => ({
      credentialId: entry.credentialId,
      salt: entry.salt,
      sealedPrivateKey: entry.sealedPrivateKey,
      name: entry.name,
      addedAt: Timestamp.fromMillis(entry.addedAt),
    })),
    recoverySealed: value.recoverySealed,
  };
}

/**
 * The account's encryption: its key from `users/{uid}.pgp`, whether this device holds it unlocked,
 * setting it up, unlocking, passkeys and the recovery phrase, recipients' keys, and opening
 * encrypted messages. docs/pgp-contract.md.
 */
export class EncryptionState {
  /** `users/{uid}.pgp`, or null when the account has no key. */
  doc = $state.raw<PgpDoc | null>(null);
  /** Whether Firestore and this device's store have both answered. */
  ready = $state(false);
  /** Whether this device holds the account's key unlocked. */
  unlocked = $state(false);
  /** Changes each time this device gains or loses the key, so open threads try again. */
  generation = $state(0);
  /** Decrypted subjects by thread id, for the list. */
  subjects = $state.raw<ReadonlyMap<string, string>>(new Map());
  #unlockRequested = $state(false);
  // Waiting for the unlock dialog to close.
  #unlockWaiters: (() => void)[] = [];
  /** Recipients' public keys. */
  readonly keys: KeyDirectory;

  readonly #uid: string;
  readonly #cache: Idb;
  #db: Firestore | null = null;
  #record: DeviceKeyRecord | null = null;
  #privateKey: Promise<string> | null = null;
  #docLoaded = false;
  #deviceLoaded = false;
  #subjectsTimer: ReturnType<typeof setTimeout> | null = null;
  #markReady: () => void = () => {};
  readonly #readyPromise = new Promise<void>((resolve) => {
    this.#markReady = resolve;
  });

  constructor(uid: string, cache: Idb) {
    this.#uid = uid;
    this.#cache = cache;
    this.keys = new KeyDirectory(
      {
        get: (address) => cache.get("keys", address),
        put: (address, entry) => cache.put("keys", address, entry),
      },
      networkFetchers(
        (address) =>
          `https://keys.openpgp.org/vks/v1/by-email/${encodeURIComponent(address.trim())}`,
      ),
      async (data, address) => (await loadPgp()).keyFromAnswer(data, address),
    );
    void this.#loadDevice();
  }

  /** Whether the account has a key. */
  get hasKey(): boolean {
    return this.doc !== null;
  }

  /** Follow `users/{uid}` (AccountSettings passes each snapshot's `pgp` field). */
  attach(database: Firestore): void {
    this.#db = database;
  }

  /** Take a new `users/{uid}.pgp` value. */
  setDoc(value: PgpDoc | null): void {
    const changed = value?.publicKey !== this.doc?.publicKey;
    this.doc = value;
    this.#docLoaded = true;
    if (changed) void this.#checkDevice();
    else this.#settle();
  }

  async #loadDevice(): Promise<void> {
    this.#record =
      (await this.#cache
        .get("device", DEVICE_RECORD_KEY)
        .catch(() => undefined)) ?? null;
    this.#deviceLoaded = true;
    await this.#checkDevice();
  }

  // The device's key must be the account's current one; a reset elsewhere makes it stale.
  async #checkDevice(): Promise<void> {
    if (!this.#docLoaded || !this.#deviceLoaded) return;
    const record = this.#record;
    let unlocked = false;
    if (record !== null && this.doc !== null) {
      try {
        const pgp = await loadPgp();
        unlocked =
          (await pgp.fingerprintOf(this.doc.publicKey)) === record.fingerprint;
      } catch {
        unlocked = false;
      }
      if (!unlocked) await this.#lock();
    }
    this.#setUnlocked(unlocked);
    this.#settle();
  }

  #settle(): void {
    this.ready = this.#docLoaded && this.#deviceLoaded;
    if (this.ready) this.#markReady();
  }

  /** Resolves once `ready` is first true. */
  whenReady(): Promise<void> {
    return this.#readyPromise;
  }

  #setUnlocked(unlocked: boolean): void {
    if (unlocked !== this.unlocked) {
      this.unlocked = unlocked;
      this.generation++;
      if (unlocked) void this.#loadSubjects();
    }
  }

  /** The unlocked private key, armored, or null when this device doesn't hold it. */
  async privateKey(): Promise<string | null> {
    const record = this.#record;
    if (record === null || !this.unlocked) return null;
    this.#privateKey ??= privateKeyOf(record);
    return this.#privateKey;
  }

  /** The device key that seals cached decrypted mail, or null. */
  deviceKey(): CryptoKey | null {
    return this.unlocked ? (this.#record?.wrappingKey ?? null) : null;
  }

  async #keep(privateKey: string, publicKey: string): Promise<void> {
    const pgp = await loadPgp();
    const fingerprint = await pgp.fingerprintOf(publicKey);
    if ((await pgp.fingerprintOf(privateKey)) !== fingerprint) {
      throw new KeyMismatch();
    }
    // Composer records kept through a forced sign-out are sealed under the device key from before.
    const parked = await this.#cache
      .get("meta", PARKED_WRAPPING_KEY)
      .catch(() => undefined);
    const record = await makeDeviceRecord(
      privateKey,
      fingerprint,
      parked instanceof CryptoKey ? parked : undefined,
    );
    await this.#cache.put("device", DEVICE_RECORD_KEY, record);
    await this.#cache.delete("meta", PARKED_WRAPPING_KEY).catch(() => {});
    this.#record = record;
    this.#privateKey = Promise.resolve(privateKey);
    this.#setUnlocked(true);
  }

  /** Whether the unlock dialog is asked for. */
  get unlockRequested(): boolean {
    return this.#unlockRequested;
  }

  set unlockRequested(value: boolean) {
    this.#unlockRequested = value;
    if (!value) {
      const waiters = this.#unlockWaiters;
      this.#unlockWaiters = [];
      for (const resolve of waiters) resolve();
    }
  }

  /** Show the unlock dialog. */
  requestUnlock = (): void => {
    this.unlockRequested = true;
  };

  /** Whether this device holds the key, after asking the user to unlock it when it doesn't. */
  async unlockForSend(): Promise<boolean> {
    if (this.unlocked) return true;
    const closed = new Promise<void>((resolve) => {
      this.#unlockWaiters.push(resolve);
    });
    this.requestUnlock();
    await closed;
    return this.unlocked;
  }

  /** Forget the unlocked key on this device; the passkey prompt comes back. */
  async #lock(): Promise<void> {
    if (this.#subjectsTimer !== null) clearTimeout(this.#subjectsTimer);
    this.#subjectsTimer = null;
    this.#record = null;
    this.#privateKey = null;
    await this.#cache.delete("device", DEVICE_RECORD_KEY).catch(() => {});
    await this.#cache.delete("meta", PARKED_WRAPPING_KEY).catch(() => {});
    this.subjects = new Map();
    await this.#cache.delete("meta", SUBJECTS_KEY).catch(() => {});
    this.#setUnlocked(false);
  }

  async #write(value: PgpDoc | null): Promise<void> {
    if (this.#db === null) throw new Error("not attached");
    const ref = doc(this.#db, "users", this.#uid);
    await updateDoc(ref, {
      pgp: value === null ? deleteField() : firestorePgp(value),
    });
    this.doc = value;
  }

  /** Make the account's key pair (setup, or reset). */
  async makeKeyPair(address: string, name: string): Promise<KeyPair> {
    return (await loadPgp()).generateKeyPair({ address, name });
  }

  /** Make a passkey and seal a private key with it; throws `PrfUnsupported` without PRF. */
  async sealForNewPasskey(
    privateKey: string,
    user: PasskeyUser,
    label: string,
  ): Promise<SealedCopy> {
    const created = await createPasskey(user, rpIdFor(location.hostname));
    const pgp = await loadPgp();
    return {
      credentialId: created.credentialId,
      salt: created.salt,
      sealedPrivateKey: await pgp.seal(created.secret, privateKey),
      name: passkeyName(label),
      addedAt: Date.now(),
    };
  }

  /** Finish setup (or reset): store the key, its passkey copies and the phrase's seal; keep it here. */
  async finishSetup(
    pair: KeyPair,
    sealed: readonly SealedCopy[],
    phrase: string,
  ): Promise<void> {
    const pgp = await loadPgp();
    const recoverySealed = await pgp.sealWithPhrase(phrase, pair.privateKey);
    await this.#lock();
    await this.#write({
      publicKey: pair.publicKey,
      sealed: [...sealed],
      recoverySealed,
    });
    await this.#keep(pair.privateKey, pair.publicKey);
  }

  /** Unlock this device with one of the account's passkeys. */
  async unlockWithPasskey(): Promise<void> {
    const current = this.doc;
    if (current === null || current.sealed.length === 0) {
      throw new Error("no passkeys");
    }
    const { entry, secret } = await unlockWithPasskey(
      current.sealed,
      rpIdFor(location.hostname),
    );
    const pgp = await loadPgp();
    const privateKey = await pgp.unseal(secret, entry.sealedPrivateKey);
    await this.#keep(privateKey, current.publicKey);
  }

  /** Unlock this device with the recovery phrase; throws `SealError` for a wrong one. */
  async unlockWithPhrase(phrase: string): Promise<void> {
    const current = this.doc;
    if (current === null) throw new Error("no key");
    const pgp = await loadPgp();
    const privateKey = await pgp.unsealWithPhrase(
      phrase,
      current.recoverySealed,
    );
    await this.#keep(privateKey, current.publicKey);
  }

  /** Add a passkey for this device (needs the key unlocked here). */
  async addPasskey(user: PasskeyUser, label: string): Promise<void> {
    const current = this.doc;
    const privateKey = await this.privateKey();
    if (current === null || privateKey === null) throw new Error("locked");
    const added = await this.sealForNewPasskey(privateKey, user, label);
    await this.#write({
      ...current,
      sealed: withPasskey(current.sealed, added),
    });
  }

  /** Stop a passkey unlocking mail. */
  async removePasskey(credentialId: string): Promise<void> {
    const current = this.doc;
    if (current === null) return;
    await this.#write({
      ...current,
      sealed: withoutPasskey(current.sealed, credentialId),
    });
  }

  /** Replace the recovery phrase (needs the key unlocked here). */
  async replacePhrase(phrase: string): Promise<void> {
    const current = this.doc;
    const privateKey = await this.privateKey();
    if (current === null || privateKey === null) throw new Error("locked");
    const pgp = await loadPgp();
    const recoverySealed = await pgp.sealWithPhrase(phrase, privateKey);
    await this.#write({ ...current, recoverySealed });
  }

  /** A new recovery phrase to show. */
  async newPhrase(): Promise<string> {
    return (await loadPgp()).generatePhrase();
  }

  /** The `Autocrypt` header for mail from `address`, or null when the account has no key. */
  async autocryptHeader(address: string): Promise<string | null> {
    const current = this.doc;
    if (current === null) return null;
    return (await loadPgp()).autocryptHeaderValue(address, current.publicKey);
  }

  /**
   * Decrypt a message as Gmail's `raw` gives it, checking the signature against the key we have for
   * `sender` and the account's own (drafts and sent mail are signed with it). Throws `PgpError`, or
   * an Error when this device has no key.
   */
  async open(raw: Uint8Array, sender: string): Promise<DecryptedMessage> {
    const privateKey = await this.privateKey();
    if (privateKey === null) throw new Error("locked");
    const pgp = await loadPgp();
    const senderKey = sender ? await this.keys.keyFor(sender) : null;
    const own = this.doc?.publicKey ?? null;
    return pgp.decryptMessage(raw, {
      decryptionKeys: [privateKey],
      verificationKeys: [
        ...new Set([senderKey, own].filter((key) => key !== null)),
      ],
    });
  }

  /** Take a sender's key from the `Autocrypt` headers of a message that isn't spam. */
  async noteAutocrypt(
    values: readonly string[],
    from: string,
    date: number,
  ): Promise<void> {
    if (values.length === 0 || from === "" || !this.hasKey) return;
    const pgp = await loadPgp();
    const header = pgp.autocryptFor(values, from);
    if (header !== null)
      await this.keys.noteAutocrypt(from, header.keydata, date);
  }

  /** Remember a thread's decrypted subject for the list, sealed on the device. */
  noteSubject(threadId: string, subject: string): void {
    if (this.subjects.get(threadId) === subject) return;
    const next = new Map(this.subjects);
    next.set(threadId, subject);
    this.subjects = next;
    if (this.#subjectsTimer !== null) clearTimeout(this.#subjectsTimer);
    this.#subjectsTimer = setTimeout(() => {
      this.#subjectsTimer = null;
      void this.#saveSubjects();
    }, SUBJECTS_SAVE_MS);
  }

  #subjectSlot(): SubjectSlot {
    return {
      get: () => this.#cache.get("meta", SUBJECTS_KEY),
      put: (value) => this.#cache.put("meta", SUBJECTS_KEY, value),
    };
  }

  async #saveSubjects(): Promise<void> {
    const key = this.deviceKey();
    if (key === null) return;
    try {
      const merged = await saveSubjects(
        this.#subjectSlot(),
        key,
        this.subjects,
      );
      if (this.deviceKey() === key) {
        this.subjects = new Map([...merged, ...this.subjects]);
      }
    } catch (error) {
      console.error("subject cache write failed", error);
    }
  }

  async #loadSubjects(): Promise<void> {
    const key = this.deviceKey();
    if (key === null) return;
    const stored = await loadSubjects(this.#subjectSlot(), key);
    if (stored.size > 0 && this.deviceKey() === key) {
      this.subjects = new Map([...stored, ...this.subjects]);
    }
  }

  /** Stop timers. */
  dispose(): void {
    if (this.#subjectsTimer !== null) clearTimeout(this.#subjectsTimer);
  }
}
