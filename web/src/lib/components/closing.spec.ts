import { expect, test } from "bun:test";
import { Closing } from "./closing";

const PLAY_MS = 20;

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test("a closed thread keeps what it last showed open until its close has played", async () => {
  const closing = new Closing<string | null>(new Map(), () => PLAY_MS);
  closing.update(new Map([["a", null]]));
  closing.update(new Map([["a", "messages"]]));
  closing.update(new Map());
  expect(closing.has("a")).toBe(true);
  expect(closing.shown("a")).toBe("messages");
  await wait(PLAY_MS * 3);
  expect(closing.has("a")).toBe(false);
  expect(closing.shown("a")).toBeUndefined();
});

test("opened again mid-close, it is open, not closing", () => {
  const closing = new Closing<string>(new Map(), () => PLAY_MS);
  closing.update(new Map([["a", "first"]]));
  closing.update(new Map());
  closing.update(new Map([["a", "again"]]));
  expect(closing.has("a")).toBe(false);
  closing.stop();
});

test("one a swipe swapped out, or with motion reduced, goes at once", () => {
  const swapped = new Closing<string>(new Map(), () => PLAY_MS);
  swapped.update(new Map([["a", "shown"]]));
  swapped.update(new Map([["b", "next"]]), true);
  expect(swapped.has("a")).toBe(false);
  const still = new Closing<string>(new Map(), () => 0);
  still.update(new Map([["a", "shown"]]));
  still.update(new Map());
  expect(still.has("a")).toBe(false);
});
