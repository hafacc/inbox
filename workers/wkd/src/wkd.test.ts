import { describe, expect, it } from "bun:test";
import { readFileSync } from "node:fs";
import { type Env, handle, MAX_KEY_BYTES, type WkdDeps } from "./index.js";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../../shared/testdata/pgp-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  wkd: { address: string; hash: string; advanced: string; direct: string }[];
};
const alice = fixtures.wkd.find((entry) => entry.address === "test@hafa.cc")!;
const joe = fixtures.wkd.find(
  (entry) => entry.address === "Joe.Doe@Example.ORG",
)!;

const KEY = Uint8Array.from([0x98, 0x33, 0x04, 1, 2, 3]);

function env(
  entries: Record<string, Uint8Array> = { [`hu/${alice.hash}`]: KEY },
  allowed = Number.POSITIVE_INFINITY,
): Env & { limited: string[] } {
  const limited: string[] = [];
  return {
    limited,
    LOOKUP_LIMIT: {
      async limit({ key }) {
        limited.push(key);
        return { success: limited.length <= allowed };
      },
    },
    KEYS: {
      async get(name) {
        const value = entries[name];
        return value === undefined
          ? null
          : (value.slice().buffer as ArrayBuffer);
      },
    },
  };
}

function deps(
  answers: Record<string, Response> = {},
): WkdDeps & { asked: string[]; redirects: (string | undefined)[] } {
  const asked: string[] = [];
  const redirects: (string | undefined)[] = [];
  return {
    asked,
    redirects,
    async fetch(input, init) {
      asked.push(input);
      redirects.push(init.redirect);
      return answers[input] ?? new Response("nope", { status: 404 });
    },
  };
}

function redirect(location: string): Response {
  return new Response(null, { status: 302, headers: { Location: location } });
}

// A body that never says its length up front.
function streamed(size: number, chunk = 64 * 1024): Response {
  let sent = 0;
  return new Response(
    new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent >= size) {
          controller.close();
          return;
        }
        const piece = new Uint8Array(Math.min(chunk, size - sent));
        sent += piece.byteLength;
        controller.enqueue(piece);
      },
    }),
  );
}

async function get(
  path: string,
  init: RequestInit = {},
  run = deps(),
  bindings = env(),
) {
  return handle(
    new Request(`https://openpgpkey.hafa.cc${path}`, init),
    bindings,
    run,
  );
}

describe("WKD", () => {
  it("serves a published key by the advanced and direct paths", async () => {
    for (const path of [
      new URL(alice.advanced).pathname + new URL(alice.advanced).search,
      new URL(alice.direct).pathname,
    ]) {
      const response = await get(path);
      expect(response.status).toBe(200);
      expect(response.headers.get("Content-Type")).toBe(
        "application/octet-stream",
      );
      expect(response.headers.get("Access-Control-Allow-Origin")).toBe("*");
      expect(new Uint8Array(await response.arrayBuffer())).toEqual(KEY);
    }
  });

  it("answers 404 for unknown or malformed hashes and other paths", async () => {
    for (const path of [
      `/.well-known/openpgpkey/hafa.cc/hu/${joe.hash}`,
      "/.well-known/openpgpkey/hafa.cc/hu/short",
      "/.well-known/openpgpkey/example.org/hu/x",
      "/",
    ]) {
      expect((await get(path)).status).toBe(404);
    }
  });

  it("serves an empty policy", async () => {
    for (const path of [
      "/.well-known/openpgpkey/hafa.cc/policy",
      "/.well-known/openpgpkey/policy",
    ]) {
      const response = await get(path);
      expect(response.status).toBe(200);
      expect(await response.text()).toBe("");
    }
  });

  it("refuses other methods", async () => {
    expect(
      (await get("/.well-known/openpgpkey/policy", { method: "POST" })).status,
    ).toBe(405);
  });
});

