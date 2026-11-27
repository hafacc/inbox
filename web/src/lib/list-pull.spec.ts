import { expect, test } from "bun:test";
import {
  LIST_PULL_DRAG_RATIO,
  LIST_PULL_THRESHOLD,
  listPullOffset,
  listPullProgress,
  refreshesByPull,
} from "./list-pull";

const ARMED_TRAVEL = LIST_PULL_THRESHOLD / LIST_PULL_DRAG_RATIO;

test("the spinner follows half the finger up to the threshold", () => {
  expect(listPullOffset(0)).toBe(0);
  expect(listPullOffset(-50)).toBe(0);
  expect(listPullOffset(100)).toBe(50);
  expect(listPullOffset(ARMED_TRAVEL)).toBe(LIST_PULL_THRESHOLD);
  expect(listPullProgress(ARMED_TRAVEL / 2)).toBe(0.5);
  expect(listPullProgress(10 * ARMED_TRAVEL)).toBe(1);
});

test("past the threshold the spinner slows and stops at twice it", () => {
  const past = listPullOffset(ARMED_TRAVEL * 1.5);
  expect(past).toBeGreaterThan(LIST_PULL_THRESHOLD);
  expect(past).toBeLessThan(listPullOffset(ARMED_TRAVEL * 2));
  expect(listPullOffset(ARMED_TRAVEL * 3)).toBe(2 * LIST_PULL_THRESHOLD);
  expect(listPullOffset(ARMED_TRAVEL * 10)).toBe(2 * LIST_PULL_THRESHOLD);
  let previous = 0;
  for (let step = 0; step <= 4 * ARMED_TRAVEL; step += 5) {
    const offset = listPullOffset(step);
    expect(offset).toBeGreaterThanOrEqual(previous);
    previous = offset;
  }
});

test("letting go refreshes once the spinner reaches the threshold", () => {
  expect(refreshesByPull(ARMED_TRAVEL - 1)).toBe(false);
  expect(refreshesByPull(ARMED_TRAVEL)).toBe(true);
});
