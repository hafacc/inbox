import type { GmailFullMessage, GmailHeader, GmailPart } from "./gmail-api.js";
import { headerValue } from "./raw-message.js";
import { decodeEntities } from "./util.js";

/** The only domain (and its subdomains) whose mail and links confirmation trusts. */
const CLOUDFLARE_DOMAIN = "cloudflare.com";

/**
 * Gmail search for Cloudflare's confirmation mail: anywhere, so one Gmail put in spam is found too.
 * `from:cloudflare.com` also matches senders on its subdomains.
 */
export const VERIFICATION_QUERY = `in:anywhere from:${CLOUDFLARE_DOMAIN} newer_than:2d`;

/** Most messages the search reads. */
export const VERIFICATION_SEARCH_LIMIT = 10;

/** Gmail's own `authserv-id`; only its verdict on a message counts. */
const GMAIL_AUTHSERV_ID = "mx.google.com";

/**
 * The only hosts a confirmation link may start on. Cloudflare doesn't document its mail; these are
 * where its destination confirmation links point.
 */
export const CONFIRM_LINK_HOSTS: readonly string[] = [
  "dash.cloudflare.com",
  "verify.cloudflare.com",
];

/** Most redirects followed from a confirmation link, each of which must stay on Cloudflare. */
const MAX_REDIRECTS = 5;

/** Whether a host is Cloudflare's domain or one of its subdomains. */
function isCloudflareHost(host: string): boolean {
  const lower = host.toLowerCase().replace(/\.$/, "");
  return lower === CLOUDFLARE_DOMAIN || lower.endsWith(`.${CLOUDFLARE_DOMAIN}`);
}

/** The domain of a `From` header's address, lowercased. */
export function senderDomain(from: string): string | undefined {
  const bracketed = /<([^<>]*)>\s*$/.exec(from);
  const address = (bracketed?.[1] ?? from).trim();
  const at = address.lastIndexOf("@");
  if (at < 0) {
    return undefined;
  } else {
    return address
      .slice(at + 1)
      .trim()
      .toLowerCase();
  }
}

/**
 * Whether Gmail itself found a message genuinely from Cloudflare: the topmost
 * `Authentication-Results` (the one Gmail adds on arrival) is Gmail's, and reports a DMARC pass for
 * a Cloudflare `From` domain or a DKIM pass signed by a Cloudflare domain.
 */
export function authenticatedAsCloudflare(
  headers: readonly GmailHeader[],
): boolean {
  const results = headerValue(headers, "Authentication-Results");
  if (results === undefined) return false;
  const clauses = results
    .replace(/\s+/g, " ")
    .split(";")
    .map((clause) => clause.trim());
  if (clauses[0]?.toLowerCase() !== GMAIL_AUTHSERV_ID) {
    return false;
  } else {
    return clauses.slice(1).some((clause) => {
      const dmarc = /^dmarc=pass\b.*\bheader\.from=([^\s]+)/i.exec(clause);
      if (dmarc?.[1] !== undefined && isCloudflareHost(dmarc[1])) return true;
      const dkim = /^dkim=pass\b.*\bheader\.(?:i=@?|d=)([^\s]+)/i.exec(clause);
      return dkim?.[1] !== undefined && isCloudflareHost(dkim[1]);
    });
  }
}

function decodeBase64Url(data: string): string {
  return Buffer.from(data, "base64url").toString("utf8");
}

/** The decoded `text/plain` and `text/html` bodies of a message. */
function textBodies(payload: GmailPart | undefined): string[] {
  if (payload === undefined) return [];
  const bodies: string[] = [];
  const mimeType = payload.mimeType?.toLowerCase() ?? "";
  if (
    (mimeType === "text/plain" || mimeType === "text/html") &&
    payload.body?.data !== undefined
  ) {
    bodies.push(decodeBase64Url(payload.body.data));
  }
  for (const part of payload.parts ?? []) bodies.push(...textBodies(part));
  return bodies;
}

