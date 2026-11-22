import { GoogleTokenError } from "./google.js";
import type { GmailTokenResult } from "./requests.js";

/** A cached access token stays in use only while it has longer than this left. */
const REUSE_MARGIN_MS = 5 * 60 * 1000;

/** A user's stored Google grant, as `private/{uid}` holds it. */
export interface StoredGrant {
  /** sealed refresh token */
  sealed: string;
  /** Gmail address, as stored at sign-in */
  email: string;
}

/** Where users' Google grants are stored. */
export interface GrantStore {
  /** The user's stored grant, or undefined when none is stored. */
  read(uid: string): Promise<StoredGrant | undefined>;
  /**
   * In one transaction, delete the stored refresh token if it is still `sealed`; returns the grant
   * stored afterwards (a newer one a sign-in wrote meanwhile), or undefined when none is left.
   */
  dropIfCurrent(uid: string, sealed: string): Promise<StoredGrant | undefined>;
}

/** Everything minting access tokens talks to, injectable for tests. */
export interface AccessTokenDeps {
  /** the stored grants */
  store: GrantStore;
  /** Unseal a stored refresh token. */
  open(sealed: string): Promise<string>;
  /** Ask Google for an access token; throws {@link GoogleTokenError} when Google refuses. */
  refresh(
    refreshToken: string,
  ): Promise<{ accessToken: string; expiresIn: number }>;
  /** the clock, in epoch milliseconds */
  now(): number;
}

/** A Google access token for a user, with their Gmail address. */
export interface AccountToken extends GmailTokenResult {
  /** Gmail address, as stored at sign-in */
  email: string;
}

/** Mints and caches users' Google access tokens; see {@link accessTokens}. */
export interface AccessTokens {
  /**
   * A Google access token from the user's stored refresh token; undefined when none is stored or
   * Google revoked it. Pass `stored` when the grant was just read.
   */
  tokenFor(
    uid: string,
    stored?: StoredGrant | null,
  ): Promise<AccountToken | undefined>;
  /** Remember an access token Google just issued from `sealed`, so the next call reuses it. */
  remember(
    uid: string,
    sealed: string,
    accessToken: string,
    expiresAt: number,
  ): void;
}

/**
 * Mint users' Google access tokens from their stored refresh tokens.
 *
 * A token minted from the same refresh token is reused while it has over 5 minutes left. When
 * Google answers `invalid_grant`, the stored refresh token is deleted, but only while it is still
 * the one tried: when a sign-in stored a new one meanwhile, that one is kept and tried instead.
 */
export function accessTokens(deps: AccessTokenDeps): AccessTokens {
  const minted = new Map<
    string,
    { sealed: string; accessToken: string; expiresAt: number }
  >();
  const remember = (
    uid: string,
    sealed: string,
    accessToken: string,
    expiresAt: number,
  ) => {
    minted.set(uid, { sealed, accessToken, expiresAt });
  };
  const tokenFor = async (
    uid: string,
    stored?: StoredGrant | null,
  ): Promise<AccountToken | undefined> => {
    const grant =
      stored === undefined ? await deps.store.read(uid) : (stored ?? undefined);
    if (grant === undefined) {
      minted.delete(uid);
      return undefined;
    }
    const { sealed, email } = grant;
    const cached = minted.get(uid);
    if (
      cached?.sealed === sealed &&
      cached.expiresAt - deps.now() > REUSE_MARGIN_MS
    ) {
      return {
        accessToken: cached.accessToken,
        expiresAt: cached.expiresAt,
        email,
      };
    }
    const refreshToken = await deps.open(sealed);
    try {
      const requestedAt = deps.now();
      const tokens = await deps.refresh(refreshToken);
      const expiresAt = requestedAt + tokens.expiresIn * 1000;
      remember(uid, sealed, tokens.accessToken, expiresAt);
      return { accessToken: tokens.accessToken, expiresAt, email };
    } catch (error) {
      if (error instanceof GoogleTokenError && error.code === "invalid_grant") {
        minted.delete(uid);
        const current = await deps.store.dropIfCurrent(uid, sealed);
        return current === undefined || current.sealed === sealed
          ? undefined
          : tokenFor(uid, current);
      }
      throw error;
    }
  };
  return { tokenFor, remember };
}
