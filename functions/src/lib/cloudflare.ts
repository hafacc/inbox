import { fetchTimeout, isTimeout } from "./util.js";

const BASE = "https://api.cloudflare.com/client/v4";

/** Most items Cloudflare returns per page. */
const PAGE_SIZE = 50;

/** Most pages read when listing; 200 destinations or rules is Cloudflare's cap. */
const MAX_PAGES = 10;

/** Cloudflare account and zone the routing lives in, and the token that may change it. */
export interface CloudflareConfig {
  /** API token with Email Routing Addresses Write and Email Routing Rules Write */
  apiToken: string;
  /** account id, which owns destination addresses */
  accountId: string;
  /** zone id of the domain, which owns routing rules */
  zoneId: string;
}

/** An account-level destination address. */
export interface Destination {
  /** destination id */
  id: string;
  /** the address mail is forwarded to */
  email: string;
  /** when its owner confirmed it (ISO time), or null while unconfirmed */
  verified: string | null;
  /** when it was added (ISO time) */
  created?: string;
}

/** One condition of a routing rule. */
export interface RuleMatcher {
  /** `literal` or `all` */
  type: string;
  /** `to` for a literal */
  field?: string;
  /** the address a literal matches */
  value?: string;
}

/** One action of a routing rule. */
export interface RuleAction {
  /** `forward`, `drop` or `worker` */
  type: string;
  /** the destinations of a forward */
  value?: string[];
}

/** A zone-level routing rule. */
export interface RoutingRule {
  /** rule id */
  id: string;
  /** rule name */
  name?: string;
  /** whether it runs */
  enabled?: boolean;
  /** conditions */
  matchers: RuleMatcher[];
  /** actions */
  actions: RuleAction[];
}

/** One error Cloudflare reported. */
export interface CloudflareErrorDetail {
  /** Cloudflare's error code */
  code: number;
  /** its message */
  message: string;
}

/** A Cloudflare API call that failed. */
export class CloudflareError extends Error {
  /** HTTP status */
  readonly status: number;
  /** errors from the response body */
  readonly errors: CloudflareErrorDetail[];

  /** Create an error for an HTTP status and the errors Cloudflare listed */
  constructor(status: number, errors: CloudflareErrorDetail[]) {
    super(
      errors.map((error) => `${error.code}: ${error.message}`).join("; ") ||
        `HTTP ${status}`,
    );
    this.name = "CloudflareError";
    this.status = status;
    this.errors = errors;
  }
}

/** The part of `fetch` the client uses, injectable for tests. */
export type CloudflareFetch = (
  input: string,
  init: {
    method: string;
    headers: Record<string, string>;
    body?: string;
    signal?: AbortSignal;
  },
) => Promise<Response>;

/** The Email Routing calls the server makes. */
export interface EmailRouting {
  /** Every destination address on the account. */
  listDestinations(): Promise<Destination[]>;
  /** Add a destination; Cloudflare mails it a confirmation link. */
  createDestination(email: string): Promise<Destination>;
  /** One destination, or undefined when it is gone. */
  getDestination(id: string): Promise<Destination | undefined>;
  /** Remove a destination; a missing one is not an error. */
  deleteDestination(id: string): Promise<void>;
  /** Every routing rule on the zone. */
  listRules(): Promise<RoutingRule[]>;
  /** The zone's catch-all rule, or undefined when it has none. */
  catchAll(): Promise<RoutingRule | undefined>;
  /** Add a rule forwarding one address to a confirmed destination. */
  createRule(address: string, destination: string): Promise<RoutingRule>;
  /** Point an existing rule at an address and destination, and enable it. */
  updateRule(
    id: string,
    address: string,
    destination: string,
  ): Promise<RoutingRule>;
  /** Remove a rule; a missing one is not an error. */
  deleteRule(id: string): Promise<void>;
}

interface Envelope<Result> {
  success?: boolean;
  errors?: CloudflareErrorDetail[];
  result?: Result;
  result_info?: { page?: number; total_pages?: number };
}

interface RawDestination {
  id?: string;
  tag?: string;
  email: string;
  verified?: string | null;
  created?: string;
}

interface RawRule {
  id?: string;
  tag?: string;
  name?: string;
  enabled?: boolean;
  matchers?: RuleMatcher[];
  actions?: RuleAction[];
}

/** A failure for an answer Cloudflare gave without what the call needs. */
function incomplete(what: string): CloudflareError {
  return new CloudflareError(0, [
    { code: 0, message: `Cloudflare answered without ${what}` },
  ]);
}

function resultOf<Result>(envelope: Envelope<Result>, what: string): Result {
  if (envelope.result === undefined) {
    throw incomplete(what);
  } else {
    return envelope.result;
  }
}

/** The id of a rule or destination Cloudflare returned; throws when it has none. */
function idOf(raw: { id?: string; tag?: string }, what: string): string {
  const id = raw.id ?? raw.tag ?? "";
  if (id === "") {
    throw incomplete(`the ${what}'s id`);
  } else {
    return id;
  }
}

function destinationFrom(raw: RawDestination): Destination {
  return {
    id: idOf(raw, "destination"),
    email: raw.email,
    verified: raw.verified ?? null,
    ...(raw.created === undefined ? {} : { created: raw.created }),
  };
}

function ruleFrom(raw: RawRule): RoutingRule {
  return {
    id: idOf(raw, "rule"),
    ...(raw.name === undefined ? {} : { name: raw.name }),
    ...(raw.enabled === undefined ? {} : { enabled: raw.enabled }),
    matchers: raw.matchers ?? [],
    actions: raw.actions ?? [],
  };
}

