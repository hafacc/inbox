import { splitAddress, wkdHash, wkdUrls } from "@inbox/shared/pgp/wkd";

/** The part of a Workers KV namespace the Worker reads. */
export interface KeyNamespace {
  /** a value as bytes, or null when missing */
  get(name: string, type: "arrayBuffer"): Promise<ArrayBuffer | null>;
}

/** The part of a Workers rate limiting binding the Worker uses. */
export interface RateLimiter {
  /** Count one request under `key`; `success` is false once the key is over its limit. */
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

/** Bindings the Worker runs with. */
export interface Env {
  /** published public keys by `hu/<WKD hash>`, written by the `publishKey` function */
  KEYS: KeyNamespace;
  /** `/lookup` calls that fetch other domains, per client IP (`wrangler.jsonc`) */
  LOOKUP_LIMIT: RateLimiter;
}

/** What the Worker talks to, injectable for tests. */
export interface WkdDeps {
  /** outbound fetch, for looking up other domains' keys */
  fetch: (input: string, init: RequestInit) => Promise<Response>;
}

/** The domain this Worker publishes keys for. */
export const DOMAIN = "hafa.cc";

/** Origins allowed to use `/lookup` from a browser. */
export const LOOKUP_ORIGINS: ReadonlySet<string> = new Set([
  "https://inbox.hafa.cc",
  "http://localhost:5173",
]);

/** Largest key answer passed on by `/lookup`, in bytes. */
export const MAX_KEY_BYTES = 256 * 1024;

/** Time allowed per upstream WKD fetch. */
export const LOOKUP_TIMEOUT_MS = 5000;

const HASH = /^[ybndrfg8ejkmcpqxot1uwisza345h769]{32}$/;
const KEY_TTL_SECONDS = 60 * 60;

const BASE_HEADERS: Record<string, string> = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
};

function corsFor(request: Request): Record<string, string> {
  const origin = request.headers.get("Origin");
  return origin !== null && LOOKUP_ORIGINS.has(origin)
    ? { "Access-Control-Allow-Origin": origin, Vary: "Origin" }
    : { Vary: "Origin" };
}

function text(
  status: number,
  body: string,
  extra: Record<string, string> = {},
): Response {
  return new Response(body, {
    status,
    headers: {
      ...BASE_HEADERS,
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control":
        status === 200 ? `public, max-age=${KEY_TTL_SECONDS}` : "no-store",
      ...extra,
    },
  });
}

function keyResponse(
  bytes: ArrayBuffer,
  extra: Record<string, string>,
): Response {
  return new Response(bytes, {
    headers: {
      ...BASE_HEADERS,
      "Content-Type": "application/octet-stream",
      "Cache-Control": `public, max-age=${KEY_TTL_SECONDS}`,
      ...extra,
    },
  });
}

function isPublicHostname(hostname: string): boolean {
  return (
    /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$/.test(
      hostname,
    ) &&
    !hostname.endsWith(".local") &&
    !hostname.endsWith(".internal") &&
    !hostname.endsWith(".localhost")
  );
}

