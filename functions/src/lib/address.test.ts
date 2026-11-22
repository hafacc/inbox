import { beforeEach, describe, expect, it } from "bun:test";
import {
  type AddressClaim,
  type AddressDeps,
  AddressRefused,
  type AddressStore,
  CHECK_DELAYS_MS,
  type CheckTask,
  type ClaimFields,
  type CreateOutcome,
  checkAddress,
  checkAfterMail,
  checkTaskId,
  claimAddress,
  decideClaim,
  finishStuckReleases,
  formerAddress,
  handleCheckTask,
  isBehind,
  parseCheckTask,
  RELEASE_STALE_MS,
  resumeUnfinishedSetups,
  retireAddress,
  retiredNameAmong,
  type SetupState,
  SWEEP_AFTER_MS,
  type UpdateOutcome,
} from "./address.js";
import {
  CloudflareError,
  type Destination,
  type EmailRouting,
  type RoutingRule,
} from "./cloudflare.js";
import type { GmailFullMessage, GmailMailbox } from "./gmail-api.js";
import { holderHash } from "./holder-hash.js";
import { VERIFICATION_QUERY } from "./verification-mail.js";

const UID = "google:1";
const OTHER = "google:2";

// Claimable names made for this run, so no claimable mailbox is written in the tests.
function randomName(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  return Array.from(bytes, (byte) =>
    String.fromCharCode(97 + (byte % 26)),
  ).join("");
}
const NAME = randomName();
const OTHER_NAME = randomName();
const CAPITALIZED = NAME[0]!.toUpperCase() + NAME.slice(1);
const EMAIL = "me@gmail.example.com";
const NOW = 1_800_000_000_000;
const LINK = "https://dash.cloudflare.com/verify?token=t";
const HASH_KEY = Buffer.alloc(32, 7);

class MemoryStore implements AddressStore {
  emails = new Map<string, string>([
    [UID, "Me@Gmail.example.com"],
    [OTHER, "other@gmail.example.com"],
  ]);
  claims = new Map<string, AddressClaim>();
  /** retired name to the holder hash kept with it */
  retired = new Map<string, string>();
  /** claimed name to the holder hash on its claim */
  hashes = new Map<string, string>();
  held = new Map<string, string>();
  setups = new Map<string, SetupState | null>();

  async accountEmail(uid: string) {
    return this.emails.get(uid);
  }
  async create(
    uid: string,
    name: string,
    destination: string,
    now: number,
    hash: string,
  ): Promise<CreateOutcome> {
    const existing = this.claims.get(name);
    const held = this.held.get(uid);
    const owned = [
      ...(held === undefined ? [] : [held]),
      ...[...this.claims.values()]
        .filter((claim) => claim.uid === uid)
        .map((claim) => claim.name),
    ];
    const record = this.retired.has(name)
      ? "retired"
      : existing === undefined
        ? undefined
        : { ...existing };
    const decision = decideClaim(
      uid,
      name,
      record,
      owned,
      await this.formerName(hash),
    );
    if (decision.kind !== "free") return decision;
    const claim: AddressClaim = {
      name,
      uid,
      createdAt: now,
      destination,
      status: "pending",
    };
    this.retired.delete(name);
    this.claims.set(name, { ...claim });
    this.hashes.set(name, hash);
    this.held.set(uid, name);
    this.setups.set(uid, { status: "pending" });
    return { kind: "created", claim };
  }
  async formerName(hash: string) {
    return [...this.retired].find(([, kept]) => kept === hash)?.[0];
  }
  async claim(name: string) {
    const found = this.claims.get(name);
    return found === undefined ? undefined : { ...found };
  }
  async claimOf(uid: string) {
    const name = this.held.get(uid);
    return name === undefined ? undefined : this.claim(name);
  }
  async verifyingFor(destination: string) {
    return [...this.claims.values()].find(
      (claim) =>
        claim.destination === destination && claim.status === "verifying",
    );
  }
  async unfinished() {
    return [...this.claims.values()].filter(
      (claim) => claim.status === "pending" || claim.status === "verifying",
    );
  }
  async claimsUsing(destination: string) {
    return [...this.claims.values()]
      .filter((claim) => claim.destination === destination)
      .map((claim) => claim.name);
  }
  async update(
    claim: AddressClaim,
    fields: ClaimFields,
    setup: SetupState,
  ): Promise<UpdateOutcome> {
    const current = this.claims.get(claim.name);
    if (
      current?.uid !== claim.uid ||
      current.createdAt !== claim.createdAt ||
      current.status === "releasing"
    ) {
      return "gone";
    }
    if (isBehind(current.status, setup.status)) return "behind";
    Object.assign(current, fields);
    this.setups.set(claim.uid, setup);
    return "saved";
  }
  async beginRelease(uid: string, now: number) {
    const name = this.held.get(uid);
    const claim = name === undefined ? undefined : this.claims.get(name);
    if (claim === undefined) return undefined;
    claim.status = "releasing";
    claim.releasingAt = now;
    this.setups.set(uid, { status: "releasing" });
    return { ...claim };
  }
  async releasing() {
    return [...this.claims.values()]
      .filter((claim) => claim.status === "releasing")
      .map((claim) => ({ ...claim }));
  }
  async rollBack(claim: AddressClaim) {
    const current = this.claims.get(claim.name);
    if (
      current?.uid !== claim.uid ||
      current.status !== "releasing" ||
      current.releasingAt !== claim.releasingAt
    ) {
      return false;
    }
    current.status = "pending";
    delete current.releasingAt;
    this.setups.set(claim.uid, { status: "pending" });
    return true;
  }
  async retire(claim: AddressClaim, hash: string) {
    this.claims.delete(claim.name);
    this.hashes.delete(claim.name);
    this.retired.set(claim.name, hash);
    this.held.delete(claim.uid);
    this.setups.set(claim.uid, null);
  }
}

