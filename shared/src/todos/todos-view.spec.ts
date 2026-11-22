import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { newEmailTodo } from "./linked";
import { newTodo, type Todo } from "./todo";
import {
  limitTodosView,
  type TodosViewBucket,
  todosView,
  todosViewCount,
  todosViewPlace,
} from "./todos-view";

type Heading = { label: string; rows: string[] };

type Email = {
  threadId: string;
  inbox: boolean;
  snoozed: boolean;
  pinned: boolean;
  sortDate: number;
  held?: { date: number; pinned: boolean };
};

type Fixtures = {
  now: number;
  cases: {
    name: string;
    todos: Partial<Todo>[];
    emails: Email[];
    expect: Heading[];
    limit?: number;
    limited?: Heading[];
    count: number;
  }[];
  places: {
    name: string;
    email: Email;
    expect: { date: number; pinned: boolean } | null;
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/todos-view-fixtures.json", import.meta.url),
    "utf8",
  ),
);

function headings(buckets: readonly TodosViewBucket<string>[]): Heading[] {
  return buckets.map((bucket) => ({
    label: bucket.label,
    rows: bucket.entries.map((entry) =>
      entry.kind === "todo" ? `todo:${entry.todo.id}` : `email:${entry.email}`,
    ),
  }));
}

describe("todos view fixtures (must match Android)", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      const todos = fixture.todos.map((fields) => {
        const id = fields.id ?? "a";
        return { ...newTodo(id, id, 0), ...fields };
      });
      const emails = fixture.emails.map(({ threadId, ...state }) => ({
        email: threadId,
        todo: newEmailTodo(threadId, threadId, 0),
        ...state,
      }));
      const buckets = todosView(todos, emails, fixtures.now);
      expect(headings(buckets)).toEqual(fixture.expect);
      expect(todosViewCount(buckets)).toBe(fixture.count);
      if (fixture.limit !== undefined) {
        expect(headings(limitTodosView(buckets, fixture.limit))).toEqual(
          fixture.limited ?? [],
        );
      }
    });
  }
});

describe("todos view places (must match Android)", () => {
  for (const fixture of fixtures.places) {
    test(fixture.name, () => {
      const { threadId, ...state } = fixture.email;
      const email = {
        email: threadId,
        todo: newEmailTodo(threadId, threadId, 0),
        ...state,
      };
      expect(todosViewPlace(email)).toEqual(fixture.expect);
    });
  }
});
