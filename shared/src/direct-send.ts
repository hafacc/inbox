// A send now that is still on screen and online when its undo window ends goes from the device
// (`drafts.send`) instead of waiting for the server's Cloud Task and its cold start; the server
// stays the backup (docs/design.md, "Sending, scheduled send, undo send"). The device claims the
// outbox doc first, as the server does, so the two never both send it. Web uses this directly;
// Android ports it to :core (DirectSend.kt). Both run shared/testdata/direct-send-fixtures.json.

import { addressKey } from "./compose/address";

/** The fields clients write on an outbox doc; any other field is the server's attempt record. */
export const CLIENT_OUTBOX_FIELDS: readonly string[] = [
  "draftId",
  "from",
  "status",
  "sendAt",
  "chosenAt",
  "threadId",
];

/**
 * How long past its `sendAt` a device still sends a send now itself; later, the server has it. The
 * rules refuse a claim more than 60 s past `sendAt` by the server's clock, which leaves room for a
 * device clock up to 30 s behind.
 */
export const DIRECT_SEND_LATE_MS = 30_000;

/**
 * Longest a device lets pass between starting its claim and handing the draft to Gmail; past it,
 * the claim stays as it is and the server's takeover settles the send once the lease ends.
 */
export const DIRECT_HANDOVER_MAX_MS = 60_000;

/**
 * Longest a device counts on Gmail's answer to its `drafts.send`; past it, the server's takeover
 * settles the send once the lease ends, unless the answer still comes first: the device records it
 * under its claim (never sending again), which the rules accept only while that claim stands.
 */
export const DIRECT_SEND_TIMEOUT_MS = 30_000;

/** One address Gmail can send as, as `settings.sendAs.list` gives it. */
export type SendAsAddress = {
  /** the address */
  sendAsEmail: string;
  /** whether it is the account's own Gmail address */
  isPrimary?: boolean;
  /** Gmail's verification of an alias */
  verificationStatus?: string;
};

/**
 * Whether `from` sends through Gmail, by the server's own check: not on `resendDomain`, and the
 * Gmail address or a send-as alias Gmail has verified.
 */
export function isGmailRoute(
  from: string,
  sendAs: readonly SendAsAddress[],
  resendDomain: string,
): boolean {
  const address = addressKey(from);
  if (address.split("@")[1] === resendDomain) return false;
  return sendAs.some(
    (alias) =>
      alias.sendAsEmail.toLowerCase() === address &&
      (alias.isPrimary === true || alias.verificationStatus === "accepted"),
  );
}

/** An outbox doc as the decision reads it. */
export type DirectSendDoc = {
  /** its status */
  status: string;
  /** its send time, epoch milliseconds */
  sendAt: number;
  /** the names of every field it has */
  fields: readonly string[];
};

/** What a device knows when a send now's undo window ends. */
export type DirectSendInput = {
  /** whether this device pressed Send (not a scheduled time) and the send wasn't undone */
  sendNow: boolean;
  /** the outbox docs of the send: 1, or more for an encrypted send with Bcc copies */
  docs: number;
  /** whether the device is online */
  online: boolean;
  /** whether its From sends through Gmail (`isGmailRoute`) */
  gmailRoute: boolean;
  /** the `sendAt` this device wrote */
  wroteSendAt: number;
  /** the doc as read now, or null when it is gone */
  doc: DirectSendDoc | null;
  /** the device clock, epoch milliseconds */
  now: number;
};

/** Whether the device sends it (`send`), or why the server's task does. */
export type DirectSendDecision =
  | "send"
  /** a scheduled send, or one undone or written elsewhere */
  | "scheduled"
  /** an encrypted send with Bcc copies */
  | "copies"
  /** no connection */
  | "offline"
  /** from @hafa.cc, or an address Gmail hasn't verified */
  | "not-gmail"
  /** the doc is gone (undone) */
  | "no-doc"
  /** no longer `waiting`: the server, or another device, has it */
  | "taken"
  /** rescheduled since */
  | "moved"
  /** it carries an attempt record of the server's (a send again after an attempt) */
  | "server-record"
  /** its time hasn't come by this device's clock */
  | "early"
  /** past `DIRECT_SEND_LATE_MS`: the server's task is on it */
  | "late";