class FakeRouting implements EmailRouting {
  destinations: Destination[] = [];
  rules: RoutingRule[] = [];
  catchAllRule: RoutingRule | undefined;
  calls: string[] = [];
  failCreateRule = false;
  failDeleteRule = false;
  nextId = 1;

  async listDestinations() {
    this.calls.push("listDestinations");
    return this.destinations.map((entry) => ({ ...entry }));
  }
  async createDestination(email: string) {
    this.calls.push(`createDestination ${email}`);
    const destination: Destination = {
      id: `d${this.nextId++}`,
      email,
      verified: null,
      created: new Date(NOW).toISOString(),
    };
    this.destinations.push(destination);
    return { ...destination };
  }
  async getDestination(id: string) {
    const found = this.destinations.find((entry) => entry.id === id);
    return found === undefined ? undefined : { ...found };
  }
  async deleteDestination(id: string) {
    this.calls.push(`deleteDestination ${id}`);
    this.destinations = this.destinations.filter((entry) => entry.id !== id);
  }
  async listRules() {
    return this.rules.map((rule) => ({ ...rule }));
  }
  async catchAll() {
    return this.catchAllRule;
  }
  async createRule(address: string, destination: string) {
    this.calls.push(`createRule ${address} ${destination}`);
    if (this.failCreateRule) {
      throw new CloudflareError(500, [{ code: 1, message: "down" }]);
    }
    const rule = this.rule(`r${this.nextId++}`, address, destination);
    this.rules.push(rule);
    return { ...rule };
  }
  async updateRule(id: string, address: string, destination: string) {
    this.calls.push(`updateRule ${id}`);
    const rule = this.rule(id, address, destination);
    this.rules = this.rules.map((entry) => (entry.id === id ? rule : entry));
    return rule;
  }
  async deleteRule(id: string) {
    this.calls.push(`deleteRule ${id}`);
    if (this.failDeleteRule) {
      throw new CloudflareError(500, [{ code: 1, message: "down" }]);
    }
    this.rules = this.rules.filter((rule) => rule.id !== id);
  }
  rule(id: string, address: string, destination: string): RoutingRule {
    return {
      id,
      name: `inbox: ${address}`,
      enabled: true,
      matchers: [{ type: "literal", field: "to", value: address }],
      actions: [{ type: "forward", value: [destination] }],
    };
  }
  verify(email: string) {
    for (const entry of this.destinations) {
      if (entry.email === email) entry.verified = new Date(NOW).toISOString();
    }
  }
}

const AUTH =
  "mx.google.com; dkim=pass header.i=@notify.cloudflare.com; dmarc=pass header.from=cloudflare.com";

