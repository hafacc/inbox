import { describe, expect, test } from "bun:test";
import { collapsedActions } from "./header-fit";

const row = ["pin", "snooze", "label", "delete", "done"];
const widths = new Map([["done", 80]]);
const lastToGo = ["done"];

describe("collapsedActions", () => {
  test("keeps every button when the row fits", () => {
    // 4 × 36 + 80 + 4 gaps of 12
    expect(collapsedActions(row, widths, 272, lastToGo)).toEqual([]);
  });

  test("moves buttons from the end, Done last, leaving room for More", () => {
    // More takes the room of the one button it replaces, so two go at once.
    expect(collapsedActions(row, widths, 271, lastToGo)).toEqual([
      "label",
      "delete",
    ]);
    // pin, snooze, done and More: 3 × 36 + 80 + 3 gaps
    expect(collapsedActions(row, widths, 224, lastToGo)).toEqual([
      "label",
      "delete",
    ]);
    expect(collapsedActions(row, widths, 223, lastToGo)).toEqual([
      "snooze",
      "label",
      "delete",
    ]);
  });

  test("moves Done only once every other button has gone", () => {
    // done and More: 80 + 36 + a gap
    expect(collapsedActions(row, widths, 128, lastToGo)).toEqual([
      "pin",
      "snooze",
      "label",
      "delete",
    ]);
    expect(collapsedActions(row, widths, 127, lastToGo)).toEqual(row);
  });

  test("treats a width it lacks as a round button's", () => {
    expect(collapsedActions(["pin", "label"], new Map(), 84, [])).toEqual([]);
    expect(collapsedActions(["pin", "label"], new Map(), 83, [])).toEqual([
      "pin",
      "label",
    ]);
  });
});
