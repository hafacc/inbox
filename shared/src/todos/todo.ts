// latr's todo, ported from latr's web/src/lib/utils/todo.ts (and the edits its app-state.svelte.ts
// makes); Android :core todo/Todo.kt is the same. Both run shared/testdata/todo-fixtures.json.
// Stored at users/{uid}/todos/{id} (design.md, "Todos").

import { epochToIso, isLocalIso, isoToEpoch } from "../local-time";
import { idKey, keyTime, readPosition, timeKey } from "./position";
import {
  anchorRepeat,
  nextOccurrence,
  type Repeat,
  type RepeatDraft,
  readRepeat,
} from "./repeat";

/** Whether a todo is done; snoozed is derived, never stored. */
export type TodoState = "ACTIVE" | "DONE";

/** A todo as the apps hold it. */
export type Todo = {
  /** doc id: a UUID for new todos, latr's id for imported ones */
  id: string;
  /** text with latr's inline style markers kept */
  text: string;
  /** done or not */
  state: TodoState;
  /** when it was made, epoch milliseconds */
  createdAt: number;
  /** when it was last changed, epoch milliseconds; orders an unmoved row */
  modifiedAt: number;
  /** local `YYYY-MM-DDTHH:mm:ss` it is snoozed until; once past, the time it woke */
  snoozeUntil: string | null;
  /** floats to the top of the active list */
  pinned: boolean;
  /** where a move put the row; null orders it by its time */
  position: string | null;
  /** a tombstone: deletes keep the doc so a device's stale copy can't bring it back */
  deleted: boolean;
  /** how the todo comes back once done; null for a one-off */
  repeat: Repeat | null;
  /** true once marking this one done has made its next copy */
  repeatSpawned: boolean;
  /** the local `YYYY-MM-DDTHH:mm:ss` this copy of a repeating todo stands for */
  occurrence: string | null;
};

/** A todo's stored fields, without its id and the server-set `serverModifiedAt`. */
export type TodoFields = Omit<Todo, "id">;

/** The todo lists. */
export const TODO_FILTERS = ["ACTIVE", "SNOOZED", "DONE", "ALL"] as const;

/** One of {@link TODO_FILTERS}. */
export type TodoFilter = (typeof TODO_FILTERS)[number];

/** A new, empty-handed todo. */
export function newTodo(id: string, text: string, now: number): Todo {
  return {
    id,
    text,
    state: "ACTIVE",
    createdAt: now,
    modifiedAt: now,
    snoozeUntil: null,
    pinned: false,
    position: null,
    deleted: false,
    repeat: null,
    repeatSpawned: false,
    occurrence: null,
  };
}

/** What a write stores for `todo`; the store adds `serverModifiedAt`. */
export function todoFields(todo: Todo): TodoFields {
  const { id: _id, ...fields } = todo;
  return fields;
}

/** A stored state; latr's old "SNOOZED" (and anything else) reads as active. */
export function readState(value: unknown): TodoState {
  return value === "DONE" ? "DONE" : "ACTIVE";
}

function readLocalTime(value: unknown): string | null {
  return isLocalIso(value) ? value : null;
}

/** A stored todo; missing or malformed fields read as latr reads them, missing times as `now`. */
export function readTodo(
  id: string,
  data: Readonly<Record<string, unknown>>,
  now: number,
): Todo {
  return {
    id,
    text: typeof data.text === "string" ? data.text : "",
    state: readState(data.state),
    createdAt: typeof data.createdAt === "number" ? data.createdAt : now,
    modifiedAt: typeof data.modifiedAt === "number" ? data.modifiedAt : now,
    snoozeUntil: readLocalTime(data.snoozeUntil),
    pinned: data.pinned === true,
    position: readPosition(data.position),
    deleted: data.deleted === true,
    repeat: readRepeat(data.repeat),
    repeatSpawned: data.repeatSpawned === true,
    occurrence: readLocalTime(data.occurrence),
  };
}

/** Snoozed-ness is derived, not stored: a todo not done whose snooze time is still ahead. */
export function isSnoozed(todo: Todo, now: number): boolean {
  return (
    todo.state !== "DONE" &&
    todo.snoozeUntil !== null &&
    isoToEpoch(todo.snoozeUntil) > now
  );
}

/** Whether `todo` shows in the `filter` list at `now`. */
export function matchesFilter(
  todo: Todo,
  filter: TodoFilter,
  now: number,
): boolean {
  switch (filter) {
    case "ALL":
      return true;
    case "DONE":
      return todo.state === "DONE";
    case "SNOOZED":
      return isSnoozed(todo, now);
    case "ACTIVE":
      return todo.state !== "DONE" && !isSnoozed(todo, now);
  }
}

/** A row's own place: where a move put it, else its modify time. */
export function ownKey(todo: Todo): string {
  return todo.position ?? timeKey(todo.modifiedAt) + idKey(todo.id);
}

/**
 * The key a todo sorts by among threads in any list, newest first: a done one's own place, any
 * other's place in Active (a snoozed or woken one at its wake time).
 */
export function todoListKey(todo: Todo): string {
  if (todo.state === "DONE") return ownKey(todo);
  else return activeKey(todo);
}

