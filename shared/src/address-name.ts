// The @hafa.cc name rules and the wording for each refusal, run by the server, web and Android
// (android/core address/AddressName.kt). Fixtures: shared/testdata/address-name-fixtures.json.
// Imported by relative path from functions/, so it imports nothing but its JSON.

import reserved from "../address/reserved-names.json" with { type: "json" };

/** Domain users claim addresses on. */
export const ADDRESS_DOMAIN = "hafa.cc";

/** Shortest name. */
const NAME_MIN_LENGTH = 2;

/** Longest name. */
const NAME_MAX_LENGTH = 32;

/**
 * Names nobody can claim, from `shared/address/reserved-names.json`: role addresses mail systems
 * expect (RFC 2142 and common practice), the subdomains of hafa.cc, and the apps on it, so an
 * address never looks like one of them. A name is also refused when it matches one of these with
 * its dots and hyphens removed.
 */
export const RESERVED_NAMES: ReadonlySet<string> = new Set([
  ...reserved.roles,
  ...reserved.subdomains,
  ...reserved.apps,
]);

/** Why a name can't be claimed. */
export type NameProblem =
  | "length"
  | "characters"
  | "edges"
  | "punctuation"
  | "reserved";

/** Why a claim was refused: the server's `details.reason`. */
export type ClaimReason =
  | NameProblem
  | "taken"
  | "has-address"
  | "had-address"
  | "no-gmail"
  | "releasing"
  | "not-configured"
  | "no-address";

/** A checked name, or why it was refused. */
export type NameCheck =
  | {
      /** the name passed */
      ok: true;
      /** the name, normalized */
      name: string;
    }
  | {
      /** the name was refused */
      ok: false;
      /** why it was refused */
      problem: NameProblem;
    };

/**
 * Check a requested name and return it normalized.
 *
 * The input is trimmed and lowercased, and a trailing `@hafa.cc` is dropped. A name is 2–32 of
 * `a-z`, `0-9`, `.` and `-`, starts and ends with a letter or digit, has no two dots or hyphens in a
 * row, and is not in {@link RESERVED_NAMES}, with or without its dots and hyphens.
 */
export function checkName(input: string): NameCheck {
  let name = input.trim().toLowerCase();
  const suffix = `@${ADDRESS_DOMAIN}`;
  if (name.endsWith(suffix)) name = name.slice(0, -suffix.length);
  if (name.length < NAME_MIN_LENGTH || name.length > NAME_MAX_LENGTH) {
    return { ok: false, problem: "length" };
  }
  if (!/^[a-z0-9.-]+$/.test(name)) return { ok: false, problem: "characters" };
  if (!/^[a-z0-9]/.test(name) || !/[a-z0-9]$/.test(name)) {
    return { ok: false, problem: "edges" };
  }
  if (/[.-]{2}/.test(name)) return { ok: false, problem: "punctuation" };
  if (
    RESERVED_NAMES.has(name) ||
    RESERVED_NAMES.has(name.replace(/[.-]/g, ""))
  ) {
    return { ok: false, problem: "reserved" };
  }
  return { ok: true, name };
}

/** The full address for a name. */
export function addressOf(name: string): string {
  return `${name}@${ADDRESS_DOMAIN}`;
}

/** The local part of an address: everything before its last `@`. */
export function nameOf(address: string): string {
  const at = address.lastIndexOf("@");
  return at < 0 ? address : address.slice(0, at);
}

/** What to show for each refusal. */
export const REASON_TEXT: Readonly<Record<ClaimReason, string>> = {
  length: "Use 2 to 32 characters.",
  characters: "Use letters, digits, dots and hyphens.",
  edges: "Start and end with a letter or digit.",
  punctuation: "Don't put dots or hyphens next to each other.",
  reserved: "That name is reserved.",
  taken: "That name is taken.",
  "has-address": "You already have an address.",
  "had-address":
    "Your account already had an address — you can only take that one back.",
  "no-gmail": "Sign in again to set up an address.",
  releasing: "Try again in a moment.",
  "not-configured": "@hafa.cc addresses aren't available yet.",
  "no-address": "You don't have an address anymore.",
};

/** Whether a string is one of the server's refusal reasons. */
export function isClaimReason(reason: unknown): reason is ClaimReason {
  return typeof reason === "string" && Object.hasOwn(REASON_TEXT, reason);
}

/**
 * What to show for a refusal's `details.reason`, naming the old address (`details.address`) for
 * `had-address`; null when the reason isn't one the server sends.
 */
export function reasonText(
  reason: unknown,
  address: string | null,
): string | null {
  if (!isClaimReason(reason)) return null;
  if (reason === "had-address" && address !== null) {
    return `Your account already had ${address} — you can only take that one back.`;
  } else {
    return REASON_TEXT[reason];
  }
}