function confirmationMail(id: string, internalDate: number): GmailFullMessage {
  return {
    id,
    threadId: id,
    internalDate: String(internalDate),
    payload: {
      mimeType: "text/html",
      headers: [
        { name: "Authentication-Results", value: AUTH },
        { name: "From", value: "Cloudflare <noreply@notify.cloudflare.com>" },
        { name: "To", value: EMAIL },
        { name: "Subject", value: "Verify your email address" },
      ],
      body: {
        data: Buffer.from(
          `<a href="${LINK}">Verify email address</a>`,
        ).toString("base64url"),
      },
    },
  };
}

class FakeMailbox implements GmailMailbox {
  messages: GmailFullMessage[] = [];
  queries: string[] = [];
  trashed: string[] = [];

  async listMessages(query: string) {
    this.queries.push(query);
    return this.messages.map((entry) => ({
      id: entry.id,
      threadId: entry.threadId,
    }));
  }
  async getFullMessage(id: string) {
    return this.messages.find((entry) => entry.id === id)!;
  }
  async trashMessage(id: string) {
    this.trashed.push(id);
  }
}

let store: MemoryStore;
let routing: FakeRouting;
let mailbox: FakeMailbox;
let configured: boolean;
let clock: number;
let opened: string[];
let linkConfirms: boolean;
let queued: { task: CheckTask; id: string; at: number }[];
let users: Set<string>;
let deps: AddressDeps;

beforeEach(() => {
  store = new MemoryStore();
  routing = new FakeRouting();
  mailbox = new FakeMailbox();
  configured = true;
  clock = NOW;
  opened = [];
  linkConfirms = true;
  queued = [];
  users = new Set([UID, OTHER]);
  deps = {
    store,
    routing: () => (configured ? routing : undefined),
    mailboxFor: async () => mailbox,
    async openLink(link) {
      opened.push(link);
      if (linkConfirms) routing.verify(EMAIL);
      return true;
    },
    queue: {
      async enqueue(task, id, at) {
        queued.push({ task, id, at: at.getTime() });
      },
    },
    holderHash: async (uid) => holderHash(HASH_KEY, uid),
    userExists: async (uid) => users.has(uid),
    now: () => clock,
    log: { info() {}, warn() {} },
  };
});

async function refusal(promise: Promise<unknown>): Promise<string> {
  const error = await promise.catch((caught) => caught);
  expect(error).toBeInstanceOf(AddressRefused);
  return (error as AddressRefused).reason;
}

