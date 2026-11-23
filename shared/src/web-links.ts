// Web addresses in plain text (a todo's text, a plain-text email body), found so they can be shown
// as links. Pure; web uses it directly, Android ports it to :core (web-link-fixtures.json).

import { unwrapLink } from "./links";

/** A web address in a text, from `start` up to (not including) `end`, in UTF-16 units. */
export type WebLink = {
  start: number;
  end: number;
  /** where it leads: the address as written, `https://` put before a bare domain, unwrapped */
  href: string;
};

/** A piece of a text: plain, or a web address with where it leads. */
export type WebLinkPiece = {
  text: string;
  /** where it leads, or null for plain text */
  href: string | null;
};

// Top-level domains a bare domain may end in: common ones, without those that are also common file
// extensions (md, py, sh, rs, pl, zip, mov) or everyday words (is, to, do, so), which would turn file
// names and run-together words into links.
// biome-ignore format: a long list reads better packed
const TOP_LEVEL_DOMAINS: ReadonlySet<string> = new Set([
  "com", "net", "org", "edu", "gov", "mil", "int", "info", "biz", "name", "pro", "mobi",
  "app", "dev", "io", "ai", "co", "me", "tv", "cc", "fm", "gg", "ly", "gl", "xyz",
  "online", "site", "tech", "store", "shop", "blog", "cloud", "page", "news", "art",
  "design", "email", "club", "top", "wiki", "link", "space", "website", "today", "eu",
  "asia", "us", "uk", "ca", "au", "nz", "ie", "de", "fr", "es", "it", "nl", "be", "ch",
  "at", "se", "no", "dk", "fi", "pt", "cz", "sk", "hu", "ro", "gr", "tr", "il", "za",
  "jp", "cn", "kr", "tw", "hk", "sg", "in", "br", "mx", "ar", "cl", "ru", "ua",
]);

