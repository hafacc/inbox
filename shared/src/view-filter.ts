// Which threads a list shows by its own filter, so an action takes a row out of a list only when the
// list's filter no longer matches it: Done leaves the inbox but stays in a label, Sent or a search
// that doesn't ask for the inbox. Web uses it directly; Android ports it to :core (ViewFilter.kt).
// Both run shared/testdata/view-filter-fixtures.json.

/** A list as its filter sees it. */
export type FilteredView =
  | {
      kind: "inbox" | "snoozed" | "done" | "sent" | "drafts" | "spam" | "trash";
    }
  | { kind: "label"; labelId: string }
  | { kind: "search"; query: string };

/** What a list's threads must and mustn't carry. */
export type ViewFilter = {
  /** labels a thread must carry */
  requires: readonly string[];
  /** labels a thread mustn't carry */
  excludes: readonly string[];
  /** whether snoozed threads show: only them, never, or either */
  snoozed: "only" | "never" | "either";
};

/** A thread as a filter sees it. */
export type FilteredThread = {
  /** the labels of all its messages */
  labelIds: readonly string[];
  /** whether it is snoozed now */
  snoozed: boolean;
};

const INBOX = "INBOX";
const SPAM = "SPAM";
const TRASH = "TRASH";
const HIDDEN = [SPAM, TRASH];

// Search terms that name a system label, as `in:` or `is:` values.
const SEARCH_LABELS: Readonly<Record<string, string>> = {
  "in:inbox": INBOX,
  "in:spam": SPAM,
  "in:trash": TRASH,
  "in:sent": "SENT",
  "in:draft": "DRAFT",
  "in:drafts": "DRAFT",
  "is:starred": "STARRED",
  "is:pinned": "STARRED",
  "is:unread": "UNREAD",
  "is:important": "IMPORTANT",
};

// Query syntax that groups or alternates terms, which the top-level terms alone can't describe.
const GROUPING = /[(){}]|\bOR\b|\bAND\b/;

/**
 * The filter of `view`. A search is read from its plain top-level terms that name labels
 * (`in:inbox`, `is:starred` or `is:pinned`, `label:name` through `labelIdOf`, each possibly
 * negated with `-`, and `is:done`, which is `-in:inbox`);
 * other terms leave it unfiltered on them, and grouping (`OR`, parentheses, braces) leaves only
 * spam and trash out. Spam and trash are left out of every list but their own and a search that
 * asks for them (`in:spam`, `in:trash`, `in:anywhere`).
 */
export function viewFilter(
  view: FilteredView,
  labelIdOf: (name: string) => string | null = () => null,
): ViewFilter {
  switch (view.kind) {
    case "inbox":
      return { requires: [INBOX], excludes: HIDDEN, snoozed: "never" };
    case "snoozed":
      return { requires: [], excludes: HIDDEN, snoozed: "only" };
    case "done":
      return { requires: [], excludes: [INBOX, ...HIDDEN], snoozed: "never" };
    case "sent":
      return { requires: ["SENT"], excludes: HIDDEN, snoozed: "either" };
    case "drafts":
      return { requires: ["DRAFT"], excludes: HIDDEN, snoozed: "either" };
    case "spam":
      return { requires: [SPAM], excludes: [], snoozed: "either" };
    case "trash":
      return { requires: [TRASH], excludes: [], snoozed: "either" };
    case "label":
      return { requires: [view.labelId], excludes: HIDDEN, snoozed: "either" };
    case "search":
      return searchFilter(view.query, labelIdOf);
  }
}

function searchFilter(
  query: string,
  labelIdOf: (name: string) => string | null,
): ViewFilter {
  const terms = query
    .trim()
    .split(/\s+/)
    .filter((term) => term !== "");
  const anywhere = terms.some((term) => term.toLowerCase() === "in:anywhere");
  const requires = new Set<string>();
  const excludes = new Set<string>();
  let snoozed: ViewFilter["snoozed"] = "either";
  if (!GROUPING.test(query)) {
    for (const term of terms) {
      const negated = term.startsWith("-");
      const bare = (negated ? term.slice(1) : term).toLowerCase();
      if (bare === "in:snoozed" || bare === "is:snoozed") {
        snoozed = negated ? "never" : "only";
        continue;
      }
      if (bare === "is:done") {
        (negated ? requires : excludes).add(INBOX);
        continue;
      }
      const label = bare.startsWith("label:")
        ? labelIdOf(bare.slice("label:".length))
        : (SEARCH_LABELS[bare] ?? null);
      if (label === null) continue;
      if (negated) excludes.add(label);
      else requires.add(label);
    }
  }
  if (!anywhere) {
    for (const hidden of HIDDEN) {
      if (!requires.has(hidden)) excludes.add(hidden);
    }
  }
  return { requires: [...requires], excludes: [...excludes], snoozed };
}

/** Whether `thread` belongs in a list with `filter`. */
export function keepsThread(
  filter: ViewFilter,
  thread: FilteredThread,
): boolean {
  const { labelIds, snoozed } = thread;
  if (filter.snoozed === "only" && !snoozed) return false;
  if (filter.snoozed === "never" && snoozed) return false;
  return (
    filter.requires.every((label) => labelIds.includes(label)) &&
    !filter.excludes.some((label) => labelIds.includes(label))
  );
}

/** Whether an action that turns `before` into `after` takes the thread's row out of a list with `filter`. */
export function actionRemoves(
  filter: ViewFilter,
  before: FilteredThread,
  after: FilteredThread,
): boolean {
  return keepsThread(filter, before) && !keepsThread(filter, after);
}

