import { expect, test } from "bun:test";
import {
  type AccessToken,
  AccountChangedError,
  TokenCache,
} from "./token-cache";

test("keeps a token until a minute before it expires, sharing one fetch", async () => {
  let now = 0;
  let fetches = 0;
  const cache = new TokenCache(
    async () => {
      fetches++;
      return { accessToken: `token-${fetches}`, expiresAt: now + 3_600_000 };
    },
    () => now,
  );
  const [first, second] = await Promise.all([cache.get(), cache.get()]);
  expect([first, second, fetches]).toEqual(["token-1", "token-1", 1]);
  now = 3_539_999;
  expect(await cache.get()).toBe("token-1");
  now = 3_540_000;
  expect(await cache.get()).toBe("token-2");
  cache.invalidate();
  expect(await cache.get()).toBe("token-3");
});

test("a failed fetch isn't cached", async () => {
  let fail = true;
  const cache = new TokenCache(async () => {
    if (fail) throw new Error("reauth");
    return { accessToken: "ok", expiresAt: Date.now() + 3_600_000 };
  });
  await expect(cache.get()).rejects.toThrow("reauth");
  fail = false;
  expect(await cache.get()).toBe("ok");
});

test("a caller waiting when Gmail rejects the token gets a fresh one", async () => {
  const resolvers: ((token: AccessToken) => void)[] = [];
  const cache = new TokenCache(
    () => new Promise((resolve) => resolvers.push(resolve)),
  );
  const before = cache.get();
  cache.invalidate();
  const after = cache.get();
  const [rejected, fresh] = resolvers;
  const expiresAt = Date.now() + 3_600_000;
  rejected?.({ accessToken: "rejected", expiresAt });
  fresh?.({ accessToken: "fresh", expiresAt });
  expect(await after).toBe("fresh");
  expect(await before).toBe("fresh");
  expect(await cache.get()).toBe("fresh");
  expect(resolvers.length).toBe(2);
});

test("a caller waiting when the account changes gets no token of the next account", async () => {
  const resolvers: ((token: AccessToken) => void)[] = [];
  const cache = new TokenCache(
    () => new Promise((resolve) => resolvers.push(resolve)),
  );
  const before = cache.get();
  cache.accountChanged();
  const after = cache.get();
  const [oldAccount, newAccount] = resolvers;
  const expiresAt = Date.now() + 3_600_000;
  oldAccount?.({ accessToken: "old-account", expiresAt });
  newAccount?.({ accessToken: "new-account", expiresAt });
  await expect(before).rejects.toBeInstanceOf(AccountChangedError);
  expect(await after).toBe("new-account");
  expect(await cache.get()).toBe("new-account");
});
