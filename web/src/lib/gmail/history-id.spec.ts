import { expect, test } from "bun:test";
import { isLaterHistory } from "./history-id";

test("compares history ids as numbers, past any that can't be read", () => {
  expect(isLaterHistory("100", "99")).toBe(true);
  expect(isLaterHistory("99", "100")).toBe(false);
  expect(isLaterHistory("100", "100")).toBe(false);
  expect(isLaterHistory("18446744073709551616", "18446744073709551615")).toBe(
    true,
  );
  expect(isLaterHistory("5", "")).toBe(true);
  expect(isLaterHistory("5", "junk")).toBe(true);
  expect(isLaterHistory("", "5")).toBe(false);
  expect(isLaterHistory("junk", "")).toBe(false);
});
