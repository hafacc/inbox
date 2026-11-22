// By relative path: the deploy uploads only functions/, and tsconfig.build.json compiles this in.
import {
  addressOf,
  type ClaimReason,
  checkName,
} from "../../../shared/src/address-name.js";
import {
  CloudflareError,
  type Destination,
  type EmailRouting,
  isOwnRule,
  type RoutingRule,
  ruleForwardsTo,
  ruleMatches,
} from "./cloudflare.js";
import type { GmailMailbox } from "./gmail-api.js";
import { type Log, messageOf } from "./util.js";
import {
  VERIFICATION_QUERY,
  VERIFICATION_SEARCH_LIMIT,
  verificationMail,
} from "./verification-mail.js";

/**
 * Where a claim's setup stands: `pending` until Cloudflare has the destination, `verifying` until
 * the Gmail address is confirmed and the rule exists, `active` once mail flows, `releasing` while
 * account deletion removes its forwarding.
 */
export type SetupStatus = "pending" | "verifying" | "active" | "releasing";

/**
 * A problem the setup is waiting out: `not-configured` (the Cloudflare settings aren't deployed),
 * `cloudflare` (an API call failed; checks retry), `confirm-by-hand` (automatic confirmation didn't
 * work; the user should open the link in Cloudflare's mail).
 */
export type SetupErrorCode =
  | "not-configured"
  | "cloudflare"
  | "confirm-by-hand";

/** A setup problem as clients see it. */
export interface SetupError {
  /** what kind of problem */
  code: SetupErrorCode;
  /** detail for logs and the curious */
  message: string;
}

/** A claim's setup as clients see it on `users/{uid}.addressSetup`. */
export interface SetupState {
  /** where it stands */
  status: SetupStatus;
  /** what it is waiting out, if anything */
  error?: SetupError;
}

/** `addresses/{name}`: who holds a name and how far its routing got. */
export interface AddressClaim {
  /** the claimed name (the doc id) */
  name: string;
  /** owner */
  uid: string;
  /** when it was claimed, in epoch milliseconds; tells a claim from a later one of the same name */
  createdAt: number;
  /** the owner's Gmail address, lowercased, mail is forwarded to */
  destination: string;
  /** where setup stands */
  status: SetupStatus;
  /** Cloudflare destination id, once known */
  destinationId?: string;
  /** Cloudflare rule id, once created */
  ruleId?: string;
  /** when we had Cloudflare send its confirmation mail, in epoch milliseconds */
  verificationSentAt?: number;
  /** when account deletion last began releasing it, in epoch milliseconds */
  releasingAt?: number;
}

/**
 * How a setup save went: `saved`; `gone` when the claim was replaced, retired or is releasing;
 * `behind` when the claim had already got further than the save (another run finished first).
 */
export type UpdateOutcome = "saved" | "gone" | "behind";

const STATUS_RANK: Record<Exclude<SetupStatus, "releasing">, number> = {
  pending: 0,
  verifying: 1,
  active: 2,
};

/**
 * Whether saving `next` over a claim at `current` would move its setup backward (an active claim
 * back to verifying, say), which a slower run of an earlier state must never do.
 */
export function isBehind(current: SetupStatus, next: SetupStatus): boolean {
  if (current === "releasing" || next === "releasing") {
    return false;
  } else {
    return STATUS_RANK[next] < STATUS_RANK[current];
  }
}

/** Fields of a claim the setup changes. */
export type ClaimFields = Partial<
  Pick<
    AddressClaim,
    "status" | "destinationId" | "ruleId" | "verificationSentAt"
  >
>;

/** Result of trying to create a claim. */
export type CreateOutcome =
  | { kind: "created"; claim: AddressClaim }
  | { kind: "same"; claim: AddressClaim }
  | { kind: "taken" }
  | { kind: "has-address"; name: string }
  | { kind: "had-address"; name: string };

