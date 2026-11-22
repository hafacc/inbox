import { lookup as dnsLookup, type LookupAddress } from "node:dns";
import { request as httpsRequest } from "node:https";
import { BlockList, isIP } from "node:net";
import {
  isOneClick,
  listUnsubscribeUris,
  ONE_CLICK_BODY,
} from "../../../shared/src/unsubscribe.js";
import { type GmailApi, GmailError, type GmailMessage } from "./gmail-api.js";

/** Time allowed for each POST. */
const TIMEOUT_MS = 10_000;

/** 307/308 redirects followed before giving up. */
const MAX_REDIRECTS = 3;

const USER_AGENT = "Mozilla/5.0 (compatible; OneClickUnsubscribe/1.0)";

/** Input of the `unsubscribe` callable. */
export interface UnsubscribeInput {
  /** Gmail id of the message carrying the headers */
  messageId: string;
  /** the https URL from its `List-Unsubscribe` header */
  url: string;
  /** its `List-Unsubscribe-Post` header */
  post: string;
}

/** Output of the `unsubscribe` callable. */
export interface UnsubscribeResult {
  /** whether the sender accepted the request (a 2xx, or a 301/302/303 after it) */
  ok: boolean;
  /** the sender's last HTTP status, when it answered */
  status?: number;
}

/** Why a request was refused before anything was sent. */
export type UnsubscribeRefusal =
  | "not-found"
  | "reauth"
  | "not-https"
  | "not-one-click"
  | "not-listed";

/** A request refused before anything was sent. */
export class UnsubscribeRefused extends Error {
  /** Create a refusal for a reason */
  constructor(readonly reason: UnsubscribeRefusal) {
    super(reason);
    this.name = "UnsubscribeRefused";
  }
}

/** A host the server must not contact. */
export class BlockedHostError extends Error {
  /** Create an error naming the host */
  constructor(host: string) {
    super(`blocked host ${host}`);
    this.name = "BlockedHostError";
  }
}

/** Validate untrusted `unsubscribe` input, returning undefined when malformed. */
export function parseUnsubscribeInput(
  data: unknown,
): UnsubscribeInput | undefined {
  if (typeof data !== "object" || data === null) return undefined;
  const { messageId, url, post } = data as Record<string, unknown>;
  if (
    typeof messageId !== "string" ||
    !/^[0-9a-zA-Z]+$/.test(messageId) ||
    typeof url !== "string" ||
    url.length > 8192 ||
    typeof post !== "string"
  ) {
    return undefined;
  }
  return { messageId, url, post };
}

/** Parse an https URL the server may POST to, or undefined. */
function parseHttpsUrl(text: string): URL | undefined {
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return undefined;
  }
  if (url.protocol !== "https:" || url.username !== "" || url.password !== "") {
    return undefined;
  } else {
    return url;
  }
}

// separate lists: a BlockList also matches IPv4 against its IPv4-mapped IPv6 rules
const privateV4 = new BlockList();
const privateV6 = new BlockList();
for (const [address, prefix] of [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["169.254.0.0", 16],
  ["172.16.0.0", 12],
  ["192.0.0.0", 24],
  ["192.0.2.0", 24],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
  ["198.51.100.0", 24],
  ["203.0.113.0", 24],
  ["224.0.0.0", 4],
  ["240.0.0.0", 4],
] as const) {
  privateV4.addSubnet(address, prefix, "ipv4");
}
// IPv4-mapped, NAT64, Teredo and 6to4 ranges are refused whole rather than unwrapped
for (const [address, prefix] of [
  ["::", 128],
  ["::1", 128],
  ["::ffff:0:0", 96],
  ["64:ff9b::", 96],
  ["64:ff9b:1::", 48],
  ["100::", 64],
  ["2001::", 32],
  ["2001:db8::", 32],
  ["2002::", 16],
  ["fc00::", 7],
  ["fe80::", 10],
  ["ff00::", 8],
] as const) {
  privateV6.addSubnet(address, prefix, "ipv6");
}

/** Whether an IP address is private, loopback, link-local or reserved. */
export function isPrivateAddress(address: string): boolean {
  const bare = address.replace(/^\[|\]$/g, "");
  const family = isIP(bare);
  if (family === 4) {
    return privateV4.check(bare, "ipv4");
  } else if (family === 6) {
    return privateV6.check(bare, "ipv6");
  } else {
    return false;
  }
}

/** Whether a hostname is refused before any lookup: a private IP literal or a local-only name. */
export function isBlockedHostname(hostname: string): boolean {
  const name = hostname
    .toLowerCase()
    .replace(/^\[|\]$/g, "")
    .replace(/\.$/, "");
  if (isIP(name) !== 0) {
    return isPrivateAddress(name);
  } else if (!name.includes(".")) {
    return true;
  } else {
    return ["localhost", "local", "internal", "home.arpa"].some(
      (suffix) => name === suffix || name.endsWith(`.${suffix}`),
    );
  }
}

/** One POST's outcome: its status and, for a redirect, where it points. */
export interface PostResponse {
  /** HTTP status */
  status: number;
  /** `Location` header, if any */
  location?: string;
}

/** Sends the one-click POST to a URL; injectable for tests. */
export type OneClickPost = (url: URL) => Promise<PostResponse>;

