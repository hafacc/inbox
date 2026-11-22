// How far a list of todos shows: a page at a time, and in a paged thread list only the todos its
// loaded threads reach. Web uses it directly; Android ports it to :core (TodoPages.kt). Both run
// shared/testdata/todo-page-fixtures.json.
import type { Bucket } from "./list";
import { type Todo, todoListDate } from "./todo";

/** How many todos a list shows at first, and how many more each time its end scrolls into view. */
export const TODO_PAGE = 100;

/** The most todos a list shows after `pages` pages: {@link TODO_PAGE} each. */
export function todoLimit(pages: number): number {
  return Math.max(1, pages) * TODO_PAGE;
}

/** The first `limit` of `todos` a list shows, and how many it leaves out until "show more". */
export function pageTodos(
  todos: readonly Todo[],
  limit: number,
): { shown: Todo[]; hidden: number } {
  return {
    shown: todos.slice(0, limit),
    hidden: Math.max(0, todos.length - limit),
  };
}

/** The first `limit` rows of `buckets`, in order; a bucket left with none is dropped. */
export function limitBuckets(
  buckets: readonly Bucket[],
  limit: number,
): Bucket[] {
  const shown: Bucket[] = [];
  let left = limit;
  for (const bucket of buckets) {
    if (left <= 0) break;
    shown.push(
      bucket.todos.length <= left
        ? bucket
        : { ...bucket, todos: bucket.todos.slice(0, left) },
    );
    left -= bucket.todos.length;
  }
  return shown;
}

/**
 * The todos a paged list (Done, a search) shows among its threads: while older threads are still to
 * load, only those no older than the oldest loaded thread (`oldestShown`, by `todoListDate`), so
 * each page of threads brings its own.
 */
export function todosLoaded(
  todos: readonly Todo[],
  oldestShown: number,
  more: boolean,
): Todo[] {
  return more
    ? todos.filter((todo) => todoListDate(todo) >= oldestShown)
    : [...todos];
}
