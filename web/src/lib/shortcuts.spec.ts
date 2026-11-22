import { expect, test } from "bun:test";
import {
  COMMAND_KEYS,
  chordTitle,
  keyLabel,
  keyTitle,
  shiftKey,
  threadActionKey,
} from "./shortcuts";

test("key hints show only while shortcuts are on", () => {
  expect(keyTitle("Compose", "c", true)).toBe("Compose  c");
  expect(keyTitle("Compose", "c", false)).toBe("Compose");
  expect(keyLabel("Pin", "p", true)).toBe("Pin (p)");
  expect(keyLabel("Pin", "p", false)).toBe("Pin");
});

test("chord hints show whatever the setting", () => {
  expect(chordTitle("Undo", "⌘Z")).toBe("Undo  ⌘Z");
});

test("a thread action's key is the one its command runs on", () => {
  expect(threadActionKey("done")).toBe("e");
  expect(threadActionKey("inbox")).toBe("e");
  expect(threadActionKey("untrash")).toBe("#");
  expect(threadActionKey("notSpam")).toBe("!");
  expect(threadActionKey("important")).toBe("+");
  expect(threadActionKey("read")).toBe(shiftKey("I"));
  expect(threadActionKey("wake")).toBeNull();
  expect(threadActionKey("filter")).toBeNull();
  for (const [key, command] of Object.entries(COMMAND_KEYS)) {
    expect(key.length).toBe(1);
    expect(command).not.toBe("");
  }
});