/**
 * What `addresses/{name}` holds: a claim, `retired` (the name of a deleted account, kept so nobody
 * else can claim it), or nothing.
 */
export type NameRecord = AddressClaim | "retired" | undefined;

/**
 * Decide a claim of `name` by `uid`, given what `addresses/{name}` holds, the names the user holds,
 * and `former`, the retired name this account held before it was deleted. Names never pass to
 * another account and an account gets one name for good: `had-address` when the account held
 * another name before, `taken` when the name is someone else's or retired by another account,
 * `has-address` when the user has another, `same` when it is already theirs, `free` when it can be
 * created (including taking back `former`).
 */
export function decideClaim(
  uid: string,
  name: string,
  record: NameRecord,
  owned: readonly string[],
  former: string | undefined,
): Exclude<CreateOutcome, { kind: "created" }> | { kind: "free" } {
  const other = owned.find((held) => held !== name);
  if (former !== undefined && former !== name) {
    return { kind: "had-address", name: former };
  } else if (
    (record === "retired" && former !== name) ||
    (record !== undefined && record !== "retired" && record.uid !== uid)
  ) {
    return { kind: "taken" };
  } else if (other !== undefined) {
    return { kind: "has-address", name: other };
  } else if (record !== undefined && record !== "retired") {
    return { kind: "same", claim: record };
  } else {
    return { kind: "free" };
  }
}

/** Firestore, as the address handlers use it. */
export interface AddressStore {
  /** The user's Gmail address, or undefined when they never signed in. */
  accountEmail(uid: string): Promise<string | undefined>;
  /**
   * In one transaction, decide the claim with {@link decideClaim} from `addresses/{name}`,
   * `users/{uid}.address`, every claim with the user's uid and the retired name kept with
   * `holderHash`; when the name is free, write `addresses/{name}` as a new claim carrying
   * `holderHash` and set
   * `users/{uid}.address` and `.addressSetup` to pending.
   */
  create(
    uid: string,
    name: string,
    destination: string,
    now: number,
    holderHash: string,
  ): Promise<CreateOutcome>;
  /** The retired name kept with this holder hash, if any; a claim carrying it doesn't count. */
  formerName(holderHash: string): Promise<string | undefined>;
  /** A claim by name. */
  claim(name: string): Promise<AddressClaim | undefined>;
  /** The claim named by `users/{uid}.address`, if it is still theirs. */
  claimOf(uid: string): Promise<AddressClaim | undefined>;
  /** A claim still verifying whose destination is this Gmail address. */
  verifyingFor(destination: string): Promise<AddressClaim | undefined>;
  /** Every claim whose setup hasn't finished: `pending` or `verifying`. */
  unfinished(): Promise<AddressClaim[]>;
  /** Names of every claim forwarding to this Gmail address. */
  claimsUsing(destination: string): Promise<string[]>;
  /**
   * In one transaction, if the claim still exists unchanged (same uid and `createdAt`), isn't
   * releasing and wouldn't move backward ({@link isBehind}): apply the fields and set
   * `users/{uid}.addressSetup`.
   */
  update(
    claim: AddressClaim,
    fields: ClaimFields,
    setup: SetupState,
  ): Promise<UpdateOutcome>;
  /**
   * Mark the user's claim releasing at `now`, so setup stops touching it; returns it, or
   * undefined.
   */
  beginRelease(uid: string, now: number): Promise<AddressClaim | undefined>;
  /** Every claim account deletion began releasing and hasn't retired. */
  releasing(): Promise<AddressClaim[]>;
  /**
   * In one transaction, if the claim is still releasing unchanged, put it back to `pending` (with
   * `users/{uid}.addressSetup`) for setup to take up again. Returns whether it did.
   */
  rollBack(claim: AddressClaim): Promise<boolean>;
  /**
   * Strip the claim (if unchanged) down to its retired name and `holderHash`, and clear
   * `users/{uid}.address` and `.addressSetup`.
   */
  retire(claim: AddressClaim, holderHash: string): Promise<void>;
}