describe("claimAddress", () => {
  it("refuses bad and reserved names before touching anything", async () => {
    expect(await refusal(claimAddress(deps, UID, "a"))).toBe("length");
    expect(await refusal(claimAddress(deps, UID, "postmaster"))).toBe(
      "reserved",
    );
    expect(await refusal(claimAddress(deps, UID, 7))).toBe("characters");
    expect(store.claims.size).toBe(0);
    expect(routing.calls).toEqual([]);
  });

  it("claims a name, adds the destination and starts the check chain", async () => {
    const result = await claimAddress(deps, UID, CAPITALIZED);
    expect(result).toEqual({
      address: `${NAME}@hafa.cc`,
      setup: { status: "verifying" },
    });
    expect(routing.calls).toEqual([
      "listDestinations",
      `createDestination ${EMAIL}`,
    ]);
    expect(store.claims.get(NAME)).toMatchObject({
      uid: UID,
      destination: EMAIL,
      status: "verifying",
      destinationId: "d1",
      verificationSentAt: NOW,
    });
    expect(queued).toEqual([
      {
        task: { name: NAME, createdAt: NOW, step: 0 },
        id: `address-${NAME}-${NOW}-step-0`,
        at: NOW + CHECK_DELAYS_MS[0]!,
      },
    ]);
  });

  it("puts the holder hash on the claim", async () => {
    await claimAddress(deps, UID, NAME);
    expect(store.hashes.get(NAME)).toBe(holderHash(HASH_KEY, UID));
    expect(await formerAddress(deps, UID)).toBeNull();
  });

  it("refuses a name someone else holds, and a second name", async () => {
    await claimAddress(deps, UID, NAME);
    expect(await refusal(claimAddress(deps, OTHER, NAME))).toBe("taken");
    expect(await refusal(claimAddress(deps, UID, OTHER_NAME))).toBe(
      "has-address",
    );
  });

  it("never lets an account claim a second name, even with its address field cleared", async () => {
    await claimAddress(deps, UID, NAME);
    store.held.delete(UID);
    expect(await refusal(claimAddress(deps, UID, OTHER_NAME))).toBe(
      "has-address",
    );
    expect(store.claims.has(OTHER_NAME)).toBe(false);
  });

  it("refuses a user who never signed in", async () => {
    store.emails.delete(UID);
    expect(await refusal(claimAddress(deps, UID, NAME))).toBe("no-gmail");
  });

  it("resumes setup when claiming the same name again, without a second chain", async () => {
    await claimAddress(deps, UID, NAME);
    routing.verify(EMAIL);
    const again = await claimAddress(deps, UID, NAME);
    expect(again.setup).toEqual({ status: "active" });
    expect(queued).toHaveLength(1);
    expect(routing.destinations).toHaveLength(1);
    expect(routing.rules).toHaveLength(1);
  });

  it("reuses a destination that is already confirmed and goes straight to active", async () => {
    routing.destinations.push({
      id: "old",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    const result = await claimAddress(deps, UID, NAME);
    expect(result.setup).toEqual({ status: "active" });
    expect(routing.calls).toEqual([
      "listDestinations",
      `createRule ${NAME}@hafa.cc ${EMAIL}`,
    ]);
    expect(store.claims.get(NAME)).toMatchObject({
      status: "active",
      destinationId: "old",
      ruleId: "r1",
    });
  });

  it("records that Cloudflare isn't set up, and claims anyway", async () => {
    configured = false;
    const result = await claimAddress(deps, UID, NAME);
    expect(result.setup).toEqual({
      status: "pending",
      error: {
        code: "not-configured",
        message: "Cloudflare Email Routing isn't set up yet.",
      },
    });
    expect(store.claims.get(NAME)?.status).toBe("pending");
  });
});

describe("automatic confirmation", () => {
  beforeEach(async () => {
    await claimAddress(deps, UID, NAME);
    queued = [];
    routing.calls = [];
    mailbox.queries = [];
  });

  it("opens the link in Cloudflare's mail, creates the rule and trashes the mail", async () => {
    mailbox.messages.push(confirmationMail("m1", NOW + 3_000));
    clock = NOW + 15_000;
    expect(
      await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 0 }),
    ).toBe("done");
    expect(mailbox.queries).toEqual([VERIFICATION_QUERY]);
    expect(opened).toEqual([LINK]);
    expect(mailbox.trashed).toEqual(["m1"]);
    expect(routing.calls).toEqual([`createRule ${NAME}@hafa.cc ${EMAIL}`]);
    expect(store.setups.get(UID)).toEqual({ status: "active" });
    expect(queued).toEqual([]);
  });

  it("skips confirmation mail older than the destination", async () => {
    mailbox.messages.push(confirmationMail("old", NOW - 60 * 60_000));
    clock = NOW + 15_000;
    expect(
      await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 0 }),
    ).toBe("waiting");
    expect(opened).toEqual([]);
    expect(queued[0]?.task.step).toBe(1);
    expect(queued[0]?.at).toBe(clock + CHECK_DELAYS_MS[1]!);
  });

  it("asks the user to open the link when opening it didn't confirm", async () => {
    linkConfirms = false;
    mailbox.messages.push(confirmationMail("m1", NOW + 3_000));
    await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 0 });
    expect(mailbox.trashed).toEqual([]);
    expect(store.setups.get(UID)).toEqual({
      status: "verifying",
      error: {
        code: "confirm-by-hand",
        message: "Open the link in the email from Cloudflare to finish.",
      },
    });
  });

  it("asks the user after ten minutes with no mail found", async () => {
    clock = NOW + 11 * 60_000;
    await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 5 });
    expect(store.setups.get(UID)?.error?.code).toBe("confirm-by-hand");
  });

  it("notices a destination the user confirmed by hand", async () => {
    routing.verify(EMAIL);
    expect(await checkAddress(deps, UID)).toEqual({
      address: `${NAME}@hafa.cc`,
      setup: { status: "active" },
    });
    expect(opened).toEqual([]);
  });

  it("sends a fresh confirmation mail when asked after the link is a day old", async () => {
    mailbox.messages.push(confirmationMail("m1", NOW + 3_000));
    linkConfirms = false;
    clock = NOW + 25 * 60 * 60_000;
    await checkAddress(deps, UID);
    expect(routing.calls).toEqual([
      "deleteDestination d1",
      `createDestination ${EMAIL}`,
    ]);
    expect(opened).toEqual([]);
    expect(store.claims.get(NAME)).toMatchObject({
      destinationId: "d2",
      verificationSentAt: clock,
    });
  });

  it("leaves an old link alone when no one asked", async () => {
    clock = NOW + 25 * 60 * 60_000;
    await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 10 });
    expect(routing.calls).toEqual([]);
  });

  it("stops the chain after its last step", async () => {
    const last = CHECK_DELAYS_MS.length - 1;
    expect(
      await handleCheckTask(deps, {
        name: NAME,
        createdAt: NOW,
        step: last,
      }),
    ).toBe("gave-up");
    expect(queued).toEqual([]);
  });

  it("keeps the claim verifying with an error when Cloudflare fails", async () => {
    routing.verify(EMAIL);
    routing.failCreateRule = true;
    await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 0 });
    expect(store.setups.get(UID)).toEqual({
      status: "verifying",
      error: { code: "cloudflare", message: "1: down" },
    });
    expect(queued).toHaveLength(1);
  });

  it("ignores tasks of an older claim and finished ones", async () => {
    expect(
      await handleCheckTask(deps, { name: NAME, createdAt: 1, step: 0 }),
    ).toBe("stale");
    expect(
      await handleCheckTask(deps, { name: "zed", createdAt: NOW, step: 0 }),
    ).toBe("stale");
    store.claims.get(NAME)!.status = "active";
    expect(
      await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 0 }),
    ).toBe("done");
  });

  it("queues an immediate check when mail reaches a verifying mailbox", async () => {
    clock = NOW + 40_000;
    expect(await checkAfterMail(deps, "ME@gmail.example.com")).toBe(true);
    expect(queued).toEqual([
      {
        task: { name: NAME, createdAt: NOW, step: null },
        id: `address-${NAME}-${NOW}-now-${Math.floor(clock / 30_000)}`,
        at: clock,
      },
    ]);
    expect(await checkAfterMail(deps, "other@gmail.example.com")).toBe(false);
  });

  it("removes the rule it made when the claim was released meanwhile", async () => {
    routing.verify(EMAIL);
    const stale = { ...store.claims.get(NAME)! };
    store.claims.get(NAME)!.status = "releasing";
    await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 0 });
    expect(stale.status).toBe("verifying");
    expect(routing.rules).toEqual([]);
  });
});