/** When a todo sorts among threads in any list: the time {@link todoListKey} starts with. */
export function todoListDate(todo: Todo): number {
  return keyTime(todoListKey(todo));
}

/**
 * `todos` in the order they keep among threads, newest first by {@link todoListKey}, then id; two
 * with the same time go by the rest of their keys, so a todo started just above another stays above it.
 */
export function newestTodosFirst(todos: readonly Todo[]): Todo[] {
  const keyed = todos.map((todo) => ({ todo, key: todoListKey(todo) }));
  keyed.sort(
    (left, right) =>
      compareText(right.key, left.key) ||
      compareText(left.todo.id, right.todo.id),
  );
  return keyed.map((entry) => entry.todo);
}

/** A row's place in Active: a row that was snoozed sits at its unsnooze time. */
export function activeKey(todo: Todo): string {
  if (todo.snoozeUntil !== null) {
    return timeKey(isoToEpoch(todo.snoozeUntil)) + ownKey(todo);
  } else {
    return ownKey(todo);
  }
}

/** The key a list orders by, newest first (Snoozed orders by snooze time before it). */
export function listKey(todo: Todo, filter: TodoFilter): string {
  return filter === "ACTIVE" ? activeKey(todo) : ownKey(todo);
}

function compareText(left: string, right: string): number {
  if (left < right) return -1;
  else if (left > right) return 1;
  else return 0;
}

/**
 * `todos` in the order the `filter` list shows them: Snoozed by snooze time, then every list by
 * {@link listKey}, newest first, then id. Pinned rows aren't moved here; the Active list puts them
 * in their own group.
 */
export function sortForFilter(
  todos: readonly Todo[],
  filter: TodoFilter,
): Todo[] {
  const keyed = todos.map((todo) => ({
    todo,
    key: listKey(todo, filter),
    snoozeAt:
      filter === "SNOOZED" && todo.snoozeUntil !== null
        ? isoToEpoch(todo.snoozeUntil)
        : 0,
  }));
  keyed.sort(
    (left, right) =>
      left.snoozeAt - right.snoozeAt ||
      compareText(right.key, left.key) ||
      compareText(left.todo.id, right.todo.id),
  );
  return keyed.map((entry) => entry.todo);
}

/** A todo with new text; the first edit of a row that woke from a snooze keeps it where it sits. */
export function withText(todo: Todo, text: string, now: number): Todo {
  if (matchesFilter(todo, "ACTIVE", now) && todo.snoozeUntil !== null) {
    return {
      ...todo,
      text,
      snoozeUntil: null,
      position: null,
      modifiedAt: isoToEpoch(todo.snoozeUntil),
    };
  } else {
    return { ...todo, text };
  }
}

/** A todo snoozed at `now` until `until` (epoch milliseconds). */
export function withSnoozed(todo: Todo, until: number, now: number): Todo {
  return {
    ...todo,
    snoozeUntil: epochToIso(until),
    position: null,
    modifiedAt: now,
  };
}

/** Flips `pinned`; only an active row also drops its was-snoozed marker. */
export function withPinToggled(todo: Todo, now: number): Todo {
  const pinned = !todo.pinned;
  if (!matchesFilter(todo, "ACTIVE", now)) {
    return { ...todo, pinned, position: null, modifiedAt: now };
  } else {
    return {
      ...todo,
      pinned,
      position: null,
      snoozeUntil: null,
      modifiedAt: now,
    };
  }
}

/** A todo marked done at `now`, and the next copy it makes if it repeats and hasn't made one yet. */
export function withDone(
  todo: Todo,
  now: number,
  newId: string,
): { done: Todo; spawned: Todo | null } {
  const done: Todo = {
    ...todo,
    state: "DONE",
    snoozeUntil: null,
    position: null,
    modifiedAt: now,
  };
  if (todo.repeat === null || todo.repeatSpawned) {
    return { done, spawned: null };
  } else {
    const nowIso = epochToIso(now);
    const after =
      todo.occurrence !== null && todo.occurrence > nowIso
        ? todo.occurrence
        : nowIso;
    const next = nextOccurrence(todo.repeat, after);
    return {
      done: { ...done, repeatSpawned: true },
      spawned: {
        ...newTodo(newId, todo.text, now),
        snoozeUntil: next,
        pinned: todo.pinned,
        repeat: todo.repeat,
        occurrence: next,
      },
    };
  }
}

/** A todo given the rule `draft` at `now`; its snooze, place and copies are left alone. */
export function withRepeat(todo: Todo, draft: RepeatDraft, now: number): Todo {
  return { ...todo, repeat: anchorRepeat(draft, epochToIso(now)) };
}

/** A done or snoozed todo made active at `now`; unsnoozing one also stops it repeating. */
export function withReactivated(todo: Todo, now: number): Todo {
  const reactivated: Todo = {
    ...todo,
    state: "ACTIVE",
    snoozeUntil: null,
    position: null,
    modifiedAt: now,
  };
  if (isSnoozed(todo, now)) {
    return {
      ...reactivated,
      repeat: null,
      repeatSpawned: false,
      occurrence: null,
    };
  } else {
    return reactivated;
  }
}

/** A todo deleted at `now`: a tombstone with its text blanked. */
export function withDeleted(todo: Todo, now: number): Todo {
  return { ...todo, deleted: true, text: "", modifiedAt: now };
}
