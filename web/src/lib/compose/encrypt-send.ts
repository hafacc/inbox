// Encrypting outgoing mail (docs/pgp-contract.md "Sending", docs/compose-contract.md "Encrypted
// sends with Bcc"): whether a message goes encrypted, the messages it becomes, and the outbox ids
// of a send split for Bcc.

import {
  addressKey,
  buildMime,
  type ComposeInput,
  type Mailbox,
  randomBoundary,
} from "@inbox/shared/compose";
import { parseAddressList } from "@inbox/shared/mime";
import { ADDRESS_DOMAIN } from "../address/address-model";
import type { PgpModule } from "../pgp/load";

/** How a message in the composer will go. */
export type SendEncryption =
  /** encrypted: from @hafa.cc, every recipient has a key, and this device holds ours */
  | { kind: "encrypted" }
  /** some recipients have no key */
  | { kind: "missing"; missing: Mailbox[] }
  /** everyone has a key, but this device hasn't unlocked the account's */
  | { kind: "locked" }
  /** still looking up keys */
  | { kind: "pending" }
  /** not from @hafa.cc, the account has no key, or no recipients yet */
  | { kind: "plain" };

/** Whether an address is on the domain whose mail is encrypted. */
export function isEncryptingAddress(address: string): boolean {
  return addressKey(address).endsWith(`@${ADDRESS_DOMAIN}`);
}

/**
 * How the composer's message will go. `keys` maps lower-cased addresses to a key, null for none,
 * and leaves out those still being looked up. Agrees with `encryptionDecision`.
 */
export function sendEncryption(
  from: string,
  recipients: readonly Mailbox[],
  keys: ReadonlyMap<string, string | null>,
  accountHasKey: boolean,
  unlocked: boolean,
): SendEncryption {
  if (!accountHasKey || !isEncryptingAddress(from) || recipients.length === 0) {
    return { kind: "plain" };
  }
  const seen = new Set<string>();
  const missing: Mailbox[] = [];
  let pending = false;
  for (const mailbox of recipients) {
    const key = addressKey(mailbox.email);
    if (seen.has(key)) continue;
    seen.add(key);
    const found = keys.get(key);
    if (found === undefined) pending = true;
    else if (found === null) missing.push(mailbox);
  }
  if (missing.length > 0) return { kind: "missing", missing };
  if (pending) return { kind: "pending" };
  return unlocked ? { kind: "encrypted" } : { kind: "locked" };
}

function firstName(mailbox: Mailbox): string {
  const name = mailbox.name.trim();
  return name === "" ? mailbox.email : (name.split(/\s+/)[0] ?? name);
}

/**
 * The line under the recipients when a message from @hafa.cc isn't plainly encrypted, or null.
 * `sendPlain`: the user chose to send without encryption though everyone has a key.
 */
export function encryptionNote(
  state: SendEncryption,
  sendPlain = false,
): string | null {
  if (state.kind === "locked") {
    return sendPlain
      ? "Not encrypted — you chose to send without encryption"
      : "Encrypted once you unlock this device; Send asks";
  }
  if (state.kind !== "missing") return null;
  const names = state.missing.map(firstName);
  if (names.length === 1) return `Not encrypted — ${names[0]} has no key`;
  if (names.length === 2) {
    return `Not encrypted — ${names[0]} and ${names[1]} have no key`;
  }
  return `Not encrypted — ${names.length} people have no key`;
}

/** What encrypting needs: every recipient's key, the account's public key, and its private key. */
export type EncryptKeys = {
  /** armored keys by lower-cased address */
  recipients: ReadonlyMap<string, string>;
  /** the account's public key */
  own: string;
  /** the unlocked private key, for signing */
  signing: string;
};

/**
 * The messages to save for a composer's content, each whole as text. Without `keys` it is the one
 * message as built. With them, a draft is one message encrypted to everyone with Bcc outside;
 * a send (`split`) is `planEncryptedSends`'s messages, the main one first.
 */
export async function outgoingMessages(
  input: ComposeInput,
  keys: EncryptKeys | null,
  split: boolean,
  pgp: Pick<PgpModule, "encryptMime" | "planEncryptedSends">,
): Promise<string[]> {
  if (keys === null) return [buildMime(input)];
  const keyOf = (address: string) => {
    const key = keys.recipients.get(addressKey(address));
    if (key === undefined) throw new Error(`no key for ${address}`);
    return key;
  };
  if (!split || input.bcc.length === 0) {
    const everyone = [...input.to, ...input.cc, ...input.bcc].map((mailbox) =>
      keyOf(mailbox.email),
    );
    return [
      await pgp.encryptMime(buildMime(input), {
        encryptionKeys: [...new Set([...everyone, keys.own])],
        signingKey: keys.signing,
        boundary: randomBoundary(),
      }),
    ];
  }
  const plan = pgp.planEncryptedSends({
    from: input.from.email,
    to: input.to,
    cc: input.cc,
    bcc: input.bcc,
  });
  const messages: string[] = [];
  for (const planned of plan) {
    const mime = buildMime({
      ...input,
      to: planned.to,
      cc: planned.cc,
      bcc: planned.bcc,
      boundary: randomBoundary(),
    });
    messages.push(
      await pgp.encryptMime(mime, {
        encryptionKeys: [
          ...new Set([...planned.encryptTo.map(keyOf), keys.own]),
        ],
        signingKey: keys.signing,
        boundary: randomBoundary(),
        bccCopy: planned.kind === "bcc",
      }),
    );
  }
  return messages;
}

/** The one recipient on the outside of a split send's Bcc copy (lower-cased), or null. */
export function copyRecipient(mime: string): string | null {
  const head = mime.split(/\r?\n\r?\n/, 1)[0] ?? "";
  const match = /^Bcc:[ \t]*(.*(?:\r?\n[ \t].*)*)/im.exec(head);
  if (match === null) return null;
  const [first] = parseAddressList(match[1]?.replace(/\r?\n[ \t]/g, " ") ?? "");
  return first === undefined ? null : addressKey(first.email);
}
