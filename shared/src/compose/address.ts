import { encodedWords, isPrintableAscii } from "./encoding";
import { isPlainAddress } from "./plain-address";

export { addressKey, isPlainAddress } from "./plain-address";

/** A person on a message: display name (may be empty) and address. */
export type Mailbox = { name: string; email: string };

// RFC 5322 "specials" plus characters that would end or confuse a bare phrase.
const PHRASE_SPECIALS = /[()<>[\]:;@\\,."]/;

/** An address that can't be written into a header as it is. */
export class InvalidAddressError extends Error {
  /** the address as given */
  readonly address: string;

  /** Create an error for an address. */
  constructor(address: string) {
    super(`Not a valid email address: ${JSON.stringify(address)}`);
    this.name = "InvalidAddressError";
    this.address = address;
  }
}

/**
 * One mailbox as it goes in an address header.
 *
 * - no name: `ana@example.com`
 * - printable ASCII name of plain words: `Ana Lopez <ana@example.com>`
 * - ASCII name with specials (`.`, `,`, `"`, `@`…): a quoted string, `"Lopez, Ana" <ana@example.com>`,
 *   with `\` and `"` escaped by a backslash
 * - any other name: RFC 2047 encoded words, `=?UTF-8?B?…?= <ana@example.com>`
 *
 * The address is trimmed, then must be `isPlainAddress`, else this throws `InvalidAddressError`.
 */
export function formatMailbox(mailbox: Mailbox): string {
  const email = mailbox.email.trim();
  if (!isPlainAddress(email)) throw new InvalidAddressError(mailbox.email);
  const name = mailbox.name.trim();
  if (name === "") return email;
  if (!isPrintableAscii(name) || name.includes("=?")) {
    return `${encodedWords(name).join(" ")} <${email}>`;
  }
  if (PHRASE_SPECIALS.test(name) || /\s{2,}/.test(name)) {
    return `${quotedString(name)} <${email}>`;
  }
  return `${name} <${email}>`;
}

/** Text as an RFC 5322 quoted string: in double quotes, with `\` and `"` escaped by a backslash. */
export function quotedString(text: string): string {
  return `"${text.replace(/[\\"]/g, (character) => `\\${character}`)}"`;
}

/** Mailboxes as an address header value, joined by `, `. */
export function formatAddressList(mailboxes: readonly Mailbox[]): string {
  return mailboxes.map(formatMailbox).join(", ");
}
