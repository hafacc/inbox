import { addressKey, type Mailbox } from "../compose/address";

/** What an encrypted send is addressed to, as the composer holds it. */
export type EncryptedSendInput = {
  /** the sender's address */
  from: string;
  /** `To` */
  to: readonly Mailbox[];
  /** `Cc` */
  cc: readonly Mailbox[];
  /** `Bcc` */
  bcc: readonly Mailbox[];
};

/** One message of an encrypted send: its own draft and outbox doc. */
export type EncryptedSend = {
  /** `main` goes to To and Cc; `bcc` is one Bcc recipient's copy */
  kind: "main" | "bcc";
  /** `To` for `buildMime` */
  to: Mailbox[];
  /** `Cc` for `buildMime` */
  cc: Mailbox[];
  /** `Bcc` for `buildMime`: empty for `main`, the one recipient for `bcc` */
  bcc: Mailbox[];
  /** addresses (`addressKey` form) whose keys it is encrypted to, besides the sender's own */
  encryptTo: string[];
};

function uniqueKeys(mailboxes: readonly Mailbox[], skip: string): string[] {
  const keys = new Set(mailboxes.map((mailbox) => addressKey(mailbox.email)));
  keys.delete(skip);
  return [...keys];
}

/**
 * Plan the messages of an encrypted send, the way Thunderbird does, so no recipient learns who was
 * Bcc'd (an OpenPGP message names the key ids it is encrypted to).
 *
 * - `main`, when To or Cc is not empty: To and Cc as given, no Bcc, encrypted to the To and Cc
 *   keys. First.
 * - then one `bcc` per Bcc address, in order: To and Cc as given (they travel only inside, see
 *   `ProtectOptions.bccCopy`), Bcc naming only that recipient, encrypted to that recipient's key.
 *   A Bcc address also in To or Cc gets no copy (it has the main message); a repeated one gets one.
 *
 * Every message is also encrypted to the sender's own key, which `encryptTo` leaves out (as it
 * leaves out the sender's address wherever it appears). Build each with
 * `buildMime({ ...input, to, cc, bcc })` and encrypt it with
 * `encryptMime(mime, { encryptionKeys: [...keysOf(encryptTo), ownKey], bccCopy: kind === "bcc", … })`.
 */
export function planEncryptedSends(input: EncryptedSendInput): EncryptedSend[] {
  const from = addressKey(input.from);
  const to = [...input.to];
  const cc = [...input.cc];
  const visible = new Set(
    [...to, ...cc].map((mailbox) => addressKey(mailbox.email)),
  );
  const sends: EncryptedSend[] = [];
  if (visible.size > 0) {
    sends.push({
      kind: "main",
      to,
      cc,
      bcc: [],
      encryptTo: uniqueKeys([...to, ...cc], from),
    });
  }
  const copied = new Set<string>();
  for (const mailbox of input.bcc) {
    const key = addressKey(mailbox.email);
    if (visible.has(key) || copied.has(key)) continue;
    copied.add(key);
    sends.push({
      kind: "bcc",
      to: [...to],
      cc: [...cc],
      bcc: [mailbox],
      encryptTo: key === from ? [] : [key],
    });
  }
  return sends;
}
