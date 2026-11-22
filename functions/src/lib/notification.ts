import type { MulticastMessage } from "firebase-admin/messaging";
import type { GmailMessage } from "./gmail-api.js";
import { headerValue } from "./raw-message.js";
import { decodeEntities } from "./util.js";

/**
 * FCM data payload every client reads; all values are strings, as FCM requires. Android and the
 * web service worker build the notification and its Done / Reply actions from it.
 */
export interface NotificationData {
  /** why it was sent: always new mail */
  kind: "message";
  /** Gmail thread id */
  threadId: string;
  /** Gmail message id the text comes from */
  messageId: string;
  /** sender's display name, or address when there is none */
  sender: string;
  /** sender's address */
  senderAddress: string;
  /** message subject; empty when `encrypted` */
  subject: string;
  /** start of the message text; empty when `encrypted` */
  snippet: string;
  /** `"true"` when the message is OpenPGP-encrypted: the device decrypts it to show more */
  encrypted: "true" | "false";
  /** the account's address, for devices signed in to several */
  account: string;
  /** capability for the `notificationAction` callable */
  actionToken: string;
  /** when the message arrived, in epoch milliseconds */
  sentAt: string;
}

/**
 * FCM data payload asking Android phones to clear notifications about threads read or taken out of
 * the inbox elsewhere. Sent to Android devices only: a web push must show a notification.
 */
export interface ClearData {
  /** always `"clear"` */
  kind: "clear";
  /** the account's address, for devices signed in to several */
  account: string;
  /** Gmail thread ids, comma-separated */
  threadIds: string;
}

/** Most threads one clear push names, keeping it well under FCM's 4 KB. */
export const MAX_CLEARED_THREADS = 100;

/** The clear payloads for these threads, at most `MAX_CLEARED_THREADS` each. */
export function clearData(
  threadIds: readonly string[],
  account: string,
): ClearData[] {
  const payloads: ClearData[] = [];
  for (let start = 0; start < threadIds.length; start += MAX_CLEARED_THREADS) {
    payloads.push({
      kind: "clear",
      account,
      threadIds: threadIds.slice(start, start + MAX_CLEARED_THREADS).join(","),
    });
  }
  return payloads;
}

const SNIPPET_LIMIT = 240;
const SUBJECT_LIMIT = 200;
const SENDER_LIMIT = 100;
const ADDRESS_LIMIT = 254;

/** How long FCM keeps a push for a device that is offline. */
export const PUSH_TTL_MS = 24 * 60 * 60 * 1000;

/**
 * The FCM multicast message carrying `data` to `tokens`. Data keys must avoid FCM's reserved names
 * (`from`, `notification`, `message_type`, and anything starting `google` or `gcm`): FCM refuses
 * the whole message otherwise.
 */
export function fcmMessage(
  tokens: string[],
  data: Record<string, string>,
  priority: "high" | "normal",
): MulticastMessage {
  return {
    tokens,
    data,
    android: { priority, ttl: PUSH_TTL_MS },
    webpush: {
      headers: { Urgency: priority, TTL: String(PUSH_TTL_MS / 1000) },
    },
  };
}

/** Error codes from FCM that mean a device token is gone for good. */
const STALE_TOKEN_CODES: ReadonlySet<string> = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/** Split a `From` header into a display name and an address. */
export function parseSender(from: string | undefined): {
  name: string;
  address: string;
} {
  const raw = (from ?? "").trim();
  const angled = /^(.*?)\s*<([^>]+)>\s*$/.exec(raw);
  if (angled) {
    const [, rawName = "", address = ""] = angled;
    const name = rawName
      .trim()
      .replace(/^"(.*)"$/, "$1")
      .trim();
    return { name: name === "" ? address : name, address };
  } else {
    return { name: raw, address: raw };
  }
}

function clip(text: string, limit: number): string {
  return text.length <= limit ? text : `${text.slice(0, limit - 1)}…`;
}

/**
 * Whether a message is OpenPGP-encrypted: PGP/MIME (`multipart/encrypted` with the
 * `application/pgp-encrypted` protocol), or an inline `-----BEGIN PGP MESSAGE-----` block, which
 * Gmail shows at the start of the snippet.
 */
function isEncrypted(message: GmailMessage): boolean {
  const contentType = (
    headerValue(message.payload?.headers ?? [], "Content-Type") ??
    message.payload?.mimeType ??
    ""
  ).toLowerCase();
  if (
    contentType.startsWith("multipart/encrypted") &&
    (contentType.includes("application/pgp-encrypted") ||
      !contentType.includes("protocol"))
  ) {
    return true;
  }
  return /-----BEGIN PGP MESSAGE-----/.test(message.snippet ?? "");
}

/**
 * Build the data payload for a message. For an encrypted message it carries no subject or text,
 * only who sent it and the ids the device needs to fetch and decrypt it.
 */
export function notificationData(
  message: GmailMessage,
  account: string,
  actionToken: string,
): NotificationData {
  const headers = message.payload?.headers ?? [];
  const sender = parseSender(headerValue(headers, "From"));
  const encrypted = isEncrypted(message);
  return {
    kind: "message",
    threadId: message.threadId,
    messageId: message.id,
    sender: clip(sender.name, SENDER_LIMIT),
    senderAddress: clip(sender.address, ADDRESS_LIMIT),
    subject: encrypted
      ? ""
      : clip(headerValue(headers, "Subject") ?? "", SUBJECT_LIMIT),
    // Gmail escapes HTML in snippets.
    snippet: encrypted
      ? ""
      : clip(decodeEntities(message.snippet ?? ""), SNIPPET_LIMIT),
    encrypted: encrypted ? "true" : "false",
    account,
    actionToken,
    sentAt: message.internalDate ?? "",
  };
}

/** The tokens of a multicast send whose results say the device is gone. */
export function staleTokens(
  tokens: readonly string[],
  results: readonly { success: boolean; error?: { code: string } }[],
): string[] {
  return tokens.filter((_, index) => {
    const result = results[index];
    return (
      result !== undefined &&
      !result.success &&
      result.error !== undefined &&
      STALE_TOKEN_CODES.has(result.error.code)
    );
  });
}
