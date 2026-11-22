// The rows and sections every list shows: the inbox (pinned first, then time sections with big
// labels folded into bundles), Snoozed (by wake day) and the rest (newest first), and what "Done all"
// and focus mode take from them. Android :core mail/ListModel.kt is the same; both run
// shared/testdata/label-group-fixtures.json.

import { PINNED_LABEL } from "./bundles";
import { type FocusResult, focusSections } from "./focus";
import {
  type HeldPlaces,
  NO_HELD_PLACES,
  type ThreadPlace,
} from "./held-places";
import { groupByLabel, MIN_GROUP_SIZE } from "./label-groups";
import {
  bundleRowKey,
  type LayoutEntry,
  type LayoutSection,
  threadRowKey,
  todoRowKey,
} from "./list-state";
import { isoToEpoch } from "./local-time";
import { futureSection, pastSection, sectionize } from "./sections";
import { newestThreadsFirst } from "./thread-dates";
import type { SnoozeStatus } from "./thread-snooze";
import {
  type EmailTodo,
  newestTodosFirst,
  rowEmailTodo,
  type Todo,
  todoListDate,
} from "./todos";
import { inboxAction } from "./view-filter";

/** What the list model reads of a thread's summary. */
export type ListThread = {
  /** Gmail thread id */
  id: string;
  /** its labels */
  labelIds: readonly string[];
};

/** Whether the thread is pinned (Gmail's star). */
export function isPinned(thread: ListThread): boolean {
  return thread.labelIds.includes(PINNED_LABEL);
}

/** A thread as a list shows it: its summary, snooze, and the date it sorts by. */
export type Row<Thread extends ListThread = ListThread> = {
  /** the thread's summary */
  thread: Thread;
  /** where it stands with its snooze */
  status: SnoozeStatus;
  /** when its snooze ends, epoch milliseconds; null when not snoozed */
  snoozeUntil: number | null;
  /** the date it sorts by, epoch milliseconds */
  sortDate: number;
  /** the Gmail threads a joined row stands for, lead first (see `joinedRows`); unset for a thread alone */
  members?: readonly string[];
};

/** A todo of its own among the threads of a list. */
export type TodoEntry = {
  /** a todo */
  kind: "todo";
  /** the todo */
  todo: Todo;
};

/** A thread of its own among a list's rows. */
export type ThreadEntry<Thread extends ListThread = ListThread> = {
  /** one thread */
  kind: "thread";
  /** the thread */
  thread: Row<Thread>;
};

/** One item of a label group: a thread or a todo. */
export type GroupItem<Thread extends ListThread = ListThread> =
  | ThreadEntry<Thread>
  | TodoEntry;

/** The threads and todos of one label folded into one row: a bundle. */
export type GroupEntry<Thread extends ListThread = ListThread> = {
  /** threads and todos of one label, folded */
  kind: "group";
  /** the label */
  labelId: string;
  /** its threads and todos, newest first */
  items: GroupItem<Thread>[];
  /** its threads, newest first */
  threads: Row<Thread>[];
  /** its todos, newest first */
  todos: Todo[];
};

/** A list entry: a thread, a label group, or a todo. */
export type SectionEntry<Thread extends ListThread = ListThread> =
  | ThreadEntry<Thread>
  | GroupEntry<Thread>
  | TodoEntry;

/** A label group of `items`, newest first. */
function groupEntry<Thread extends ListThread>(
  labelId: string,
  items: GroupItem<Thread>[],
): GroupEntry<Thread> {
  return {
    kind: "group",
    labelId,
    items,
    threads: items.flatMap((item) =>
      item.kind === "thread" ? [item.thread] : [],
    ),
    todos: items.flatMap((item) => (item.kind === "todo" ? [item.todo] : [])),
  };
}

/** One section of a list, ready to render. */
export type SectionModel<Thread extends ListThread = ListThread> = {
  /** key that identifies the section */
  key: string;
  /** heading */
  title: string;
  /** its entries, in order */
  entries: SectionEntry<Thread>[];
  /** whether the heading offers "Done all" */
  canDoneAll: boolean;
};

/** The inbox action a row offers (see `inboxAction`): Done, Move to inbox, or neither. */
export function rowInboxAction<Thread extends ListThread>(
  row: Row<Thread>,
): "done" | "inbox" | null {
  return inboxAction({
    labelIds: row.thread.labelIds,
    snoozed: row.status === "snoozed",
  });
}

/** The todo of a row's email (any of its Gmail threads, lead first), or null. */
export function rowTodo<Thread extends ListThread>(
  byThread: ReadonlyMap<string, EmailTodo>,
  row: Row<Thread>,
): EmailTodo | null {
  return rowEmailTodo(byThread, row.members ?? [row.thread.id]);
}