/**
 * Whether this device sends a send now itself when its undo window ends: one doc, online, from a
 * Gmail address, still `waiting` at the time this device wrote, with only the fields clients write,
 * and no more than `DIRECT_SEND_LATE_MS` late. Checked on the doc as last seen, and again on the
 * doc as read in the claim's transaction.
 */
export function directSendDecision(input: DirectSendInput): DirectSendDecision {
  const { doc } = input;
  if (!input.sendNow) {
    return "scheduled";
  } else if (input.docs !== 1) {
    return "copies";
  } else if (!input.online) {
    return "offline";
  } else if (!input.gmailRoute) {
    return "not-gmail";
  } else if (doc === null) {
    return "no-doc";
  } else if (doc.status !== "waiting") {
    return "taken";
  } else if (doc.sendAt !== input.wroteSendAt) {
    return "moved";
  } else if (
    !doc.fields.every((field) => CLIENT_OUTBOX_FIELDS.includes(field))
  ) {
    return "server-record";
  } else if (input.now < doc.sendAt) {
    return "early";
  } else if (input.now > doc.sendAt + DIRECT_SEND_LATE_MS) {
    return "late";
  } else {
    return "send";
  }
}

/** A `name: value` header line. */
export type HeaderLine = { name: string; value: string };

// A display name of plain words or quoted strings, then one address in angle brackets.
const NAMED_MAILBOX =
  /^(?:"(?:[^"\\\r\n]|\\.)*"|[^"<>@,;:()\r\n])*<([^<>\s@]+@[^<>\s@]+)>$/;
const BARE_MAILBOX = /^[^"<>@,;:()\s]+@[^"<>@,;:()\s]+$/;

/**
 * The draft's sender, lower-cased, when its headers hold exactly one `From` naming one plain
 * mailbox; null for anything else (two headers, a group, two mailboxes, comments), which the device
 * leaves to the server's full parse.
 */
export function draftSender(headers: readonly HeaderLine[]): string | null {
  const fields = headers.filter((header) => /^from$/i.test(header.name));
  const [field] = fields;
  if (fields.length !== 1 || field === undefined) return null;
  const value = field.value.trim();
  const named = NAMED_MAILBOX.exec(value)?.[1];
  const address = named ?? (BARE_MAILBOX.test(value) ? value : null);
  return address === null ? null : address.toLowerCase();
}

/** A Gmail error as the device saw it: its HTTP status and Google's reason, or null with no answer. */
export type GmailFailure = { status: number; reason: string | null } | null;

const RATE_REASONS = new Set([
  "rateLimitExceeded",
  "userRateLimitExceeded",
  "quotaExceeded",
  "dailyLimitExceeded",
]);

/**
 * What a device does after its own `drafts.send` failed, reading the error as the server does:
 * `rejected` for a refusal retrying can't fix (recorded as `failed` with `rejected`, as the server
 * records it), `leave` for anything else: no answer, a limit, a server error, an expired token or a
 * draft gone (whose send the server looks up). The doc then stays `sending`, and the server's
 * takeover settles it once the lease ends. The device never retries.
 */
export function directSendFailure(error: GmailFailure): "rejected" | "leave" {
  if (error === null) {
    return "leave";
  } else {
    const { status, reason } = error;
    const passing =
      status === 401 ||
      status === 404 ||
      status === 408 ||
      status === 429 ||
      status >= 500 ||
      (status === 403 && RATE_REASONS.has(reason ?? ""));
    return status >= 400 && !passing ? "rejected" : "leave";
  }
}
