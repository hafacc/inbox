import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  emptyListState,
  type LayoutEntry,
  type LayoutSection,
  type ListEffect,
  type ListEvent,
  type ListState,
  listProblems,
  listStep,
  type OpenBundleTail,
  type OpenNeighbors,
  openBundleTail,
  openNeighbors,
} from "./list-state";

type Expect = {
  rows?: string[];
  focus?: string | null;
  open?: string | null;
  bundle?: string | null;
  menu?: string | null;
  awaiting?: string | null;
  showAll?: boolean;
  pendingFocus?: string | null;
  selection?: string[];
  removing?: string[];
  anchor?: { key: string; offset: number } | null;
  effect?: ListEffect | null;
  revealed?: boolean;
  undo?: boolean;
  neighbors?: OpenNeighbors;
  tail?: OpenBundleTail | null;
};

type FixtureEntry =
  | { kind: "thread"; id: string }
  | { kind: "todo"; id: string }
  | {
      kind: "bundle";
      labelId: string;
      /** its threads, when it holds no todos */
      threads?: { id: string }[];
      /** its threads and todos, in order */
      items?: { kind: "thread" | "todo"; id: string }[];
      more: boolean;
      loading: boolean;
    };

type FixtureSection = {
  key: string;
  title: string;
  doneAll: boolean;
  entries: FixtureEntry[];
};

type Fixtures = {
  cases: {
    name: string;
    steps: { event: Record<string, unknown>; expect: Expect }[];
  }[];
};

type State = ListState<null, null>;

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/list-state-fixtures.json", import.meta.url),
    "utf8",
  ),
);

function entryOf(entry: FixtureEntry): LayoutEntry<null, null> {
  if (entry.kind === "thread") {
    return {
      kind: "thread",
      id: entry.id,
      data: null,
    };
  } else if (entry.kind === "todo") {
    return { kind: "todo", id: entry.id, data: null };
  } else {
    const items =
      entry.items ??
      (entry.threads ?? []).map((thread) => ({
        kind: "thread" as const,
        id: thread.id,
      }));
    return {
      kind: "bundle",
      labelId: entry.labelId,
      more: entry.more,
      loading: entry.loading,
      items: items.map((item) => ({ ...item, data: null })),
    };
  }
}

function eventOf(raw: Record<string, unknown>): ListEvent<null, null> {
  if (raw.type === "layout") {
    const sections = raw.sections as FixtureSection[];
    return {
      type: "layout",
      sections: sections.map((section) => ({
        ...section,
        entries: section.entries.map(entryOf),
      })),
    };
  }
  return raw as ListEvent<null, null>;
}

function check(before: State, after: State, want: Expect): void {
  if (want.rows !== undefined) {
    expect(after.rows.map((row) => row.key)).toEqual(want.rows);
  }
  if (want.focus !== undefined) expect(after.focus).toBe(want.focus);
  if (want.open !== undefined) expect(after.open).toBe(want.open);
  if (want.bundle !== undefined) {
    expect(after.bundle?.labelId ?? null).toBe(want.bundle);
  }
  if (want.menu !== undefined) expect(after.menu).toBe(want.menu);
  if (want.awaiting !== undefined) {
    expect(after.bundle?.awaiting ?? null).toBe(want.awaiting);
  }
  if (want.showAll !== undefined) {
    expect(after.bundle?.showAll).toBe(want.showAll);
  }
  if (want.pendingFocus !== undefined) {
    expect(after.pendingFocus).toBe(want.pendingFocus);
  }
  if (want.selection !== undefined) {
    expect([...after.selection]).toEqual(want.selection);
  }
  if (want.removing !== undefined) {
    expect([...after.removing].sort()).toEqual(want.removing);
  }
  if (want.anchor !== undefined) expect(after.anchor).toEqual(want.anchor);
  if (want.effect !== undefined) expect(after.effect).toEqual(want.effect);
  if (want.revealed !== undefined) {
    expect(after.reveal > before.reveal).toBe(want.revealed);
  }
  if (want.undo !== undefined) expect(after.undo !== null).toBe(want.undo);
  if (want.neighbors !== undefined) {
    expect(openNeighbors(after)).toEqual(want.neighbors);
  }
  if (want.tail !== undefined) expect(openBundleTail(after)).toEqual(want.tail);
}

describe("list-state fixtures", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      let state: State = emptyListState();
      fixture.steps.forEach(({ event, expect: want }, index) => {
        const next = listStep(state, eventOf(event));
        expect({ step: index, problems: listProblems(next) }).toEqual({
          step: index,
          problems: [],
        });
        check(state, next, want);
        state = next;
      });
    });
  }
});

// A small deterministic generator (mulberry32), so a failing sequence can be replayed by its seed.
function random(seed: number): () => number {
  let value = seed >>> 0;
  return () => {
    value = (value + 0x6d2b79f5) >>> 0;
    let mixed = value;
    mixed = Math.imul(mixed ^ (mixed >>> 15), mixed | 1);
    mixed ^= mixed + Math.imul(mixed ^ (mixed >>> 7), mixed | 61);
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296;
  };
}

