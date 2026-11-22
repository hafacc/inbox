import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type BundleSwipeAction,
  bundleSwipeAction,
  bundleSwipeLabel,
  committedSide,
  type SwipeSide,
  swipeProgress,
  type ThreadSwipeAction,
  type ThreadSwipeRow,
  type TodoSwipeAction,
  type TodoSwipeRow,
  threadSwipeAction,
  todoSwipeAction,
} from "./swipe";

type Sides<Action> = { start: Action | null; end: Action | null };

type Fixtures = {
  threads: {
    name: string;
    row: ThreadSwipeRow;
    expect: Sides<ThreadSwipeAction>;
  }[];
  todos: {
    name: string;
    row: TodoSwipeRow;
    expect: { start: TodoSwipeAction; end: TodoSwipeAction };
  }[];
  commit: {
    name: string;
    dx: number;
    width: number;
    velocity: number;
    expect: SwipeSide | null;
  }[];
  progress: { name: string; dx: number; width: number; expect: number }[];
  bundles: {
    name: string;
    open: boolean;
    threads: string;
    expect: Sides<BundleSwipeAction>;
    labels: Record<BundleSwipeAction, string>;
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/swipe-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("threadSwipeAction", () => {
  for (const fixture of fixtures.threads) {
    test(fixture.name, () => {
      expect({
        start: threadSwipeAction("start", fixture.row),
        end: threadSwipeAction("end", fixture.row),
      }).toEqual(fixture.expect);
    });
  }
});

describe("todoSwipeAction", () => {
  for (const fixture of fixtures.todos) {
    test(fixture.name, () => {
      expect({
        start: todoSwipeAction("start", fixture.row),
        end: todoSwipeAction("end", fixture.row),
      }).toEqual(fixture.expect);
    });
  }
});

describe("committedSide", () => {
  for (const fixture of fixtures.commit) {
    test(fixture.name, () => {
      expect(committedSide(fixture.dx, fixture.width, fixture.velocity)).toBe(
        fixture.expect,
      );
    });
  }
});

describe("swipeProgress", () => {
  for (const fixture of fixtures.progress) {
    test(fixture.name, () => {
      expect(swipeProgress(fixture.dx, fixture.width)).toBe(fixture.expect);
    });
  }
});

describe("bundleSwipeAction", () => {
  for (const fixture of fixtures.bundles) {
    test(fixture.name, () => {
      expect({
        start: bundleSwipeAction("start", fixture.open),
        end: bundleSwipeAction("end", fixture.open),
      }).toEqual(fixture.expect);
      expect({
        snooze: bundleSwipeLabel("snooze", fixture.threads),
        done: bundleSwipeLabel("done", fixture.threads),
      }).toEqual(fixture.labels);
    });
  }
});
