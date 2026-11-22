// Encrypted messages in threads: spotting them, turning a decrypted message into what the reader
// shows, the badge each gets, and sealing decrypted copies for the offline cache
// (docs/pgp-contract.md "Reading", "Offline cache and search").

import {
  decodeBase64UrlText,
  extractBody,
  headerValue,
  type MessageBody,
  type Person,
  parseAddressList,
  walkParts,
} from "@inbox/shared/mime";
import type {
  DecryptedMessage,
  MimeEntity,
  SignatureStatus,
} from "@inbox/shared/pgp";
import type { GmailMessage, GmailMessagePart } from "../gmail/types";
import type { MessageView, ReplyFields, ThreadDetail } from "../thread-detail";
import { openJson, sealJson } from "./device-key";

/** The outer subject of a message with protected headers. */
export const OBSCURED_SUBJECT = "...";

/** What a row or notification says for an encrypted message this device hasn't opened. */
export const ENCRYPTED_PLACEHOLDER = "Encrypted message";

const INLINE_BEGIN = "-----BEGIN PGP MESSAGE-----";
// Attachments larger than this are left out of the cached copy; opening online decrypts them again.
const MAX_CACHED_ATTACHMENT = 1024 * 1024;

/** Where opening an encrypted message stands. */
export type PgpStatus =
  /** this device has no unlocked key */
  | "locked"
  /** being fetched and decrypted */
  | "decrypting"
  | "decrypted"
  /** encrypted to a key this account doesn't have */
  | "wrong-key"
  /** damaged, or not OpenPGP */
  | "malformed"
  /** couldn't fetch it (offline) */
  | "failed";

/** The encryption side of a message in a thread. */
export type MessagePgp = {
  status: PgpStatus;
  /** the sender's signature, once decrypted */
  signature: SignatureStatus | null;
  /**
   * address headers (lower-case names) that say otherwise inside the message than outside, where
   * no valid signature by the sender vouches for the inside; the outside ones show. Absent when none.
   */
  mismatched?: string[];
  /** the decrypted fields, sealed under the device key; only in the offline cache */
  sealed?: Uint8Array<ArrayBuffer>;
};

/** The warning for address headers a message's inside and outside disagree on, or null. */
export function mismatchWarning(mismatched: readonly string[]): string | null {
  const sender = mismatched.some(
    (name) => name === "from" || name === "reply-to",
  );
  const recipients = mismatched.some((name) => name === "to" || name === "cc");
  const what =
    sender && recipients
      ? "a different sender and recipients"
      : sender
        ? "a different sender"
        : recipients
          ? "different recipients"
          : null;
  return what === null
    ? null
    : `Inside, this message names ${what}, and no signature confirms it. Showing the addresses it was sent with.`;
}

/** Whether a Gmail message (`format=full`) is OpenPGP-encrypted: PGP/MIME, or inline PGP. */
export function isEncryptedMessage(message: GmailMessage): boolean {
  const payload = message.payload;
  if (payload === undefined) return false;
  const contentType = (
    headerValue(payload.headers, "Content-Type") ?? payload.mimeType
  ).toLowerCase();
  if (payload.mimeType.toLowerCase() === "multipart/encrypted") return true;
  if (contentType.startsWith("multipart/encrypted")) return true;
  let inline = false;
  walkParts(payload, (part) => {
    if (inline || part.mimeType.toLowerCase() !== "text/plain") return;
    const data = part.body?.data;
    if (data && decodeBase64UrlText(data).includes(INLINE_BEGIN)) inline = true;
  });
  return inline;
}

/** Whether a list row's thread looks encrypted: the obscured subject, or an armored snippet. */
export function looksEncrypted(subject: string, snippet: string): boolean {
  return (
    subject.trim() === OBSCURED_SUBJECT ||
    snippet.includes(INLINE_BEGIN) ||
    snippet.startsWith("This is an OpenPGP/MIME encrypted message")
  );
}

/** A thread row's subject and snippet, with the decrypted subject when the device has it. */
export function rowText(
  subject: string,
  snippet: string,
  decryptedSubject: string | undefined,
): { subject: string; snippet: string; encrypted: boolean } {
  if (decryptedSubject !== undefined) {
    return { subject: decryptedSubject, snippet: "", encrypted: true };
  } else if (looksEncrypted(subject, snippet)) {
    return { subject: ENCRYPTED_PLACEHOLDER, snippet: "", encrypted: true };
  } else {
    return { subject, snippet, encrypted: false };
  }
}

/** The empty body an encrypted message shows until it is opened. */
export function emptyBody(): MessageBody {
  return {
    html: null,
    text: null,
    attachments: [],
    inline: [],
    calendar: null,
  };
}