/**
 * A row's place as a list shows it: its held place while open, else its own, with its held pinning
 * while focused (see `HeldPlaces`).
 */
function placeOf<Thread extends ListThread>(
  row: Row<Thread>,
  held: HeldPlaces,
): ThreadPlace {
  return (
    held.threads.get(row.thread.id) ?? {
      date: row.sortDate,
      pinned: held.pins.get(row.thread.id) ?? isPinned(row.thread),
      single: false,
    }
  );
}

/** Each thread once, newest first by its place (see `newestThreadsFirst`). */
function newestFirst<Thread extends ListThread>(
  rows: readonly Row<Thread>[],
  held: HeldPlaces,
): { row: Row<Thread>; place: ThreadPlace }[] {
  return newestThreadsFirst(
    rows.map((row) => ({ row, place: placeOf(row, held) })),
    ({ row }) => row.thread.id,
    ({ place }) => place.date,
  );
}

/** Whether a todo sits with the pinned rows: its held pinning while focused, else its own. */
function todoPinned(todo: Todo, held: HeldPlaces): boolean {
  return held.pins.get(todoRowKey(todo.id)) ?? todo.pinned;
}

/**
 * Inbox: pinned threads and todos first, then time sections; a big label folds into one group, in
 * the section of its newest thread or todo. Active `todos` sit among the threads by
 * `todoListDate`, and an unpinned one joins the groups of the labels its tags name (`todoLabels`,
 * see `todoLabelIds`) as a thread joins its labels': it counts toward a group and takes part in
 * choosing one. An email groups by its own labels and those its todo's tags name (`rowTodoLabels`),
 * together, by the same rule. An open thread or bundle in `held`
 * keeps its held place: a held thread its date, its pinning and its own row; a held bundle its
 * date, and stays a bundle however few items are left in it, even none, until Gmail is known to
 * have no more of its label (`exhausted`), when an empty one leaves.
 */
export function inboxSections<Thread extends ListThread>(
  rows: readonly Row<Thread>[],
  now: number,
  userLabelIds: readonly string[],
  held: HeldPlaces = NO_HELD_PLACES,
  todos: readonly Todo[] = [],
  exhausted: ReadonlySet<string> = new Set(),
  todoLabels: (todo: Todo) => readonly string[] = () => [],
  rowTodoLabels: (row: Row<Thread>) => readonly string[] = () => [],
): SectionModel<Thread>[] {
  const placed = newestFirst(rows, held);
  const pinned = placed.filter(({ place }) => place.pinned);
  const rest = placed.filter(({ place }) => !place.pinned);
  const kept = new Set(
    userLabelIds.filter((labelId) => held.bundles.has(labelId)),
  );
  const loose = newestTodosFirst(todos);
  const todoDates = new Map(loose.map((todo) => [todo.id, todoListDate(todo)]));
  const todoDate = (todo: Todo): number => todoDates.get(todo.id) ?? 0;
  const dates = new Map(placed.map(({ row, place }) => [row, place.date]));
  const itemDate = (item: GroupItem<Thread>): number =>
    item.kind === "thread" ? dates.get(item.thread)! : todoDate(item.todo);
  const items: GroupItem<Thread>[] = [
    ...rest.map(({ row }) => ({ kind: "thread" as const, thread: row })),
    ...loose
      .filter((todo) => !todoPinned(todo, held))
      .map((todo) => ({ kind: "todo" as const, todo })),
  ].sort((left, right) => itemDate(right) - itemDate(left));
  const entries: SectionEntry<Thread>[] = groupByLabel(
    items,
    (item) =>
      item.kind === "todo"
        ? todoLabels(item.todo)
        : placeOf(item.thread, held).single
          ? []
          : [
              ...new Set([
                ...item.thread.thread.labelIds,
                ...rowTodoLabels(item.thread),
              ]),
            ],
    userLabelIds,
    MIN_GROUP_SIZE,
    kept,
  ).map((entry) =>
    entry.kind === "thread"
      ? entry.thread
      : groupEntry(entry.labelId, entry.threads),
  );
  const out: SectionModel<Thread>[] = [];
  const pinnedEntries: { entry: SectionEntry<Thread>; date: number }[] = [
    ...pinned.map(({ row, place }) => ({
      entry: { kind: "thread" as const, thread: row },
      date: place.date,
    })),
    ...loose
      .filter((todo) => todoPinned(todo, held))
      .map((todo) => ({
        entry: { kind: "todo" as const, todo },
        date: todoDate(todo),
      })),
  ].sort((left, right) => right.date - left.date);
  if (pinnedEntries.length > 0) {
    out.push({
      key: "pinned",
      title: "Pinned",
      entries: pinnedEntries.map(({ entry }) => entry),
      canDoneAll: false,
    });
  }
  for (const labelId of kept) {
    if (
      !exhausted.has(labelId) &&
      !entries.some(
        (entry) => entry.kind === "group" && entry.labelId === labelId,
      )
    ) {
      entries.push(groupEntry(labelId, []));
    }
  }
  const dateOf = (entry: SectionEntry<Thread>): number =>
    entry.kind === "group"
      ? (held.bundles.get(entry.labelId) ?? itemDate(entry.items[0]!))
      : itemDate(entry);
  const all = [...entries].sort((left, right) => dateOf(right) - dateOf(left));
  for (const section of sectionize(all, (entry) =>
    pastSection(dateOf(entry), now),
  )) {
    out.push({
      key: section.key,
      title: section.title,
      entries: section.items,
      canDoneAll: true,
    });
  }
  return out;
}