const SCHEME = /^https?:\/\/[^\s<>"]+/i;
const DOMAIN =
  /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+/i;
const PORT = /^:\d{1,5}(?![\p{L}\p{N}])/u;
const PATH = /^[/?#][^\s<>"]*/;
const ALPHANUMERIC = /[\p{L}\p{N}]/u;
// A link never starts right after one of these: a word, an email address, or a longer address.
const NOT_BEFORE = "@.-/+%=&#~\\";
const TRAILING_PUNCTUATION = "?!.,:;*_~'\"";
// What may follow a bare domain's `_` that closes a style (`_example.com_`): not more of a word.
const AFTER_CLOSING_MARKER = /^_+(?![\p{L}\p{N}@])/u;
// The rest of an email address's local part up to its `@` (`ana.co+news@x.com`).
const REST_OF_ADDRESS = /^[\w.%+-]*@/;
// The schemes a link may lead to; any other (`javascript:`, `data:`) is never a link.
const LINK_SCHEMES: ReadonlySet<string> = new Set(["http", "https", "mailto"]);
const ANY_SCHEME = /^([a-z][a-z0-9+.-]*):/i;

/**
 * `href` trimmed and with its scheme in lower case when that scheme is http, https or mailto;
 * null for any other scheme or none, so a `javascript:` address is never linked or opened.
 */
export function safeHref(href: string): string | null {
  const trimmed = href.trim();
  const scheme = ANY_SCHEME.exec(trimmed)?.[1]?.toLowerCase();
  if (scheme === undefined || !LINK_SCHEMES.has(scheme)) return null;
  else return scheme + trimmed.slice(scheme.length);
}

/** Where `link` ends once its trailing punctuation and unmatched closing parentheses are left out. */
function trimmedLength(link: string, least: number): number {
  let length = link.length;
  while (length > least) {
    const last = link[length - 1]!;
    if (TRAILING_PUNCTUATION.includes(last)) {
      length--;
    } else if (last === ")") {
      const kept = link.slice(0, length);
      const opens = kept.split("(").length - 1;
      const closes = kept.split(")").length - 1;
      if (closes > opens) length--;
      else return length;
    } else {
      return length;
    }
  }
  return length;
}

/** The link starting at `index` of `text`, or null. */
function linkAt(text: string, index: number): WebLink | null {
  const rest = text.slice(index);
  const scheme = SCHEME.exec(rest);
  if (scheme !== null) {
    const prefix = rest.indexOf("//") + 2;
    const length = trimmedLength(scheme[0], prefix);
    if (length === prefix) return null;
    const link = scheme[0].slice(0, length);
    return { start: index, end: index + length, href: leadsTo(link) };
  }
  const domain = DOMAIN.exec(rest);
  if (domain === null) return null;
  const host = domain[0];
  const topLevel = host.slice(host.lastIndexOf(".") + 1).toLowerCase();
  if (!TOP_LEVEL_DOMAINS.has(topLevel)) return null;
  let length = host.length;
  const after = rest.slice(length);
  const next = after[0] ?? "";
  if (REST_OF_ADDRESS.test(after) || ALPHANUMERIC.test(next)) return null;
  if (next === "_" && !AFTER_CLOSING_MARKER.test(after)) return null;
  const port = PORT.exec(rest.slice(length));
  if (port !== null) length += port[0].length;
  const path = PATH.exec(rest.slice(length));
  if (path !== null) {
    length = trimmedLength(rest.slice(0, length + path[0].length), length);
  }
  const link = rest.slice(0, length);
  return {
    start: index,
    end: index + length,
    href: leadsTo(`https://${link}`),
  };
}

// Where an http(s) `link` leads: unwrapped, its scheme in lower case; itself when what it wraps isn't a web address.
function leadsTo(link: string): string {
  return safeHref(unwrapLink(link)) ?? safeHref(link) ?? link;
}

/**
 * The web addresses in `text`, in order: `http://` and `https://` URLs, and bare domains ending in
 * a known top-level domain with an optional port and path (`example.com/x`, `www.example.com`).
 * One never starts inside a word, an email address or a `#tag`, and its trailing punctuation and
 * unmatched closing parentheses are left out (`see example.com.` links `example.com`).
 */
export function findWebLinks(text: string): WebLink[] {
  const links: WebLink[] = [];
  let index = 0;
  while (index < text.length) {
    const before = text[index - 1];
    const free =
      before === undefined ||
      (!ALPHANUMERIC.test(before) && !NOT_BEFORE.includes(before));
    const link =
      free && ALPHANUMERIC.test(text[index]!) ? linkAt(text, index) : null;
    if (link === null) {
      index++;
    } else {
      links.push(link);
      index = link.end;
    }
  }
  return links;
}

/** `text` cut into plain pieces and the web addresses between them, in order. */
export function webLinkPieces(text: string): WebLinkPiece[] {
  const pieces: WebLinkPiece[] = [];
  let at = 0;
  for (const link of findWebLinks(text)) {
    if (link.start > at) {
      pieces.push({ text: text.slice(at, link.start), href: null });
    }
    pieces.push({ text: text.slice(link.start, link.end), href: link.href });
    at = link.end;
  }
  if (at < text.length) pieces.push({ text: text.slice(at), href: null });
  return pieces;
}

/**
 * A link's address as typed: trimmed, with `https://` put before one without a scheme
 * (`example.com`); null for any scheme but http, https and mailto (see {@link safeHref}).
 */
export function linkAddress(typed: string): string | null {
  const address = typed.trim();
  if (ANY_SCHEME.test(address)) return safeHref(address);
  else return `https://${address}`;
}

/**
 * The web address that ends the last word of `before` (the text up to a space or line break just
 * typed), trailing punctuation aside, with where it leads as written (`https://` put before a bare
 * domain, not unwrapped); null when that word holds none or it follows an unclosed backtick.
 */
export function finishedWebLink(before: string): WebLink | null {
  const link = findWebLinks(before).at(-1);
  if (link === undefined || /\s/.test(before.slice(link.end))) return null;
  // After an unclosed backtick it is inline code being typed.
  if ((before.slice(0, link.start).split("`").length - 1) % 2 === 1) {
    return null;
  }
  const text = before.slice(link.start, link.end);
  const href = linkAddress(text);
  return href === null ? null : { start: link.start, end: link.end, href };
}
