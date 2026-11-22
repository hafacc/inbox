import { fetchTimeout, isTimeout } from "./util.js";

/** Google's OAuth 2.0 token endpoint. */
export const TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

/** Google's OAuth 2.0 revocation endpoint. */
export const REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";

/** Scope the app needs to read, label and archive mail. */
export const GMAIL_MODIFY_SCOPE =
  "https://www.googleapis.com/auth/gmail.modify";

/** OAuth web client credentials. */
export interface ClientCredentials {
  /** web client id */
  clientId: string;
  /** web client secret */
  clientSecret: string;
}

/** Parsed body of a successful token endpoint response. */
export interface TokenResponse {
  /** short-lived access token */
  accessToken: string;
  /** lifetime of the access token in seconds */
  expiresIn: number;
  /** granted scopes */
  scopes: string[];
  /** refresh token, present only when Google issues a new one */
  refreshToken: string | undefined;
  /** OpenID Connect id token, present for code exchanges with the openid scope */
  idToken: string | undefined;
}

/** Error the token endpoint returned, carrying its OAuth error code (e.g. `invalid_grant`). */
export class GoogleTokenError extends Error {
  /** OAuth error code */
  readonly code: string;

  /** Create an error for an OAuth error code */
  constructor(code: string, description: string | undefined) {
    super(description === undefined ? code : `${code}: ${description}`);
    this.name = "GoogleTokenError";
    this.code = code;
  }
}

/** Fetch implementation, injectable for tests. */
export type Fetch = (
  input: string,
  init: {
    method: "POST";
    headers: Record<string, string>;
    body: string;
    signal?: AbortSignal;
  },
) => Promise<Response>;

/** POST a form, turning a timeout into a {@link GoogleTokenError} `timeout`, which callers retry. */
async function postForm(
  fetchImpl: Fetch,
  url: string,
  form: Record<string, string>,
): Promise<Response> {
  try {
    return await fetchImpl(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(form).toString(),
      signal: fetchTimeout(),
    });
  } catch (error) {
    if (isTimeout(error)) {
      throw new GoogleTokenError("timeout", "Google took too long to answer");
    }
    throw error;
  }
}

/** Split a space-delimited OAuth scope string */
export function parseScopes(scope: string | undefined): string[] {
  return (scope ?? "").split(" ").filter((entry) => entry !== "");
}

/** Check whether every required scope was granted */
export function hasScopes(
  granted: readonly string[],
  required: readonly string[],
): boolean {
  const grantedSet = new Set(granted);
  return required.every((scope) => grantedSet.has(scope));
}

function optionalString(
  body: Record<string, unknown>,
  key: string,
): string | undefined {
  const value = body[key];
  return typeof value === "string" && value !== "" ? value : undefined;
}

/** Validate a token endpoint JSON body, throwing {@link GoogleTokenError} on an error body */
export function parseTokenResponse(body: unknown): TokenResponse {
  if (typeof body !== "object" || body === null) {
    throw new GoogleTokenError(
      "invalid_response",
      "token response is not an object",
    );
  }
  const record = body as Record<string, unknown>;
  const errorCode = optionalString(record, "error");
  if (errorCode !== undefined) {
    throw new GoogleTokenError(
      errorCode,
      optionalString(record, "error_description"),
    );
  }
  const accessToken = optionalString(record, "access_token");
  const expiresIn = record.expires_in;
  if (
    accessToken === undefined ||
    typeof expiresIn !== "number" ||
    !(expiresIn > 0)
  ) {
    throw new GoogleTokenError(
      "invalid_response",
      "token response lacks access_token or expires_in",
    );
  }
  return {
    accessToken,
    expiresIn,
    scopes: parseScopes(optionalString(record, "scope")),
    refreshToken: optionalString(record, "refresh_token"),
    idToken: optionalString(record, "id_token"),
  };
}

async function postToken(
  params: Record<string, string>,
  fetchImpl: Fetch,
): Promise<TokenResponse> {
  const response = await postForm(fetchImpl, TOKEN_ENDPOINT, params);
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new GoogleTokenError(
      "invalid_response",
      `token endpoint returned HTTP ${response.status} without JSON`,
    );
  }
  return parseTokenResponse(body);
}

/** Exchange an authorization code from the code client for tokens */
export function exchangeCode(
  credentials: ClientCredentials,
  code: string,
  redirectUri: string,
  fetchImpl: Fetch = fetch,
): Promise<TokenResponse> {
  return postToken(
    {
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri,
      client_id: credentials.clientId,
      client_secret: credentials.clientSecret,
    },
    fetchImpl,
  );
}

/** Trade a refresh token for a fresh access token */
export function refreshAccessToken(
  credentials: ClientCredentials,
  refreshToken: string,
  fetchImpl: Fetch = fetch,
): Promise<TokenResponse> {
  return postToken(
    {
      grant_type: "refresh_token",
      refresh_token: refreshToken,
      client_id: credentials.clientId,
      client_secret: credentials.clientSecret,
    },
    fetchImpl,
  );
}

/**
 * Revoke a refresh token, which ends the whole grant. Returns `already-revoked` when Google answers
 * `invalid_token` (revoked, expired or never valid); throws {@link GoogleTokenError} otherwise.
 */
export async function revokeToken(
  token: string,
  fetchImpl: Fetch = fetch,
): Promise<"revoked" | "already-revoked"> {
  const response = await postForm(fetchImpl, REVOKE_ENDPOINT, { token });
  if (response.ok) return "revoked";
  let code = "invalid_response";
  let description: string | undefined =
    `revoke returned HTTP ${response.status}`;
  try {
    const body = (await response.json()) as Record<string, unknown>;
    code = optionalString(body, "error") ?? code;
    description = optionalString(body, "error_description") ?? description;
  } catch {
    // not JSON
  }
  if (code === "invalid_token") return "already-revoked";
  throw new GoogleTokenError(code, description);
}
