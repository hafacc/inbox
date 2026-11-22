import { expect, test } from "bun:test";
import { formatOpen, formatRequest, shortPath } from "./open-timing";

test("a Gmail call's path drops the origin and the user prefix", () => {
  expect(
    shortPath(
      "https://gmail.googleapis.com/gmail/v1/users/me/threads/abc?format=full",
    ),
  ).toBe("threads/abc?format=full");
  expect(shortPath("https://people.googleapis.com/v1/people:search?q=x")).toBe(
    "/v1/people:search?q=x",
  );
});

test("a call shows its wait only when it waited, and its outcome only when it failed", () => {
  const call = {
    at: 4.6,
    method: "GET",
    url: "https://gmail.googleapis.com/gmail/v1/users/me/threads/abc",
    priority: "visible",
    queuedMs: 0.2,
    ms: 230.4,
    outcome: "ok",
  };
  expect(formatRequest(call)).toBe("+5 GET threads/abc 230ms visible");
  expect(formatRequest({ ...call, queuedMs: 12.4, outcome: "429" })).toBe(
    "+5 GET threads/abc 230ms q12 visible 429",
  );
});

test("an open is one line: source, length, phases, then Gmail calls", () => {
  expect(
    formatOpen({
      id: "thread-1a2b3c",
      source: "Gmail",
      end: "quiet",
      phases: [
        { at: 0.4, phase: "asking Gmail" },
        { at: 250.2, phase: "Gmail answered" },
        { at: 300, phase: "message cleaned", took: 12.25 },
      ],
      requests: [
        {
          at: 1,
          method: "GET",
          url: "https://gmail.googleapis.com/gmail/v1/users/me/threads/1a2b3c?format=full",
          priority: "visible",
          queuedMs: 3,
          ms: 245,
          outcome: "ok",
        },
      ],
    }),
  ).toBe(
    "open 1a2b3c from Gmail 312ms (quiet) | +0 asking Gmail, +250 Gmail answered, +300 message cleaned (12.3)" +
      " | gmail: +1 GET threads/1a2b3c?format=full 245ms q3 visible",
  );
});

test("an open with no source or calls says so briefly", () => {
  expect(
    formatOpen({
      id: "abc",
      source: null,
      end: "next open",
      phases: [],
      requests: [],
    }),
  ).toBe("open abc from ? 0ms (next open) | ");
});
