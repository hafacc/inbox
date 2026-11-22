import { joinKeys } from "@inbox/shared/join-keys";
import {
  decodeEntities,
  extractBody,
  headerValue,
  type MessageBody,
  type Person,
  parseAddress,
  parseAddressList,
} from "@inbox/shared/mime";
import { threadDates } from "@inbox/shared/thread-dates";
import { threadEntries } from "@inbox/shared/thread-drafts";
import { compareHistoryIds } from "@inbox/shared/wake";
import type { LocalDraft } from "./compose/local-drafts";
import type { GmailMessage, GmailThread } from "./gmail/types";
import {
  emptyBody,
  isEncryptedMessage,
  type MessagePgp,
  OBSCURED_SUBJECT,
} from "./pgp/message-crypto";
import { LABEL, recipientsOf, type ThreadSummary } from "./thread-summary";

/** One message of an opened thread, ready to show. */
export type MessageView = {
  id: string;
  // Its Gmail thread.
  threadId: string;
  from: Person;
  to: Person[];
  date: number;
  snippet: string;
  unread: boolean;
  body: MessageBody;
  // Missing from threads cached before replies existed; a fresh load fills it in.
  headers?: ReplyFields;
  // `List-Unsubscribe` and `List-Unsubscribe-Post`; missing from threads cached before them.
  listUnsubscribe?: string | null;
  listUnsubscribePost?: string | null;
  // Set when the message is OpenPGP-encrypted; its body is empty until it is decrypted.
  pgp?: MessagePgp;
};

/** The headers replying to or forwarding a message needs. */
export type ReplyFields = {
  messageId: string;
  references: string | null;
  inReplyTo: string | null;
  subject: string;
  // the `Date` header, epoch milliseconds, or null when missing or unreadable
  date?: number | null;
  from: Person[];
  replyTo: Person[];
  to: Person[];
  cc: Person[];
  // `Bcc` as the message carries it (a draft or sent mail); missing from threads saved before it
  bcc?: Person[];
};

/** An opened thread: its subject, its messages and its drafts, each oldest first. */
export type ThreadDetail = {
  // Gmail's thread id
  id: string;
  // the thread's history id when it was fetched
  historyId: string;
  subject: string;
  // sent and received messages
  messages: MessageView[];
  // Gmail's drafts in the thread
  drafts: MessageView[];
};

/** One entry of an open thread as it shows. */
export type ThreadItem =
  /** a sent or received message */
  | { kind: "message"; message: MessageView }
  /** a draft only Gmail has */
  | { kind: "draft"; message: MessageView }
  /** a draft, or a message being sent, as this device holds it */
  | { kind: "local"; draft: LocalDraft; sending: boolean };

function dateHeader(value: string | null): number | null {
  if (value === null) return null;
  const parsed = Date.parse(value.replace(/\s*\([^)]*\)\s*$/, ""));
  return Number.isNaN(parsed) ? null : parsed;
}

function messageView(message: GmailMessage): MessageView {
  const headers = message.payload?.headers;
  const from = headerValue(headers, "From");
  const header = (name: string) => headerValue(headers, name);
  const encrypted = isEncryptedMessage(message);
  return {
    id: message.id,
    threadId: message.threadId,
    from: from ? parseAddress(from) : { name: "", email: "" },
    to: recipientsOf(message),
    date: Number(message.internalDate ?? 0),
    snippet: encrypted ? "" : decodeEntities(message.snippet ?? ""),
    unread: message.labelIds?.includes(LABEL.unread) ?? false,
    body:
      message.payload && !encrypted
        ? extractBody(message.payload)
        : emptyBody(),
    ...(encrypted ? { pgp: { status: "locked", signature: null } } : {}),
    listUnsubscribe: header("List-Unsubscribe"),
    listUnsubscribePost: header("List-Unsubscribe-Post"),
    headers: {
      messageId: header("Message-ID")?.trim() ?? "",
      references: header("References"),
      inReplyTo: header("In-Reply-To"),
      subject: header("Subject")?.trim() ?? "",
      date: dateHeader(header("Date")),
      from: parseAddressList(from),
      replyTo: parseAddressList(header("Reply-To")),
      to: parseAddressList(header("To")),
      cc: parseAddressList(header("Cc")),
      bcc: parseAddressList(header("Bcc")),
    },
  };
}

