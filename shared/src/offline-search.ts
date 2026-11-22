// Searching the mail saved on the device, for when Gmail can't be reached: the free text's words
// and the chips the device can answer, over a thread's row and, when it was opened here, its
// messages. Chips it can't answer (dates, sizes, categories…) are skipped, so results may be
// broader than Gmail's.
import { type Chip, isLocalChip, labelQueryValue } from "./search";

/** The words of free text a search looks for, lower-cased: phrases kept whole. */
export type FreeTerms = {
  /** words and phrases that must appear */
  include: string[];
  /** words and phrases (`-word`, `-"a phrase"`) that must not */
  exclude: string[];
};

const TERM = /(-?)"([^"]*)"|(-?)([^\s"(){}]+)/g;
const SPACES = /\s+/g;

/** Words of free text: `-word` excluded, quotes kept as phrases, `OR`/`AND` and brackets dropped. */
export function freeTerms(free: string): FreeTerms {
  const include: string[] = [];
  const exclude: string[] = [];
  for (const match of free.matchAll(TERM)) {
    const negated = (match[1] ?? match[3]) === "-";
    const term = (match[2] ?? match[4] ?? "")
      .toLowerCase()
      .replace(SPACES, " ")
      .trim();
    if (term === "" || term === "or" || term === "and") continue;
    (negated ? exclude : include).push(term);
  }
  return { include, exclude };
}

/** A person on a thread. */
export type SearchPerson = {
  /** display name, empty when none */
  name: string;
  /** address */
  email: string;
};

/** What the device keeps of an opened thread, lower-cased with runs of spaces made one. */
export type SavedText = {
  /** every message's To, Cc and Bcc names and addresses */
  recipients: string;
  /** subjects, addresses and body text of every message */
  text: string;
  /** whether any message has an attachment */
  attachment: boolean;
};

/** What offline search reads of a thread. */
export type OfflineThread = {
  /** the thread's subject */
  subject: string;
  /** Gmail's snippet of its newest message */
  snippet: string;
  /** who wrote its messages */
  senders: readonly SearchPerson[];
  /** its Gmail label ids */
  labelIds: readonly string[];
  /** whether any message is unread */
  unread: boolean;
  /** the thread's messages, when it was opened on this device */
  saved: SavedText | null;
};

/** Text for {@link SavedText}: lower-cased, with runs of spaces made one. */
export function savedSearchText(parts: readonly string[]): string {
  return parts.join("\n").replace(SPACES, " ").toLowerCase();
}

/** Mailboxes `in:` names and the label each means. */
const IN_LABELS: Record<string, string> = {
  inbox: "INBOX",
  sent: "SENT",
  drafts: "DRAFT",
  spam: "SPAM",
  trash: "TRASH",
  starred: "STARRED",
};

/** Whether one chip holds for a thread; null when the device can't tell. */
function chipHolds(
  chip: Chip,
  thread: OfflineThread,
  labels: readonly { id: string; name: string }[],
): boolean | null {
  const value = chip.value.toLowerCase();
  switch (chip.operator) {
    case "from":
      return thread.senders.some(
        (person) =>
          person.name.toLowerCase().includes(value) ||
          person.email.toLowerCase().includes(value),
      );
    case "to":
    case "cc":
    case "bcc":
    case "deliveredto":
      return thread.saved ? thread.saved.recipients.includes(value) : null;
    case "subject":
      return thread.subject.toLowerCase().includes(value);
    case "label": {
      const label = labels.find(
        (candidate) => labelQueryValue(candidate.name) === value,
      );
      return label ? thread.labelIds.includes(label.id) : false;
    }
    case "in": {
      if (value === "anywhere") return true;
      const id = IN_LABELS[value];
      return id === undefined ? null : thread.labelIds.includes(id);
    }
    case "is":
      if (value === "unread") return thread.unread;
      if (value === "read") return !thread.unread;
      if (value === "starred" || value === "pinned") {
        return thread.labelIds.includes("STARRED");
      }
      if (value === "done") return !thread.labelIds.includes("INBOX");
      if (value === "important") return thread.labelIds.includes("IMPORTANT");
      return null;
    case "has":
      if (value === "attachment" && thread.saved) {
        return thread.saved.attachment;
      }
      return null;
    default:
      return null;
  }
}

/**
 * Whether a saved thread matches a search: each included term appears in its subject, snippet,
 * senders or saved messages, no excluded one does, and every chip the device can answer holds
 * (our own chips, like `is:snoozed`, are left to the caller).
 */
export function matchesOffline(
  thread: OfflineThread,
  chips: readonly Chip[],
  terms: FreeTerms,
  labels: readonly { id: string; name: string }[],
): boolean {
  const own = savedSearchText([
    thread.subject,
    thread.snippet,
    ...thread.senders.map((person) => `${person.name} ${person.email}`),
  ]);
  const holds = (term: string) =>
    own.includes(term) || (thread.saved?.text.includes(term) ?? false);
  if (!terms.include.every(holds) || terms.exclude.some(holds)) return false;
  return chips
    .filter((chip) => !isLocalChip(chip))
    .every((chip) => {
      const answer = chipHolds(chip, thread, labels);
      return answer === null || answer !== chip.negated;
    });
}
