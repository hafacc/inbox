import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { newestTodosFirst, type Todo } from "./todo";

type Stored = Readonly<Record<string, unknown>>;

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/todo-order-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  base: Todo;
  order: { name: string; todos: Stored[]; ids: string[] }[];
};

describe("todo order fixtures", () => {
  for (const fixture of fixtures.order) {
    test(fixture.name, () => {
      const todos = fixture.todos.map(
        (fields) => ({ ...fixtures.base, ...fields }) as Todo,
      );
      expect(newestTodosFirst(todos).map(({ id }) => id)).toEqual(fixture.ids);
      expect(newestTodosFirst(todos.toReversed()).map(({ id }) => id)).toEqual(
        fixture.ids,
      );
    });
  }
});