/** Turn a thread fetched with `format=full` into what the reader shows. */
export function threadDetail(thread: GmailThread): ThreadDetail {
  const all = thread.messages ?? [];
  const isDraft = (message: GmailMessage) =>
    message.labelIds?.includes(LABEL.draft) ?? false;
  const first = thread.messages?.[0];
  return {
    id: thread.id,
    historyId: thread.historyId ?? "0",
    subject: headerValue(first?.payload?.headers, "Subject")?.trim() ?? "",
    messages: all.filter((message) => !isDraft(message)).map(messageView),
    drafts: all.filter(isDraft).map(messageView),
  };
}

/**
 * An open row of several Gmail threads (`details`, lead first) as one thread: the lead's id and
 * subject, the latest history id, and every message and draft by date.
 */
export function joinDetails(details: readonly ThreadDetail[]): ThreadDetail {
  const lead = details[0]!;
  const byDate = (left: MessageView, right: MessageView) =>
    left.date - right.date;
  return {
    ...lead,
    historyId: details.reduce(
      (best, detail) =>
        compareHistoryIds(detail.historyId, best) > 0 ? detail.historyId : best,
      lead.historyId,
    ),
    messages: details.flatMap((detail) => detail.messages).sort(byDate),
    drafts: details.flatMap((detail) => detail.drafts).sort(byDate),
  };
}

/**
 * `detail` with Gmail `messages` new to it added, each in date order with the rest; those it
 * already holds stay as they are.
 */
export function withAddedMessages(
  detail: ThreadDetail,
  messages: readonly GmailMessage[],
): ThreadDetail {
  const known = new Set(
    [...detail.messages, ...detail.drafts].map((message) => message.id),
  );
  const fresh = messages.filter((message) => !known.has(message.id));
  if (fresh.length === 0) return detail;
  const added = threadDetail({ id: detail.id, messages: fresh });
  const byDate = (left: MessageView, right: MessageView) =>
    left.date - right.date;
  return {
    ...detail,
    messages: [...detail.messages, ...added.messages].sort(byDate),
    drafts: [...detail.drafts, ...added.drafts].sort(byDate),
  };
}

/**
 * A saved thread's row from its saved body and every message's labels (`labels`), as
 * `summarizeThread` makes it from Gmail's copy; `base` gives what the body can't (an encrypted
 * message's snippet, and the join keys when the saved messages carry no headers).
 */
export function savedSummary(
  detail: ThreadDetail,
  labels: Readonly<Record<string, readonly string[]>>,
  base: ThreadSummary | undefined,
  historyId: string,
): ThreadSummary {
  const labelsOf = (message: MessageView) =>
    labels[message.id] ??
    (message.unread ? [LABEL.unread] : ([] as readonly string[]));
  const all = [...detail.messages, ...detail.drafts].sort(
    (left, right) => left.date - right.date,
  );
  const counted = detail.messages.length > 0 ? detail.messages : all;
  const threadLabels = new Set<string>();
  for (const message of all) {
    for (const label of labelsOf(message)) threadLabels.add(label);
  }
  const senders: Person[] = [];
  for (const message of counted) {
    const person = message.from;
    if (person.email === "" && person.name === "") continue;
    const known = senders.find((entry) => entry.email === person.email);
    if (known === undefined) senders.push({ ...person });
    else if (known.name === "" && person.name !== "") known.name = person.name;
  }
  const last = counted.at(-1);
  const dates = threadDates(
    all.map((message) => ({
      date: message.date,
      labelIds: labelsOf(message),
    })),
  );
  const hiddenSubject = detail.subject === OBSCURED_SUBJECT;
  const keys = joinKeys(
    all.map((message) => ({
      messageId: message.headers?.messageId ?? null,
      inReplyTo: message.headers?.inReplyTo ?? null,
      references: message.headers?.references ?? null,
      date: message.date,
    })),
    hiddenSubject,
  );
  // A saved copy with no headers to read joins as Gmail's copy did.
  const fromSaved = keys.messageIds.length > 0 || base === undefined;
  return {
    id: detail.id,
    historyId,
    subject: detail.subject,
    snippet: last?.snippet || base?.snippet || "",
    senders,
    messageCount: detail.messages.length,
    lastDate: dates.received,
    sentDate: dates.sent,
    labelIds: [...threadLabels].sort(),
    unread: counted.some((message) => labelsOf(message).includes(LABEL.unread)),
    messageIds: fromSaved ? keys.messageIds : [...base.messageIds],
    ...(hiddenSubject
      ? { answers: fromSaved ? keys.answers : [...(base.answers ?? [])] }
      : {}),
    firstDate: keys.firstDate,
  };
}

