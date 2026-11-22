import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  allSelected,
  keepShown,
  type LabelChoice,
  labelChoice,
  type SelectedThread,
  type SelectedTodo,
  type SelectionAction,
  type SelectionDiscAction,
  type SelectionPlan,
  type SetAction,
  selectAll,
  selectionActions,
  selectionAfter,
  selectionCategory,
  selectionDisc,
  selectionPlan,
  selectRange,
  type TodoSelectionPlan,
  todoSelectionPlan,
  toggleSelected,
} from "./selection";

type Fixtures = {
  toggle: { name: string; selected: string[]; id: string; expect: string[] }[];
  range: {
    name: string;
    selected: string[];
    order: string[];
    anchor: string | null;
    id: string;
    expect: string[];
  }[];
  keepShown: {
    name: string;
    selected: string[];
    shown: string[];
    expect: string[];
  }[];
  after: Record<SetAction, "clear" | "keep">;
  plan: { name: string; threads: SelectedThread[]; expect: SelectionPlan }[];
  label: {
    name: string;
    threads: SelectedThread[];
    labelId: string;
    expect: LabelChoice;
  }[];
  category: {
    name: string;
    threads: SelectedThread[];
    expect: string | null;
  }[];
  todos: {
    name: string;
    threads: SelectedThread[];
    todos: SelectedTodo[];
    expect: TodoSelectionPlan;
  }[];
  all: {
    name: string;
    selected: string[];
    shown: string[];
    all: boolean;
    expect: string[];
  }[];
  actions: {
    name: string;
    threads: number;
    todos: number;
    expect: SelectionAction[];
  }[];
  disc: {
    name: string;
    available: SelectionDiscAction[];
    inner: SelectionDiscAction[];
    outer: SelectionDiscAction[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/selection-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("selection fixtures (must match Android)", () => {
  for (const { name, selected, id, expect: want } of fixtures.toggle) {
    test(`toggle: ${name}`, () => {
      expect(toggleSelected(selected, id)).toEqual(want);
    });
  }
  for (const {
    name,
    selected,
    order,
    anchor,
    id,
    expect: want,
  } of fixtures.range) {
    test(`range: ${name}`, () => {
      expect(selectRange(selected, order, anchor, id)).toEqual(want);
    });
  }
  for (const { name, selected, shown, expect: want } of fixtures.keepShown) {
    test(`keepShown: ${name}`, () => {
      expect(keepShown(selected, shown)).toEqual(want);
    });
  }
  for (const [action, want] of Object.entries(fixtures.after)) {
    test(`after: ${action}`, () => {
      expect(selectionAfter(action as SetAction)).toBe(want);
    });
  }
  for (const { name, threads, expect: want } of fixtures.plan) {
    test(`plan: ${name}`, () => {
      expect(selectionPlan(threads)).toEqual(want);
    });
  }
  for (const { name, threads, labelId, expect: want } of fixtures.label) {
    test(`label: ${name}`, () => {
      expect(labelChoice(threads, labelId)).toEqual(want);
    });
  }
  for (const { name, threads, expect: want } of fixtures.category) {
    test(`category: ${name}`, () => {
      expect(selectionCategory(threads)).toBe(want);
    });
  }
  for (const { name, threads, todos, expect: want } of fixtures.todos) {
    test(`todos: ${name}`, () => {
      expect(todoSelectionPlan(threads, todos)).toEqual(want);
    });
  }
  for (const { name, selected, shown, all, expect: want } of fixtures.all) {
    test(`all: ${name}`, () => {
      expect(allSelected(selected, shown)).toBe(all);
      expect(selectAll(selected, shown)).toEqual(want);
    });
  }
  for (const { name, threads, todos, expect: want } of fixtures.actions) {
    test(`actions: ${name}`, () => {
      expect(selectionActions(threads, todos)).toEqual(want);
    });
  }
  for (const { name, available, inner, outer } of fixtures.disc) {
    test(`disc: ${name}`, () => {
      expect(selectionDisc(available)).toEqual({ inner, outer });
    });
  }
});