/**
 * POST the one-click body to a listed URL. Follows 307/308 (which repeat the POST) to other https
 * hosts that aren't private; other redirects mean the sender took the request. Network failures and
 * blocked hosts come back as `{ ok: false }`.
 */
export async function oneClickUnsubscribe(
  start: URL,
  post: OneClickPost,
): Promise<UnsubscribeResult> {
  let url = start;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    if (isBlockedHostname(url.hostname)) return { ok: false };
    let response: PostResponse;
    try {
      response = await post(url);
    } catch {
      return { ok: false };
    }
    const { status, location } = response;
    if ((status === 307 || status === 308) && location !== undefined) {
      const next = parseHttpsUrl(new URL(location, url).href);
      if (next === undefined) return { ok: false, status };
      url = next;
    } else {
      const accepted =
        (status >= 200 && status < 300) ||
        status === 301 ||
        status === 302 ||
        status === 303;
      return { ok: accepted, status };
    }
  }
  return { ok: false };
}

type LookupCallback = (
  error: NodeJS.ErrnoException | null,
  address: string | LookupAddress[],
  family?: number,
) => void;

/** `dns.lookup` that fails for any name resolving to a private address. */
export function guardedLookup(
  hostname: string,
  options: { all?: boolean; family?: number | "IPv4" | "IPv6" },
  callback: LookupCallback,
): void {
  dnsLookup(
    hostname,
    { family: options.family ?? 0, all: true },
    (error, addresses) => {
      if (error !== null) {
        callback(error, []);
      } else if (
        addresses.length === 0 ||
        addresses.some((entry) => isPrivateAddress(entry.address))
      ) {
        callback(new BlockedHostError(hostname), []);
      } else if (options.all === true) {
        callback(null, addresses);
      } else {
        const [first] = addresses;
        callback(null, first!.address, first!.family);
      }
    },
  );
}

/** The real one-click POST: no cookies or referrer, private addresses refused at connect time. */
export const httpsOneClickPost: OneClickPost = (url) =>
  new Promise((resolve, reject) => {
    const outgoing = httpsRequest(
      url,
      {
        method: "POST",
        lookup: guardedLookup,
        signal: AbortSignal.timeout(TIMEOUT_MS),
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "Content-Length": String(ONE_CLICK_BODY.length),
          "User-Agent": USER_AGENT,
        },
      },
      (incoming) => {
        incoming.resume();
        const { location } = incoming.headers;
        resolve({
          status: incoming.statusCode ?? 0,
          ...(location === undefined ? {} : { location }),
        });
      },
    );
    outgoing.on("error", reject);
    outgoing.end(ONE_CLICK_BODY);
  });

/** The unsubscribe headers of one Gmail message. */
export interface UnsubscribeHeaders {
  /** every `List-Unsubscribe` value */
  listUnsubscribe: string[];
  /** every `List-Unsubscribe-Post` value */
  listUnsubscribePost: string[];
}

/** What `unsubscribe` needs from the outside world. */
export interface UnsubscribeDeps {
  /** the message's headers; undefined when it doesn't exist; throws `reauth` when Gmail access is gone */
  headers(
    uid: string,
    messageId: string,
  ): Promise<UnsubscribeHeaders | undefined>;
  /** sends the POST */
  post: OneClickPost;
}

/**
 * Check that the user's message really offers one-click unsubscribe at this URL, then POST it.
 * Throws {@link UnsubscribeRefused} when the request is refused before sending.
 */
export async function unsubscribe(
  deps: UnsubscribeDeps,
  uid: string,
  input: UnsubscribeInput,
): Promise<UnsubscribeResult> {
  const url = parseHttpsUrl(input.url.trim());
  if (url === undefined) throw new UnsubscribeRefused("not-https");
  if (!isOneClick(input.post)) throw new UnsubscribeRefused("not-one-click");
  const headers = await deps.headers(uid, input.messageId);
  if (headers === undefined) throw new UnsubscribeRefused("not-found");
  if (!headers.listUnsubscribePost.some(isOneClick)) {
    throw new UnsubscribeRefused("not-one-click");
  }
  if (
    !headers.listUnsubscribe
      .flatMap((header) => listUnsubscribeUris(header))
      .includes(input.url.trim())
  ) {
    throw new UnsubscribeRefused("not-listed");
  }
  return oneClickUnsubscribe(url, deps.post);
}

/** Read a message's unsubscribe headers from Gmail; undefined when the message doesn't exist. */
export async function unsubscribeHeaders(
  gmail: Pick<GmailApi, "getMessage">,
  messageId: string,
): Promise<UnsubscribeHeaders | undefined> {
  let message: GmailMessage;
  try {
    message = await gmail.getMessage(messageId, [
      "List-Unsubscribe",
      "List-Unsubscribe-Post",
    ]);
  } catch (error) {
    if (
      error instanceof GmailError &&
      (error.status === 404 || error.status === 400)
    ) {
      return undefined;
    } else if (error instanceof GmailError && error.status === 401) {
      throw new UnsubscribeRefused("reauth");
    } else {
      throw error;
    }
  }
  const all = message.payload?.headers ?? [];
  const values = (name: string) =>
    all
      .filter((header) => header.name.toLowerCase() === name.toLowerCase())
      .map((header) => header.value);
  return {
    listUnsubscribe: values("List-Unsubscribe"),
    listUnsubscribePost: values("List-Unsubscribe-Post"),
  };
}
