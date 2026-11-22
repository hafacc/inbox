import { inInbox } from "@inbox/shared/bundles";
import { joinKeys } from "@inbox/shared/join-keys";
import {
  decodeEntities,
  headerValue,
  type Person,
  parseAddress,
  parseAddressList,
} from "@inbox/shared/mime";
import { threadDates } from "@inbox/shared/thread-dates";
import type { GmailMessage, GmailThread } from "./gmail/types";
import { OBSCURED_SUBJECT } from "./pgp/message-crypto";

export { isPinned } from "@inbox/shared/list-model";

/** What a list row needs to know about a thread. */
export type ThreadSummary = {
  id: string;
  historyId: string;
  subject: string;
  snippet: string;
  // Distinct senders, oldest first.
  senders: Person[];
  // Messages, not counting drafts.
  messageCount: number;
  // Epoch milliseconds lists place, sort and date it by: its latest received message (see `threadDates`).
  lastDate: number;
  // Epoch milliseconds of its latest message the user sent, for Sent.
  sentDate: number;
  // Every label on any message of the thread.
  labelIds: string[];
  unread: boolean;
  // What joining a thread Gmail split off reads (see `joinKeys` and `joinThreads`): the
  // `Message-ID`s of its messages, and only when its first message has the hidden subject, the ids
  // its messages' `In-Reply-To` and `References` name.
  messageIds: string[];
  answers?: string[];
  // Epoch milliseconds of its earliest message, which orders a joined row's threads.
  firstDate: number;
};

/** Gmail's category labels that the inbox treats like user labels, with the names it shows. */
export const CATEGORY_NAMES: Readonly<Record<string, string>> = {
  CATEGORY_PROMOTIONS: "Promotions",
  CATEGORY_SOCIAL: "Social",
  CATEGORY_UPDATES: "Updates",
  CATEGORY_FORUMS: "Forums",
};

/** Gmail's system labels this app reads. */
export const LABEL = {
  inbox: "INBOX",
  starred: "STARRED",
  unread: "UNREAD",
  draft: "DRAFT",
  sent: "SENT",
  spam: "SPAM",
  trash: "TRASH",
  important: "IMPORTANT",
} as const;

function isDraft(message: GmailMessage): boolean {
  return message.labelIds?.includes(LABEL.draft) ?? false;
}

/** Summarize a thread fetched with `format=metadata` (From, Subject) or `format=full`. */
export function summarizeThread(thread: GmailThread): ThreadSummary {
  const messages = thread.messages ?? [];
  const sent = messages.filter((message) => !isDraft(message));
  const counted = sent.length > 0 ? sent : messages;
  const labels = new Set<string>();
  const senders: Person[] = [];
  let unread = false;
  for (const message of messages) {
    for (const label of message.labelIds ?? []) labels.add(label);
  }
  for (const message of counted) {
    if (message.labelIds?.includes(LABEL.unread)) unread = true;
    const from = headerValue(message.payload?.headers, "From");
    if (!from) continue;
    const person = parseAddress(from);
    if (!senders.some((known) => known.email === person.email)) {
      senders.push(person);
    } else if (person.name !== "") {
      const known = senders.find((entry) => entry.email === person.email);
      if (known && known.name === "") known.name = person.name;
    }
  }
  const first = messages[0];
  const last = counted.at(-1);
  const subject = headerValue(first?.payload?.headers, "Subject")?.trim() ?? "";
  const hiddenSubject = subject === OBSCURED_SUBJECT;
  const keys = joinKeys(
    messages.map((message) => {
      const headers = message.payload?.headers;
      return {
        messageId: headerValue(headers, "Message-ID"),
        inReplyTo: headerValue(headers, "In-Reply-To"),
        references: headerValue(headers, "References"),
        date: Number(message.internalDate ?? 0),
      };
    }),
    hiddenSubject,
  );
  const dates = threadDates(
    messages.map((message) => ({
      date: Number(message.internalDate ?? 0),
      labelIds: message.labelIds ?? [],
    })),
  );
  return {
    id: thread.id,
    historyId: thread.historyId ?? last?.historyId ?? "0",
    subject,
    snippet: decodeEntities(last?.snippet ?? thread.snippet ?? ""),
    senders,
    messageCount: sent.length,
    lastDate: dates.received,
    sentDate: dates.sent,
    labelIds: [...labels].sort(),
    unread,
    messageIds: keys.messageIds,
    ...(hiddenSubject ? { answers: keys.answers } : {}),
    firstDate: keys.firstDate,
  };
}

/** The thread as Sent lists it: placed, sorted and dated by its latest message the user sent. */
export function sentDated(thread: ThreadSummary): ThreadSummary {
  return { ...thread, lastDate: thread.sentDate };
}

/** Whether the thread is in the inbox: there, and not in spam or trash. */
export function isInInbox(thread: ThreadSummary): boolean {
  return inInbox(thread.labelIds);
}

function firstName(person: Person): string {
  const name = person.name.trim();
  if (name === "") return person.email.split("@")[0] ?? person.email;
  return name.split(/\s+/)[0] ?? name;
}

/** Name for a sender: their display name, else the address's local part; "me" for the account itself. */
export function displayName(person: Person, selfEmail: string): string {
  if (person.email === selfEmail.toLowerCase()) return "me";
  if (person.name.trim() !== "") return person.name.trim();
  return person.email.split("@")[0] ?? person.email;
}

/** The sender column: one sender by full name, several by first names (up to three). */
export function sendersLabel(senders: Person[], selfEmail: string): string {
  if (senders.length === 0) return "(no sender)";
  if (senders.length === 1) return displayName(senders[0]!, selfEmail);
  const self = selfEmail.toLowerCase();
  const names = senders.map((person) =>
    person.email === self ? "me" : firstName(person),
  );
  const shown = names.slice(0, 3).join(", ");
  return names.length > 3 ? `${shown} …` : shown;
}

/** The thread's user labels, in the given label order. */
export function userLabelsOf(
  thread: ThreadSummary,
  userLabelIds: readonly string[],
): string[] {
  return userLabelIds.filter((id) => thread.labelIds.includes(id));
}

/** Recipients of a message header, for the "to …" line. */
export function recipientsOf(message: GmailMessage): Person[] {
  const headers = message.payload?.headers;
  return [
    ...parseAddressList(headerValue(headers, "To")),
    ...parseAddressList(headerValue(headers, "Cc")),
  ];
}
