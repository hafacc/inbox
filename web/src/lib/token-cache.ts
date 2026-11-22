/** A Gmail access token and when it stops working (epoch milliseconds). */
export type AccessToken = { accessToken: string; expiresAt: number };

/** How long before expiry a cached token is replaced. */
export const TOKEN_MARGIN_MS = 60_000;

/** A token was asked for under an account that is no longer signed in. */
export class AccountChangedError extends Error {
  /** Create the error. */
  constructor() {
    super("The account changed while its token was fetched");
    this.name = "AccountChangedError";
  }
}

/** Keeps one access token until shortly before it expires, sharing a single fetch between callers. */
export class TokenCache {
  #fetchToken: () => Promise<AccessToken>;
  #clock: () => number;
  #current: AccessToken | null = null;
  #pending: Promise<AccessToken> | null = null;
  // Bumped by `invalidate` and `accountChanged`, so a fetch started before either can't fill the
  // cache or answer later callers.
  #generation = 0;
  // Bumped by `accountChanged`, so callers waiting then get no token of the next account.
  #account = 0;

  constructor(fetchToken: () => Promise<AccessToken>, clock = Date.now) {
    this.#fetchToken = fetchToken;
    this.#clock = clock;
  }

  /**
   * A token good for at least another minute, fetching one if needed. Rejects with
   * {@link AccountChangedError} when the account changes while it waits.
   */
  async get(): Promise<string> {
    const current = this.#current;
    if (current && current.expiresAt - TOKEN_MARGIN_MS > this.#clock()) {
      return current.accessToken;
    }
    const generation = this.#generation;
    const account = this.#account;
    if (!this.#pending) {
      const pending = this.#fetchToken()
        .then((token) => {
          if (this.#generation === generation) this.#current = token;
          return token;
        })
        .finally(() => {
          if (this.#pending === pending) this.#pending = null;
        });
      this.#pending = pending;
    }
    const token = await this.#pending;
    if (this.#account !== account) {
      throw new AccountChangedError();
    } else if (this.#generation === generation) {
      return token.accessToken;
    } else {
      return this.get();
    }
  }

  /** Forget the cached token and any fetch under way after Gmail rejected it; waiting callers get a fresh one. */
  invalidate(): void {
    this.#generation++;
    this.#current = null;
    this.#pending = null;
  }

  /** Forget the cached token and any fetch under way as the account changes; waiting callers reject. */
  accountChanged(): void {
    this.#account++;
    this.invalidate();
  }
}
