import { addressKey, type Mailbox } from "./address";

/** The headers of the message being replied to that threading needs. */
export type ReplySource = {
  /** its `Message-ID`, angle brackets included */
  messageId: string;
  /** its `References` header, or null */
  references: string | null;
  /** its `In-Reply-To` header, or null */
  inReplyTo: string | null;
  /** its decoded `Subject` */
  subject: string;
};

/** Threading headers for a reply. */
export type ReplyHeaders = {
  /** the reply's subject */
  subject: string;
  /** `In-Reply-To`: the replied-to message's id */
  inReplyTo: string;
  /** `References`: message ids, oldest first */
  references: string[];
};

/** The people on the message being replied to. */
export type ReplyParticipants = {
  /** `From` */
  from: Mailbox[];
  /** `Reply-To`; empty when the header is absent */
  replyTo: Mailbox[];
  /** `To` */
  to: Mailbox[];
  /** `Cc` */
  cc: Mailbox[];
};

/** Recipients of a reply. */
export type ReplyRecipients = {
  /** `To` */
  to: Mailbox[];
  /** `Cc` */
  cc: Mailbox[];
};

const REPLY_PREFIX = /^re(\[\d+\])?\s*:/i;
const FORWARD_PREFIX = /^(fwd?|fw)\s*:/i;
// Control characters aren't taken inside an id: buildMime refuses them in a header.
const MESSAGE_ID = /<[^<>\s\p{Cc}]+>/gu;

/** A reply's subject: kept when it already starts with `Re:` (any case, `Re[2]:` too), else `Re: ` in front. */
export function replySubject(subject: string): string {
  const trimmed = subject.trim();
  if (REPLY_PREFIX.test(trimmed)) return trimmed;
  return trimmed === "" ? "Re:" : `Re: ${trimmed}`;
}

/** A forward's subject: kept when it already starts with `Fwd:` or `Fw:`, else `Fwd: ` in front. */
export function forwardSubject(subject: string): string {
  const trimmed = subject.trim();
  if (FORWARD_PREFIX.test(trimmed)) return trimmed;
  return trimmed === "" ? "Fwd:" : `Fwd: ${trimmed}`;
}

/** The `<…>` message ids in a header value, in order. */
export function messageIds(header: string | null): string[] {
  return header === null ? [] : (header.match(MESSAGE_ID) ?? []);
}

/**
 * Threading headers for replying to `source` (RFC 5322 §3.6.4): `In-Reply-To` is its id;
 * `References` is its `References` (or, without one, its single `In-Reply-To` id) followed by its
 * id, each id once, however long the chain. Null when its `messageId` holds no `<id>`.
 */
export function replyHeaders(source: ReplySource): ReplyHeaders | null {
  const [own] = messageIds(source.messageId);
  if (own === undefined) return null;
  let earlier = messageIds(source.references);
  if (earlier.length === 0) {
    const replied = messageIds(source.inReplyTo);
    if (replied.length === 1) earlier = replied;
  }
  const seen = new Set<string>([own]);
  const references: string[] = [];
  for (const id of earlier) {
    if (seen.has(id)) continue;
    seen.add(id);
    references.push(id);
  }
  references.push(own);
  return {
    subject: replySubject(source.subject),
    inReplyTo: own,
    references,
  };
}

/** Subject and threading headers of a reply or forward; no threading when the source has no `<id>`. */
export type AnswerHeaders = {
  /** the answer's subject */
  subject: string;
  /** `In-Reply-To`, or null when the source's `Message-ID` holds no `<id>` */
  inReplyTo: string | null;
  /** `References`, oldest first; empty when the source's `Message-ID` holds no `<id>` */
  references: string[];
};

/**
 * What keeps an answer to `source` in its thread: a reply's subject (`replySubject`), or with
 * `forward` a forward's (`forwardSubject`), and for both `replyHeaders`' `In-Reply-To` and
 * `References`. Gmail files a message sent with a thread id into that thread only when these hold
 * and the subjects match. A source whose `messageId` holds no `<id>` (blank, or anything else) gives
 * no threading headers, so nothing of it is copied into the answer's header.
 */
export function answerHeaders(
  source: ReplySource,
  forward: boolean,
): AnswerHeaders {
  const subject = forward
    ? forwardSubject(source.subject)
    : replySubject(source.subject);
  const threading = replyHeaders(source);
  if (threading === null) {
    return { subject, inReplyTo: null, references: [] };
  } else {
    return {
      subject,
      inReplyTo: threading.inReplyTo,
      references: threading.references,
    };
  }
}

function withoutAddresses(
  mailboxes: readonly Mailbox[],
  excluded: Set<string>,
): Mailbox[] {
  const out: Mailbox[] = [];
  for (const mailbox of mailboxes) {
    const key = addressKey(mailbox.email);
    if (excluded.has(key)) continue;
    excluded.add(key);
    out.push(mailbox);
  }
  return out;
}

/**
 * Who a reply goes to.
 *
 * To someone else's message: `Reply-To` if present, else `From`. To one of the user's own
 * messages (its `From` is one of `ownAddresses`): the original `To`, or the sender again when that
 * leaves no one. Reply all adds the original `To` and `Cc` as `Cc`. The user's own addresses and
 * repeats (compared case-insensitively) are dropped.
 */
export function replyRecipients(
  original: ReplyParticipants,
  ownAddresses: readonly string[],
  replyAll: boolean,
): ReplyRecipients {
  const own = new Set(ownAddresses.map(addressKey));
  const fromMe = original.from.some((mailbox) =>
    own.has(addressKey(mailbox.email)),
  );
  const used = new Set(own);
  let to: Mailbox[];
  if (fromMe) {
    to = withoutAddresses(original.to, used);
    if (to.length === 0) to = [...original.from];
  } else {
    to = withoutAddresses(
      original.replyTo.length > 0 ? original.replyTo : original.from,
      used,
    );
  }
  for (const mailbox of to) used.add(addressKey(mailbox.email));
  const cc = replyAll
    ? withoutAddresses(
        fromMe ? original.cc : [...original.to, ...original.cc],
        used,
      )
    : [];
  return { to, cc };
}

/** Whether answering `original` offers Reply all: it would reach more than one other person. */
export function offersReplyAll(
  original: ReplyParticipants,
  ownAddresses: readonly string[],
): boolean {
  const { to, cc } = replyRecipients(original, ownAddresses, true);
  return to.length + cc.length > 1;
}

/** The user's address the original was sent to (first in `To`, then `Cc`), to reply from; else `fallback`. */
export function replyFromAddress(
  original: ReplyParticipants,
  ownAddresses: readonly string[],
  fallback: string,
): string {
  const own = new Map(
    ownAddresses.map((address) => [addressKey(address), address]),
  );
  for (const mailbox of [...original.to, ...original.cc]) {
    const match = own.get(addressKey(mailbox.email));
    if (match !== undefined) return match;
  }
  return fallback;
}