/**
 * The retired name among the docs that carry a holder hash: a doc that still names its holder is
 * that account's live claim, not a name it held before.
 */
export function retiredNameAmong(
  found: readonly { name: string; uid: unknown }[],
): string | undefined {
  return found.find((entry) => typeof entry.uid !== "string")?.name;
}

/** A follow-up check of a claim's setup. */
export interface CheckTask {
  /** claimed name */
  name: string;
  /** the claim's `createdAt`, so a task outliving its claim does nothing */
  createdAt: number;
  /** position in {@link CHECK_DELAYS_MS} for the chain started at claim time; null for a one-off */
  step: number | null;
}

/** Cloud Tasks, as the address handlers use it. */
export interface CheckQueue {
  /** Queue a check at a time; an id already queued is not an error. */
  enqueue(task: CheckTask, id: string, at: Date): Promise<void>;
}

/** Everything the address handlers talk to, injectable for tests. */
export interface AddressDeps {
  /** Firestore */
  store: AddressStore;
  /** Cloudflare Email Routing, or undefined when its settings aren't deployed */
  routing(): EmailRouting | undefined;
  /** Gmail for a user, or undefined when their grant is gone */
  mailboxFor(uid: string): Promise<GmailMailbox | undefined>;
  /** Open a confirmation link; returns whether Cloudflare answered with a success. */
  openLink(link: string): Promise<boolean>;
  /** Cloud Tasks */
  queue: CheckQueue;
  /** Keyed one-way hash of an account id, kept with its claim and its retired name so that account can take it back. */
  holderHash(uid: string): Promise<string>;
  /** Whether the Firebase Auth user still exists. */
  userExists(uid: string): Promise<boolean>;
  /** the clock, in epoch milliseconds */
  now(): number;
  /** logger */
  log: Log;
}

/**
 * Delays of the check chain after a claim, each from the previous check: quick at first, while
 * Cloudflare's mail arrives, then sparser for about a day, which is how long the link should last.
 */
export const CHECK_DELAYS_MS: readonly number[] = [
  15_000,
  30_000,
  60_000,
  2 * 60_000,
  5 * 60_000,
  10 * 60_000,
  30 * 60_000,
  60 * 60_000,
  3 * 60 * 60_000,
  6 * 60 * 60_000,
  12 * 60 * 60_000,
];

/**
 * How long Cloudflare's confirmation link is taken to last: an older one is replaced on request,
 * and no link is opened for a destination added longer ago.
 */
const LINK_LIFETIME_MS = 24 * 60 * 60_000;

/** How long automatic confirmation gets before the user is asked to open the link themselves. */
const CONFIRM_BY_HAND_AFTER_MS = 10 * 60_000;

/** Slack for clock differences between Cloudflare, Gmail and us when matching the mail. */
const CLOCK_SKEW_MS = 2 * 60_000;

/** Why a request was refused, as the `reason` callables report. */
export type RefusalReason = ClaimReason;

/** A request the address handlers refuse. */
export class AddressRefused extends Error {
  /** why */
  readonly reason: RefusalReason;
  /** the address the refusal is about, for `had-address` */
  readonly address: string | undefined;

  /** Create a refusal */
  constructor(reason: RefusalReason, message: string, address?: string) {
    super(message);
    this.name = "AddressRefused";
    this.reason = reason;
    this.address = address;
  }
}

/** What the callables return. */
export interface AddressResult {
  /** the full address */
  address: string;
  /** where its setup stands */
  setup: SetupState;
}

/** Result of a check task, for logs and tests. */
export type CheckResult = "stale" | "done" | "waiting" | "gave-up";

const NOT_CONFIGURED: SetupError = {
  code: "not-configured",
  message: "Cloudflare Email Routing isn't set up yet.",
};