describe("retiredNameAmong", () => {
  it("skips a live claim carrying the hash", () => {
    expect(retiredNameAmong([{ name: NAME, uid: UID }])).toBeUndefined();
    expect(
      retiredNameAmong([
        { name: NAME, uid: UID },
        { name: OTHER_NAME, uid: undefined },
      ]),
    ).toBe(OTHER_NAME);
    expect(retiredNameAmong([])).toBeUndefined();
  });
});

describe("decideClaim", () => {
  const claim: AddressClaim = {
    name: NAME,
    uid: UID,
    createdAt: NOW,
    destination: EMAIL,
    status: "active",
  };

  it("frees only a name nobody ever held, for an account with none", () => {
    expect(decideClaim(UID, NAME, undefined, [], undefined)).toEqual({
      kind: "free",
    });
    expect(decideClaim(UID, NAME, "retired", [], undefined)).toEqual({
      kind: "taken",
    });
    expect(decideClaim(OTHER, NAME, claim, [], undefined)).toEqual({
      kind: "taken",
    });
    expect(decideClaim(UID, OTHER_NAME, undefined, [NAME], undefined)).toEqual({
      kind: "has-address",
      name: NAME,
    });
    expect(decideClaim(UID, NAME, claim, [NAME], undefined)).toEqual({
      kind: "same",
      claim,
    });
  });

  it("lets a returning account take back only its old name", () => {
    expect(decideClaim(UID, NAME, "retired", [], NAME)).toEqual({
      kind: "free",
    });
    expect(decideClaim(UID, OTHER_NAME, undefined, [], NAME)).toEqual({
      kind: "had-address",
      name: NAME,
    });
    expect(decideClaim(UID, OTHER_NAME, "retired", [], NAME)).toEqual({
      kind: "had-address",
      name: NAME,
    });
  });
});

