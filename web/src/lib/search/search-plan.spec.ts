import { expect, test } from "bun:test";
import type { ThreadDetail } from "../thread-detail";
import type { ThreadSummary } from "../thread-summary";
import { searchLocal, searchPlan } from "./search-plan";

test("our chips stay out of Gmail's query", () => {
  const plan = searchPlan("is:snoozed from:maya lease");
  expect(plan.gmailQuery).toBe("from:maya lease");
  expect(plan.snoozedOnly).toBe(true);
  expect(plan.outbox).toBe(false);
  expect(searchPlan("in:outbox").gmailQuery).toBe("");
  expect(searchPlan("in:outbox").outbox).toBe(true);
});

test("is:todo shows only todos, -is:todo none, and neither reaches Gmail", () => {
  const only = searchPlan("is:todo groceries");
  expect(only.gmailQuery).toBe("groceries");
  expect(only.todosOnly).toBe(true);
  expect(only.snoozedOnly).toBe(false);
  const none = searchPlan("-is:todo lease");
  expect(none.gmailQuery).toBe("lease");
  expect(none.noTodos).toBe(true);
  expect(none.todosOnly).toBe(false);
});

function summary(
  id: string,
  subject: string,
  sender: string,
  labelIds: string[],
  lastDate: number,
): ThreadSummary {
  return {
    id,
    historyId: "1",
    subject,
    snippet: "",
    senders: [{ name: sender, email: `${sender.toLowerCase()}@x.example.com` }],
    messageCount: 1,
    lastDate,
    sentDate: lastDate,
    labelIds,
    unread: labelIds.includes("UNREAD"),
    messageIds: [],
    firstDate: lastDate,
  };
}

test("offline search matches words and the chips it can check", () => {
  const threads = [
    {
      summary: summary("a", "Lease paperwork", "Sam", ["INBOX"], 3),
      detail: null,
    },
    {
      summary: summary("b", "Garage lease", "Maya", ["Label_1", "UNREAD"], 5),
      detail: null,
    },
    { summary: summary("c", "Dinner", "Sam", ["INBOX"], 4), detail: null },
  ];
  const labels = [{ id: "Label_1", name: "Home Stuff", type: "user" as const }];
  expect(searchLocal(threads, searchPlan("lease"), labels)).toEqual(["b", "a"]);
  expect(searchLocal(threads, searchPlan("from:sam"), labels)).toEqual([
    "c",
    "a",
  ]);
  expect(searchLocal(threads, searchPlan("-from:sam lease"), labels)).toEqual([
    "b",
  ]);
  expect(searchLocal(threads, searchPlan("label:home-stuff"), labels)).toEqual([
    "b",
  ]);
  expect(searchLocal(threads, searchPlan("is:unread"), labels)).toEqual(["b"]);
  expect(searchLocal(threads, searchPlan("in:inbox -dinner"), labels)).toEqual([
    "a",
  ]);
  // Dates can't be checked offline, so they don't narrow.
  expect(
    searchLocal(threads, searchPlan("after:2026/01/01 dinner"), labels),
  ).toEqual(["c"]);
});

test("offline search reads an opened thread's recipients, bodies and attachments", () => {
  const detail: ThreadDetail = {
    id: "d",
    historyId: "1",
    subject: "Trip",
    messages: [
      {
        id: "m1",
        threadId: "d",
        from: { name: "Sam", email: "sam@x.example.com" },
        to: [{ name: "Jose Rivera", email: "jose@rivera.example.net" }],
        date: 1,
        snippet: "",
        unread: false,
        body: {
          html: "<p>Bring the <b>garage</b>\n  opener</p>",
          text: null,
          attachments: [],
          inline: [],
          calendar: null,
        },
      },
    ],
    drafts: [],
  };
  const threads = [
    { summary: summary("d", "Trip", "Sam", ["INBOX"], 1), detail },
    { summary: summary("e", "Trip", "Sam", ["INBOX"], 2), detail: null },
  ];
  expect(searchLocal(threads, searchPlan('"garage opener"'), [])).toEqual([
    "d",
  ]);
  expect(searchLocal(threads, searchPlan("to:jose trip"), [])).toEqual([
    "e",
    "d",
  ]);
  expect(searchLocal(threads, searchPlan("-to:jose trip"), [])).toEqual(["e"]);
  expect(searchLocal(threads, searchPlan("has:attachment"), [])).toEqual(["e"]);
});