const FOREIGN_RULE: SetupError = {
  code: "cloudflare",
  message: "A forwarding rule someone made by hand already uses this address.",
};

/** A rule this app didn't make already matches the address, so setup leaves it alone. */
class ForeignRuleError extends Error {
  constructor(address: string) {
    super(`a hand-made rule already matches ${address}`);
    this.name = "ForeignRuleError";
  }
}

const CONFIRM_BY_HAND: SetupError = {
  code: "confirm-by-hand",
  message: "Open the link in the email from Cloudflare to finish.",
};

/** A Cloud Tasks id for a check; one-offs share an id per 30 seconds so a burst queues one. */
export function checkTaskId(task: CheckTask, now: number): string {
  const name = task.name.replace(/\./g, "_");
  const which =
    task.step === null
      ? `now-${Math.floor(now / 30_000)}`
      : `step-${task.step}`;
  return `address-${name}-${task.createdAt}-${which}`;
}

/** Validate an untrusted check task payload, returning undefined when malformed. */
export function parseCheckTask(data: unknown): CheckTask | undefined {
  if (typeof data !== "object" || data === null) return undefined;
  const { name, createdAt, step } = data as Record<string, unknown>;
  if (typeof name !== "string" || typeof createdAt !== "number") {
    return undefined;
  }
  if (step !== null && !(typeof step === "number" && Number.isInteger(step))) {
    return undefined;
  } else {
    return { name, createdAt, step };
  }
}

function setupOf(claim: AddressClaim): SetupState {
  return { status: claim.status };
}

async function enqueueCheck(
  deps: AddressDeps,
  task: CheckTask,
  delayMs: number,
): Promise<void> {
  const now = deps.now();
  await deps.queue.enqueue(
    task,
    checkTaskId(task, now),
    new Date(now + delayMs),
  );
}

async function findDestination(
  routing: EmailRouting,
  claim: AddressClaim,
): Promise<Destination | undefined> {
  if (claim.destinationId !== undefined) {
    const known = await routing.getDestination(claim.destinationId);
    if (known?.email.toLowerCase() === claim.destination) return known;
  }
  return (await routing.listDestinations()).find(
    (destination) => destination.email.toLowerCase() === claim.destination,
  );
}

/** Whether a rule this app didn't make already forwards `address`. */
function hasForeignRule(rules: readonly RoutingRule[], address: string) {
  return rules.some(
    (rule) => ruleMatches(rule, address) && !isOwnRule(rule, address),
  );
}

/**
 * Make sure this app's rule forwards `address` to `destination`; throws {@link ForeignRuleError}
 * rather than touch a rule made by hand for the same address.
 */
async function ensureRule(
  routing: EmailRouting,
  claim: AddressClaim,
  address: string,
  destination: string,
): Promise<RoutingRule> {
  const rules = await routing.listRules();
  if (hasForeignRule(rules, address)) throw new ForeignRuleError(address);
  const matching = rules.filter((rule) => ruleMatches(rule, address));
  const existing =
    matching.find((rule) => rule.id === claim.ruleId) ?? matching[0];
  if (existing === undefined) {
    return routing.createRule(address, destination);
  } else if (
    existing.enabled !== false &&
    ruleForwardsTo(existing, destination)
  ) {
    return existing;
  } else {
    return routing.updateRule(existing.id, address, destination);
  }
}

type ConfirmOutcome =
  | { kind: "verified"; destination: Destination }
  | { kind: "refused" }
  | { kind: "none" };

/**
 * Look in the user's Gmail for Cloudflare's confirmation mail sent since `notBefore`, open its link,
 * and trash the mail once the destination reads as confirmed.
 */