const THREADS = ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"];
const TODOS = ["t1", "t2", "t3"];
const LABELS = ["L", "M"];

function randomLayout(next: () => number): LayoutSection<null, null>[] {
  const pick = <Item>(items: readonly Item[]): Item =>
    items[Math.floor(next() * items.length)]!;
  const sections: LayoutSection<null, null>[] = [];
  for (const key of ["pinned", "today", "earlier"]) {
    if (next() < 0.2) continue;
    const entries: LayoutEntry<null, null>[] = [];
    const count = Math.floor(next() * 6);
    for (let index = 0; index < count; index++) {
      const roll = next();
      if (roll < 0.55) {
        entries.push({
          kind: "thread",
          id: pick(THREADS),
          data: null,
        });
      } else if (roll < 0.7) {
        entries.push({ kind: "todo", id: pick(TODOS), data: null });
      } else {
        const size = Math.floor(next() * 8);
        entries.push({
          kind: "bundle",
          labelId: pick(LABELS),
          items: Array.from({ length: size }, () =>
            next() < 0.75
              ? { kind: "thread" as const, id: pick(THREADS), data: null }
              : { kind: "todo" as const, id: pick(TODOS), data: null },
          ),
          more: next() < 0.4,
          loading: next() < 0.2,
        });
      }
    }
    sections.push({ key, title: key, doneAll: key !== "pinned", entries });
  }
  return sections;
}

function randomEvent(state: State, next: () => number): ListEvent<null, null> {
  const pick = <Item>(items: readonly Item[]): Item =>
    items[Math.floor(next() * items.length)]!;
  const keys = state.rows.map((row) => row.key);
  const anyKey = () => (keys.length > 0 ? pick(keys) : "a");
  const roll = Math.floor(next() * 24);
  switch (roll) {
    case 0:
    case 1:
      return { type: "layout", sections: randomLayout(next) };
    case 2:
      return { type: "layout", sections: state.layout };
    case 3:
    case 4:
      return { type: "next" };
    case 5:
      return { type: "previous" };
    case 6:
      return { type: "enter" };
    case 7:
      return { type: "back" };
    case 8:
      return { type: "select" };
    case 9:
      return { type: "command", action: "done" };
    case 10:
      return {
        type: "focus",
        key: anyKey(),
        by: next() < 0.5 ? "keyboard" : "pointer",
      };
    case 11:
      return { type: "tap", key: anyKey() };
    case 12:
      return { type: "openThread", id: pick(THREADS) };
    case 13:
      return next() < 0.5 ? { type: "closeThread" } : { type: "closeBundle" };
    case 14:
      return next() < 0.5
        ? { type: "showAll" }
        : { type: "reveal", key: next() < 0.5 ? anyKey() : "todo:new" };
    case 15:
      return { type: "pick", key: anyKey(), range: next() < 0.3 };
    case 16:
      return next() < 0.5 ? { type: "pickAll" } : { type: "clearSelection" };
    case 17:
    case 18: {
      const removes =
        state.selection.length > 0 && next() < 0.4
          ? [...state.selection]
          : [state.open ?? state.focus ?? anyKey()];
      return {
        type: "act",
        removes,
        from: state.selection.length > 0 ? "selection" : "row",
        undoable: next() < 0.8,
        clearSelection: next() < 0.5,
      };
    }
    case 19:
      return next() < 0.8 ? { type: "undo" } : { type: "undoExpired" };
    case 20:
      return {
        type: "anchor",
        key: anyKey(),
        offset: Math.floor(next() * 300),
      };
    case 21:
      return next() < 0.5 ? { type: "dismiss" } : { type: "back" };
    case 22:
      return next() < 0.6
        ? { type: "openMenu", id: pick(["labels", "snooze"]) }
        : { type: "closeMenu", id: pick(["labels", "snooze"]) };
    default:
      return next() < 0.9
        ? { type: "restore", keys: [anyKey()] }
        : { type: "reset" };
  }
}

const focusable = (state: State) =>
  state.rows
    .filter(
      (row) =>
        row.kind === "thread" || row.kind === "todo" || row.kind === "bundle",
    )
    .map((row) => row.key);