/** The shared MIME helpers this module needs (from `@inbox/shared/pgp`, loaded lazily). */
export type MimeTools = {
  decodedBody(entity: MimeEntity): Uint8Array;
  parseContentType(value: string | undefined): {
    type: string;
    params: Record<string, string>;
  };
  header(entity: MimeEntity, name: string): string | undefined;
  decodeHeaderValue(value: string): string;
};

/** Bytes as a binary string (one character per byte), as the shared MIME reader takes them. */
export function binaryString(bytes: Uint8Array): string {
  let binary = "";
  for (let start = 0; start < bytes.length; start += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(start, start + 0x8000));
  }
  return binary;
}

function base64Url(bytes: Uint8Array): string {
  return btoa(binaryString(bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

/** A decrypted MIME tree in Gmail's shape, so `extractBody` reads it like any message. */
export function entityToPart(
  entity: MimeEntity,
  tools: MimeTools,
): GmailMessagePart {
  const disposition = tools.header(entity, "Content-Disposition");
  const filename =
    (disposition ? tools.parseContentType(disposition).params.filename : "") ||
    entity.params.name ||
    "";
  const headers = entity.headers.map((field) => ({
    name: field.name,
    value: field.value,
  }));
  if (entity.parts.length > 0) {
    return {
      mimeType: entity.type,
      filename: "",
      headers,
      body: { size: 0 },
      parts: entity.parts.map((part) => entityToPart(part, tools)),
    };
  }
  const bytes = tools.decodedBody(entity);
  return {
    mimeType: entity.type,
    filename: tools.decodeHeaderValue(filename),
    headers,
    body: { size: bytes.length, data: base64Url(bytes) },
  };
}

function firstText(body: MessageBody): string {
  const text =
    body.text ??
    (body.html ?? "")
      .replace(/<(style|script)[\s\S]*?<\/\1>/gi, " ")
      .replace(/<[^>]+>/g, " ");
  return text.replace(/\s+/g, " ").trim().slice(0, 200);
}

function people(value: string | undefined, fallback: Person[]): Person[] {
  return value === undefined ? fallback : parseAddressList(value);
}

/** A message with its decrypted content in place of the ciphertext. */
export function decryptedView(
  message: MessageView,
  decrypted: DecryptedMessage,
  tools: MimeTools,
): MessageView {
  const body = extractBody(entityToPart(decrypted.entity, tools));
  const inner = decrypted.protectedHeaders ?? {};
  const mismatched = decrypted.mismatchedHeaders ?? [];
  const outer = message.headers;
  const from = people(inner.from, outer?.from ?? [message.from]);
  const to = people(inner.to, outer?.to ?? message.to);
  const cc = people(inner.cc, outer?.cc ?? []);
  const headers: ReplyFields | undefined =
    outer === undefined
      ? undefined
      : {
          ...outer,
          subject: inner.subject ?? outer.subject,
          from,
          to,
          cc,
          replyTo: people(inner["reply-to"], outer.replyTo),
        };
  return {
    ...message,
    from: from[0] ?? message.from,
    to: [...to, ...cc],
    snippet: firstText(body),
    body,
    ...(headers ? { headers } : {}),
    pgp: {
      status: "decrypted",
      signature: decrypted.signature.status,
      ...(mismatched.length > 0 ? { mismatched } : {}),
    },
  };
}

/** The subject a decrypted thread shows: its first decrypted message's protected subject. */
export function decryptedSubject(detail: ThreadDetail): string | undefined {
  for (const message of detail.messages) {
    if (message.pgp?.status === "decrypted" && message.headers?.subject) {
      return message.headers.subject;
    }
  }
  return undefined;
}

/** What a message's encryption badge shows. */
export type PgpBadge = {
  /** a lock: it arrived or left encrypted */
  lock: boolean;
  /** a check: signed by the key we have for the sender */
  signed: boolean;
  /** the badge's label */
  label: string;
  /** a warning line under the header, or null */
  warning: string | null;
  /** shown in place of the body when it can't be shown, or null */
  problem: string | null;
  /** offer to unlock this device */
  unlock: boolean;
};

/** The badge for a message's encryption state; `sender` is how the reader names the sender. */
export function pgpBadge(pgp: MessagePgp, sender: string): PgpBadge {
  const base = {
    lock: true,
    signed: false,
    label: "Encrypted",
    warning: null,
    problem: null,
    unlock: false,
  };
  switch (pgp.status) {
    case "decrypted": {
      const mismatch = mismatchWarning(pgp.mismatched ?? []);
      if (pgp.signature === "valid") {
        return {
          ...base,
          signed: true,
          label: `Encrypted · Signed by ${sender}`,
          warning: mismatch,
        };
      } else if (pgp.signature === "invalid") {
        return {
          ...base,
          warning: `The signature doesn't match the key we have for ${sender}`,
        };
      } else {
        return { ...base, warning: mismatch };
      }
    }
    case "locked":
      return {
        ...base,
        problem: "This message is encrypted. Unlock encrypted mail to read it.",
        unlock: true,
      };
    case "decrypting":
      return { ...base, problem: "Decrypting…" };
    case "wrong-key":
      return { ...base, problem: "Can't open: encrypted to another key" };
    case "malformed":
      return { ...base, problem: "Can't open this encrypted message" };
    case "failed":
      return {
        ...base,
        problem:
          "Couldn't open this encrypted message. It opens once you're back online.",
      };
  }
}

type SealedFields = Pick<MessageView, "from" | "to" | "snippet" | "body"> & {
  headers?: ReplyFields;
  signature: SignatureStatus | null;
  mismatched?: string[];
};

function cachedBody(body: MessageBody): MessageBody {
  return {
    ...body,
    attachments: body.attachments.map((attachment) =>
      attachment.data !== null &&
      attachment.data.length * 0.75 > MAX_CACHED_ATTACHMENT
        ? { ...attachment, data: null }
        : attachment,
    ),
  };
}

/**
 * The copy of a thread for the offline cache: each decrypted message's content sealed under the
 * device key, and nothing decrypted left in the clear. Without a device key, decrypted messages
 * are stored locked.
 */
export async function sealDetail(
  detail: ThreadDetail,
  key: CryptoKey | null,
): Promise<ThreadDetail> {
  const messages = await Promise.all(
    detail.messages.map(async (message): Promise<MessageView> => {
      if (message.pgp === undefined) return message;
      const locked: MessageView = {
        ...message,
        snippet: "",
        body: emptyBody(),
        ...(message.headers
          ? { headers: { ...message.headers, subject: OBSCURED_SUBJECT } }
          : {}),
        pgp: {
          status:
            message.pgp.status === "decrypted" ? "locked" : message.pgp.status,
          signature: null,
        },
      };
      if (message.pgp.status !== "decrypted" || key === null) {
        return locked.pgp?.status === "decrypting"
          ? { ...locked, pgp: { status: "locked", signature: null } }
          : locked;
      }
      const fields: SealedFields = {
        from: message.from,
        to: message.to,
        snippet: message.snippet,
        body: cachedBody(message.body),
        ...(message.headers ? { headers: message.headers } : {}),
        signature: message.pgp.signature,
        ...(message.pgp.mismatched
          ? { mismatched: message.pgp.mismatched }
          : {}),
      };
      return {
        ...locked,
        pgp: {
          status: "locked",
          signature: null,
          sealed: await sealJson(key, fields),
        },
      };
    }),
  );
  return { ...detail, messages };
}

/** A cached thread with its sealed messages opened again; those it can't open stay locked. */
export async function openDetail(
  detail: ThreadDetail,
  key: CryptoKey | null,
): Promise<ThreadDetail> {
  const messages = await Promise.all(
    detail.messages.map(async (message): Promise<MessageView> => {
      const sealed = message.pgp?.sealed;
      if (sealed === undefined || key === null) return message;
      try {
        const fields = await openJson<SealedFields>(key, sealed);
        const { signature, mismatched, ...rest } = fields;
        return {
          ...message,
          ...rest,
          pgp: {
            status: "decrypted",
            signature,
            ...(mismatched ? { mismatched } : {}),
          },
        };
      } catch {
        return { ...message, pgp: { status: "locked", signature: null } };
      }
    }),
  );
  return { ...detail, messages };
}

const DRAFT_HEADERS = [
  "From",
  "To",
  "Cc",
  "Bcc",
  "Subject",
  "In-Reply-To",
  "References",
] as const;

/**
 * An encrypted draft as the composer reads it: the decrypted MIME tree in Gmail's shape, with the
 * protected headers (and the outer `Bcc`, which never goes inside the draft) as its headers.
 */
export function decryptedDraft(
  message: GmailMessage,
  decrypted: DecryptedMessage,
  tools: MimeTools,
): GmailMessage {
  const part = entityToPart(decrypted.entity, tools);
  const outer = message.payload?.headers;
  const inner = decrypted.protectedHeaders ?? {};
  const headers = DRAFT_HEADERS.flatMap((name) => {
    const lower = name.toLowerCase();
    const value =
      inner[lower] ??
      (name === "Subject" ? undefined : headerValue(outer, name));
    return value === undefined || value === null ? [] : [{ name, value }];
  });
  return {
    ...message,
    payload: {
      ...part,
      headers: [
        ...headers,
        ...(part.headers ?? []).filter((header) =>
          /^content-/i.test(header.name),
        ),
      ],
    },
  };
}