async function autoConfirm(
  deps: AddressDeps,
  routing: EmailRouting,
  claim: AddressClaim,
  destinationId: string,
  notBefore: number,
): Promise<ConfirmOutcome> {
  // Past a day the link has lapsed, and any link found would be some other mail's.
  if (deps.now() - notBefore > LINK_LIFETIME_MS) return { kind: "none" };
  const mailbox = await deps.mailboxFor(claim.uid);
  if (mailbox === undefined) return { kind: "none" };
  let outcome: ConfirmOutcome = { kind: "none" };
  const stubs = await mailbox.listMessages(
    VERIFICATION_QUERY,
    VERIFICATION_SEARCH_LIMIT,
  );
  for (const stub of stubs) {
    const found = verificationMail(
      await mailbox.getFullMessage(stub.id),
      notBefore - CLOCK_SKEW_MS,
      claim.destination,
    );
    if (found === undefined) continue;
    let opened = false;
    try {
      opened = await deps.openLink(found.link);
    } catch (error) {
      deps.log.warn("opening confirmation link failed", {
        name: claim.name,
        message: messageOf(error),
      });
    }
    const destination = await routing.getDestination(destinationId);
    if (destination !== undefined && destination.verified !== null) {
      try {
        await mailbox.trashMessage(found.messageId);
      } catch (error) {
        deps.log.warn("trashing confirmation mail failed", {
          name: claim.name,
          message: messageOf(error),
        });
      }
      deps.log.info("address confirmed automatically", { name: claim.name });
      return { kind: "verified", destination };
    }
    deps.log.info("confirmation link didn't confirm", {
      name: claim.name,
      opened,
    });
    outcome = { kind: "refused" };
  }
  return outcome;
}

/**
 * Save a setup; returns it, the claim's own setup when it had already got further, or undefined
 * when the claim went away.
 */
async function save(
  deps: AddressDeps,
  claim: AddressClaim,
  fields: ClaimFields,
  setup: SetupState,
): Promise<SetupState | undefined> {
  const outcome = await deps.store.update(claim, fields, setup);
  if (outcome === "saved") {
    return setup;
  } else if (outcome === "gone") {
    return undefined;
  } else {
    const current = await deps.store.claim(claim.name);
    return current === undefined ? undefined : setupOf(current);
  }
}

/**
 * Take a claim's setup as far as it goes now: make sure Cloudflare has the Gmail address as a
 * destination (which mails a confirmation link), try to confirm it from the user's Gmail, and once
 * confirmed make sure the forwarding rule exists and mark the claim active. With `resend`, a
 * confirmation mail older than {@link LINK_LIFETIME_MS} is replaced by a fresh one. Safe to run any
 * number of times, concurrently too. Returns the new setup, or undefined when the claim went away.
 */