/** Start of the name of every rule this app makes; a rule named otherwise was made by hand. */
export const RULE_NAME_PREFIX = "inbox: ";

/** The name this app gives the rule forwarding `address`. */
export function ruleNameFor(address: string): string {
  return `${RULE_NAME_PREFIX}${address.toLowerCase()}`;
}

/** Whether this app made a rule for `address`, judged by its name; any other is never touched. */
export function isOwnRule(rule: RoutingRule, address: string): boolean {
  return rule.name?.toLowerCase() === ruleNameFor(address);
}

/** The body of a rule forwarding `address` to `destination`. */
function forwardRule(address: string, destination: string) {
  return {
    name: ruleNameFor(address),
    enabled: true,
    matchers: [{ type: "literal", field: "to", value: address }],
    actions: [{ type: "forward", value: [destination] }],
  };
}

/** Whether a rule matches exactly this address. */
export function ruleMatches(rule: RoutingRule, address: string): boolean {
  return rule.matchers.some(
    (matcher) =>
      matcher.type === "literal" &&
      matcher.field === "to" &&
      matcher.value?.toLowerCase() === address.toLowerCase(),
  );
}

/** Whether a rule forwards anything to this destination. */
export function ruleForwardsTo(rule: RoutingRule, email: string): boolean {
  return rule.actions.some(
    (action) =>
      action.type === "forward" &&
      (action.value ?? []).some(
        (value) => value.toLowerCase() === email.toLowerCase(),
      ),
  );
}

/** Create an Email Routing client. */
export function emailRouting(
  config: CloudflareConfig,
  fetchImpl: CloudflareFetch = fetch,
): EmailRouting {
  const accountPath = `/accounts/${encodeURIComponent(config.accountId)}/email/routing/addresses`;
  const zonePath = `/zones/${encodeURIComponent(config.zoneId)}/email/routing/rules`;

  async function request<Result>(
    method: string,
    path: string,
    body?: unknown,
  ): Promise<Envelope<Result>> {
    let response: Response;
    let envelope: Envelope<Result> = {};
    try {
      response = await fetchImpl(`${BASE}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${config.apiToken}`,
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: fetchTimeout(),
      });
      try {
        envelope = (await response.json()) as Envelope<Result>;
      } catch (error) {
        if (isTimeout(error)) throw error;
        // not JSON
      }
    } catch (error) {
      // Status 0 marks a call that got no answer; setup treats it like any Cloudflare failure.
      if (isTimeout(error)) {
        throw new CloudflareError(0, [
          { code: 0, message: "Cloudflare took too long to answer" },
        ]);
      }
      throw error;
    }
    if (!response.ok || envelope.success === false) {
      throw new CloudflareError(response.status, envelope.errors ?? []);
    }
    return envelope;
  }

  async function listAll<Raw>(path: string): Promise<Raw[]> {
    const items: Raw[] = [];
    for (let page = 1; page <= MAX_PAGES; page++) {
      const envelope = await request<Raw[]>(
        "GET",
        `${path}?page=${page}&per_page=${PAGE_SIZE}`,
      );
      const result = envelope.result ?? [];
      items.push(...result);
      const totalPages = envelope.result_info?.total_pages;
      if (
        result.length < PAGE_SIZE ||
        (totalPages !== undefined && page >= totalPages)
      ) {
        break;
      }
    }
    return items;
  }

  /** The call's result, or undefined when Cloudflare answers 404. */
  async function ignoreMissing<Result>(
    call: Promise<Result>,
  ): Promise<Result | undefined> {
    try {
      return await call;
    } catch (error) {
      if (error instanceof CloudflareError && error.status === 404) {
        return undefined;
      } else {
        throw error;
      }
    }
  }

  return {
    async listDestinations() {
      return (await listAll<RawDestination>(accountPath)).map(destinationFrom);
    },
    async createDestination(email) {
      const envelope = await request<RawDestination>("POST", accountPath, {
        email,
      });
      return destinationFrom(resultOf(envelope, "the destination"));
    },
    async getDestination(id) {
      const envelope = await ignoreMissing(
        request<RawDestination>(
          "GET",
          `${accountPath}/${encodeURIComponent(id)}`,
        ),
      );
      return envelope?.result === undefined
        ? undefined
        : destinationFrom(envelope.result);
    },
    async deleteDestination(id) {
      await ignoreMissing(
        request("DELETE", `${accountPath}/${encodeURIComponent(id)}`),
      );
    },
    async listRules() {
      return (await listAll<RawRule>(zonePath)).map(ruleFrom);
    },
    async catchAll() {
      const envelope = await ignoreMissing(
        request<RawRule>("GET", `${zonePath}/catch_all`),
      );
      return envelope?.result === undefined
        ? undefined
        : ruleFrom(envelope.result);
    },
    async createRule(address, destination) {
      const envelope = await request<RawRule>(
        "POST",
        zonePath,
        forwardRule(address, destination),
      );
      return ruleFrom(resultOf(envelope, "the rule"));
    },
    async updateRule(id, address, destination) {
      const envelope = await request<RawRule>(
        "PUT",
        `${zonePath}/${encodeURIComponent(id)}`,
        forwardRule(address, destination),
      );
      return ruleFrom(resultOf(envelope, "the rule"));
    },
    async deleteRule(id) {
      await ignoreMissing(
        request("DELETE", `${zonePath}/${encodeURIComponent(id)}`),
      );
    },
  };
}
