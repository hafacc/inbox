import { expect, test } from "bun:test";
import { parseUnsubscribe } from "@inbox/shared/unsubscribe";
import { type OneClickCall, Unsubscribes } from "./unsubscribes";

const ONE_CLICK = parseUnsubscribe(
  "<https://list.example/u?id=1>",
  "List-Unsubscribe=One-Click",
);
const MAILTO = parseUnsubscribe("<mailto:leave@list.example>", null);
const PAGE = parseUnsubscribe("<https://list.example/page>", null);

function run(unsubscribes: Unsubscribes, ways = ONE_CLICK, messageId = "m1") {
  const opened: string[] = [];
  const sent: string[] = [];
  const done = unsubscribes.run({
    messageId,
    post: "List-Unsubscribe=One-Click",
    ways,
    send: async (mail) => {
      sent.push(mail.to[0]?.email ?? "");
      return true;
    },
    openPage: (url) => opened.push(url),
  });
  return { done, opened, sent };
}

test("a message's unsubscribe outcome stays with it, not with the screen showing it", async () => {
  const calls: Parameters<OneClickCall>[0][] = [];
  const unsubscribes = new Unsubscribes(new Map(), async (request) => {
    calls.push(request);
    return true;
  });
  expect(unsubscribes.stepOf("m1")).toBe("idle");
  const { done } = run(unsubscribes);
  expect(unsubscribes.stepOf("m1")).toBe("working");
  await done;
  expect(unsubscribes.stepOf("m1")).toBe("unsubscribed");
  expect(unsubscribes.stepOf("m2")).toBe("idle");
  expect(calls).toEqual([
    {
      messageId: "m1",
      url: "https://list.example/u?id=1",
      post: "List-Unsubscribe=One-Click",
    },
  ]);
});

test("a second press while the request is under way sends nothing more", async () => {
  let calls = 0;
  const unsubscribes = new Unsubscribes(new Map(), async () => {
    calls += 1;
    return true;
  });
  const first = run(unsubscribes);
  await run(unsubscribes).done;
  await first.done;
  expect(calls).toBe(1);
});

test("a refused or failed request can be tried again", async () => {
  const offline = new Unsubscribes(
    new Map(),
    async () => {
      throw Object.assign(new Error("down"), { code: "functions/unavailable" });
    },
    () => true,
  );
  await run(offline).done;
  expect(offline.stepOf("m1")).toBe("offline");
  const refused = new Unsubscribes(new Map(), async () => false);
  await run(refused).done;
  expect(refused.stepOf("m1")).toBe("failed");
});

test("mailto sends the request mail; a sender's page opens and leaves the step as it was", async () => {
  const unsubscribes = new Unsubscribes(new Map(), async () => true);
  const mail = run(unsubscribes, MAILTO);
  await mail.done;
  expect(mail.sent).toEqual(["leave@list.example"]);
  expect(unsubscribes.stepOf("m1")).toBe("requested");
  const page = run(unsubscribes, PAGE, "m2");
  await page.done;
  expect(page.opened).toEqual(["https://list.example/page"]);
  expect(unsubscribes.stepOf("m2")).toBe("idle");
});
