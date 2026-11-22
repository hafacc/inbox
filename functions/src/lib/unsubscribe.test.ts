import { describe, expect, it } from "bun:test";
import { GmailError } from "./gmail-api.js";
import {
  BlockedHostError,
  guardedLookup,
  httpsOneClickPost,
  isBlockedHostname,
  isPrivateAddress,
  type OneClickPost,
  oneClickUnsubscribe,
  type PostResponse,
  parseUnsubscribeInput,
  type UnsubscribeDeps,
  type UnsubscribeHeaders,
  UnsubscribeRefused,
  unsubscribe,
  unsubscribeHeaders,
} from "./unsubscribe.js";

const URL_TEXT = "https://lists.example.com/unsub?u=1&t=abc";

const HEADERS: UnsubscribeHeaders = {
  listUnsubscribe: [`<mailto:unsub@example.com>, <${URL_TEXT}>`],
  listUnsubscribePost: ["List-Unsubscribe=One-Click"],
};

function poster(responses: Record<string, PostResponse | Error>) {
  const posted: string[] = [];
  const post: OneClickPost = async (url) => {
    posted.push(url.href);
    const response = responses[url.href];
    if (response === undefined) return { status: 404 };
    if (response instanceof Error) throw response;
    return response;
  };
  return { posted, post };
}

function deps(
  headers: UnsubscribeHeaders | undefined,
  post: OneClickPost,
): UnsubscribeDeps & { asked: string[] } {
  const asked: string[] = [];
  return {
    asked,
    async headers(uid, messageId) {
      asked.push(`${uid}/${messageId}`);
      return headers;
    },
    post,
  };
}

const input = {
  messageId: "18c0ffee",
  url: URL_TEXT,
  post: "List-Unsubscribe=One-Click",
};

async function refusal(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof UnsubscribeRefused) return error.reason;
    throw error;
  }
  throw new Error("expected a refusal");
}

describe("parsing", () => {
  it("validates input", () => {
    expect(parseUnsubscribeInput(input)).toEqual(input);
    for (const data of [
      null,
      { ...input, messageId: "" },
      { ...input, messageId: "../x" },
      { ...input, url: 1 },
      { ...input, post: undefined },
    ]) {
      expect(parseUnsubscribeInput(data)).toBeUndefined();
    }
  });

  it("knows private addresses and local names", () => {
    for (const address of [
      "127.0.0.1",
      "10.0.0.1",
      "169.254.169.254",
      "::1",
      "fd00::1",
      "::ffff:127.0.0.1",
      "2002:7f00:1::1",
      "2001:0:4136:e378:8000:63bf:3fff:fdd2",
    ]) {
      expect(isPrivateAddress(address)).toBe(true);
    }
    expect(isPrivateAddress("93.184.216.34")).toBe(false);
    expect(isPrivateAddress("2001:4860:4860::8888")).toBe(false);
    expect(isBlockedHostname("localhost")).toBe(true);
    expect(isBlockedHostname("[::1]")).toBe(true);
    expect(isBlockedHostname("metadata.google.internal")).toBe(true);
    expect(isBlockedHostname("lists.example.com")).toBe(false);
  });
});

type ParseFixture = {
  name: string;
  listUnsubscribe: string | null;
  listUnsubscribePost: string | null;
  expect: { oneClickUrl: string | null };
};

const fixtures = (await Bun.file(
  new URL(
    "../../../shared/testdata/unsubscribe-fixtures.json",
    import.meta.url,
  ),
).json()) as { parse: ParseFixture[] };

describe("the shared unsubscribe fixtures", () => {
  for (const fixture of fixtures.parse) {
    it(fixture.name, async () => {
      const headers: UnsubscribeHeaders = {
        listUnsubscribe:
          fixture.listUnsubscribe === null ? [] : [fixture.listUnsubscribe],
        listUnsubscribePost:
          fixture.listUnsubscribePost === null
            ? []
            : [fixture.listUnsubscribePost],
      };
      if (fixture.expect.oneClickUrl !== null) {
        const post: OneClickPost = async () => ({ status: 200 });
        // The server takes the URL and POST header a client reads from these headers.
        const result = await unsubscribe(deps(headers, post), "uid", {
          messageId: "18c0ffee",
          url: fixture.expect.oneClickUrl,
          post: fixture.listUnsubscribePost ?? "",
        });
        expect(result).toBeDefined();
      }
    });
  }
});