/** The body, read until it passes `MAX_KEY_BYTES` (then canceled); null when empty or too big. */
async function readLimited(response: Response): Promise<ArrayBuffer | null> {
  const length = Number(response.headers.get("Content-Length") ?? "0");
  if (length > MAX_KEY_BYTES || response.body === null) {
    await response.body?.cancel();
    return null;
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_KEY_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  if (total === 0) return null;
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes.buffer;
}

/** Most redirects followed per WKD URL, and only to https on the same host. */
export const MAX_REDIRECTS = 1;

async function fetchWkd(
  deps: WkdDeps,
  url: string,
): Promise<ArrayBuffer | null> {
  const signal = AbortSignal.timeout(LOOKUP_TIMEOUT_MS);
  const host = new URL(url).host;
  let target = url;
  try {
    for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
      const response = await deps.fetch(target, {
        headers: { Accept: "application/octet-stream" },
        redirect: "manual",
        signal,
      });
      if (response.ok) return await readLimited(response);
      await response.body?.cancel();
      const location = response.headers.get("Location");
      if (
        response.status < 300 ||
        response.status >= 400 ||
        location === null
      ) {
        return null;
      }
      const next = new URL(location, target);
      if (next.protocol !== "https:" || next.host !== host) return null;
      target = next.href;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Look up someone's key by WKD for a browser, which can't fetch other domains' WKD itself (they
 * rarely send CORS headers): our own addresses from KV, others by the advanced method, then the
 * direct one. Passes on the binary answer as is; the client checks it (`keyFromAnswer`). Upstream
 * redirects are followed only to https on the same host, at most `MAX_REDIRECTS`, so the Worker
 * can't be steered to fetch other hosts.
 *
 * Only the app's origins are served, and lookups that fetch another domain are rate limited per
 * client IP (`LOOKUP_LIMIT`), so the Worker can't be used as a fetcher by other sites or servers.
 */
async function lookup(
  request: Request,
  url: URL,
  env: Env,
  deps: WkdDeps,
): Promise<Response> {
  const cors = corsFor(request);
  if (!LOOKUP_ORIGINS.has(request.headers.get("Origin") ?? "")) {
    return text(403, "Only for the inbox app", cors);
  }
  const email = url.searchParams.get("email") ?? "";
  let parts: { local: string; domain: string };
  try {
    parts = splitAddress(email);
  } catch {
    return text(400, "Give ?email=", cors);
  }
  if (parts.local.length > 64 || !isPublicHostname(parts.domain)) {
    return text(400, "Not an address", cors);
  }
  if (parts.domain === DOMAIN) {
    const stored = await env.KEYS.get(
      `hu/${await wkdHash(parts.local)}`,
      "arrayBuffer",
    );
    return stored === null
      ? text(404, "No key", cors)
      : keyResponse(stored, cors);
  }
  const client = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await env.LOOKUP_LIMIT.limit({ key: client })).success) {
    return text(429, "Too many lookups", { ...cors, "Retry-After": "60" });
  }
  const urls = await wkdUrls(email);
  const found =
    (await fetchWkd(deps, urls.advanced)) ??
    (await fetchWkd(deps, urls.direct));
  return found === null ? text(404, "No key", cors) : keyResponse(found, cors);
}

/**
 * Serve WKD for hafa.cc (draft-koch-openpgp-webkey-service):
 * - `/.well-known/openpgpkey/hafa.cc/hu/<hash>` (advanced method, on openpgpkey.hafa.cc) and
 *   `/.well-known/openpgpkey/hu/<hash>` (direct method, should hafa.cc itself be routed here):
 *   the binary key from KV, or 404. `?l=` is ignored.
 * - `/.well-known/openpgpkey/hafa.cc/policy` and `/.well-known/openpgpkey/policy`: empty.
 * - `/lookup?email=<address>`: WKD lookups for the web app (see `lookup`).
 * Keys are public, so any origin may read them.
 */
export async function handle(
  request: Request,
  env: Env,
  deps: WkdDeps,
): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        ...corsFor(request),
        "Access-Control-Allow-Methods": "GET, HEAD",
        "Access-Control-Max-Age": "86400",
      },
    });
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    return text(405, "Method not allowed", { Allow: "GET, HEAD" });
  }
  if (url.pathname === "/lookup") return lookup(request, url, env, deps);
  const open = { "Access-Control-Allow-Origin": "*" };
  const path = url.pathname.replace(
    /^\/\.well-known\/openpgpkey\/(?:hafa\.cc\/)?/,
    "",
  );
  if (path === url.pathname) return text(404, "Not found", open);
  if (path === "policy") return text(200, "", open);
  const match = /^hu\/([^/]+)$/.exec(path);
  if (match === null || !HASH.test(match[1] ?? "")) {
    return text(404, "Not found", open);
  }
  const stored = await env.KEYS.get(`hu/${match[1]}`, "arrayBuffer");
  if (stored === null) return text(404, "No key", open);
  return keyResponse(stored, open);
}

/** The WKD key Worker. */
export default {
  fetch(request: Request, env: Env) {
    return handle(request, env, { fetch: (input, init) => fetch(input, init) });
  },
};