async function advance(
  deps: AddressDeps,
  claim: AddressClaim,
  resend = false,
): Promise<SetupState | undefined> {
  if (claim.status === "active" || claim.status === "releasing") {
    return setupOf(claim);
  }
  const routing = deps.routing();
  if (routing === undefined) {
    return save(
      deps,
      claim,
      {},
      { status: claim.status, error: NOT_CONFIGURED },
    );
  }
  const fields: ClaimFields = {};
  try {
    let destination = await findDestination(routing, claim);
    if (destination === undefined) {
      destination = await routing.createDestination(claim.destination);
      fields.verificationSentAt = deps.now();
    }
    fields.destinationId = destination.id;
    if (destination.verified === null) {
      const createdAt =
        destination.created === undefined
          ? Number.NaN
          : Date.parse(destination.created);
      let sentAt =
        fields.verificationSentAt ??
        claim.verificationSentAt ??
        (Number.isNaN(createdAt) ? claim.createdAt : createdAt);
      if (resend && deps.now() - sentAt > LINK_LIFETIME_MS) {
        // Cloudflare mails a link only when a destination is added, so add it again.
        await routing.deleteDestination(destination.id);
        destination = await routing.createDestination(claim.destination);
        sentAt = deps.now();
        fields.verificationSentAt = sentAt;
        fields.destinationId = destination.id;
      }
      let confirmed: ConfirmOutcome = { kind: "none" };
      try {
        confirmed = await autoConfirm(
          deps,
          routing,
          claim,
          destination.id,
          sentAt,
        );
      } catch (error) {
        if (error instanceof CloudflareError) throw error;
        deps.log.warn("automatic confirmation failed", {
          name: claim.name,
          message: messageOf(error),
        });
      }
      if (confirmed.kind === "verified") {
        destination = confirmed.destination;
      } else {
        const byHand =
          confirmed.kind === "refused" ||
          deps.now() - sentAt > CONFIRM_BY_HAND_AFTER_MS;
        return save(
          deps,
          claim,
          { ...fields, status: "verifying" },
          byHand
            ? { status: "verifying", error: CONFIRM_BY_HAND }
            : { status: "verifying" },
        );
      }
    }
    const address = addressOf(claim.name);
    let rule: RoutingRule;
    try {
      rule = await ensureRule(routing, claim, address, destination.email);
    } catch (error) {
      if (!(error instanceof ForeignRuleError)) throw error;
      deps.log.warn("hand-made rule matches a claimed address", {
        name: claim.name,
      });
      return save(
        deps,
        claim,
        { ...fields, status: "verifying" },
        { status: "verifying", error: FOREIGN_RULE },
      );
    }
    const setup = await save(
      deps,
      claim,
      { ...fields, status: "active", ruleId: rule.id },
      { status: "active" },
    );
    if (setup === undefined) {
      deps.log.info("claim went away during setup; removing its rule", {
        name: claim.name,
      });
      await routing.deleteRule(rule.id);
    }
    return setup;
  } catch (error) {
    if (!(error instanceof CloudflareError)) throw error;
    deps.log.warn("cloudflare call failed", {
      name: claim.name,
      status: error.status,
      message: error.message,
    });
    const status =
      fields.destinationId === undefined ? claim.status : "verifying";
    return save(
      deps,
      claim,
      { ...fields, status },
      { status, error: { code: "cloudflare", message: error.message } },
    );
  }
}

/**
 * Claim a name for a user: validate it, take it in a transaction (one per account, for good; a
 * name once taken never passes to another account, though a returning account may take its old
 * one back), start the check chain and take setup as far as it goes.
 * Claiming the name the user already holds resumes its setup instead, like {@link checkAddress}.
 */
export async function claimAddress(
  deps: AddressDeps,
  uid: string,
  input: unknown,
): Promise<AddressResult> {
  if (typeof input !== "string") {
    throw new AddressRefused("characters", "Expected { name }.");
  }
  const checked = checkName(input);
  if (!checked.ok) {
    throw new AddressRefused(checked.problem, `That name can't be used.`);
  }
  const { name } = checked;
  const email = await deps.store.accountEmail(uid);
  if (email === undefined) {
    throw new AddressRefused("no-gmail", "Sign in again first.");
  }
  if (await handMade(deps, uid, name)) {
    throw new AddressRefused("taken", "That name is taken.");
  }
  const outcome = await deps.store.create(
    uid,
    name,
    email.toLowerCase(),
    deps.now(),
    await deps.holderHash(uid),
  );
  switch (outcome.kind) {
    case "taken":
      throw new AddressRefused("taken", "That name is taken.");
    case "has-address":
      throw new AddressRefused(
        "has-address",
        `You already have ${addressOf(outcome.name)}.`,
      );
    case "had-address":
      throw new AddressRefused(
        "had-address",
        `Your account already had ${addressOf(outcome.name)} — you can only take that one back.`,
        addressOf(outcome.name),
      );
    case "same":
      if (outcome.claim.status === "releasing") {
        throw new AddressRefused("releasing", "That account is being deleted.");
      }
      break;
    case "created":
      await enqueueCheck(
        deps,
        { name, createdAt: outcome.claim.createdAt, step: 0 },
        CHECK_DELAYS_MS[0]!,
      );
      break;
  }
  const setup =
    (await advance(deps, outcome.claim, outcome.kind === "same")) ??
    setupOf(outcome.claim);
  return { address: addressOf(name), setup };
}