describe("retireAddress", () => {
  it("deletes the rule and the unused destination, and keeps the name taken", async () => {
    routing.destinations.push({
      id: "d0",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    await claimAddress(deps, UID, NAME);
    routing.calls = [];
    expect(await retireAddress(deps, UID)).toBe(`${NAME}@hafa.cc`);
    expect(routing.calls).toEqual(["deleteRule r1", "deleteDestination d0"]);
    expect(store.claims.size).toBe(0);
    expect([...store.retired.keys()]).toEqual([NAME]);
    expect(store.setups.get(UID)).toBeNull();
    expect(await retireAddress(deps, UID)).toBeUndefined();
  });

  it("keeps a destination another rule forwards to", async () => {
    routing.destinations.push({
      id: "d0",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    routing.rules.push(routing.rule("mine", "owner@hafa.cc", EMAIL));
    await claimAddress(deps, UID, NAME);
    routing.calls = [];
    await retireAddress(deps, UID);
    expect(routing.calls).toEqual(["deleteRule r1"]);
  });

  it("keeps a destination the catch-all forwards to", async () => {
    routing.destinations.push({
      id: "d0",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    routing.catchAllRule = {
      id: "all",
      matchers: [{ type: "all" }],
      actions: [{ type: "forward", value: [EMAIL] }],
    };
    await claimAddress(deps, UID, NAME);
    routing.calls = [];
    await retireAddress(deps, UID);
    expect(routing.calls).toEqual(["deleteRule r1"]);
  });

  it("never lets another account claim the name", async () => {
    await claimAddress(deps, UID, NAME);
    await retireAddress(deps, UID);
    clock = NOW + 1;
    expect(await refusal(claimAddress(deps, OTHER, NAME))).toBe("taken");
    expect(store.retired.has(NAME)).toBe(true);
    expect(await formerAddress(deps, OTHER)).toBeNull();
  });

  it("lets the returning account take its old name back through the normal setup", async () => {
    await claimAddress(deps, UID, NAME);
    await retireAddress(deps, UID);
    expect(routing.rules).toEqual([]);
    expect(await formerAddress(deps, UID)).toBe(`${NAME}@hafa.cc`);
    clock = NOW + 1;
    queued = [];
    routing.calls = [];
    const result = await claimAddress(deps, UID, NAME);
    expect(result).toEqual({
      address: `${NAME}@hafa.cc`,
      setup: { status: "verifying" },
    });
    expect(routing.calls).toContain(`createDestination ${EMAIL}`);
    expect(store.claims.get(NAME)).toMatchObject({
      uid: UID,
      createdAt: NOW + 1,
      status: "verifying",
    });
    expect(store.retired.has(NAME)).toBe(false);
    expect(queued[0]?.task).toEqual({
      name: NAME,
      createdAt: NOW + 1,
      step: 0,
    });
    expect(await formerAddress(deps, UID)).toBeNull();
  });

  it("refuses the returning account any other name and names the old one", async () => {
    await claimAddress(deps, UID, NAME);
    await retireAddress(deps, UID);
    clock = NOW + 1;
    const error = await claimAddress(deps, UID, OTHER_NAME).catch(
      (caught) => caught,
    );
    expect(error).toBeInstanceOf(AddressRefused);
    expect(error).toMatchObject({
      reason: "had-address",
      address: `${NAME}@hafa.cc`,
      message: `Your account already had ${NAME}@hafa.cc — you can only take that one back.`,
    });
    expect(store.claims.has(OTHER_NAME)).toBe(false);
  });

  it("keeps a keyed hash with the retired name, never the account id", async () => {
    await claimAddress(deps, UID, NAME);
    await retireAddress(deps, UID);
    const kept = store.retired.get(NAME)!;
    expect(kept).toBe(holderHash(HASH_KEY, UID));
    expect(kept).not.toContain(UID);
    expect(kept).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(kept).not.toBe(holderHash(Buffer.alloc(32, 8), UID));
    expect(kept).not.toBe(holderHash(HASH_KEY, OTHER));
  });

  it("refuses to drop forwarding it can't remove", async () => {
    routing.destinations.push({
      id: "d0",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    await claimAddress(deps, UID, NAME);
    configured = false;
    expect(await refusal(retireAddress(deps, UID))).toBe("not-configured");
    expect(store.claims.has(NAME)).toBe(true);
  });
});

describe("check tasks", () => {
  it("round-trips a task and refuses malformed ones", () => {
    const task = { name: "a.b", createdAt: NOW, step: 2 };
    expect(parseCheckTask(task)).toEqual(task);
    expect(parseCheckTask({ ...task, step: null })).toEqual({
      ...task,
      step: null,
    });
    expect(parseCheckTask({ ...task, step: "2" })).toBeUndefined();
    expect(parseCheckTask({ name: "a" })).toBeUndefined();
    expect(parseCheckTask(null)).toBeUndefined();
  });

  it("makes ids Cloud Tasks accepts", () => {
    expect(checkTaskId({ name: "a.b-c", createdAt: 5, step: 0 }, NOW)).toMatch(
      /^[A-Za-z0-9_-]+$/,
    );
  });
});

describe("resumeUnfinishedSetups", () => {
  it("finishes a setup claimed before Cloudflare was configured, once it is old enough", async () => {
    configured = false;
    await claimAddress(deps, UID, NAME);
    configured = true;
    expect(await resumeUnfinishedSetups(deps)).toBe(0);
    expect(store.claims.get(NAME)?.status).toBe("pending");
    clock = NOW + SWEEP_AFTER_MS;
    expect(await resumeUnfinishedSetups(deps)).toBe(1);
    expect(store.claims.get(NAME)).toMatchObject({
      status: "verifying",
      destinationId: "d1",
    });
    routing.verify(EMAIL);
    expect(await resumeUnfinishedSetups(deps)).toBe(1);
    expect(store.claims.get(NAME)?.status).toBe("active");
    expect(await resumeUnfinishedSetups(deps)).toBe(0);
  });
});

describe("hand-made rules", () => {
  function handMade(id: string, address: string): RoutingRule {
    return {
      id,
      name: "support",
      enabled: true,
      matchers: [{ type: "literal", field: "to", value: address }],
      actions: [{ type: "forward", value: ["owner@example.com"] }],
    };
  }

  it("refuses a name a hand-made rule forwards, as taken", async () => {
    routing.rules.push(handMade("h1", `${CAPITALIZED}@hafa.cc`));
    expect(await refusal(claimAddress(deps, UID, NAME))).toBe("taken");
    expect(store.claims.size).toBe(0);
    expect(routing.rules).toEqual([handMade("h1", `${CAPITALIZED}@hafa.cc`)]);
  });

  it("ignores hand-made rules for other addresses, subdomains included", async () => {
    routing.rules.push(handMade("h1", `${NAME}@inbox.hafa.cc`));
    const result = await claimAddress(deps, UID, NAME);
    expect(result.address).toBe(`${NAME}@hafa.cc`);
  });

  it("never updates a hand-made rule that appeared after the claim", async () => {
    await claimAddress(deps, UID, NAME);
    routing.rules.push(handMade("h1", `${NAME}@hafa.cc`));
    routing.verify(EMAIL);
    routing.calls = [];
    const result = await checkAddress(deps, UID);
    expect(result.setup).toEqual({
      status: "verifying",
      error: {
        code: "cloudflare",
        message:
          "A forwarding rule someone made by hand already uses this address.",
      },
    });
    expect(routing.calls).toEqual([]);
    expect(routing.rules).toEqual([handMade("h1", `${NAME}@hafa.cc`)]);
  });

  it("resumes the holder's own claim without refusing it", async () => {
    await claimAddress(deps, UID, NAME);
    routing.rules.push(handMade("h1", `${NAME}@hafa.cc`));
    const again = await claimAddress(deps, UID, NAME);
    expect(again.setup.status).toBe("verifying");
  });

  it("re-enables its own disabled rule", async () => {
    routing.destinations.push({
      id: "d0",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    routing.rules.push({
      ...routing.rule("old", `${NAME}@hafa.cc`, EMAIL),
      enabled: false,
    });
    await claimAddress(deps, UID, NAME);
    expect(routing.calls).toContain("updateRule old");
    expect(store.claims.get(NAME)?.status).toBe("active");
  });

  it("retires only its own rule and leaves a hand-made one alone", async () => {
    routing.destinations.push({
      id: "d0",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    await claimAddress(deps, UID, NAME);
    routing.rules.push(handMade("h1", `${NAME}@hafa.cc`));
    routing.calls = [];
    await retireAddress(deps, UID);
    expect(routing.calls).toEqual(["deleteRule r1", "deleteDestination d0"]);
    expect(routing.rules).toEqual([handMade("h1", `${NAME}@hafa.cc`)]);
  });
});

describe("overlapping setup runs", () => {
  it("never moves an active claim back to verifying", async () => {
    await claimAddress(deps, UID, NAME);
    const slow = { ...store.claims.get(NAME)! };
    routing.verify(EMAIL);
    expect((await checkAddress(deps, UID)).setup).toEqual({
      status: "active",
    });
    // A run that read the claim while it was verifying saves late.
    const outcome = await store.update(
      slow,
      { status: "verifying" },
      { status: "verifying" },
    );
    expect(outcome).toBe("behind");
    expect(store.claims.get(NAME)?.status).toBe("active");
    expect(store.setups.get(UID)).toEqual({ status: "active" });
  });

  it("reports the claim's own setup when a slower run lost", async () => {
    await claimAddress(deps, UID, NAME);
    let raced = false;
    mailbox.listMessages = async (query: string) => {
      if (!raced) {
        raced = true;
        routing.verify(EMAIL);
        await handleCheckTask(deps, {
          name: NAME,
          createdAt: NOW,
          step: 0,
        });
        routing.destinations[0]!.verified = null;
      }
      mailbox.queries.push(query);
      return [];
    };
    const result = await handleCheckTask(deps, {
      name: NAME,
      createdAt: NOW,
      step: 1,
    });
    expect(result).toBe("done");
    expect(store.claims.get(NAME)?.status).toBe("active");
    expect(store.setups.get(UID)).toEqual({ status: "active" });
  });

  it("orders statuses, leaving releasing to its own checks", () => {
    expect(isBehind("active", "verifying")).toBe(true);
    expect(isBehind("verifying", "pending")).toBe(true);
    expect(isBehind("verifying", "verifying")).toBe(false);
    expect(isBehind("pending", "active")).toBe(false);
    expect(isBehind("releasing", "pending")).toBe(false);
  });
});

describe("finishStuckReleases", () => {
  beforeEach(async () => {
    routing.destinations.push({
      id: "d0",
      email: EMAIL,
      verified: "2026-01-01T00:00:00Z",
    });
    await claimAddress(deps, UID, NAME);
    routing.failDeleteRule = true;
    expect(
      await retireAddress(deps, UID).catch((error) => error),
    ).toBeInstanceOf(CloudflareError);
    routing.failDeleteRule = false;
    expect(store.claims.get(NAME)?.status).toBe("releasing");
  });

  it("leaves a release alone while deletion may still be running", async () => {
    users.delete(UID);
    clock = NOW + RELEASE_STALE_MS - 1;
    expect(await finishStuckReleases(deps)).toBe(0);
    expect(store.claims.get(NAME)?.status).toBe("releasing");
  });

  it("finishes retiring the name once the account is gone", async () => {
    users.delete(UID);
    clock = NOW + RELEASE_STALE_MS;
    expect(await finishStuckReleases(deps)).toBe(1);
    expect(store.claims.has(NAME)).toBe(false);
    expect(store.retired.get(NAME)).toBe(holderHash(HASH_KEY, UID));
    expect(routing.rules).toEqual([]);
  });

  it("rolls back when the user is still here, and setup restores forwarding", async () => {
    clock = NOW + RELEASE_STALE_MS;
    expect(await finishStuckReleases(deps)).toBe(1);
    expect(store.claims.get(NAME)?.status).toBe("pending");
    expect(store.setups.get(UID)).toEqual({ status: "pending" });
    expect(await resumeUnfinishedSetups(deps)).toBe(1);
    expect(store.claims.get(NAME)?.status).toBe("active");
  });

  it("lets a retried deletion finish instead", async () => {
    expect(await retireAddress(deps, UID)).toBe(`${NAME}@hafa.cc`);
    clock = NOW + RELEASE_STALE_MS;
    expect(await finishStuckReleases(deps)).toBe(0);
  });
});

describe("confirmation link age", () => {
  it("opens no link once the destination is over a day old", async () => {
    await claimAddress(deps, UID, NAME);
    mailbox.messages.push(confirmationMail("m1", NOW + 3_000));
    clock = NOW + 25 * 60 * 60_000;
    await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 10 });
    expect(opened).toEqual([]);
  });

  it("opens no link in mail to another address", async () => {
    await claimAddress(deps, UID, NAME);
    const mail = confirmationMail("m1", NOW + 3_000);
    mail.payload!.headers = mail.payload!.headers!.map((header) =>
      header.name === "To"
        ? { ...header, value: "other@gmail.example.com" }
        : header,
    );
    mailbox.messages.push(mail);
    await handleCheckTask(deps, { name: NAME, createdAt: NOW, step: 0 });
    expect(opened).toEqual([]);
  });
});
