// Selecting several threads in a list and acting on them together. Pure; web uses it directly,
// Android ports it to :core (selection-fixtures.json).
import { threadCategory } from "./categories";

/** A selected thread as the selection bar's actions see it. */
export type SelectedThread = {
  /** Gmail thread id */
  id: string;
  /** the labels of all its messages */
  labelIds: readonly string[];
  /** its snooze status where it is listed */
  status: "snoozed" | "lapsed" | "none";
};

/** A toggle the bar offers for the whole set: what it does, and the threads it changes. */
export type SetToggle = {
  /** whether it turns the state on (pin, mark unread, report spam) rather than off */
  on: boolean;
  /** the threads it changes */
  ids: string[];
};

/** What each of the selection bar's actions does to the selected threads. */
export type SelectionPlan = {
  /** threads Done takes out of the inbox */
  done: string[];
  /** snoozed threads Wake now brings back */
  wake: string[];
  /** Pin: pins the unpinned ones, or unpins all when every one is pinned */
  pin: SetToggle;
  /** Mark read: reads the unread ones, or marks all unread when none is */
  unread: SetToggle;
  /** Spam: reports the ones not in spam, or takes all out when every one is */
  spam: SetToggle;
};

/** A selected todo as the selection bar's actions see it. */
export type SelectedTodo = {
  /** todo id */
  id: string;
  /** whether it is done */
  done: boolean;
  /** whether it is pinned */
  pinned: boolean;
};

/** Pin across selected threads and todos together, and what it changes in each. */
export type MixedPin = {
  /** whether it pins (some thread or todo isn't pinned) rather than unpins */
  on: boolean;
  /** the threads it changes */
  threads: string[];
  /** the todos it changes */
  todos: string[];
};

/** Delete across selected threads and todos together, and what it changes in each. */
export type MixedDelete = {
  /** whether it moves the threads out of the trash to the inbox (every one is there and no todo is selected) rather than deleting */
  untrash: boolean;
  /** the threads it moves to the trash, or out of it */
  threads: string[];
  /** the todos it deletes */
  todos: string[];
};

/** What Done, Snooze, Pin and Delete do to selected todos, beside the selected threads. */
export type TodoSelectionPlan = {
  /** todos Done marks done */
  done: string[];
  /** todos Snooze snoozes */
  snooze: string[];
  /** Pin across threads and todos */
  pin: MixedPin;
  /** Delete across threads and todos */
  delete: MixedDelete;
};

/** One label's state across the selected threads, and what choosing it changes. */
export type LabelChoice = {
  /** whether every, some or none of the threads carry it */
  state: "all" | "some" | "none";
  /** threads choosing it adds it to */
  add: string[];
  /** threads choosing it removes it from */
  remove: string[];
};

/** An action the selection bar applies to every selected thread. */
export type SetAction =
  | "done"
  | "snooze"
  | "wake"
  | "pin"
  | "unread"
  | "spam"
  | "label"
  | "category";

const INBOX = "INBOX";
const STARRED = "STARRED";
const UNREAD = "UNREAD";
const SPAM = "SPAM";
const TRASH = "TRASH";

// Actions that take threads out of the list, or move them where the selection no longer makes sense.
const CLEARS: ReadonlySet<SetAction> = new Set([
  "done",
  "snooze",
  "wake",
  "spam",
  "category",
]);

/** Select `id`, or unselect it when it already is; a newly selected id goes last. */
export function toggleSelected(
  selected: readonly string[],
  id: string,
): string[] {
  if (selected.includes(id)) return selected.filter((other) => other !== id);
  else return [...selected, id];
}

/**
 * Shift-click: select every id from `anchor` to `id` in list `order`, keeping what was selected.
 * Without an anchor in the list, or with `id` not in it, it toggles `id` alone.
 */
export function selectRange(
  selected: readonly string[],
  order: readonly string[],
  anchor: string | null,
  id: string,
): string[] {
  const from = anchor === null ? -1 : order.indexOf(anchor);
  const to = order.indexOf(id);
  if (from === -1 || to === -1) return toggleSelected(selected, id);
  const range = order.slice(Math.min(from, to), Math.max(from, to) + 1);
  return [...selected, ...range.filter((other) => !selected.includes(other))];
}