/**
 * Whether the domain's owner made a forwarding rule for `name` by hand, which makes the name
 * taken for anyone not already holding it. When Cloudflare can't be asked, the claim goes ahead:
 * setup never touches such a rule anyway.
 */
async function handMade(
  deps: AddressDeps,
  uid: string,
  name: string,
): Promise<boolean> {
  const routing = deps.routing();
  if (routing === undefined) return false;
  let rules: RoutingRule[];
  try {
    rules = await routing.listRules();
  } catch (error) {
    if (!(error instanceof CloudflareError)) throw error;
    deps.log.warn("couldn't check for hand-made rules", {
      name,
      status: error.status,
    });
    return false;
  }
  if (!hasForeignRule(rules, addressOf(name))) return false;
  return (await deps.store.claim(name))?.uid !== uid;
}

/**
 * The address the user's account held before it was deleted, which only it can take back; null
 * when there is none.
 */
export async function formerAddress(
  deps: Pick<AddressDeps, "store" | "holderHash">,
  uid: string,
): Promise<string | null> {
  const name = await deps.store.formerName(await deps.holderHash(uid));
  return name === undefined ? null : addressOf(name);
}

/**
 * Re-check the user's claim now, e.g. after they opened Cloudflare's link themselves; sends a fresh
 * confirmation mail when the last one is too old to work.
 */
export async function checkAddress(
  deps: AddressDeps,
  uid: string,
): Promise<AddressResult> {
  const claim = await deps.store.claimOf(uid);
  if (claim === undefined) {
    throw new AddressRefused("no-address", "You have no address.");
  }
  const setup = (await advance(deps, claim, true)) ?? setupOf(claim);
  return { address: addressOf(claim.name), setup };
}

/**
 * Stop the user's address for account deletion, if they hold one: mark the claim releasing, then
 * {@link finishRelease}. Returns the address, or undefined when they held none. Safe to retry after
 * a failure partway; {@link finishStuckReleases} takes over one nobody retries.
 */
export async function retireAddress(
  deps: AddressDeps,
  uid: string,
): Promise<string | undefined> {
  const claim = await deps.store.beginRelease(uid, deps.now());
  if (claim === undefined) return undefined;
  await finishRelease(deps, claim);
  return addressOf(claim.name);
}

/**
 * Retire a releasing claim: delete this app's forwarding rule for it (never a rule made by hand),
 * delete the destination unless another claim, rule or the catch-all still forwards to it, then
 * strip the claim down to its retired name and a keyed hash of the account id (so only that
 * account can claim it again) and clear the user's address.
 */
async function finishRelease(
  deps: AddressDeps,
  claim: AddressClaim,
): Promise<void> {
  const address = addressOf(claim.name);
  const routing = deps.routing();
  if (routing === undefined) {
    if (claim.ruleId !== undefined || claim.destinationId !== undefined) {
      throw new AddressRefused(
        "not-configured",
        "Cloudflare Email Routing isn't set up, so forwarding can't be removed.",
      );
    }
  } else {
    const rules = await routing.listRules();
    const kept: RoutingRule[] = [];
    for (const rule of rules) {
      if (ruleMatches(rule, address) && isOwnRule(rule, address)) {
        await routing.deleteRule(rule.id);
      } else {
        kept.push(rule);
      }
    }
    const others = (await deps.store.claimsUsing(claim.destination)).filter(
      (other) => other !== claim.name,
    );
    const catchAll = await routing.catchAll();
    const used =
      others.length > 0 ||
      kept.some((rule) => ruleForwardsTo(rule, claim.destination)) ||
      (catchAll !== undefined && ruleForwardsTo(catchAll, claim.destination));
    if (!used) {
      const destination = await findDestination(routing, claim);
      if (destination !== undefined) {
        await routing.deleteDestination(destination.id);
      }
    }
  }
  await deps.store.retire(claim, await deps.holderHash(claim.uid));
  deps.log.info("address retired", { name: claim.name });
}