/**
 * Gmail thread `threadId`'s own messages and drafts out of `detail` (a joined row's, say), under
 * its id; subject and history id come from `base` when given, else from `detail`.
 */
export function threadPart(
  detail: ThreadDetail,
  threadId: string,
  base: ThreadDetail = detail,
): ThreadDetail {
  const own = (message: MessageView) => message.threadId === threadId;
  return {
    id: threadId,
    historyId: base.historyId,
    subject: base.subject,
    messages: detail.messages.filter(own),
    drafts: detail.drafts.filter(own),
  };
}

/**
 * `detail` with Gmail thread `threadId`'s messages set to the labels history gave them (`labels`,
 * message id to label ids): each one's unread flag follows, and those history deleted go. Other
 * threads' messages of a joined row stay; `detail` itself comes back when nothing changed.
 */
export function relabelDetail(
  detail: ThreadDetail,
  threadId: string,
  labels: Readonly<Record<string, readonly string[]>>,
): ThreadDetail {
  let changed = false;
  const relabel = (views: readonly MessageView[]): MessageView[] =>
    views.flatMap((message) => {
      if (message.threadId !== threadId) return [message];
      const own = labels[message.id];
      if (own === undefined) {
        changed = true;
        return [];
      }
      const unread = own.includes(LABEL.unread);
      if (unread === message.unread) return [message];
      changed = true;
      return [{ ...message, unread }];
    });
  const messages = relabel(detail.messages);
  const drafts = relabel(detail.drafts);
  return changed ? { ...detail, messages, drafts } : detail;
}

/** Whether Gmail thread `threadId` is one `detail` shows. */
export function holdsThread(
  detail: ThreadDetail,
  threadId: string | null | undefined,
): boolean {
  return (
    threadId === detail.id ||
    [...detail.messages, ...detail.drafts].some(
      (message) => message.threadId === threadId,
    )
  );
}

/** The first of Gmail's `drafts` saved in one of `threadIds`. */
export function draftIn<Draft extends { message: { threadId: string } }>(
  drafts: readonly Draft[],
  threadIds: readonly string[],
): Draft | undefined {
  return drafts.find((draft) => threadIds.includes(draft.message.threadId));
}

/**
 * What an open thread shows, in order: its messages and drafts, with this device's drafts of the
 * thread in place of Gmail's copies, and none of the drafts the Outbox holds (`outbox`, their
 * message ids) (`threadEntries` in `@inbox/shared/thread-drafts`).
 */
export function threadItems(
  detail: ThreadDetail,
  local: Iterable<LocalDraft>,
  outbox: ReadonlySet<string> = new Set(),
): ThreadItem[] {
  const gmail = [
    ...detail.messages.map((message) => ({ message, draft: false })),
    ...detail.drafts.map((message) => ({ message, draft: true })),
  ].sort((left, right) => left.message.date - right.message.date);
  const byId = new Map(gmail.map((entry) => [entry.message.id, entry.message]));
  const mine = [...local].filter((draft) =>
    holdsThread(detail, draft.threadId),
  );
  const byLocal = new Map(mine.map((draft) => [draft.localId, draft]));
  return threadEntries(
    gmail.map(({ message, draft }) => ({ id: message.id, draft })),
    mine,
    outbox,
  ).flatMap((entry): ThreadItem[] => {
    if (entry.kind === "local") {
      const draft = byLocal.get(entry.localId);
      return draft ? [{ kind: "local", draft, sending: entry.sending }] : [];
    } else {
      const message = byId.get(entry.id);
      return message ? [{ kind: entry.kind, message }] : [];
    }
  });
}
