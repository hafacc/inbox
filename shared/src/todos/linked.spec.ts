import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type EmailTodo,
  type EmailTodoFields,
  emailTodoEditEnded,
  emailTodoFields,
  emailTodosByThread,
  emailTodoToEdit,
  isEmailTodoDoc,
  newEmailTodo,
  readEmailTodo,
  rowEmailTodo,
  withEmailTodoDeleted,
  withEmailTodoText,
} from "./linked";

type Fields = Readonly<Record<string, unknown>>;

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/linked-todo-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  base: EmailTodo;
  read: {
    name: string;
    id: string;
    data: Fields;
    now?: number;
    email: boolean;
    todo?: EmailTodo;
  }[];
  fields: {
    name: string;
    threadId: string;
    text: string;
    now: number;
    fields: EmailTodoFields;
  }[];
  text: {
    name: string;
    todo: Fields;
    text: string;
    out: Fields;
  }[];
  ended: {
    name: string;
    todo: Fields;
    startText: string | null;
    now: number;
    out: Fields;
  }[];
  delete: { name: string; todo: Fields; now: number; out: Fields }[];
  row: {
    name: string;
    todos: Fields[];
    threadIds: string[];
    id: string | null;
  }[];
  edit: {
    name: string;
    todos: Fields[];
    threadIds: string[];
    now: number;
    id: string;
    fresh: boolean;
  }[];
};

function todo(fields: Fields): EmailTodo {
  return { ...fixtures.base, ...fields } as EmailTodo;
}

function picked(subject: EmailTodo, expected: Fields): Fields {
  return Object.fromEntries(
    Object.keys(expected).map((key) => [key, subject[key as keyof EmailTodo]]),
  );
}

describe("linked todo fixtures", () => {
  for (const fixture of fixtures.read) {
    test(`read: ${fixture.name}`, () => {
      expect(isEmailTodoDoc(fixture.data)).toBe(fixture.email);
      if (fixture.todo !== undefined) {
        expect(
          readEmailTodo(fixture.id, fixture.data, fixture.now ?? 0),
        ).toEqual(fixture.todo);
      }
    });
  }

  for (const fixture of fixtures.fields) {
    test(`fields: ${fixture.name}`, () => {
      expect(
        emailTodoFields(
          newEmailTodo(fixture.threadId, fixture.text, fixture.now),
        ),
      ).toEqual(fixture.fields);
    });
  }

  for (const fixture of fixtures.text) {
    test(`text: ${fixture.name}`, () => {
      const changed = withEmailTodoText(todo(fixture.todo), fixture.text);
      expect(picked(changed, fixture.out)).toEqual(fixture.out);
    });
  }

  for (const fixture of fixtures.ended) {
    test(`ended: ${fixture.name}`, () => {
      const ended = emailTodoEditEnded(
        todo(fixture.todo),
        fixture.startText,
        fixture.now,
      );
      expect(picked(ended, fixture.out)).toEqual(fixture.out);
    });
  }

  for (const fixture of fixtures.delete) {
    test(`delete: ${fixture.name}`, () => {
      const deleted = withEmailTodoDeleted(todo(fixture.todo), fixture.now);
      expect(picked(deleted, fixture.out)).toEqual(fixture.out);
    });
  }

  for (const fixture of fixtures.row) {
    test(`row: ${fixture.name}`, () => {
      const byThread = emailTodosByThread(fixture.todos.map(todo));
      expect(rowEmailTodo(byThread, fixture.threadIds)?.id ?? null).toBe(
        fixture.id,
      );
    });
  }

  for (const fixture of fixtures.edit) {
    test(`edit: ${fixture.name}`, () => {
      const byThread = emailTodosByThread(fixture.todos.map(todo));
      const { todo: opened, fresh } = emailTodoToEdit(
        byThread,
        fixture.threadIds,
        fixture.now,
      );
      expect({ id: opened.id, fresh }).toEqual({
        id: fixture.id,
        fresh: fixture.fresh,
      });
    });
  }
});
