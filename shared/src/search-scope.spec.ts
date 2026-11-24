import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type SearchScope,
  searchEmailTodos,
  searchScope,
  searchTagTerms,
  searchTodos,
} from "./search-scope";
import { labelTags, type TagLabel } from "./todos/label-tags";
import type { EmailTodo } from "./todos/linked";
import type { Todo } from "./todos/todo";

type Fixtures = {
  zone: string;
  labels: TagLabel[];
  base: Todo;
  scope: { name: string; query: string; expect: SearchScope }[];
  todos: {
    name: string;
    query: string;
    now: number;
    todos: Partial<Todo>[];
    ids: string[];
  }[];
  emailTodos: {
    name: string;
    query: string;
    todos: Pick<EmailTodo, "threadId" | "text">[];
    threadIds: string[];
  }[];
  tags: { name: string; query: string; expect: string }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/search-scope-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("searchScope", () => {
  for (const { name, query, expect: expected } of fixtures.scope) {
    test(name, () => {
      expect(searchScope(query)).toEqual(expected);
    });
  }
});

describe("searchTodos", () => {
  test("fixtures read in their zone", () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(
      fixtures.zone,
    );
  });
  for (const { name, query, now, todos, ids } of fixtures.todos) {
    test(name, () => {
      const list = todos.map((fields) => ({ ...fixtures.base, ...fields }));
      expect(
        searchTodos(list, query, now, labelTags(fixtures.labels)).map(
          (todo) => todo.id,
        ),
      ).toEqual(ids);
    });
  }
});

describe("searchEmailTodos", () => {
  for (const { name, query, todos, threadIds } of fixtures.emailTodos) {
    test(name, () => {
      expect(
        searchEmailTodos(todos, query, labelTags(fixtures.labels)),
      ).toEqual(threadIds);
    });
  }
});

describe("searchTagTerms", () => {
  for (const { name, query, expect: expected } of fixtures.tags) {
    test(name, () => {
      expect(searchTagTerms(query, labelTags(fixtures.labels))).toBe(expected);
    });
  }
});
