import { describe, expect, it } from "bun:test";
import {
  exchangeCode,
  type Fetch,
  GMAIL_MODIFY_SCOPE,
  GoogleTokenError,
  hasScopes,
  parseScopes,
  parseTokenResponse,
  REVOKE_ENDPOINT,
  refreshAccessToken,
  revokeToken,
  TOKEN_ENDPOINT,
} from "./google.js";

const credentials = { clientId: "client-id", clientSecret: "client-secret" };

interface RecordedCall {
  url: string;
  params: URLSearchParams;
}

function mockFetch(
  status: number,
  body: unknown,
): { fetchImpl: Fetch; calls: RecordedCall[] } {
  const calls: RecordedCall[] = [];
  const fetchImpl: Fetch = async (url, init) => {
    calls.push({ url, params: new URLSearchParams(init.body) });
    return new Response(
      typeof body === "string" ? body : JSON.stringify(body),
      { status },
    );
  };
  return { fetchImpl, calls };
}

describe("scopes", () => {
  it("splits on spaces and drops empties", () => {
    expect(parseScopes(`openid  ${GMAIL_MODIFY_SCOPE} email`)).toEqual([
      "openid",
      GMAIL_MODIFY_SCOPE,
      "email",
    ]);
    expect(parseScopes(undefined)).toEqual([]);
  });

  it("requires every scope exactly", () => {
    expect(
      hasScopes(["openid", GMAIL_MODIFY_SCOPE], [GMAIL_MODIFY_SCOPE]),
    ).toBe(true);
    expect(
      hasScopes(
        ["openid", "https://www.googleapis.com/auth/gmail.readonly"],
        [GMAIL_MODIFY_SCOPE],
      ),
    ).toBe(false);
    expect(
      hasScopes([`${GMAIL_MODIFY_SCOPE}.extra`], [GMAIL_MODIFY_SCOPE]),
    ).toBe(false);
  });
});

describe("parseTokenResponse", () => {
  it("parses a full code exchange response", () => {
    expect(
      parseTokenResponse({
        access_token: "access",
        expires_in: 3599,
        refresh_token: "refresh",
        scope: `openid ${GMAIL_MODIFY_SCOPE}`,
        token_type: "Bearer",
        id_token: "id",
      }),
    ).toEqual({
      accessToken: "access",
      expiresIn: 3599,
      refreshToken: "refresh",
      scopes: ["openid", GMAIL_MODIFY_SCOPE],
      idToken: "id",
    });
  });

  it("leaves absent optional fields undefined", () => {
    const parsed = parseTokenResponse({
      access_token: "access",
      expires_in: 3599,
    });
    expect(parsed.refreshToken).toBeUndefined();
    expect(parsed.idToken).toBeUndefined();
    expect(parsed.scopes).toEqual([]);
  });

  it("surfaces OAuth error codes", () => {
    const error = (() => {
      try {
        parseTokenResponse({
          error: "invalid_grant",
          error_description: "Token has been expired or revoked.",
        });
      } catch (caught) {
        return caught;
      }
    })();
    expect(error).toBeInstanceOf(GoogleTokenError);
    expect((error as GoogleTokenError).code).toBe("invalid_grant");
  });

  it("rejects malformed bodies", () => {
    for (const body of [
      null,
      "text",
      {},
      { access_token: "access" },
      { access_token: "access", expires_in: 0 },
    ]) {
      expect(() => parseTokenResponse(body)).toThrow(GoogleTokenError);
    }
  });
});

describe("token endpoint calls", () => {
  it("exchanges a code with the client credentials", async () => {
    const { fetchImpl, calls } = mockFetch(200, {
      access_token: "access",
      expires_in: 3599,
    });
    const tokens = await exchangeCode(
      credentials,
      "the-code",
      "postmessage",
      fetchImpl,
    );
    expect(tokens.accessToken).toBe("access");
    const [call] = calls;
    expect(call?.url).toBe(TOKEN_ENDPOINT);
    expect(Object.fromEntries(call?.params ?? [])).toEqual({
      grant_type: "authorization_code",
      code: "the-code",
      redirect_uri: "postmessage",
      client_id: "client-id",
      client_secret: "client-secret",
    });
  });

  it("refreshes with the refresh token", async () => {
    const { fetchImpl, calls } = mockFetch(200, {
      access_token: "fresh",
      expires_in: 3599,
    });
    expect(
      (await refreshAccessToken(credentials, "refresh", fetchImpl)).accessToken,
    ).toBe("fresh");
    expect(calls[0]?.params.get("grant_type")).toBe("refresh_token");
    expect(calls[0]?.params.get("refresh_token")).toBe("refresh");
  });

  it("reports invalid_grant from an HTTP 400", async () => {
    const { fetchImpl } = mockFetch(400, { error: "invalid_grant" });
    await expect(
      refreshAccessToken(credentials, "revoked", fetchImpl),
    ).rejects.toMatchObject({ code: "invalid_grant" });
  });

  it("reports a non-JSON body", async () => {
    const { fetchImpl } = mockFetch(502, "<html>Bad Gateway</html>");
    await expect(
      refreshAccessToken(credentials, "refresh", fetchImpl),
    ).rejects.toMatchObject({
      code: "invalid_response",
    });
  });
});

describe("revokeToken", () => {
  it("posts the token to the revocation endpoint", async () => {
    const { fetchImpl, calls } = mockFetch(200, {});
    expect(await revokeToken("refresh", fetchImpl)).toBe("revoked");
    expect(calls[0]?.url).toBe(REVOKE_ENDPOINT);
    expect(Object.fromEntries(calls[0]?.params ?? [])).toEqual({
      token: "refresh",
    });
  });

  it("treats invalid_token as already revoked", async () => {
    const { fetchImpl } = mockFetch(400, {
      error: "invalid_token",
      error_description: "Token expired or revoked",
    });
    expect(await revokeToken("gone", fetchImpl)).toBe("already-revoked");
  });

  it("throws on other failures", async () => {
    const { fetchImpl } = mockFetch(503, "<html>Unavailable</html>");
    await expect(revokeToken("refresh", fetchImpl)).rejects.toBeInstanceOf(
      GoogleTokenError,
    );
  });
});

describe("timeouts", () => {
  it("gives token calls a deadline and reports a timeout, never invalid_grant", async () => {
    let signal: AbortSignal | undefined;
    const fetchImpl: Fetch = async (_url, init) => {
      signal = init.signal;
      throw new DOMException("The operation timed out.", "TimeoutError");
    };
    const error = await refreshAccessToken(credentials, "r", fetchImpl).catch(
      (caught) => caught,
    );
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(error).toBeInstanceOf(GoogleTokenError);
    expect(error).toMatchObject({ code: "timeout" });
    expect(
      await revokeToken("r", fetchImpl).catch((caught) => caught),
    ).toMatchObject({ code: "timeout" });
  });
});