describe("unsubscribe", () => {
  it("posts when the message carries the header and URL", async () => {
    const { posted, post } = poster({ [URL_TEXT]: { status: 200 } });
    const wiring = deps(HEADERS, post);
    expect(await unsubscribe(wiring, "uid", input)).toEqual({
      ok: true,
      status: 200,
    });
    expect(wiring.asked).toEqual(["uid/18c0ffee"]);
    expect(posted).toEqual([URL_TEXT]);
  });

  it("refuses URLs the message doesn't list", async () => {
    const { posted, post } = poster({});
    const other = { ...input, url: "https://evil.example/x" };
    expect(await refusal(unsubscribe(deps(HEADERS, post), "uid", other))).toBe(
      "not-listed",
    );
    expect(posted).toEqual([]);
  });

  it("refuses messages without one-click", async () => {
    const { post } = poster({});
    const plain = { ...HEADERS, listUnsubscribePost: [] };
    expect(await refusal(unsubscribe(deps(plain, post), "uid", input))).toBe(
      "not-one-click",
    );
    expect(
      await refusal(
        unsubscribe(deps(HEADERS, post), "uid", { ...input, post: "" }),
      ),
    ).toBe("not-one-click");
  });

  it("refuses non-https URLs before reading Gmail", async () => {
    const { post } = poster({});
    const wiring = deps(HEADERS, post);
    const http = { ...input, url: "http://lists.example.com/unsub" };
    expect(await refusal(unsubscribe(wiring, "uid", http))).toBe("not-https");
    expect(wiring.asked).toEqual([]);
  });

  it("refuses unknown messages", async () => {
    const { post } = poster({});
    expect(
      await refusal(unsubscribe(deps(undefined, post), "uid", input)),
    ).toBe("not-found");
  });
});

describe("oneClickUnsubscribe", () => {
  const start = new URL(URL_TEXT);

  it("reports failures and network errors as not ok", async () => {
    expect(
      await oneClickUnsubscribe(
        start,
        poster({ [URL_TEXT]: { status: 500 } }).post,
      ),
    ).toEqual({ ok: false, status: 500 });
    expect(
      await oneClickUnsubscribe(
        start,
        poster({ [URL_TEXT]: new Error("timeout") }).post,
      ),
    ).toEqual({ ok: false });
  });

  it("takes a 303 as accepted without following it", async () => {
    const { posted, post } = poster({
      [URL_TEXT]: { status: 303, location: "https://lists.example.com/done" },
    });
    expect(await oneClickUnsubscribe(start, post)).toEqual({
      ok: true,
      status: 303,
    });
    expect(posted).toHaveLength(1);
  });

  it("repeats the POST on 307 but never to private or non-https hosts", async () => {
    const moved = "https://other.example.com/u";
    const followed = poster({
      [URL_TEXT]: { status: 307, location: moved },
      [moved]: { status: 204 },
    });
    expect(await oneClickUnsubscribe(start, followed.post)).toEqual({
      ok: true,
      status: 204,
    });
    expect(followed.posted).toEqual([URL_TEXT, moved]);

    for (const location of [
      "https://127.0.0.1/u",
      "http://other.example.com/u",
    ]) {
      const blocked = poster({ [URL_TEXT]: { status: 308, location } });
      expect((await oneClickUnsubscribe(start, blocked.post)).ok).toBe(false);
      expect(blocked.posted).toEqual([URL_TEXT]);
    }
  });

  it("refuses a private literal host up front", async () => {
    const { posted, post } = poster({});
    expect(
      (await oneClickUnsubscribe(new URL("https://10.0.0.1/u"), post)).ok,
    ).toBe(false);
    expect(posted).toEqual([]);
  });
});

describe("unsubscribeHeaders", () => {
  it("reads both headers from Gmail metadata", async () => {
    let asked: readonly string[] | undefined;
    const headers = await unsubscribeHeaders(
      {
        async getMessage(id, names) {
          asked = names;
          return {
            id,
            threadId: "t",
            payload: {
              headers: [
                { name: "list-unsubscribe", value: `<${URL_TEXT}>` },
                {
                  name: "List-Unsubscribe-Post",
                  value: "List-Unsubscribe=One-Click",
                },
                { name: "Subject", value: "hi" },
              ],
            },
          };
        },
      },
      "18c0ffee",
    );
    expect(asked).toEqual(["List-Unsubscribe", "List-Unsubscribe-Post"]);
    expect(headers).toEqual({
      listUnsubscribe: [`<${URL_TEXT}>`],
      listUnsubscribePost: ["List-Unsubscribe=One-Click"],
    });
  });

  it("maps 404 to undefined and 401 to reauth", async () => {
    const failing = (status: number) => ({
      async getMessage(): Promise<never> {
        throw new GmailError(status, "no");
      },
    });
    expect(await unsubscribeHeaders(failing(404), "x")).toBeUndefined();
    expect(await refusal(unsubscribeHeaders(failing(401), "x"))).toBe("reauth");
  });
});

describe("guardedLookup", () => {
  it("fails names that resolve to private addresses", async () => {
    const error = await new Promise((resolve) =>
      guardedLookup("localhost", {}, (failure) => resolve(failure)),
    );
    expect(error).toBeInstanceOf(BlockedHostError);
  });

  it("stops the real POST at connect time", async () => {
    await expect(
      httpsOneClickPost(new URL("https://localhost:9/unsub")),
    ).rejects.toBeInstanceOf(BlockedHostError);
  });
});
