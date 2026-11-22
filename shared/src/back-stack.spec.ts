import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type BackEffect,
  type BackEvent,
  type BackStack,
  backStep,
  startBack,
  type ViewStep,
  viewStep,
} from "./back-stack";

type Fixtures = {
  backStep: {
    name: string;
    start: string;
    steps: { event: BackEvent; effect: BackEffect | null }[];
    stack: BackStack;
  }[];
  viewStep: {
    name: string;
    previous: string | null;
    current: string;
    next: string;
    expect: ViewStep;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/back-stack-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("backStep", () => {
  for (const entry of fixtures.backStep) {
    test(entry.name, () => {
      let stack = startBack(entry.start);
      const effects: (BackEffect | null)[] = [];
      for (const { event } of entry.steps) {
        const next = backStep(stack, event);
        stack = next.stack;
        effects.push(next.effect);
      }
      expect(effects).toEqual(entry.steps.map(({ effect }) => effect));
      expect(stack).toEqual(entry.stack);
    });
  }
});

describe("viewStep", () => {
  for (const entry of fixtures.viewStep) {
    test(entry.name, () => {
      expect(viewStep(entry.previous, entry.current, entry.next)).toBe(
        entry.expect,
      );
    });
  }
});