// The rules of design.md, "The list", that compare a step's state with the one before it.
function stepProblems(
  before: State,
  event: ListEvent<null, null>,
  after: State,
): string[] {
  const problems: string[] = [];
  const nowFocusable = new Set(focusable(after));
  const wasFocusable = focusable(before);
  if (event.type === "layout") {
    const landed = before.pendingFocus !== null && after.pendingFocus === null;
    const paged =
      before.bundle?.awaiting != null && after.bundle?.awaiting == null;
    const closed = before.open !== null && after.open === null;
    if (
      before.focus !== null &&
      nowFocusable.has(before.focus) &&
      after.focus !== before.focus &&
      !landed &&
      !paged &&
      !closed
    ) {
      problems.push("a refetch moved focus off a row that is still there");
    }
    if (
      before.anchor !== null &&
      after.rows.some((row) => row.key === before.anchor!.key) &&
      after.anchor?.key !== before.anchor.key
    ) {
      problems.push("a refetch moved the scroll anchor");
    }
  }
  // A focused row that left hands focus to the next surviving row, else the previous one.
  // (unless it waits on the open bundle's header for the bundle's next page).
  const waits =
    before.bundle?.awaiting == null && after.bundle?.awaiting != null;
  if (
    before.focus !== null &&
    !nowFocusable.has(before.focus) &&
    after.pendingFocus === before.pendingFocus &&
    !waits &&
    event.type !== "back" &&
    event.type !== "reset" &&
    !(event.type === "undo" && before.undo !== null)
  ) {
    const index = wasFocusable.indexOf(before.focus);
    const following = wasFocusable
      .slice(index + 1)
      .find((key) => nowFocusable.has(key));
    const preceding = wasFocusable
      .slice(0, index)
      .reverse()
      .find((key) => nowFocusable.has(key));
    // Inside an open bundle that stays open, focus stays inside it: its next row, else its previous.
    const label = before.bundle?.labelId;
    const insideOf = (state: State, key: string | undefined) =>
      state.rows.some(
        (row) =>
          row.key === key &&
          (row.kind === "thread" || row.kind === "todo") &&
          row.bundle === label,
      );
    const stays =
      label !== undefined &&
      after.bundle?.labelId === label &&
      insideOf(before, before.focus) &&
      insideOf(after, after.focus ?? undefined);
    const expected =
      stays && !insideOf(after, following)
        ? after.focus
        : event.type === "next" ||
            event.type === "previous" ||
            event.type === "focus" ||
            event.type === "tap" ||
            event.type === "reveal" ||
            event.type === "enter" ||
            event.type === "openThread" ||
            event.type === "closeBundle" ||
            event.type === "dismiss"
          ? after.focus
          : (following ?? preceding ?? null);
    if (after.focus !== expected) {
      problems.push(
        `focus went to ${after.focus}, not the next row ${expected}`,
      );
    }
  }
  // The keyboard leaving an open bundle closes it.
  const inBundle = (state: State, labelId: string) => {
    const row = state.rows.find((candidate) => candidate.key === state.focus);
    return (
      row !== undefined &&
      ((row.kind === "bundle" && row.labelId === labelId) ||
        ((row.kind === "thread" || row.kind === "todo") &&
          row.bundle === labelId))
    );
  };
  if (
    (event.type === "next" ||
      event.type === "previous" ||
      (event.type === "focus" && event.by === "keyboard")) &&
    before.bundle !== null &&
    inBundle(before, before.bundle.labelId) &&
    after.bundle?.labelId === before.bundle.labelId &&
    !inBundle(after, before.bundle.labelId)
  ) {
    problems.push("keyboard focus left an open bundle that stayed open");
  }
  // Escape, a click away or a tap closes only the top layer: the menu, else the thread, else the bundle.
  const closer =
    event.type === "dismiss" ||
    event.type === "back" ||
    (event.type === "tap" && before.rows.some((row) => row.key === event.key));
  if (closer && before.menu !== null) {
    if (
      after.menu !== null ||
      after.open !== before.open ||
      after.bundle?.labelId !== before.bundle?.labelId
    ) {
      problems.push(`${event.type} with a menu open closed more than the menu`);
    }
  } else if (
    (event.type === "dismiss" || event.type === "back") &&
    before.open !== null &&
    after.bundle?.labelId !== before.bundle?.labelId
  ) {
    problems.push(`${event.type} with a thread open closed its bundle too`);
  }
  // Undo brings focus back once its rows are listed.
  if (
    event.type === "undo" &&
    before.undo !== null &&
    before.undo.keys.length > 0
  ) {
    if (after.pendingFocus === null && after.focus === null) {
      problems.push("undo left nothing to focus");
    }
  }
  return problems;
}

function summary(state: State): string {
  return JSON.stringify({
    rows: state.rows.map((row) => row.key),
    focus: state.focus,
    open: state.open,
    bundle: state.bundle && {
      labelId: state.bundle.labelId,
      awaiting: state.bundle.awaiting,
    },
    pending: state.pendingFocus,
  });
}

describe("list-state random sequences", () => {
  test("every step keeps the rules", () => {
    for (let seed = 1; seed <= 400; seed++) {
      const next = random(seed);
      let state: State = listStep(emptyListState(), {
        type: "layout",
        sections: randomLayout(next),
      });
      for (let count = 0; count < 120; count++) {
        const event = randomEvent(state, next);
        const after = listStep(state, event);
        const problems = [
          ...listProblems(after),
          ...stepProblems(state, event, after),
        ];
        if (problems.length > 0) {
          throw new Error(
            `seed ${seed}, step ${count}, ${JSON.stringify(event)}: ${problems.join("; ")}\nbefore ${summary(state)}\nafter ${summary(after)}`,
          );
        }
        state = after;
      }
    }
    // About a second alone, but the checks run beside Gradle builds, which slow it past bun's 5 s.
  }, 30_000);
});
