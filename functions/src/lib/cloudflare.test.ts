import { describe, expect, it } from "bun:test";
import {
  CloudflareError,
  type CloudflareFetch,
  emailRouting,
  ruleForwardsTo,
  ruleMatches,
} from "./cloudflare.js";

const CONFIG = { apiToken: "tok", accountId: "acc", zoneId: "zone" };

interface Call {
  url: string;
  method: string;
  body: unknown;
  auth: string | undefined;
}

function scripted(responses: { status: number; body: unknown }[]) {
  const calls: Call[] = [];
  const fetchImpl: CloudflareFetch = async (url, init) => {
    calls.push({
      url,
      method: init.method,
      body: init.body === undefined ? undefined : JSON.parse(init.body),
      auth: init.headers.Authorization,
    });
    const next = responses.shift() ?? { status: 200, body: { success: true } };
    return new Response(JSON.stringify(next.body), { status: next.status });
  };
  return { calls, fetchImpl };
}

describe("emailRouting", () => {
  it("creates a destination on the account", async () => {
    const { calls, fetchImpl } = scripted([
      {
        status: 200,
        body: {
          success: true,
          result: {
            id: "d1",
            email: "me@gmail.example.com",
            verified: null,
            created: "2026-11-04T10:00:00Z",
          },
        },
      },
    ]);
    const destination = await emailRouting(CONFIG, fetchImpl).createDestination(
      "me@gmail.example.com",
    );
    expect(destination).toEqual({
      id: "d1",
      email: "me@gmail.example.com",
      verified: null,
      created: "2026-11-04T10:00:00Z",
    });
    expect(calls[0]).toEqual({
      url: "https://api.cloudflare.com/client/v4/accounts/acc/email/routing/addresses",
      method: "POST",
      body: { email: "me@gmail.example.com" },
      auth: "Bearer tok",
    });
  });

  it("reads every page of destinations and falls back to the old tag", async () => {
    const page = Array.from({ length: 50 }, (_, index) => ({
      tag: `d${index}`,
      email: `${index}@gmail.example.com`,
      verified: "2026-01-01T00:00:00Z",
    }));
    const { calls, fetchImpl } = scripted([
      {
        status: 200,
        body: { success: true, result: page, result_info: { total_pages: 2 } },
      },
      {
        status: 200,
        body: {
          success: true,
          result: [{ id: "last", email: "z@gmail.example.com" }],
          result_info: { total_pages: 2 },
        },
      },
    ]);
    const all = await emailRouting(CONFIG, fetchImpl).listDestinations();
    expect(all).toHaveLength(51);
    expect(all[0]?.id).toBe("d0");
    expect(all[50]).toEqual({
      id: "last",
      email: "z@gmail.example.com",
      verified: null,
    });
    expect(calls.map((call) => new URL(call.url).search)).toEqual([
      "?page=1&per_page=50",
      "?page=2&per_page=50",
    ]);
  });

  it("answers undefined for a missing destination and ignores deleting one", async () => {
    const missing = { status: 404, body: { success: false, errors: [] } };
    const { fetchImpl } = scripted([missing, missing]);
    const routing = emailRouting(CONFIG, fetchImpl);
    expect(await routing.getDestination("gone")).toBeUndefined();
    await routing.deleteDestination("gone");
  });

  it("creates, updates and deletes forwarding rules on the zone", async () => {
    const rule = {
      id: "r1",
      enabled: true,
      matchers: [{ type: "literal", field: "to", value: "test@hafa.cc" }],
      actions: [{ type: "forward", value: ["me@gmail.example.com"] }],
    };
    const { calls, fetchImpl } = scripted([
      { status: 200, body: { success: true, result: rule } },
      { status: 200, body: { success: true, result: rule } },
      { status: 200, body: { success: true, result: { id: "r1" } } },
    ]);
    const routing = emailRouting(CONFIG, fetchImpl);
    expect(
      await routing.createRule("test@hafa.cc", "me@gmail.example.com"),
    ).toEqual(rule);
    await routing.updateRule("r1", "test@hafa.cc", "me@gmail.example.com");
    await routing.deleteRule("r1");
    const zone =
      "https://api.cloudflare.com/client/v4/zones/zone/email/routing/rules";
    expect(calls.map((call) => [call.method, call.url])).toEqual([
      ["POST", zone],
      ["PUT", `${zone}/r1`],
      ["DELETE", `${zone}/r1`],
    ]);
    expect(calls[0]?.body).toEqual({
      name: "inbox: test@hafa.cc",
      enabled: true,
      matchers: [{ type: "literal", field: "to", value: "test@hafa.cc" }],
      actions: [{ type: "forward", value: ["me@gmail.example.com"] }],
    });
  });

  it("refuses an answer with no rule or no id, rather than keep an empty id", async () => {
    const { fetchImpl } = scripted([
      { status: 200, body: { success: true } },
      { status: 200, body: { success: true, result: { name: "x" } } },
      { status: 200, body: { success: true, result: [{ email: "a@b.c" }] } },
    ]);
    const routing = emailRouting(CONFIG, fetchImpl);
    await expect(
      routing.createRule("test@hafa.cc", "me@gmail.example.com"),
    ).rejects.toThrow("Cloudflare answered without the rule");
    await expect(
      routing.updateRule("r1", "test@hafa.cc", "me@gmail.example.com"),
    ).rejects.toThrow("Cloudflare answered without the rule's id");
    await expect(routing.listDestinations()).rejects.toThrow(
      "Cloudflare answered without the destination's id",
    );
  });

  it("throws Cloudflare's errors", async () => {
    const { fetchImpl } = scripted([
      {
        status: 400,
        body: {
          success: false,
          errors: [{ code: 2032, message: "destination not verified" }],
        },
      },
    ]);
    const error = await emailRouting(CONFIG, fetchImpl)
      .createRule("test@hafa.cc", "me@gmail.example.com")
      .catch((caught) => caught);
    expect(error).toBeInstanceOf(CloudflareError);
    expect((error as CloudflareError).status).toBe(400);
    expect((error as CloudflareError).message).toBe(
      "2032: destination not verified",
    );
  });

  it("treats success: false as an error even on 200", async () => {
    const { fetchImpl } = scripted([
      { status: 200, body: { success: false, errors: [] } },
    ]);
    await expect(
      emailRouting(CONFIG, fetchImpl).listRules(),
    ).rejects.toBeInstanceOf(CloudflareError);
  });
});

describe("rule matching", () => {
  const rule = {
    id: "r",
    matchers: [{ type: "literal", field: "to", value: "Test@hafa.cc" }],
    actions: [{ type: "forward", value: ["Me@gmail.example.com"] }],
  };

  it("matches the address and destination without regard to case", () => {
    expect(ruleMatches(rule, "test@hafa.cc")).toBe(true);
    expect(ruleMatches(rule, "staff@hafa.cc")).toBe(false);
    expect(ruleForwardsTo(rule, "me@gmail.example.com")).toBe(true);
    expect(ruleForwardsTo(rule, "you@gmail.example.com")).toBe(false);
  });
});

describe("timeouts", () => {
  it("gives every call a deadline and turns a timeout into a CloudflareError", async () => {
    let signal: AbortSignal | undefined;
    const fetchImpl: CloudflareFetch = async (_url, init) => {
      signal = init.signal;
      throw new DOMException("The operation timed out.", "TimeoutError");
    };
    const error = await emailRouting(CONFIG, fetchImpl)
      .listRules()
      .catch((caught) => caught);
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(error).toBeInstanceOf(CloudflareError);
    expect(error).toMatchObject({ status: 0 });
  });
});