/**
 * The open thread and bundle at the places `sections` shows them: the open thread on its own row
 * or in a bundle, and the open bundle by its label and the date of its newest thread; and the
 * focused thread, when it isn't the open one, by whether it is pinned now; and the focused todo
 * (`focusedTodo`) the same way, under its row key.
 */
export function openPlaces<Thread extends ListThread>(
  sections: readonly SectionModel<Thread>[],
  openBundle: string | null,
  openId: string | null,
  focusedId: string | null = null,
  focusedTodo: string | null = null,
): HeldPlaces {
  const threads = new Map<string, ThreadPlace>();
  const bundles = new Map<string, number>();
  const pins = new Map<string, boolean>();
  const pinFocused = focusedId !== openId ? focusedId : null;
  const place = (row: Row<Thread>, held: ThreadPlace): void => {
    if (row.thread.id === openId) threads.set(openId, held);
    else if (row.thread.id === pinFocused) {
      pins.set(pinFocused, isPinned(row.thread));
    }
  };
  const placeTodo = (todo: Todo): void => {
    if (todo.id === focusedTodo) pins.set(todoRowKey(todo.id), todo.pinned);
  };
  for (const section of sections) {
    for (const entry of section.entries) {
      if (entry.kind === "todo") {
        placeTodo(entry.todo);
      } else if (entry.kind === "thread") {
        place(entry.thread, {
          date: entry.thread.sortDate,
          pinned: section.key === "pinned",
          single: true,
        });
      } else {
        if (entry.labelId === openBundle) {
          const [first] = entry.items;
          bundles.set(
            entry.labelId,
            first === undefined
              ? 0
              : first.kind === "thread"
                ? first.thread.sortDate
                : todoListDate(first.todo),
          );
        }
        for (const row of entry.threads) {
          place(row, { date: row.sortDate, pinned: false, single: false });
        }
        for (const todo of entry.todos) placeTodo(todo);
      }
    }
  }
  return { threads, bundles, pins };
}

/** Snoozed: soonest wake first, threads and snoozed `todos` together, in sections by wake day. */
export function snoozedSections<Thread extends ListThread>(
  rows: readonly Row<Thread>[],
  now: number,
  todos: readonly Todo[] = [],
): SectionModel<Thread>[] {
  const loose = newestTodosFirst(todos);
  const dated: { entry: SectionEntry<Thread>; date: number }[] = [
    ...rows.map((row) => ({
      entry: { kind: "thread" as const, thread: row },
      date: row.snoozeUntil ?? now,
    })),
    ...loose.map((todo) => ({
      entry: { kind: "todo" as const, todo },
      date: todo.snoozeUntil === null ? now : isoToEpoch(todo.snoozeUntil),
    })),
  ];
  dated.sort((left, right) => left.date - right.date);
  return sectionize(dated, ({ date }) => futureSection(date, now)).map(
    (section) => {
      return {
        key: section.key,
        title: section.title,
        entries: section.items.map(({ entry }) => entry),
        canDoneAll: false,
      };
    },
  );
}

/**
 * Any other mailbox or a search: newest first in time sections, an open thread in `held` at its held
 * date, and `todos` among the threads by `todoListDate`.
 */
