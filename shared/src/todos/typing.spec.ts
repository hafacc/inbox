import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import type { Todo } from "./todo";
import { todoEditEnded, undoneTodo, withTyping } from "./typing";

type Stored = Readonly<Record<string, unknown>>;

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/todo-typing-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  zone: string;
  base: Todo;
  typing: {
    name: string;
    stored: Stored[];
    typing: Record<string, string>;
    now: number;
    out: Stored[];
  }[];
  undo: {
    name: string;
    before: Stored;
    current: Stored | null;
    now: number;
    out: Stored;
  }[];
  ended: {
    name: string;
    todo: Stored;
    startText: string | null;
    now: number;
    out: Stored;
  }[];
};

function todo(fields: Stored): Todo {
  return { ...fixtures.base, ...fields } as Todo;
}

test("runs in the fixtures' zone", () => {
  expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(fixtures.zone);
});

describe("withTyping", () => {
  for (const fixture of fixtures.typing) {
    test(fixture.name, () => {
      const typing = new Map(Object.entries(fixture.typing));
      expect(withTyping(fixture.stored.map(todo), typing, fixture.now)).toEqual(
        fixture.out.map(todo),
      );
    });
  }
});

describe("undoneTodo", () => {
  for (const fixture of fixtures.undo) {
    test(fixture.name, () => {
      const current =
        fixture.current === null ? undefined : todo(fixture.current);
      expect(undoneTodo(todo(fixture.before), current, fixture.now)).toEqual(
        todo(fixture.out),
      );
    });
  }
});

describe("todoEditEnded", () => {
  for (const fixture of fixtures.ended) {
    test(fixture.name, () => {
      expect(
        todoEditEnded(todo(fixture.todo), fixture.startText, fixture.now),
      ).toEqual(todo(fixture.out));
    });
  }
});
