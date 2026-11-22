import { afterAll, beforeAll, expect, test } from "bun:test";
import { ListedRows } from "./row-motion";

const node = {} as Element;
const settled = () => new Promise<void>((resolve) => queueMicrotask(resolve));

// Svelte's slide reads the row's style, which this test has no page for.
const computed = globalThis.getComputedStyle;
beforeAll(() => {
  globalThis.getComputedStyle = (() => ({
    display: "block",
    opacity: "1",
    height: "68px",
  })) as unknown as typeof getComputedStyle;
});
afterAll(() => {
  globalThis.getComputedStyle = computed;
});

test("a row only moving to another piece, as an open thread splits a bundle, stays still", async () => {
  const listed = new ListedRows();
  listed.note(new Set(Array.from({ length: 12 }, (_, index) => `t${index}`)));
  await settled();
  // Opening t8 draws t9 to t11 again in the piece after it, and closing it puts them back.
  for (const key of ["t9", "t10", "t11"]) {
    expect(listed.enter(node, key).duration).toBe(0);
    expect(listed.leave(node, key).duration).toBe(0);
  }
});

test("rows Show more adds grow in, rows that go collapse out, and the rest stay", async () => {
  const listed = new ListedRows();
  listed.note(new Set(["t0", "t1"]));
  await settled();
  listed.note(new Set(["t0", "t2", "t3"]));
  expect(listed.enter(node, "t3").duration).toBeGreaterThan(0);
  expect(listed.leave(node, "t1").duration).toBeGreaterThan(0);
  expect(listed.enter(node, "t0").duration).toBe(0);
  await settled();
  expect(listed.enter(node, "t3").duration).toBe(0);
});
