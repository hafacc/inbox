// What the Todos view lists (design.md, "Lists"): the active todos of their own and the todos on
// emails whose email is active, under latr's headings. Android :core todo/TodosView.kt is the same;
// both run shared/testdata/todos-view-fixtures.json.

import type { EmailTodo } from "./linked";
import { idKey, keyTime, timeKey } from "./position";
import { activeKey, matchesFilter, type Todo } from "./todo";

/** An email with a todo, as the Todos view reads it: its own state, which is its todo's. */
export type TodosViewEmail<Email> = {
  /** the email (a list row), handed back as it is */
  email: Email;
  /** its todo */
  todo: EmailTodo;
  /** whether it is in Gmail's inbox */
  inbox: boolean;
  /** whether its snooze is still ahead */
  snoozed: boolean;
  /** whether it is pinned (starred) */
  pinned: boolean;
  /** its date in a list, epoch milliseconds: its latest received message, or a woken snooze's wake time */
  sortDate: number;
  /** where it sat when it opened, while it is open (or its close still plays): it stays there */
  held?: TodosViewPlace | null;
};

/** Where an open email's todo sat in the Todos view when the email opened. */
export type TodosViewPlace = {
  /** the date it sorted by, epoch milliseconds */
  date: number;
  /** whether it sat under Pinned */
  pinned: boolean;
};

/** One row of the Todos view: a todo of its own, or an email's todo. */
export type TodosViewEntry<Email> =
  | { kind: "todo"; todo: Todo }
  | { kind: "email"; email: Email; todo: EmailTodo };

/** One heading of the Todos view and its rows, in list order. */
export type TodosViewBucket<Email> = {
  /** the heading */
  label: string;
  /** whether it is the Pinned heading */
  pinned: boolean;
  /** its rows */
  entries: TodosViewEntry<Email>[];
};

type Keyed<Email> = {
  entry: TodosViewEntry<Email>;
  key: string;
  id: string;
  pinned: boolean;
};

function startOfDay(epoch: number): number {
  const date = new Date(epoch);
  date.setHours(0, 0, 0, 0);
  return date.getTime();
}

function daysFrom(dayStart: number, days: number): number {
  const date = new Date(dayStart);
  date.setDate(date.getDate() + days);
  return date.getTime();
}

// latr's date heading for a row dated `at`, as `groupActive` heads them.
function dateHeading(at: number, now: number): string {
  const today = startOfDay(now);
  if (at >= today) return "Today";
  else if (at >= daysFrom(today, -1)) return "Yesterday";
  else if (at >= daysFrom(today, -6)) return "This week";
  else return "Earlier";
}

const HEADINGS = ["Pinned", "Today", "Yesterday", "This week", "Earlier"];

/**
 * The Todos view's rows under their headings: each active todo of its own (as `groupActive` lists
 * them), and each email's todo whose email is in the inbox and not snoozed, pinned when its email
 * is, dated by its email; an open one (`held`) stays where it sat when it opened. Pinned first, then Today, Yesterday, This week and Earlier; newest
 * first under each, ties by id.
 */
export function todosView<Email>(
  todos: readonly Todo[],
  emails: readonly TodosViewEmail<Email>[],
  now: number,
): TodosViewBucket<Email>[] {
  const keyed: Keyed<Email>[] = [
    ...todos
      .filter((todo) => matchesFilter(todo, "ACTIVE", now))
      .map(
        (todo): Keyed<Email> => ({
          entry: { kind: "todo", todo },
          key: activeKey(todo),
          id: todo.id,
          pinned: todo.pinned,
        }),
      ),
    ...emails
      .filter(
        (email) =>
          (email.held ?? null) !== null || (email.inbox && !email.snoozed),
      )
      .map(
        (email): Keyed<Email> => ({
          entry: { kind: "email", email: email.email, todo: email.todo },
          key:
            timeKey(email.held?.date ?? email.sortDate) +
            idKey(email.todo.threadId),
          id: email.todo.id,
          pinned: email.held?.pinned ?? email.pinned,
        }),
      ),
  ];
  keyed.sort((left, right) =>
    left.key === right.key
      ? compare(left.id, right.id)
      : compare(right.key, left.key),
  );
  const byHeading = new Map<string, TodosViewEntry<Email>[]>();
  for (const { entry, key, pinned } of keyed) {
    const label = pinned ? "Pinned" : dateHeading(keyTime(key), now);
    const entries = byHeading.get(label) ?? [];
    entries.push(entry);
    byHeading.set(label, entries);
  }
  return HEADINGS.flatMap((label) => {
    const entries = byHeading.get(label);
    return entries === undefined
      ? []
      : [{ label, pinned: label === "Pinned", entries }];
  });
}

function compare(left: string, right: string): number {
  if (left < right) return -1;
  else if (left > right) return 1;
  else return 0;
}

/** The Todos view's headings and rows as far as `limit` rows reach. */
export function limitTodosView<Email>(
  buckets: readonly TodosViewBucket<Email>[],
  limit: number,
): TodosViewBucket<Email>[] {
  const shown: TodosViewBucket<Email>[] = [];
  let left = limit;
  for (const bucket of buckets) {
    if (left <= 0) break;
    shown.push(
      bucket.entries.length <= left
        ? bucket
        : { ...bucket, entries: bucket.entries.slice(0, left) },
    );
    left -= bucket.entries.length;
  }
  return shown;
}

/** How many rows the Todos view has under all its headings. */
export function todosViewCount<Email>(
  buckets: readonly TodosViewBucket<Email>[],
): number {
  return buckets.reduce((count, bucket) => count + bucket.entries.length, 0);
}

/** Where `email` sits in the Todos view now, to hold while it is open; null when it isn't listed. */
export function todosViewPlace<Email>(
  email: TodosViewEmail<Email>,
): TodosViewPlace | null {
  if (email.held) return email.held;
  else if (email.inbox && !email.snoozed)
    return { date: email.sortDate, pinned: email.pinned };
  else return null;
}