/**
 * The inbox action a thread's row, header and the selection bar offer: Done for one in the inbox or
 * snoozed, Move to inbox for one that is neither (not one in spam or trash, which have their own).
 */
export function inboxAction(thread: FilteredThread): "done" | "inbox" | null {
  if (thread.labelIds.includes(SPAM) || thread.labelIds.includes(TRASH)) {
    return null;
  } else if (thread.labelIds.includes(INBOX) || thread.snoozed) {
    return "done";
  } else {
    return "inbox";
  }
}

/** Done and Move to inbox across selected threads: the ids each acts on (see {@link inboxAction}). */
export function inboxPlan(
  threads: readonly (FilteredThread & { id: string })[],
): { done: string[]; inbox: string[] } {
  return {
    done: threads
      .filter((thread) => inboxAction(thread) === "done")
      .map((thread) => thread.id),
    inbox: threads
      .filter((thread) => inboxAction(thread) === "inbox")
      .map((thread) => thread.id),
  };
}

/** An action an open thread offers. */
export type ThreadAction =
  | "done"
  | "inbox"
  | "snooze"
  | "wake"
  | "pin"
  | "unpin"
  | "label"
  | "addTodo"
  | "editTodo"
  | "important"
  | "unimportant"
  | "read"
  | "unread"
  | "spam"
  | "notSpam"
  | "delete"
  | "untrash"
  | "filter";

/** What an open thread's actions look at. */
export type ThreadActionState = {
  /** the labels of all its messages */
  labelIds: readonly string[];
  /** whether it is snoozed now */
  snoozed: boolean;
  /** whether any of its messages is unread */
  unread: boolean;
  /** the todo action: add one, edit its own, or none while todos are off */
  todo: "add" | "edit" | null;
  /** whether a sender is known to filter mail like it by */
  hasSender: boolean;
};

/**
 * The actions an open thread offers, in order: Done or Move to inbox (see `inboxAction`), Snooze,
 * Wake now for a snoozed thread, Pin or Unpin, Label, Add or Edit todo, Mark important or not,
 * Mark read or unread, Report spam or Not spam, Delete (Move to inbox from the trash), and Filter
 * mail like this: every action a thread takes anywhere else (its row, a swipe, the selection bar,
 * a key).
 */
export function threadActions(state: ThreadActionState): ThreadAction[] {
  const actions: ThreadAction[] = [];
  const inbox = inboxAction(state);
  if (inbox !== null) actions.push(inbox);
  actions.push("snooze");
  if (state.snoozed) actions.push("wake");
  actions.push(state.labelIds.includes("STARRED") ? "unpin" : "pin");
  actions.push("label");
  if (state.todo === "add") actions.push("addTodo");
  else if (state.todo === "edit") actions.push("editTodo");
  actions.push(
    state.labelIds.includes("IMPORTANT") ? "unimportant" : "important",
  );
  actions.push(state.unread ? "read" : "unread");
  actions.push(state.labelIds.includes(SPAM) ? "notSpam" : "spam");
  actions.push(state.labelIds.includes(TRASH) ? "untrash" : "delete");
  if (state.hasSender) actions.push("filter");
  return actions;
}

/** Where a phone shows an open thread's actions. */
export type ThreadActionPlaces = {
  /** the corner button's ring next to it, from the top: Done or Move to inbox, Snooze, Pin or Unpin */
  inner: ThreadAction[];
  /** the corner button's ring beyond, from the top: Label, Add or Edit todo, Report spam or Not spam, Delete or Move to inbox */
  outer: ThreadAction[];
  /** the marker beside the subject: Mark important or not */
  marker: "important" | "unimportant" | null;
  /** the line above the messages: Wake now and Filter mail like this */
  line: ThreadAction[];
};

// The actions a phone shows on the line above the messages rather than on the corner button.
const LINE_ACTIONS: readonly ThreadAction[] = ["wake", "filter"];

// The corner button's rings, each from the top.
const DISC_INNER: readonly ThreadAction[] = [
  "done",
  "inbox",
  "snooze",
  "pin",
  "unpin",
];
const DISC_OUTER: readonly ThreadAction[] = [
  "label",
  "addTodo",
  "editTodo",
  "spam",
  "notSpam",
  "delete",
  "untrash",
];

/**
 * Where a phone shows each of `actions` (from `threadActions`): Mark important as a marker
 * beside the subject, Wake now and Filter mail like this on the line above the messages, and on
 * the corner button's two rings: Done (Move to inbox), Snooze and Pin (Unpin) next to it, Label,
 * Add or Edit todo, Report spam (Not spam) and Delete (Move to inbox from the trash) beyond. Mark
 * read or unread has no place on a phone.
 */
export function threadActionPlaces(
  actions: readonly ThreadAction[],
): ThreadActionPlaces {
  const marker =
    actions.find(
      (action): action is "important" | "unimportant" =>
        action === "important" || action === "unimportant",
    ) ?? null;
  return {
    inner: DISC_INNER.filter((action) => actions.includes(action)),
    outer: DISC_OUTER.filter((action) => actions.includes(action)),
    marker,
    line: actions.filter((action) => LINE_ACTIONS.includes(action)),
  };
}

/**
 * What an email row's round menu offers of `actions` (from `threadActions`), in its two places as a
 * todo's: Pin or Unpin, then Delete (Move to inbox from the trash). Every other action is on its
 * swipes, the open thread and the selection bar.
 */
export function rowHoldActions(
  actions: readonly ThreadAction[],
): ThreadAction[] {
  const order: readonly ThreadAction[] = ["pin", "unpin", "delete", "untrash"];
  return order.filter((action) => actions.includes(action));
}
