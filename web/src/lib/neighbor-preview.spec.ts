import { expect, test } from "bun:test";
import {
  PREVIEW_TEXT_CHARS,
  previewText,
  swipeReveals,
} from "./neighbor-preview";
import type { MessageView } from "./thread-detail";

function message(text: string | null, snippet = "snippet"): MessageView {
  return {
    id: "m",
    threadId: "t",
    from: { name: "Ann", email: "ann@example.com" },
    to: [],
    date: 0,
    snippet,
    unread: false,
    body: {
      html: "<p>html</p>",
      text,
      attachments: [],
      inline: [],
      calendar: null,
    },
  };
}

test("only a swipe toward a neighbor draws it in full", () => {
  expect(swipeReveals(0, -1)).toBe(false);
  expect(swipeReveals(0, 1)).toBe(false);
  expect(swipeReveals(40, -1)).toBe(true);
  expect(swipeReveals(40, 1)).toBe(false);
  expect(swipeReveals(-40, 1)).toBe(true);
  expect(swipeReveals(-40, -1)).toBe(false);
});

test("a light preview shows a message's plain text, else its snippet, cut short", () => {
  expect(previewText(message("  Hello there  "))).toBe("Hello there");
  expect(previewText(message(null))).toBe("snippet");
  expect(previewText(message("   "))).toBe("snippet");
  const long = previewText(message("word ".repeat(1000)));
  expect(long.length).toBeLessThanOrEqual(PREVIEW_TEXT_CHARS + 1);
  expect(long.endsWith("…")).toBe(true);
});
