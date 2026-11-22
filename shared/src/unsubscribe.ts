// List-Unsubscribe (RFC 2369) and List-Unsubscribe-Post (RFC 8058) read into the ways to
// unsubscribe. Pure; web uses it directly, Android ports it to :core (unsubscribe-fixtures.json).

import { isPlainAddress } from "./compose/plain-address.js";

/** A `mailto:` unsubscribe: send this message. */
export type UnsubscribeMail = {
  /** recipient */
  address: string;
  /** subject, empty when none is given */
  subject: string;
  /** body, empty when none is given */
  body: string;
};

/** The ways a message offers to unsubscribe, and which to use. */
export type Unsubscribe = {
  /** https URL to POST `List-Unsubscribe=One-Click` to (RFC 8058), or null */
  oneClickUrl: string | null;
  /** mail to send, or null */
  mailto: UnsubscribeMail | null;
  /** http(s) URL to open in a browser, or null */
  url: string | null;
  /** the one to use: one-click, then mailto, then the URL; null when there is none */
  preferred: "oneClick" | "mailto" | "url" | null;
};

/** Body of the one-click POST, sent as `application/x-www-form-urlencoded`. */
export const ONE_CLICK_BODY = "List-Unsubscribe=One-Click";

/** Percent-decode as UTF-8; a sequence that isn't valid is kept as written. */
function decoded(text: string): string {
  try {
    return decodeURIComponent(text);
  } catch {
    return text.replace(/(%[0-9A-Fa-f]{2})+/g, (run) => {
      try {
        return decodeURIComponent(run);
      } catch {
        return run;
      }
    });
  }
}

/** The URIs a `List-Unsubscribe` header lists, in order: `<…>` entries, else bare ones. */
export function listUnsubscribeUris(header: string): string[] {
  const unfolded = header.replace(/\r?\n[ \t]*/g, " ");
  const bracketed = [...unfolded.matchAll(/<([^>]*)>/g)].map((match) =>
    match[1]!.replace(/\s+/g, ""),
  );
  const uris =
    bracketed.length > 0
      ? bracketed
      : unfolded
          .replace(/\([^)]*\)/g, " ")
          .split(",")
          .map((part) => part.trim());
  return uris.filter((uri) => /^(https?|mailto):\S+$/i.test(uri));
}

/** Addresses in a decoded `mailto:` address list, trimmed, empty ones left out. */
function addressList(text: string): string[] {
  return text
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part !== "");
}

/**
 * A `mailto:` URI (RFC 6068) as a message to send, or null. Only the first address is used (from
 * before `?`, else from `to=`), and only when it is one plain address (`isPlainAddress`), so an
 * encoded `,` or line break can't add recipients or headers.
 */
export function parseMailto(uri: string): UnsubscribeMail | null {
  const match = /^mailto:([^?]*)(?:\?(.*))?$/is.exec(uri.trim());
  if (match === null) return null;
  const addresses = addressList(decoded(match[1]!));
  let subject = "";
  let body = "";
  for (const pair of (match[2] ?? "").split("&")) {
    const equals = pair.indexOf("=");
    if (equals < 0) continue;
    const name = pair.slice(0, equals).toLowerCase();
    const value = decoded(pair.slice(equals + 1));
    if (name === "subject") subject = value;
    else if (name === "body") body = value;
    else if (name === "to") addresses.push(...addressList(value));
  }
  const [address] = addresses;
  return address === undefined || !isPlainAddress(address)
    ? null
    : { address, subject, body };
}

/** Whether `List-Unsubscribe-Post` asks for one-click unsubscribe. */
export function isOneClick(post: string | null): boolean {
  return (
    post !== null &&
    post.replace(/\s+/g, "").toLowerCase() === ONE_CLICK_BODY.toLowerCase()
  );
}

/**
 * Read `List-Unsubscribe` and `List-Unsubscribe-Post` (either may be null).
 *
 * One-click needs the POST header to be exactly `List-Unsubscribe=One-Click` (case and spaces
 * aside) and an https URI; it is the first https URI listed. `url` is the first http(s) URI and
 * `mailto` the first `mailto:` with an address. `preferred` picks one-click, then mailto, then the
 * URL (design.md, "Unsubscribe").
 */
export function parseUnsubscribe(
  listUnsubscribe: string | null,
  listUnsubscribePost: string | null,
): Unsubscribe {
  const uris =
    listUnsubscribe === null ? [] : listUnsubscribeUris(listUnsubscribe);
  const url = uris.find((uri) => /^https?:/i.test(uri)) ?? null;
  const https = uris.find((uri) => /^https:/i.test(uri)) ?? null;
  const oneClickUrl = isOneClick(listUnsubscribePost) ? https : null;
  let mailto: UnsubscribeMail | null = null;
  for (const uri of uris) {
    if (!/^mailto:/i.test(uri)) continue;
    mailto = parseMailto(uri);
    if (mailto !== null) break;
  }
  const preferred =
    oneClickUrl !== null
      ? "oneClick"
      : mailto !== null
        ? "mailto"
        : url !== null
          ? "url"
          : null;
  return { oneClickUrl, mailto, url, preferred };
}