export function plainSections<Thread extends ListThread>(
  rows: readonly Row<Thread>[],
  now: number,
  held: HeldPlaces = NO_HELD_PLACES,
  todos: readonly Todo[] = [],
): SectionModel<Thread>[] {
  const loose = newestTodosFirst(todos);
  const dated: { entry: SectionEntry<Thread>; date: number }[] = [
    ...newestFirst(rows, held).map(({ row, place }) => ({
      entry: { kind: "thread" as const, thread: row },
      date: place.date,
    })),
    ...loose.map((todo) => ({
      entry: { kind: "todo" as const, todo },
      date: todoListDate(todo),
    })),
  ];
  dated.sort((left, right) => right.date - left.date);
  return sectionize(dated, ({ date }) => pastSection(date, now)).map(
    (section) => {
      return {
        key: section.key,
        title: section.title,
        entries: section.items.map(({ entry }) => entry),
        canDoneAll: false,
      };
    },
  );
}

/** How far a bundle's own list has loaded, as its layout entry says it. */
export type BundlePaging = {
  /** whether Gmail has more of the label's inbox threads than were fetched */
  more: boolean;
  /** whether its next page is on its way */
  loading: boolean;
};

/**
 * Sections as the list model takes them (see `listStep`): threads, todos, and each label group as a
 * bundle with its paging from `paging`.
 */
export function layoutSections<Thread extends ListThread>(
  sections: readonly SectionModel<Thread>[],
  paging: (labelId: string) => BundlePaging,
): LayoutSection<Row<Thread>, Todo>[] {
  return sections.map((section) => ({
    key: section.key,
    title: section.title,
    doneAll: section.canDoneAll,
    entries: section.entries.map((entry): LayoutEntry<Row<Thread>, Todo> => {
      if (entry.kind === "thread") {
        return {
          kind: "thread",
          id: entry.thread.thread.id,
          data: entry.thread,
        };
      } else if (entry.kind === "todo") {
        return { kind: "todo", id: entry.todo.id, data: entry.todo };
      } else {
        return {
          kind: "bundle",
          labelId: entry.labelId,
          items: entry.items.map((item) =>
            item.kind === "thread"
              ? { kind: "thread", id: item.thread.thread.id, data: item.thread }
              : { kind: "todo", id: item.todo.id, data: item.todo },
          ),
          ...paging(entry.labelId),
        };
      }
    }),
  }));
}

/** Every row key a layout holds, rows or not: threads (in bundles too), todos and bundles. */
export function layoutKeys<Thread extends ListThread>(
  sections: readonly LayoutSection<Row<Thread>, Todo>[],
): Set<string> {
  const keys = new Set<string>();
  for (const section of sections) {
    for (const entry of section.entries) {
      const items = entry.kind === "bundle" ? entry.items : [entry];
      if (entry.kind === "bundle") keys.add(bundleRowKey(entry.labelId));
      for (const item of items) {
        keys.add(
          item.kind === "thread" ? threadRowKey(item.id) : todoRowKey(item.id),
        );
      }
    }
  }
  return keys;
}

/** The unpinned thread ids a section's "Done all" sweeps. */
export function doneAllIds<Thread extends ListThread>(
  section: SectionModel<Thread>,
): string[] {
  const ids: string[] = [];
  for (const entry of section.entries) {
    if (entry.kind === "todo") continue;
    const rows = entry.kind === "thread" ? [entry.thread] : entry.threads;
    for (const row of rows) {
      if (!isPinned(row.thread)) ids.push(row.thread.id);
    }
  }
  return ids;
}

/** The unpinned todos a section's "Done all" sweeps: its todo rows and its bundles' todos. */
export function doneAllTodos<Thread extends ListThread>(
  section: SectionModel<Thread>,
): Todo[] {
  return section.entries.flatMap((entry) =>
    (entry.kind === "todo"
      ? [entry.todo]
      : entry.kind === "group"
        ? entry.todos
        : []
    ).filter((todo) => !todo.pinned),
  );
}

/**
 * Focus mode on the inbox's sections (`focusSections`): pinned rows, the open thread, and the
 * focused thread while `held` holds it pinned.
 */
export function focusInboxSections<Thread extends ListThread>(
  sections: readonly SectionModel<Thread>[],
  openId: string | null,
  held: HeldPlaces = NO_HELD_PLACES,
): FocusResult<SectionModel<Thread>> {
  return focusSections<SectionEntry<Thread>, SectionModel<Thread>>(
    sections,
    (entry) => {
      if (entry.kind === "thread") {
        return {
          kind: "thread",
          id: entry.thread.thread.id,
          labelIds: entry.thread.thread.labelIds,
        };
      } else if (entry.kind === "todo") {
        return {
          kind: "todo",
          pinned:
            entry.todo.pinned ||
            held.pins.get(todoRowKey(entry.todo.id)) === true,
        };
      } else {
        return {
          kind: "bundle",
          threadIds: entry.threads.map((row) => row.thread.id),
        };
      }
    },
    openId,
    held.pins,
  );
}
