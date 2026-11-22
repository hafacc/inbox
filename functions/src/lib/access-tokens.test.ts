import { beforeEach, describe, expect, it } from "bun:test";
import {
  type AccessTokenDeps,
  accessTokens,
  type GrantStore,
  type StoredGrant,
} from "./access-tokens.js";
import { GoogleTokenError } from "./google.js";

const UID = "google:1";
const NOW = 1_800_000_000_000;

class MemoryGrants implements GrantStore {
  grants = new Map<string, StoredGrant>();
  /** runs once at the next drop, as a sign-in landing while Google was asked */
  beforeDrop: (() => void) | undefined;

  async read(uid: string) {
    return this.grants.get(uid);
  }
  async dropIfCurrent(uid: string, sealed: string) {
    const signIn = this.beforeDrop;
    this.beforeDrop = undefined;
    signIn?.();
    const grant = this.grants.get(uid);
    if (grant?.sealed === sealed) this.grants.delete(uid);
    return this.grants.get(uid);
  }
}

let grants: MemoryGrants;
let revoked: Set<string>;
let refreshed: string[];
let deps: AccessTokenDeps;

beforeEach(() => {
  grants = new MemoryGrants();
  grants.grants.set(UID, {
    sealed: "sealed:old",
    email: "me@gmail.example.com",
  });
  revoked = new Set();
  refreshed = [];
  deps = {
    store: grants,
    open: async (sealed) => sealed.replace("sealed:", ""),
    async refresh(refreshToken) {
      refreshed.push(refreshToken);
      if (revoked.has(refreshToken)) {
        throw new GoogleTokenError("invalid_grant", "Token has been revoked.");
      }
      return { accessToken: `access:${refreshToken}`, expiresIn: 3600 };
    },
    now: () => NOW,
  };
});

describe("accessTokens", () => {
  it("mints a token once and reuses it", async () => {
    const tokens = accessTokens(deps);
    expect(await tokens.tokenFor(UID)).toEqual({
      accessToken: "access:old",
      expiresAt: NOW + 3600 * 1000,
      email: "me@gmail.example.com",
    });
    await tokens.tokenFor(UID);
    expect(refreshed).toEqual(["old"]);
  });

  it("deletes a refresh token Google revoked", async () => {
    revoked.add("old");
    expect(await accessTokens(deps).tokenFor(UID)).toBeUndefined();
    expect(grants.grants.has(UID)).toBe(false);
  });

  it("keeps and uses a refresh token a sign-in stored while the old one was refused", async () => {
    revoked.add("old");
    grants.beforeDrop = () => {
      grants.grants.set(UID, {
        sealed: "sealed:new",
        email: "me@gmail.example.com",
      });
    };
    const token = await accessTokens(deps).tokenFor(UID);
    expect(token?.accessToken).toBe("access:new");
    expect(grants.grants.get(UID)?.sealed).toBe("sealed:new");
  });

  it("returns nothing when the account went while Google was asked", async () => {
    revoked.add("old");
    grants.beforeDrop = () => grants.grants.delete(UID);
    expect(await accessTokens(deps).tokenFor(UID)).toBeUndefined();
  });

  it("takes a grant just read, and none", async () => {
    const tokens = accessTokens(deps);
    expect(await tokens.tokenFor(UID, null)).toBeUndefined();
    const token = await tokens.tokenFor(UID, {
      sealed: "sealed:other",
      email: "me@gmail.example.com",
    });
    expect(token?.accessToken).toBe("access:other");
  });

  it("reuses a token remembered at sign-in", async () => {
    const tokens = accessTokens(deps);
    tokens.remember(UID, "sealed:old", "access:sign-in", NOW + 3600 * 1000);
    expect((await tokens.tokenFor(UID))?.accessToken).toBe("access:sign-in");
    expect(refreshed).toEqual([]);
  });
});
