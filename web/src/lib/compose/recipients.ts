import { addressKey, type Mailbox } from "@inbox/shared/compose";

// Loose on purpose: Gmail is the judge; this only catches typos like a missing @ or domain.
const EMAIL = /^[^\s@<>(),;:"]+@[^\s@<>(),;:"]+\.[^\s@<>(),;:".]+$/;

/** Whether text looks like a deliverable address. */
export function isValidEmail(text: string): boolean {
  return EMAIL.test(text.trim());
}

/** Split typed or pasted text into entries at commas, semicolons and line breaks outside quotes and angle brackets. */
export function splitEntries(text: string): string[] {
  const out: string[] = [];
  let current = "";
  let quoted = false;
  let angled = false;
  for (const character of text) {
    if (character === '"' && !angled) quoted = !quoted;
    else if (character === "<" && !quoted) angled = true;
    else if (character === ">" && !quoted) angled = false;
    if (/[,;\n]/.test(character) && !quoted && !angled) {
      out.push(current);
      current = "";
    } else {
      current += character;
    }
  }
  out.push(current);
  return out.map((entry) => entry.trim()).filter((entry) => entry !== "");
}

/** One entry such as `Ana Lopez <ana@example.com>`, `"Lopez, Ana" <ana@example.com>` or `ana@example.com`; null when it holds no valid address. */
export function parseEntry(entry: string): Mailbox | null {
  const trimmed = entry.trim();
  const angled = /^(.*)<([^<>]+)>\s*$/.exec(trimmed);
  if (angled) {
    const email = angled[2]!.trim();
    if (!isValidEmail(email)) return null;
    const name = angled[1]!
      .trim()
      .replace(/^"(.*)"$/, "$1")
      .replace(/\\(.)/g, "$1")
      .trim();
    return { name, email };
  } else {
    const email = trimmed.replace(/^mailto:/i, "");
    return isValidEmail(email) ? { name: "", email } : null;
  }
}

/** Parse pasted text into mailboxes and the entries that weren't addresses. */
export function parseRecipients(text: string): {
  mailboxes: Mailbox[];
  invalid: string[];
} {
  const mailboxes: Mailbox[] = [];
  const invalid: string[] = [];
  for (const entry of splitEntries(text)) {
    const mailbox = parseEntry(entry);
    if (mailbox) mailboxes.push(mailbox);
    else invalid.push(entry);
  }
  return { mailboxes, invalid };
}

/** `list` with `added` appended, skipping addresses already present (case-insensitive). */
export function addRecipients(
  list: readonly Mailbox[],
  added: readonly Mailbox[],
): Mailbox[] {
  const seen = new Set(list.map((mailbox) => addressKey(mailbox.email)));
  const out = [...list];
  for (const mailbox of added) {
    const key = addressKey(mailbox.email);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(mailbox);
  }
  return out;
}

/** A chip's label: the name, else the address. */
export function chipLabel(mailbox: Mailbox): string {
  return mailbox.name.trim() || mailbox.email;
}
