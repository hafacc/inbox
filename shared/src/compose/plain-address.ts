// Imported by relative path from functions/, so it imports nothing.

/**
 * Whether text is one plain address (`local@domain`), safe to write into a header or a `mailto:`:
 * exactly one `@` with something on each side, and no whitespace, control characters, `,`, `;`,
 * `<` or `>`.
 */
export function isPlainAddress(email: string): boolean {
  const at = email.indexOf("@");
  return (
    at > 0 &&
    at === email.lastIndexOf("@") &&
    at < email.length - 1 &&
    // biome-ignore lint/suspicious/noControlCharactersInRegex: control characters are what it rejects
    !/[\s,;<>\x00-\x1f\x7f]/.test(email)
  );
}

/** An address in the form used to compare two: trimmed and lower-cased. */
export function addressKey(email: string): string {
  return email.trim().toLowerCase();
}
