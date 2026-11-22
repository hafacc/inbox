// latr's list behavior, ported from latr's web/src/lib/utils/group.ts (the date headings),
// reorder.ts (where a dragged row lands), and todo.ts (heldInPlace, rankBySearch), kept to the
// Active list, the one list this app groups and lets rows move in.
// shared/testdata/todo-list-fixtures.json was generated from latr's own code.

import { plainText } from "../compose/inline-style";
import {
  keyTime,
  positionAbove,
  positionBelow,
  positionBetween,
  timeKey,
} from "./position";
import { activeKey, isSnoozed, type Todo } from "./todo";

/** One heading of a todo list and its rows. */
export type Bucket = {
  /** the heading */
  label: string;
  /** its rows, in list order */
  todos: Todo[];
  /** the Pinned bucket, which only pinned rows move within */
  pinned: boolean;
  /** the earliest time a row under a date heading can have; null when the bucket isn't one */
  floor: number | null;
  /** the time rows under a date heading are before; null for Today, which runs to now */
  ceiling: number | null;
};

function makeBucket(label: string): Bucket {
  return { label, todos: [], pinned: false, floor: null, ceiling: null };
}

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

function groupByPastKey(
  todos: readonly Todo[],
  keyOf: (todo: Todo) => string,
  now: number,
): Bucket[] {
  const today = startOfDay(now);
  const yesterday = daysFrom(today, -1);
  const weekAgo = daysFrom(today, -6);
  const buckets: [Bucket, Bucket, Bucket, Bucket] = [
    { ...makeBucket("Today"), floor: today },
    { ...makeBucket("Yesterday"), floor: yesterday, ceiling: today },
    { ...makeBucket("This week"), floor: weekAgo, ceiling: yesterday },
    { ...makeBucket("Earlier"), floor: 0, ceiling: weekAgo },
  ];
  for (const todo of todos) {
    const at = keyTime(keyOf(todo));
    const bucket =
      buckets.find(
        (candidate) => candidate.floor !== null && at >= candidate.floor,
      ) ?? buckets[3];
    bucket.todos.push(todo);
  }
  return buckets.filter((bucket) => bucket.todos.length > 0);
}

/** Active todos, already sorted, under their headings: Pinned, then by date, keeping their order. */
export function groupActive(sorted: readonly Todo[], now: number): Bucket[] {
  const pinned = sorted.filter((todo) => todo.pinned);
  const rest = sorted.filter((todo) => !todo.pinned);
  const buckets: Bucket[] = [];
  if (pinned.length > 0) {
    buckets.push({ ...makeBucket("Pinned"), todos: pinned, pinned: true });
  }
  buckets.push(...groupByPastKey(rest, activeKey, now));
  return buckets;
}

/** Where a row is dropped: a bucket, and an index among that bucket's other rows. */
export type DropSlot = { bucket: number; index: number };

/** The slot a row is in now, or null when it isn't in `buckets`. */
export function slotOf(
  buckets: readonly Bucket[],
  id: string,
): DropSlot | null {
  for (const [bucket, { todos }] of buckets.entries()) {
    const index = todos.findIndex((todo) => todo.id === id);
    if (index >= 0) return { bucket, index };
  }
  return null;
}

/**
 * The todo as dropping it at `slot` leaves it.
 *
 * Returns the same object when the slot is where the row already is, and null when the row can't
 * go there: an unpinned row among pinned ones, or the reverse. `buckets` is the Active list as
 * shown, from {@link groupActive}.
 */
export function planMove(
  todo: Todo,
  buckets: readonly Bucket[],
  slot: DropSlot,
  now: number,
): Todo | null {
  const current = slotOf(buckets, todo.id);
  const bucket = buckets[slot.bucket];
  if (current === null || bucket === undefined) return null;
  if (current.bucket === slot.bucket && current.index === slot.index) {
    return todo;
  }
  const others = bucket.todos.filter((other) => other.id !== todo.id);
  const above: Todo | undefined = others[slot.index - 1];
  const below: Todo | undefined = others[slot.index];
  if (bucket.pinned !== todo.pinned) return null;
  // Under a date heading, the top and the bottom go halfway to the heading's edge.
  const floor = bucket.floor;
  let position: string | null;
  if (above && below) {
    position = positionBetween(activeKey(below), activeKey(above), todo.id);
  } else if (below) {
    const low = activeKey(below);
    const edge = floor === null ? null : timeKey(bucket.ceiling ?? now);
    position =
      (edge === null ? null : positionBetween(low, edge, todo.id)) ??
      positionAbove(low, todo.id);
  } else if (above) {
    const high = activeKey(above);
    position =
      (floor === null
        ? null
        : positionBetween(timeKey(floor), high, todo.id)) ??
      positionBelow(high, todo.id);
  } else {
    return null;
  }
  if (position === null) return null;
  return {
    ...todo,
    position,
    modifiedAt: now,
    snoozeUntil: isSnoozed(todo, now) ? todo.snoozeUntil : null,
  };
}

/**
 * The copy of a todo being edited that the list places it by, so its row stays put until it loses
 * focus.
 *
 * `held` is the copy so far, `previous` and `live` the stored todo before and after a change. A
 * text change keeps the held text and place; any other change (a pin, say) is taken, text aside.
 */
export function heldInPlace(held: Todo, previous: Todo, live: Todo): Todo {
  if (live.text !== previous.text) {
    return {
      ...live,
      text: held.text,
      snoozeUntil: held.snoozeUntil,
      position: held.position,
      modifiedAt: held.modifiedAt,
    };
  } else {
    return { ...live, text: held.text };
  }
}

function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * The todos whose text (markers removed) has a word starting with one of the query's words, most
 * words matched first, otherwise in the order given. An empty query keeps them all.
 */
export function rankBySearch<Item extends Pick<Todo, "text">>(
  todos: readonly Item[],
  query: string,
): Item[] {
  const words = query
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter((word) => word.length > 0);
  if (words.length === 0) return todos.slice();
  // `\b` only sits before a word char, so a word like `#work` anchors on "no word char before".
  const patterns = words.map(
    (word) =>
      new RegExp(
        `${/^\w/.test(word) ? "\\b" : "(?<!\\w)"}${escapeRegex(word)}`,
      ),
  );
  const scored: { todo: Item; score: number }[] = [];
  for (const todo of todos) {
    const haystack = plainText(todo.text).toLowerCase();
    let score = 0;
    for (const pattern of patterns) {
      if (pattern.test(haystack)) score++;
    }
    if (score > 0) scored.push({ todo, score });
  }
  scored.sort((left, right) => right.score - left.score);
  return scored.map((entry) => entry.todo);
}
