import { expect, test } from "bun:test";
import { relayedLines } from "./open-timing-relay";

test("a relay post's lines come out as printable text", () => {
  expect(relayedLines('{"lines":["open a\\nb", 3, "open c"]}')).toEqual([
    "open a b",
    "open c",
  ]);
});

test("a post that isn't a relay post prints nothing", () => {
  expect(relayedLines("not json")).toEqual([]);
  expect(relayedLines('{"other":1}')).toEqual([]);
});