/** The selected ids still shown in the list, in the order they were selected. */
export function keepShown(
  selected: readonly string[],
  shown: readonly string[],
): string[] {
  const listed = new Set(shown);
  return selected.filter((id) => listed.has(id));
}

/** Whether the selection ends once an action is applied to it, or stays for the next one. */
export function selectionAfter(action: SetAction): "clear" | "keep" {
  if (CLEARS.has(action)) return "clear";
  else return "keep";
}

/** What each of the selection bar's actions would do to `threads`. */
export function selectionPlan(
  threads: readonly SelectedThread[],
): SelectionPlan {
  const has = (thread: SelectedThread, label: string) =>
    thread.labelIds.includes(label);
  const ids = (list: readonly SelectedThread[]) =>
    list.map((thread) => thread.id);
  const unpinned = threads.filter((thread) => !has(thread, STARRED));
  const unread = threads.filter((thread) => has(thread, UNREAD));
  const notSpam = threads.filter((thread) => !has(thread, SPAM));
  return {
    done: ids(
      notSpam.filter(
        (thread) => has(thread, INBOX) || thread.status !== "none",
      ),
    ),
    wake: ids(threads.filter((thread) => thread.status === "snoozed")),
    pin:
      unpinned.length > 0
        ? { on: true, ids: ids(unpinned) }
        : { on: false, ids: ids(threads) },
    unread:
      unread.length > 0
        ? { on: false, ids: ids(unread) }
        : { on: true, ids: ids(threads) },
    spam:
      notSpam.length > 0
        ? { on: true, ids: ids(notSpam) }
        : { on: false, ids: ids(threads) },
  };
}

/** `labelId` across `threads`; choosing it adds it to all unless all carry it, then removes it. */
export function labelChoice(
  threads: readonly SelectedThread[],
  labelId: string,
): LabelChoice {
  const carrying = threads.filter((thread) =>
    thread.labelIds.includes(labelId),
  );
  if (threads.length > 0 && carrying.length === threads.length) {
    return {
      state: "all",
      add: [],
      remove: carrying.map((thread) => thread.id),
    };
  }
  return {
    state: carrying.length === 0 ? "none" : "some",
    add: threads
      .filter((thread) => !thread.labelIds.includes(labelId))
      .map((thread) => thread.id),
    remove: [],
  };
}

/** The category every one of `threads` is in, or null when they differ (or none is given). */
export function selectionCategory(
  threads: readonly SelectedThread[],
): string | null {
  const categories = new Set(
    threads.map((thread) => threadCategory(thread.labelIds)),
  );
  if (categories.size === 1) return [...categories][0] ?? null;
  else return null;
}

/**
 * What Done, Snooze, Pin and Delete do to `todos` selected with `threads`; Done and Snooze leave a
 * done todo alone, while Pin takes it as it takes an archived thread. Delete moves the threads not in the trash there and deletes every todo; with
 * every thread in the trash and no todo it moves them back to the inbox instead.
 */
export function todoSelectionPlan(
  threads: readonly SelectedThread[],
  todos: readonly SelectedTodo[],
): TodoSelectionPlan {
  const openIds = todos.filter((todo) => !todo.done).map((todo) => todo.id);
  const unpinnedThreads = threads.filter(
    (thread) => !thread.labelIds.includes(STARRED),
  );
  const unpinnedTodos = todos.filter((todo) => !todo.pinned);
  const pin: MixedPin =
    unpinnedThreads.length > 0 || unpinnedTodos.length > 0
      ? {
          on: true,
          threads: unpinnedThreads.map((thread) => thread.id),
          todos: unpinnedTodos.map((todo) => todo.id),
        }
      : {
          on: false,
          threads: threads.map((thread) => thread.id),
          todos: todos.map((todo) => todo.id),
        };
  const untrashed = threads.filter(
    (thread) => !thread.labelIds.includes(TRASH),
  );
  const remove: MixedDelete =
    threads.length > 0 && untrashed.length === 0 && todos.length === 0
      ? {
          untrash: true,
          threads: threads.map((thread) => thread.id),
          todos: [],
        }
      : {
          untrash: false,
          threads: untrashed.map((thread) => thread.id),
          todos: todos.map((todo) => todo.id),
        };
  return { done: openIds, snooze: openIds, pin, delete: remove };
}