describe("lookup", () => {
  const origin = { headers: { Origin: "https://inbox.hafa.cc" } };

  it("answers our own addresses from KV", async () => {
    const run = deps();
    const response = await get("/lookup?email=Test%40hafa.cc", origin, run);
    expect(response.status).toBe(200);
    expect(response.headers.get("Access-Control-Allow-Origin")).toBe(
      "https://inbox.hafa.cc",
    );
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(KEY);
    expect(run.asked).toEqual([]);
  });

  it("tries the advanced method, then the direct one", async () => {
    const run = deps({ [joe.direct]: new Response(KEY) });
    const response = await get(
      `/lookup?email=${encodeURIComponent(joe.address)}`,
      origin,
      run,
    );
    expect(response.status).toBe(200);
    expect(run.asked).toEqual([joe.advanced, joe.direct]);
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(KEY);
  });

  it("answers 404 when neither has a key, and drops oversized answers", async () => {
    const big = new Uint8Array(MAX_KEY_BYTES + 1);
    const run = deps({ [joe.advanced]: new Response(big) });
    expect(
      (
        await get(
          `/lookup?email=${encodeURIComponent(joe.address)}`,
          origin,
          run,
        )
      ).status,
    ).toBe(404);
  });

  it("drops an oversized answer sent without a length, without reading it all", async () => {
    const run = deps({ [joe.advanced]: streamed(MAX_KEY_BYTES * 8) });
    const response = await get(
      `/lookup?email=${encodeURIComponent(joe.address)}`,
      origin,
      run,
    );
    expect(response.status).toBe(404);
  });

  it("never lets fetch follow redirects itself", async () => {
    const run = deps();
    await get(`/lookup?email=${encodeURIComponent(joe.address)}`, origin, run);
    expect(run.redirects).toEqual(["manual", "manual"]);
  });

  it("follows one redirect on the same host", async () => {
    const advanced = new URL(joe.advanced);
    const moved = `https://${advanced.host}/moved/key`;
    const run = deps({
      [joe.advanced]: redirect("/moved/key"),
      [moved]: new Response(KEY),
    });
    const response = await get(
      `/lookup?email=${encodeURIComponent(joe.address)}`,
      origin,
      run,
    );
    expect(response.status).toBe(200);
    expect(run.asked).toEqual([joe.advanced, moved]);
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(KEY);
  });

  it("refuses redirects to another host, to http, or a second one", async () => {
    const advanced = new URL(joe.advanced);
    const refused = [
      "http://169.254.169.254/latest",
      "https://evil.example/key",
      `http://${advanced.host}/key`,
      `https://${advanced.host}/two`,
    ];
    for (const answers of [
      { [joe.advanced]: redirect(refused[0]!) },
      { [joe.advanced]: redirect(refused[1]!) },
      { [joe.advanced]: redirect(refused[2]!) },
      {
        [joe.advanced]: redirect("/one"),
        [`https://${advanced.host}/one`]: redirect("/two"),
      },
    ]) {
      const run = deps({ ...answers, [refused[3]!]: new Response(KEY) });
      const response = await get(
        `/lookup?email=${encodeURIComponent(joe.address)}`,
        origin,
        run,
      );
      expect(response.status).toBe(404);
      for (const url of refused) expect(run.asked).not.toContain(url);
    }
  });

  it("refuses non-addresses and private hosts without fetching", async () => {
    const run = deps();
    for (const email of [
      "",
      "nobody",
      "a@127.0.0.1",
      "a@localhost",
      "a@printer.local",
      "a@[::1]",
    ]) {
      expect(
        (await get(`/lookup?email=${encodeURIComponent(email)}`, origin, run))
          .status,
      ).toBe(400);
    }
    expect(run.asked).toEqual([]);
  });

  it("allows only the app's origins", async () => {
    const run = deps();
    const headerSets: Record<string, string>[] = [
      { Origin: "https://evil.example" },
      {},
    ];
    for (const headers of headerSets) {
      const response = await get(
        `/lookup?email=${encodeURIComponent(joe.address)}`,
        { headers },
        run,
      );
      expect(response.status).toBe(403);
      expect(response.headers.get("Access-Control-Allow-Origin")).toBeNull();
    }
    expect(run.asked).toEqual([]);
    const preflight = await get("/lookup", { method: "OPTIONS", ...origin });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get("Access-Control-Allow-Origin")).toBe(
      "https://inbox.hafa.cc",
    );
  });

  it("limits lookups of other domains per client IP, not our own addresses", async () => {
    const bindings = env(undefined, 1);
    const run = deps();
    const asked = (email: string) =>
      get(
        `/lookup?email=${encodeURIComponent(email)}`,
        { headers: { ...origin.headers, "CF-Connecting-IP": "203.0.113.9" } },
        run,
        bindings,
      );
    expect((await asked(alice.address)).status).toBe(200);
    expect((await asked(joe.address)).status).toBe(404);
    const refused = await asked(joe.address);
    expect(refused.status).toBe(429);
    expect(refused.headers.get("Retry-After")).toBe("60");
    expect(bindings.limited).toEqual(["203.0.113.9", "203.0.113.9"]);
    expect(run.asked).toHaveLength(2);
  });
});
