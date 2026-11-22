import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import type { RepeatDraft } from "./repeat";
import {
  activeKey,
  matchesFilter,
  ownKey,
  readTodo,
  sortForFilter,
  TODO_FILTERS,
  type Todo,
  type TodoFields,
  type TodoFilter,
  todoFields,
  todoListDate,
  withDeleted,
  withDone,
  withPinToggled,
  withReactivated,
  withRepeat,
  withSnoozed,
  withText,
} from "./todo";

type Stored = Readonly<Record<string, unknown>>;

type Edit = {
  name: string;
  edit: "text" | "snooze" | "pin" | "done" | "reactivate" | "repeat" | "delete";
  todo: Stored;
  now: number;
  text?: string;
  until?: number;
  newId?: string;
  draft?: RepeatDraft;
  out: Stored;
  spawned?: Stored | null;
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/todo-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  zone: string;
  base: Todo;
  read: {
    name: string;
    id: string;
    data: Stored;
    now: number;
    todo: Todo;
  }[];
  fields: { name: string; todo: Stored; fields: TodoFields }[];
  filters: { name: string; todo: Stored; now: number; filters: string[] }[];
  keys: { name: string; todo: Stored; ownKey: string; activeKey: string }[];
  listDates: { name: string; todo: Stored; listDate: number }[];
  sort: {
    name: string;
    filter: TodoFilter;
    todos: Stored[];
    ids: string[];
  }[];
  edits: Edit[];
};

function todo(fields: Stored): Todo {
  return { ...fixtures.base, ...fields } as Todo;
}

function edited(fixture: Edit): { out: Todo; spawned?: Todo | null } {
  const before = todo(fixture.todo);
  switch (fixture.edit) {
    case "text":
      return { out: withText(before, fixture.text ?? "", fixture.now) };
    case "snooze":
      return { out: withSnoozed(before, fixture.until ?? 0, fixture.now) };
    case "pin":
      return { out: withPinToggled(before, fixture.now) };
    case "done": {
      const { done, spawned } = withDone(
        before,
        fixture.now,
        fixture.newId ?? "",
      );
      return { out: done, spawned };
    }
    case "reactivate":
      return { out: withReactivated(before, fixture.now) };
    case "repeat":
      return {
        out: withRepeat(before, fixture.draft as RepeatDraft, fixture.now),
      };
    case "delete":
      return { out: withDeleted(before, fixture.now) };
  }
}

test("runs in the fixtures' zone", () => {
  expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(fixtures.zone);
});

describe("readTodo", () => {
  for (const fixture of fixtures.read) {
    test(fixture.name, () => {
      expect(readTodo(fixture.id, fixture.data, fixture.now)).toEqual(
        fixture.todo,
      );
    });
  }
});

describe("todoFields", () => {
  for (const fixture of fixtures.fields) {
    test(fixture.name, () => {
      expect(todoFields(todo(fixture.todo))).toEqual(fixture.fields);
    });
  }
});

describe("matchesFilter", () => {
  for (const fixture of fixtures.filters) {
    test(fixture.name, () => {
      expect(
        TODO_FILTERS.filter((filter) =>
          matchesFilter(todo(fixture.todo), filter, fixture.now),
        ),
      ).toEqual(fixture.filters as TodoFilter[]);
    });
  }
});

describe("keys", () => {
  for (const fixture of fixtures.keys) {
    test(fixture.name, () => {
      expect([
        ownKey(todo(fixture.todo)),
        activeKey(todo(fixture.todo)),
      ]).toEqual([fixture.ownKey, fixture.activeKey]);
    });
  }
});

describe("sortForFilter", () => {
  for (const fixture of fixtures.sort) {
    test(fixture.name, () => {
      expect(
        sortForFilter(fixture.todos.map(todo), fixture.filter).map(
          (sorted) => sorted.id,
        ),
      ).toEqual(fixture.ids);
    });
  }
});

describe("edits", () => {
  for (const fixture of fixtures.edits) {
    test(fixture.name, () => {
      const { out, spawned } = edited(fixture);
      expect(out).toEqual(todo(fixture.out));
      if (fixture.spawned !== undefined) {
        expect(spawned ?? null).toEqual(
          fixture.spawned === null ? null : todo(fixture.spawned),
        );
      }
    });
  }
});

describe("list dates", () => {
  for (const fixture of fixtures.listDates) {
    test(fixture.name, () => {
      expect(todoListDate(todo(fixture.todo))).toBe(fixture.listDate);
    });
  }
});
