import { expect, test } from "bun:test";
import { answerButtons } from "./answer-buttons";

test("reply all shows only when it reaches more than one other person", () => {
  expect(answerButtons("reply", true)).toEqual(["reply", "forward"]);
  expect(answerButtons("replyAll", true)).toEqual([
    "reply",
    "replyAll",
    "forward",
  ]);
  // Wider, the field answers as its mode says.
  expect(answerButtons("reply", false)).toEqual(["forward"]);
  expect(answerButtons("replyAll", false)).toEqual(["reply", "forward"]);
});
