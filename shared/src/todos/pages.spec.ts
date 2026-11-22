import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import type { Bucket } from "./list";
import {
  limitBuckets,
  pageTodos,
  TODO_PAGE,
  todoLimit,
  todosLoaded,
} from "./pages";
import { newTodo, type Todo } from "./todo";

type Fixtures = {
  pageSize: number;
  limits: { pages: number; limit: number }[];
  pages: {
    name: string;
    count: number;
    pages: number;
    shown: number;
    hidden: number;
  }[];
  loaded: {
    name: string;
    todos: Partial<Todo>[];
    oldestShown: number;
    more: boolean;
    expect: string[];
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/todo-page-fixtures.json", import.meta.url),
    "utf8",
  ),
);

function todo(fields: Partial<Todo>): Todo {
  const id = fields.id ?? "a";
  return { ...newTodo(id, id, 0), ...fields };
}

describe("todo page fixtures (must match Android)", () => {
  test("page size", () => {
    expect(TODO_PAGE).toBe(fixtures.pageSize);
  });
  for (const { pages, limit } of fixtures.limits) {
    test(`limit after ${pages} pages`, () => {
      expect(todoLimit(pages)).toBe(limit);
    });
  }
  for (const fixture of fixtures.pages) {
    test(fixture.name, () => {
      const todos = Array.from({ length: fixture.count }, (_, index) =>
        todo({ id: `t${index}` }),
      );
      const { shown, hidden } = pageTodos(todos, todoLimit(fixture.pages));
      expect([shown.length, hidden]).toEqual([fixture.shown, fixture.hidden]);
    });
  }
  for (const fixture of fixtures.loaded) {
    test(fixture.name, () => {
      expect(
        todosLoaded(
          fixture.todos.map(todo),
          fixture.oldestShown,
          fixture.more,
        ).map((shown) => shown.id),
      ).toEqual(fixture.expect);
    });
  }
});

describe("limitBuckets", () => {
  const bucket = (label: string, ids: string[]): Bucket => ({
    label,
    todos: ids.map((id) => todo({ id })),
    pinned: false,
    floor: null,
    ceiling: null,
  });
  const buckets = [bucket("Today", ["a", "b"]), bucket("Older", ["c", "d"])];

  test("cuts within a bucket and drops the ones past the limit", () => {
    expect(
      limitBuckets(buckets, 3).map((shown) => [
        shown.label,
        shown.todos.map((item) => item.id),
      ]),
    ).toEqual([
      ["Today", ["a", "b"]],
      ["Older", ["c"]],
    ]);
  });

  test("keeps every bucket when the limit reaches past them", () => {
    expect(limitBuckets(buckets, 10)).toEqual(buckets);
  });
});
