import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type DropSlot,
  groupActive,
  heldInPlace,
  planMove,
  rankBySearch,
} from "./list";
import { type StyledNode, styledTree } from "./styled";
import { sortForFilter, type Todo } from "./todo";

type Partial = Readonly<Record<string, unknown>>;

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/todo-list-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  zone: string;
  base: Todo;
  group: {
    name: string;
    now: number;
    todos: Partial[];
    buckets: {
      label: string;
      ids: string[];
      pinned: boolean;
      floor: number | null;
      ceiling: number | null;
    }[];
  }[];
  move: {
    name: string;
    now: number;
    todos: Partial[];
    id: string;
    slot: DropSlot;
    out: Partial | "same" | null;
  }[];
  held: {
    name: string;
    held: Partial;
    previous: Partial;
    live: Partial;
    out: Partial;
  }[];
  search: { name: string; todos: Partial[]; query: string; ids: string[] }[];
  styled: { in: string; tree: StyledNode[] }[];
};

function todo(fields: Partial): Todo {
  return { ...fixtures.base, ...fields } as Todo;
}

function shown(todos: Partial[]): Todo[] {
  return sortForFilter(todos.map(todo), "ACTIVE");
}

test("fixtures run in their zone", () => {
  expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(fixtures.zone);
});

describe("groupActive", () => {
  for (const fixture of fixtures.group) {
    test(fixture.name, () => {
      const buckets = groupActive(shown(fixture.todos), fixture.now);
      expect(
        buckets.map((bucket) => ({
          label: bucket.label,
          ids: bucket.todos.map((entry) => entry.id),
          pinned: bucket.pinned,
          floor: bucket.floor,
          ceiling: bucket.ceiling,
        })),
      ).toEqual(fixture.buckets);
    });
  }
});

describe("planMove", () => {
  for (const fixture of fixtures.move) {
    test(fixture.name, () => {
      const list = shown(fixture.todos);
      const buckets = groupActive(list, fixture.now);
      const moving = list.find((entry) => entry.id === fixture.id)!;
      const moved = planMove(moving, buckets, fixture.slot, fixture.now);
      if (fixture.out === "same") expect(moved).toBe(moving);
      else if (fixture.out === null) expect(moved).toBeNull();
      else expect(moved).toEqual(todo(fixture.out));
    });
  }
});

describe("heldInPlace", () => {
  for (const fixture of fixtures.held) {
    test(fixture.name, () => {
      expect(
        heldInPlace(
          todo(fixture.held),
          todo(fixture.previous),
          todo(fixture.live),
        ),
      ).toEqual(todo(fixture.out));
    });
  }
});

describe("rankBySearch", () => {
  for (const fixture of fixtures.search) {
    test(fixture.name, () => {
      expect(
        rankBySearch(fixture.todos.map(todo), fixture.query).map(
          (entry) => entry.id,
        ),
      ).toEqual(fixture.ids);
    });
  }
});

describe("styledTree", () => {
  for (const fixture of fixtures.styled) {
    test(JSON.stringify(fixture.in), () => {
      expect(styledTree(fixture.in)).toEqual(fixture.tree);
    });
  }
});
