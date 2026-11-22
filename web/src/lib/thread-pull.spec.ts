import { expect, test } from "bun:test";
import {
  closesByPull,
  mayPull,
  pullProgress,
  pullStretch,
  pullTravel,
  THREAD_PULL_DISTANCE,
  THREAD_PULL_STRETCH,
} from "./thread-pull";

test("a pull counts only away from the edge the touch began at", () => {
  expect(pullTravel(40, true, false)).toBe(40);
  expect(pullTravel(-40, true, false)).toBe(0);
  expect(pullTravel(-40, false, true)).toBe(-40);
  expect(pullTravel(40, false, true)).toBe(0);
  expect(pullTravel(40, false, false)).toBe(0);
  expect(pullTravel(-40, true, true)).toBe(-40);
});

test("letting go closes once the pull reaches the distance, either way", () => {
  expect(closesByPull(THREAD_PULL_DISTANCE - 1)).toBe(false);
  expect(closesByPull(THREAD_PULL_DISTANCE)).toBe(true);
  expect(closesByPull(-THREAD_PULL_DISTANCE)).toBe(true);
  expect(pullProgress(THREAD_PULL_DISTANCE / 2)).toBe(0.5);
  expect(pullProgress(-3 * THREAD_PULL_DISTANCE)).toBe(1);
});

test("the thread follows the finger less and less, never past the stretch", () => {
  expect(pullStretch(0)).toBe(0);
  expect(pullStretch(10)).toBeCloseTo(10, 0);
  expect(pullStretch(1000)).toBeLessThan(THREAD_PULL_STRETCH);
  expect(pullStretch(-1000)).toBeGreaterThan(-THREAD_PULL_STRETCH);
});

test("touches on fields, forms and menus never pull", () => {
  const field = {
    closest: (selector: string) => (selector.includes("textarea") ? {} : null),
  };
  const text = { closest: () => null };
  expect(mayPull(field as unknown as EventTarget)).toBe(false);
  expect(mayPull(text as unknown as EventTarget)).toBe(true);
  expect(mayPull(null)).toBe(true);
});