/** How long a claim may stay releasing before the sweep takes it to be abandoned. */
export const RELEASE_STALE_MS = 60 * 60_000;

/**
 * Settle every claim left releasing for over {@link RELEASE_STALE_MS} by an account deletion that
 * failed partway and wasn't retried: when the account is gone, finish retiring the name; when it
 * still exists (the user is still here), put the claim back to `pending` so setup restores its
 * forwarding. Returns how many it settled; one failing doesn't stop the rest.
 */
export async function finishStuckReleases(deps: AddressDeps): Promise<number> {
  const claims = await deps.store.releasing();
  let settled = 0;
  for (const claim of claims) {
    if (deps.now() - (claim.releasingAt ?? 0) < RELEASE_STALE_MS) continue;
    try {
      if (await deps.userExists(claim.uid)) {
        if (await deps.store.rollBack(claim)) {
          deps.log.info("abandoned release rolled back", { name: claim.name });
          settled++;
        }
      } else {
        await finishRelease(deps, claim);
        settled++;
      }
    } catch (error) {
      deps.log.warn("sweep couldn't settle a release", {
        name: claim.name,
        message: messageOf(error),
      });
    }
  }
  return settled;
}

/** How old a claim must be before the sweep takes over its setup from the check chain. */
export const SWEEP_AFTER_MS = 15 * 60_000;

/**
 * Take every unfinished setup at least {@link SWEEP_AFTER_MS} old as far as it goes now, so a setup
 * whose check chain ran out (as when Cloudflare settings were deployed after the claim) finishes
 * without the user. Returns how many it advanced; one failing doesn't stop the rest.
 */
export async function resumeUnfinishedSetups(
  deps: AddressDeps,
): Promise<number> {
  const claims = await deps.store.unfinished();
  let advanced = 0;
  for (const claim of claims) {
    if (deps.now() - claim.createdAt < SWEEP_AFTER_MS) continue;
    try {
      await advance(deps, claim);
      advanced++;
    } catch (error) {
      deps.log.warn("sweep couldn't advance a setup", {
        name: claim.name,
        message: messageOf(error),
      });
    }
  }
  return advanced;
}

/** Run one check of a claim; a chain task queues the next step while the setup isn't done. */
export async function handleCheckTask(
  deps: AddressDeps,
  task: CheckTask,
): Promise<CheckResult> {
  const claim = await deps.store.claim(task.name);
  if (claim === undefined || claim.createdAt !== task.createdAt) return "stale";
  if (claim.status === "active" || claim.status === "releasing") return "done";
  const setup = await advance(deps, claim);
  if (setup === undefined) return "stale";
  if (setup.status === "active") return "done";
  if (task.step === null) return "waiting";
  const next = task.step + 1;
  const delay = CHECK_DELAYS_MS[next];
  if (delay === undefined) return "gave-up";
  await enqueueCheck(deps, { ...task, step: next }, delay);
  return "waiting";
}

/**
 * After new mail reached a Gmail address, queue an immediate check of a claim still waiting on its
 * confirmation, so the link is opened as soon as Cloudflare's mail lands. Returns whether it did.
 */
export async function checkAfterMail(
  deps: Pick<AddressDeps, "store" | "queue" | "now">,
  email: string,
): Promise<boolean> {
  const claim = await deps.store.verifyingFor(email.toLowerCase());
  if (claim === undefined) return false;
  const task: CheckTask = {
    name: claim.name,
    createdAt: claim.createdAt,
    step: null,
  };
  const now = deps.now();
  await deps.queue.enqueue(task, checkTaskId(task, now), new Date(now));
  return true;
}
