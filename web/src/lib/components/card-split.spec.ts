import { expect, test } from "bun:test";
import { CardSplit } from "./card-split";

type Opened = { kind: "open"; key: string };

test("an open row splits its card, and cards keep their place's key", () => {
  const split = new CardSplit<string, Opened>("today");
  split.add("a");
  split.add("b");
  split.lift({ kind: "open", key: "open:c" });
  split.add("d");
  expect(split.done()).toEqual([
    { kind: "card", key: "card:today:0", items: ["a", "b"] },
    { kind: "open", key: "open:c" },
    { kind: "card", key: "card:today:1", items: ["d"] },
  ]);
});

test("nothing around an open row leaves no empty card", () => {
  const split = new CardSplit<string, Opened>("today");
  split.lift({ kind: "open", key: "open:a" });
  split.lift({ kind: "open", key: "open:b" });
  expect(split.done().map((chunk) => chunk.key)).toEqual(["open:a", "open:b"]);
});
