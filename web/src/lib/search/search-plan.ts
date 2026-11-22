// How a search runs: Gmail's `q` online, our own chips (`is:snoozed`, `is:todo`, `in:outbox`)
// answered on the device, and plain-text matching over the cache while offline.

import {
  freeTerms,
  matchesOffline,
  type SavedText,
  type SearchPerson,
  savedSearchText,
} from "@inbox/shared/offline-search";
import {
  type Chip,
  gmailQueryText,
  isLocalChip,
  parseQuery,
} from "@inbox/shared/search";
import type { GmailLabel } from "../gmail/types";
import type { ThreadDetail } from "../thread-detail";
import type { ThreadSummary } from "../thread-summary";

/** A search query split into what Gmail answers and what the device answers. */
export type SearchPlan = {
  /** Gmail's `q`, without our chips; empty when only our chips are left */
  gmailQuery: string;
  /** `is:snoozed`: only threads (and todos) still snoozed */
  snoozedOnly: boolean;
  /** `is:todo`: only todos, and no mail */
  todosOnly: boolean;
  /** `-is:todo`: no todos */
  noTodos: boolean;
  /** `in:outbox`: show the outbox instead */
  outbox: boolean;
  chips: Chip[];
  free: string;
};

/** Split a whole query (as the search view keeps it) into its plan. */
export function searchPlan(query: string): SearchPlan {
  const { chips, free } = parseQuery(query, null);
  const local = chips.filter(isLocalChip);
  const has = (operator: string, value: string, negated: boolean) =>
    local.some(
      (chip) =>
        chip.operator === operator &&
        chip.value.toLowerCase() === value &&
        chip.negated === negated,
    );
  return {
    gmailQuery: gmailQueryText(chips, free),
    snoozedOnly: has("is", "snoozed", false),
    todosOnly: has("is", "todo", false),
    noTodos: has("is", "todo", true),
    outbox: has("in", "outbox", false),
    chips,
    free,
  };
}

/** What offline matching reads of a thread. */
export type LocalThread = {
  summary: ThreadSummary;
  /** the thread's messages, when it was opened on this device */
  detail: ThreadDetail | null;
};

function savedText(detail: ThreadDetail): SavedText {
  const person = ({ name, email }: SearchPerson) => `${name} ${email}`;
  return {
    recipients: savedSearchText(
      detail.messages
        .flatMap((message) => [
          ...message.to,
          ...(message.headers?.cc ?? []),
          ...(message.headers?.bcc ?? []),
        ])
        .map(person),
    ),
    text: savedSearchText(
      detail.messages.flatMap((message) => [
        message.headers?.subject ?? "",
        person(message.from),
        ...message.to.map(person),
        ...(message.headers?.cc ?? []).map(person),
        ...(message.headers?.bcc ?? []).map(person),
        message.body.text ??
          message.body.html?.replace(/<[^>]*>/g, " ") ??
          message.snippet,
      ]),
    ),
    attachment: detail.messages.some(
      (message) => message.body.attachments.length > 0,
    ),
  };
}

/** Threads in the cache that match a plan ({@link matchesOffline}), newest first. */
export function searchLocal(
  threads: readonly LocalThread[],
  plan: SearchPlan,
  labels: readonly GmailLabel[],
): string[] {
  const terms = freeTerms(plan.free);
  return threads
    .filter(({ summary, detail }) =>
      matchesOffline(
        {
          subject: summary.subject,
          snippet: summary.snippet,
          senders: summary.senders,
          labelIds: summary.labelIds,
          unread: summary.unread,
          saved: detail ? savedText(detail) : null,
        },
        plan.chips,
        terms,
        labels,
      ),
    )
    .sort((left, right) => right.summary.lastDate - left.summary.lastDate)
    .map((thread) => thread.summary.id);
}
