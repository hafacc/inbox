// Typing not yet saved, undo, and the end of an edit, over the todos Firestore holds; Android :core
// todo/Typing.kt is the same. Both run shared/testdata/todo-typing-fixtures.json.

import { matchesFilter, type Todo, withText } from "./todo";

/**
 * `stored` with each todo's unsaved `typing` laid over it: only the text changes, as
 * {@link withText} makes it, so a change another device made since (done, pinned, snoozed) holds.
 * A todo no longer stored stays gone.
 */
export function withTyping(
  stored: Todo[],
  typing: ReadonlyMap<string, string>,
  now: number,
): Todo[] {
  if (typing.size === 0) return stored;
  return stored.map((todo) => {
    const text = typing.get(todo.id);
    return text === undefined ? todo : withText(todo, text, now);
  });
}

/**
 * What undoing an action writes back for a todo: `before`, as it was, keeping text typed into
 * `current` since; a todo no longer live (deleted) comes back as it was.
 */
export function undoneTodo(
  before: Todo,
  current: Todo | undefined,
  now: number,
): Todo {
  if (current === undefined || current.text === before.text) {
    return before;
  } else {
    return withText(before, current.text, now);
  }
}

/**
 * The todo as editing it ends at `now`, its text `startText` when editing began (null for one new
 * in this edit). Changed text stamps `modifiedAt` and drops a place set by a move, so the row takes
 * its new place once, as editing ends; a row that woke from a snooze leaves the snooze behind.
 * Saves made while typing change only the text ({@link withText}), so the row holds its place.
 */
export function todoEditEnded(
  todo: Todo,
  startText: string | null,
  now: number,
): Todo {
  if (startText === null || todo.text === startText) {
    return todo;
  } else {
    const woke =
      matchesFilter(todo, "ACTIVE", now) && todo.snoozeUntil !== null;
    return {
      ...todo,
      modifiedAt: now,
      position: null,
      snoozeUntil: woke ? null : todo.snoozeUntil,
    };
  }
}