/** Every distinct link in some message bodies, HTML `href`s first, in order. */
export function linksIn(bodies: readonly string[]): string[] {
  const links: string[] = [];
  for (const body of bodies) {
    for (const match of body.matchAll(/href\s*=\s*(["'])(.*?)\1/gis)) {
      links.push(decodeEntities(match[2] ?? "").trim());
    }
  }
  for (const body of bodies) {
    for (const match of body.matchAll(/https:\/\/[^\s<>"'()[\]]+/g)) {
      links.push(decodeEntities(match[0]));
    }
  }
  return [...new Set(links)];
}

/**
 * Whether a link is safe to open as a Cloudflare confirmation: https on Cloudflare's domain, on
 * the default port, with no credentials.
 */
export function isCloudflareLink(link: string): boolean {
  let url: URL;
  try {
    url = new URL(link);
  } catch {
    return false;
  }
  return (
    url.protocol === "https:" &&
    url.username === "" &&
    url.password === "" &&
    url.port === "" &&
    isCloudflareHost(url.hostname)
  );
}

/** A confirmation mail found in Gmail. */
export interface VerificationMail {
  /** Gmail message id */
  messageId: string;
  /** the confirmation link */
  link: string;
}

/** Whether a message is addressed to `email`, in its `To` header or its text. */
function namesAddress(
  headers: readonly GmailHeader[],
  bodies: readonly string[],
  email: string,
): boolean {
  const wanted = email.toLowerCase();
  return [headerValue(headers, "To") ?? "", ...bodies].some((text) =>
    decodeEntities(text).toLowerCase().includes(wanted),
  );
}

/**
 * The confirmation link in a message, if it is Cloudflare's confirmation mail for `destination`.
 *
 * The message must have arrived no earlier than `notBefore`, come from a Cloudflare domain as Gmail
 * authenticated it, name `destination` (in `To` or its text) and mention verifying; the link must
 * be a {@link isCloudflareLink} on one of {@link CONFIRM_LINK_HOSTS} whose path or query mentions
 * verifying. Cloudflare doesn't document the mail, so this keys on those traits rather than an
 * exact subject or URL.
 */
export function verificationMail(
  message: GmailFullMessage,
  notBefore: number,
  destination: string,
): VerificationMail | undefined {
  if (Number(message.internalDate ?? 0) < notBefore) return undefined;
  const headers = message.payload?.headers ?? [];
  const domain = senderDomain(headerValue(headers, "From") ?? "");
  if (domain === undefined || !isCloudflareHost(domain)) return undefined;
  if (!authenticatedAsCloudflare(headers)) return undefined;
  const bodies = textBodies(message.payload);
  const subject = headerValue(headers, "Subject") ?? "";
  if (![subject, ...bodies].some((text) => /verif/i.test(text))) {
    return undefined;
  }
  if (!namesAddress(headers, bodies, destination)) return undefined;
  const link = linksIn(bodies).find((candidate) => {
    if (!isCloudflareLink(candidate)) return false;
    const url = new URL(candidate);
    return (
      CONFIRM_LINK_HOSTS.includes(url.hostname.toLowerCase()) &&
      /verif/i.test(url.pathname + url.search)
    );
  });
  return link === undefined ? undefined : { messageId: message.id, link };
}

/** The part of `fetch` link following uses, injectable for tests. */
export type LinkFetch = (
  input: string,
  init: { method: string; redirect: "manual"; signal?: AbortSignal },
) => Promise<Response>;

/**
 * Open a confirmation link as a browser would (GET), if it starts on one of
 * {@link CONFIRM_LINK_HOSTS}, following redirects only while they stay on Cloudflare. Returns
 * whether Cloudflare answered with a success; the caller still checks the destination through the
 * API, since a page may need more than a GET to confirm.
 */
export async function openVerificationLink(
  link: string,
  fetchImpl: LinkFetch = fetch,
  timeoutMs = 10_000,
): Promise<boolean> {
  if (
    !isCloudflareLink(link) ||
    !CONFIRM_LINK_HOSTS.includes(new URL(link).hostname.toLowerCase())
  ) {
    return false;
  }
  let current = link;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    if (!isCloudflareLink(current)) return false;
    const response = await fetchImpl(current, {
      method: "GET",
      redirect: "manual",
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get("Location");
      if (location === null) return false;
      current = new URL(location, current).toString();
    } else {
      return response.ok;
    }
  }
  return false;
}
