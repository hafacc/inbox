import { searchScope, searchTagTerms } from "@inbox/shared/search-scope";
import type { LabelTags } from "@inbox/shared/todos";
import type { FilteredView } from "@inbox/shared/view-filter";
import type { ListOptions } from "./gmail/client";
import { LABEL } from "./thread-summary";

/** A mailbox shown in the main column. */
export type View =
  | {
      kind:
        | "inbox"
        | "snoozed"
        | "outbox"
        | "done"
        | "sent"
        | "drafts"
        | "spam"
        | "todos";
    }
  | { kind: "label"; labelId: string }
  | { kind: "search"; query: string }
  | { kind: "settings"; section: SettingsSection };

/** A page of the settings. */
export type SettingsSection =
  | "general"
  | "addresses"
  | "encryption"
  | "filters"
  | "labels"
  | "shortcuts";

/** The settings pages in order, with their titles. */
export const SETTINGS_SECTIONS: readonly {
  section: SettingsSection;
  title: string;
}[] = [
  { section: "general", title: "General" },
  { section: "addresses", title: "Addresses" },
  { section: "encryption", title: "Encryption" },
  { section: "filters", title: "Filters" },
  { section: "labels", title: "Labels" },
  { section: "shortcuts", title: "Shortcuts & search" },
];

/** Gmail's search for Done: archived mail that isn't spam, trash, sent or a draft. */
export const DONE_QUERY = "-in:inbox -in:spam -in:trash -in:sent -in:draft";

/** A stable key for a view, used for its cached list. */
export function viewKey(view: View): string {
  if (view.kind === "label") return `label:${view.labelId}`;
  if (view.kind === "search") return `search:${view.query}`;
  if (view.kind === "settings") return `settings:${view.section}`;
  return view.kind;
}

/** Whether two views are the same mailbox. */
export function sameView(left: View, right: View): boolean {
  return viewKey(left) === viewKey(right);
}

/**
 * The `threads.list` query for a view; Snoozed, Outbox and Todos have none, as they come from
 * Firestore, nor a search that lists no Gmail mail (`searchScope`), nor settings. A search runs
 * with its `#name`s naming `labels` as their `label:` terms (`searchTagTerms`).
 */
export function listOptions(
  view: View,
  labels: LabelTags = new Map(),
): ListOptions | null {
  switch (view.kind) {
    case "inbox":
      return { labelIds: [LABEL.inbox] };
    case "snoozed":
    case "outbox":
    case "todos":
      return null;
    case "done":
      return { q: DONE_QUERY };
    case "sent":
      return { labelIds: [LABEL.sent] };
    case "drafts":
      return { labelIds: [LABEL.draft] };
    case "spam":
      return { labelIds: [LABEL.spam], includeSpamTrash: true };
    case "label":
      return { labelIds: [view.labelId] };
    case "search": {
      const scope = searchScope(searchTagTerms(view.query, labels));
      if (scope.gmailQuery === null) return null;
      // Gmail leaves spam and trash out of the API's results unless asked.
      return scope.includeSpamTrash
        ? { q: scope.gmailQuery, includeSpamTrash: true }
        : { q: scope.gmailQuery };
    }
    case "settings":
      return null;
  }
}

/** Short names of the fixed mailboxes, for the sidebar and the page title. */
export const VIEW_TITLES = {
  inbox: "Inbox",
  snoozed: "Snoozed",
  outbox: "Outbox",
  done: "Done",
  sent: "Sent",
  drafts: "Drafts",
  spam: "Spam",
  todos: "Todos",
} as const;

/**
 * The list `view` is to its filter (see `viewFilter`), a search's query as it runs with `labels`
 * (`searchTagTerms`); none for one that lists no threads.
 */
export function filteredView(
  view: View,
  labels: LabelTags = new Map(),
): FilteredView | null {
  switch (view.kind) {
    case "inbox":
    case "snoozed":
    case "done":
    case "sent":
    case "drafts":
    case "spam":
      return { kind: view.kind };
    case "label":
      return { kind: "label", labelId: view.labelId };
    case "search":
      return { kind: "search", query: searchTagTerms(view.query, labels) };
    default:
      return null;
  }
}

/** A label's name as a search's `label:` term writes it: lower case, spaces and slashes as dashes. */
export function searchLabelName(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[\s/]+/g, "-");
}

/**
 * The view leaving search returns to, once `next` is shown after `shown`: the view a search was
 * started from, kept while one search follows another.
 */
export function searchOriginAfter(shown: View, next: View, origin: View): View {
  return next.kind === "search" && shown.kind !== "search" ? shown : origin;
}