/** Whether every one of `shown` is selected (and there is at least one). */
export function allSelected(
  selected: readonly string[],
  shown: readonly string[],
): boolean {
  return shown.length > 0 && shown.every((id) => selected.includes(id));
}

/** Select all: every one of `shown`, in list order; or none when all already are. */
export function selectAll(
  selected: readonly string[],
  shown: readonly string[],
): string[] {
  if (allSelected(selected, shown)) return [];
  else return [...shown];
}

/** An action the selection bar can offer. */
export type SelectionAction =
  | "done"
  | "snooze"
  | "wake"
  | "pin"
  | "label"
  | "move"
  | "category"
  | "read"
  | "spam"
  | "inbox"
  | "delete";

// In bar order.
const THREAD_ACTIONS: readonly SelectionAction[] = [
  "wake",
  "snooze",
  "pin",
  "label",
  "move",
  "category",
  "read",
  "spam",
  "delete",
  "inbox",
  "done",
];
const TODO_ACTIONS: readonly SelectionAction[] = [
  "snooze",
  "pin",
  "delete",
  "done",
];

/**
 * The actions the selection bar offers for `threads` selected threads and `todos` selected todos:
 * each kind's own, or only those both support when the selection mixes them. Whether one is shown
 * also depends on the rows (Wake needs a snoozed thread; see {@link selectionPlan}).
 */
export function selectionActions(
  threads: number,
  todos: number,
): SelectionAction[] {
  if (threads > 0 && todos > 0) {
    return THREAD_ACTIONS.filter((action) => TODO_ACTIONS.includes(action));
  } else if (threads > 0) {
    return [...THREAD_ACTIONS];
  } else if (todos > 0) {
    return [...TODO_ACTIONS];
  } else {
    return [];
  }
}

/** An action the selection's corner button offers on a phone. */
export type SelectionDiscAction =
  | "done"
  | "inbox"
  | "snooze"
  | "wake"
  | "pin"
  | "label"
  | "read"
  | "spam"
  | "delete";

/** The selection's actions on a phone's corner button: the ring next to it and the ring beyond, each from the top. */
export type SelectionDisc = {
  /** Done (or Move to inbox) and Snooze (or Wake now); for todos alone Done alone */
  inner: SelectionDiscAction[];
  /** the rest, Pin first; for todos alone Snooze (or Wake now) first */
  outer: SelectionDiscAction[];
};

// The outer ring's order, from the top.
const DISC_OUTER: readonly SelectionDiscAction[] = [
  "pin",
  "snooze",
  "done",
  "label",
  "read",
  "spam",
  "delete",
];

/**
 * Where the corner button puts each of `available` (the actions that would change something): the
 * inner ring holds Done and Snooze, Move to inbox taking Done's place and Wake now Snooze's wherever
 * they apply; Pin, a Done or Snooze so displaced, and every other action go on the outer ring, in
 * the open thread's order (Pin, Label, Mark read, Spam, Delete). For `todosAlone` (no thread
 * selected) the inner ring holds Done alone, and Snooze (or Wake now) goes first on the outer ring,
 * before Pin and Delete. An outer ring of one, or with nothing next to the button every action,
 * goes on the inner ring; otherwise, with Done or Snooze missing, Pin joins it, as in the open
 * thread.
 */
export function selectionDisc(
  available: readonly SelectionDiscAction[],
  todosAlone: boolean,
): SelectionDisc {
  const has = (action: SelectionDiscAction) => available.includes(action);
  const first = has("inbox") ? "inbox" : has("done") ? "done" : null;
  const second = has("wake") ? "wake" : has("snooze") ? "snooze" : null;
  const candidates: (SelectionDiscAction | null)[] = todosAlone
    ? [first]
    : [first, second];
  const placed = candidates.filter(
    (action): action is SelectionDiscAction => action !== null,
  );
  const inner: SelectionDiscAction[] =
    !todosAlone && placed.length < 2 && has("pin")
      ? [...placed, "pin"]
      : placed;
  const lead: SelectionDiscAction[] =
    todosAlone && second !== null ? [second] : [];
  const outer = [
    ...lead,
    ...DISC_OUTER.filter(
      (action) => has(action) && !inner.includes(action) && action !== second,
    ),
  ];
  if (inner.length === 0 || outer.length === 1) {
    return { inner: [...inner, ...outer], outer: [] };
  } else {
    return { inner, outer };
  }
}
