// The search field: Gmail query text split into chips (finished operators) and free text, with
// Tab completion. Pure; web uses it directly, Android ports it to :core (search-fixtures.json).

import type { Mailbox } from "./compose/address";
import { dateTimeFormat, zeroPad } from "./format";
import { parseTags } from "./todos/tags";

/** Operators the field turns into chips, in the order completion lists them. */
export const OPERATORS = [
  "from",
  "to",
  "cc",
  "bcc",
  "subject",
  "in",
  "is",
  "has",
  "label",
  "filename",
  "after",
  "before",
  "older_than",
  "newer_than",
  "larger",
  "smaller",
  "size",
  "list",
  "deliveredto",
  "category",
] as const;

/** The few operators offered before anything is typed; typing completes every operator. */
export const COMMON_OPERATORS = ["from", "to", "subject", "has", "is"] as const;

/** A search operator name, lower-case, without the colon. */
export type Operator = (typeof OPERATORS)[number];

/** What each operator matches, shown beside it in completion. */
export const OPERATOR_DETAILS: Record<Operator, string> = {
  from: "Sender",
  to: "Recipient",
  cc: "Copied",
  bcc: "Blind-copied",
  subject: "Words in the subject",
  in: "Mailbox",
  is: "State: unread, starred, snoozed, done…",
  has: "Attachment, Drive file…",
  label: "Label",
  filename: "Attachment name or type",
  after: "Sent after a date",
  before: "Sent before a date",
  older_than: "Older than (1d, 2m, 1y)",
  newer_than: "Newer than (1d, 2m, 1y)",
  larger: "Larger than a size",
  smaller: "Smaller than a size",
  size: "Larger than a size in bytes",
  list: "Mailing list",
  deliveredto: "Delivered to an address",
  category: "Inbox category",
};

/** One example query per operator, for the search help. */
export const OPERATOR_EXAMPLES: Record<Operator, string> = {
  from: "from:maya@example.com",
  to: "to:me",
  cc: "cc:team@example.com",
  bcc: "bcc:alex",
  subject: "subject:invoice",
  in: "in:snoozed",
  is: "is:unread",
  has: "has:attachment",
  label: "label:receipts",
  filename: "filename:pdf",
  after: "after:2026/01/31",
  before: "before:2026/06/01",
  older_than: "older_than:1y",
  newer_than: "newer_than:2d",
  larger: "larger:10M",
  smaller: "smaller:1M",
  size: "size:500000",
  list: "list:news@example.com",
  deliveredto: "deliveredto:user@hafa.cc",
  category: "category:promotions",
};

/** The `operator` of a chip made from a `#tag`, which searches as the text it is. */
export const TAG_CHIP = "#";

/** A finished operator shown as a chip, or a finished `#tag` ({@link TAG_CHIP}) in the search field. */
export type Chip = {
  /** operator, lower-case; {@link TAG_CHIP} for a `#tag` */
  operator: Operator | typeof TAG_CHIP;
  /** value without quotes; a tag's name without `#` */
  value: string;
  /** written with a leading `-` */
  negated: boolean;
};

/** What the token under the cursor is: an operator name being typed, or the value of one. */
export type CompletionContext =
  | {
      kind: "operator";
      /** the name typed so far, without `-` */
      prefix: string;
      /** written with a leading `-` */
      negated: boolean;
      /** start of the free text a completion replaces */
      start: number;
      /** end of the free text a completion replaces */
      end: number;
    }
  | {
      kind: "value";
      /** the operator whose value this is */
      operator: Operator;
      /** the value typed so far, without an opening quote */
      prefix: string;
      /** the whole value, past the cursor too, without an opening quote */
      value: string;
      /** written with a leading `-` */
      negated: boolean;
      /** start of the free text a completion replaces (the whole token) */
      start: number;
      /** end of the free text a completion replaces */
      end: number;
    };

/** Search field text split into chips and the text left in the input. */
export type ParsedQuery = {
  /** finished operators, in order */
  chips: Chip[];
  /** the text with the chips taken out */
  free: string;
  /** the cursor's place in `free` */
  cursor: number;
  /** what the token under the cursor completes to; null without a cursor or inside a quote or group */
  context: CompletionContext | null;
};

/** A completion offered for a context. */
export type Candidate = {
  /** what the list shows */
  label: string;
  /** what goes into the query: `from:` for an operator, the bare value for a value */
  value: string;
  /** second line, or null */
  detail: string | null;
};

/** What completion needs besides the context. */
export type CompletionOptions = {
  /** epoch ms, for date shortcuts */
  now: number;
  /** IANA zone the date shortcuts are counted in */
  timeZone: string;
  /** label names, for `label:` */
  labels?: readonly string[];
  /** contacts and recent senders, best first, for `from:`, `to:`, `cc:`, `bcc:` and `deliveredto:` */
  contacts?: readonly Mailbox[];
};

/** A run of query text between whitespace; quotes and `()`/`{}` groups may hold whitespace. */
export type Token = {
  /** index of its first character */
  start: number;
  /** index just past its last character */
  end: number;
  /** its text */
  text: string;
  /** false when a quote or group is still open at the end of the text */
  complete: boolean;
};

/** Most label or contact candidates returned. */
export const MAX_HOOK_CANDIDATES = 8;

/** Chips that are ours, not Gmail's: answered from Firestore and the outbox, not sent to Gmail. */
export const LOCAL_CHIPS: readonly Pick<Chip, "operator" | "value">[] = [
  { operator: "is", value: "snoozed" },
  { operator: "is", value: "todo" },
  { operator: "in", value: "outbox" },
];

/** Values completed for `in:`. */
export const IN_VALUES = [
  "inbox",
  "sent",
  "drafts",
  "outbox",
  "spam",
  "trash",
  "anywhere",
] as const;

/** Values completed for `is:`. */
export const IS_VALUES = [
  "unread",
  "read",
  "starred",
  "pinned",
  "snoozed",
  "done",
  "todo",
  "important",
  "muted",
] as const;

/** Our own search terms (Gmail has none of them) and what each lists, for the search help. */
export const OWN_TERMS: readonly [string, string][] = [
  ["is:snoozed", "Snoozed mail and todos"],
  ["is:pinned", "Pinned mail and todos, as is:starred"],
  ["is:done", "Mail out of the inbox and done todos"],
  ["is:todo", "Only todos"],
  ["in:outbox", "Mail waiting to be sent"],
];

/** Values completed for `has:`. */
export const HAS_VALUES = [
  "attachment",
  "drive",
  "document",
  "spreadsheet",
  "presentation",
  "youtube",
  "userlabels",
  "nouserlabels",
] as const;

/** Values completed for `category:`. */
export const CATEGORY_VALUES = [
  "primary",
  "social",
  "promotions",
  "updates",
  "forums",
  "reservations",
  "purchases",
] as const;

/** Values completed for `larger:`, `smaller:` and `size:`. */
export const SIZE_VALUES = ["1M", "5M", "10M", "25M"] as const;

/** Values completed for `older_than:` and `newer_than:`, with their labels. */
export const AGE_VALUES: readonly [string, string][] = [
  ["1d", "1 day"],
  ["7d", "7 days"],
  ["1m", "1 month"],
  ["1y", "1 year"],
];

const OPERATOR_SET: ReadonlySet<string> = new Set(OPERATORS);

const CONTACT_OPERATORS: ReadonlySet<Operator> = new Set([
  "from",
  "to",
  "cc",
  "bcc",
  "deliveredto",
]);

const CLOSERS: Record<string, string> = { "(": ")", "{": "}" };

function isSpace(character: string): boolean {
  return /\s/.test(character);
}

/** Split query text into tokens at whitespace outside quotes and groups. */
export function tokenize(text: string): Token[] {
  const tokens: Token[] = [];
  let index = 0;
  while (index < text.length) {
    if (isSpace(text[index]!)) {
      index++;
      continue;
    }
    const start = index;
    const open: string[] = [];
    let complete = true;
    while (index < text.length) {
      const character = text[index]!;
      if (open.length === 0 && isSpace(character)) break;
      if (character === '"') {
        const close = text.indexOf('"', index + 1);
        if (close < 0) {
          index = text.length;
          complete = false;
          break;
        }
        index = close + 1;
        continue;
      }
      const closer = CLOSERS[character];
      if (closer !== undefined) open.push(closer);
      else if (character === open[open.length - 1]) open.pop();
      index++;
    }
    if (open.length > 0) complete = false;
    tokens.push({
      start,
      end: index,
      text: text.slice(start, index),
      complete,
    });
  }
  return tokens;
}

/**
 * The chip a token makes when finished, or null for free text. With `tags`, a token that is one
 * whole `#tag` (as todos have them, optionally after `-`) makes a {@link TAG_CHIP} chip.
 */
export function tokenChip(token: Token, tags = false): Chip | null {
  if (!token.complete) return null;
  if (tags) {
    const minus = token.text.startsWith("-") ? 1 : 0;
    const body = token.text.slice(minus);
    const [span, ...others] = parseTags(body);
    if (
      span !== undefined &&
      others.length === 0 &&
      span.start === 0 &&
      span.end === body.length
    ) {
      return { operator: TAG_CHIP, value: body.slice(1), negated: minus === 1 };
    }
  }
  const match = /^(-?)([A-Za-z_]+):(.+)$/s.exec(token.text);
  if (match === null) return null;
  const [, minus, name, raw] = match;
  const operator = name!.toLowerCase();
  if (!OPERATOR_SET.has(operator)) return null;
  let value: string;
  const quoted = /^"([^"]*)"$/.exec(raw!);
  if (quoted !== null) value = quoted[1]!.trim();
  else if (/^[^"(){}]+$/.test(raw!)) value = raw!;
  else return null;
  if (value === "") return null;
  return { operator: operator as Operator, value, negated: minus === "-" };
}

/**
 * A chip as query text: `-from:"Maya Chen"`; quoted when the value has spaces, quotes or brackets.
 * A tag chip is its `#tag`.
 */
export function formatChip(chip: Chip): string {
  if (chip.operator === TAG_CHIP) {
    return `${chip.negated ? "-" : ""}#${chip.value}`;
  }
  const value = /[\s"(){}]/.test(chip.value)
    ? `"${chip.value.replace(/"/g, "")}"`
    : chip.value;
  return `${chip.negated ? "-" : ""}${chip.operator}:${value}`;
}

/** Whether a chip is one of ours (`is:snoozed`, `is:todo`, `in:outbox`), to be answered locally. */
export function isLocalChip(chip: Chip): boolean {
  return LOCAL_CHIPS.some(
    (local) =>
      local.operator === chip.operator &&
      local.value === chip.value.toLowerCase(),
  );
}

/** The whole query: chips, then the free text. */
export function queryText(chips: readonly Chip[], free: string): string {
  return [...chips.map(formatChip), free.trim()]
    .filter((part) => part !== "")
    .join(" ");
}

/**
 * A chip as Gmail is asked for it: our `is:pinned` is Gmail's `is:starred`, and `is:done` (out of
 * the inbox) is `-in:inbox`, so `-is:done` is `in:inbox`. Any other chip is as it is.
 */
export function gmailChip(chip: Chip): Chip {
  const value = chip.value.toLowerCase();
  if (chip.operator === "is" && value === "pinned") {
    return { ...chip, value: "starred" };
  } else if (chip.operator === "is" && value === "done") {
    return { operator: "in", value: "inbox", negated: !chip.negated };
  } else {
    return chip;
  }
}

/** Gmail's `q` for a query's chips and free text: our local chips left out, the rest as {@link gmailChip} has them. */
export function gmailQueryText(chips: readonly Chip[], free: string): string {
  return queryText(
    chips.filter((chip) => !isLocalChip(chip)).map(gmailChip),
    free,
  );
}

/**
 * Split search field text into chips and free text, aware of the cursor.
 *
 * An operator token becomes a chip once it is finished: it has a value, any quote is closed, and
 * the cursor is not inside or touching it (so a chip forms when the user types the space after
 * it, or moves away). Groups (`(…)`, `{…}`), `OR` and unknown operators stay text, and so does an
 * operator right before or after a top-level `OR` (a chip would move it out of the `OR`). Each chip is
 * taken out with the whitespace after it (or before it, at the end). With `cursor` null every
 * finished operator becomes a chip and there is no context. With `tags` (the search field), a
 * finished `#tag` becomes a chip too ({@link tokenChip}); a query read for searching leaves it in
 * the free text.
 *
 * ```ts
 * parseQuery("from:maya hello", 15)
 * // { chips: [{ operator: "from", value: "maya", negated: false }], free: "hello", cursor: 5,
 * //   context: { kind: "operator", prefix: "hello", negated: false, start: 0, end: 5 } }
 * ```
 */
export function parseQuery(
  text: string,
  cursor: number | null,
  tags = false,
): ParsedQuery {
  const tokens = tokenize(text);
  const chips: Chip[] = [];
  const removed: [number, number][] = [];
  tokens.forEach((token, index) => {
    const chip = tokenChip(token, tags);
    const touching =
      cursor !== null && cursor >= token.start && cursor <= token.end;
    const besideOr =
      tokens[index - 1]?.text === "OR" || tokens[index + 1]?.text === "OR";
    if (chip === null || touching || besideOr) return;
    chips.push(chip);
    const next = tokens[index + 1];
    let start = token.start;
    let end = token.end;
    if (next !== undefined) end = next.start;
    else if (end < text.length) end = text.length;
    else
      start = Math.max(tokens[index - 1]?.end ?? 0, removed.at(-1)?.[1] ?? 0);
    removed.push([start, end]);
  });
  let free = "";
  let at = 0;
  let freeCursor = cursor ?? 0;
  for (const [start, end] of removed) {
    free += text.slice(at, start);
    at = end;
    if (cursor === null) continue;
    if (cursor >= end) freeCursor -= end - start;
    else if (cursor > start) freeCursor -= cursor - start;
  }
  free += text.slice(at);
  return {
    chips,
    free,
    cursor: cursor === null ? free.length : freeCursor,
    context: cursor === null ? null : completionContext(free, freeCursor),
  };
}

/** What the token under the cursor completes to (see `parseQuery`). */
export function completionContext(
  text: string,
  cursor: number,
): CompletionContext | null {
  const token = tokenize(text).find(
    (candidate) => cursor >= candidate.start && cursor <= candidate.end,
  );
  if (token === undefined) {
    return {
      kind: "operator",
      prefix: "",
      negated: false,
      start: cursor,
      end: cursor,
    };
  }
  const typed = token.text.slice(0, cursor - token.start);
  const negated = typed.startsWith("-");
  const body = negated ? typed.slice(1) : typed;
  const colon = body.indexOf(":");
  if (colon < 0) {
    if (!/^[A-Za-z_]*$/.test(body)) return null;
    const tokenColon = token.text.indexOf(":");
    return {
      kind: "operator",
      prefix: body,
      negated,
      start: token.start,
      end: tokenColon < 0 ? token.end : token.start + tokenColon + 1,
    };
  }
  const operator = body.slice(0, colon).toLowerCase();
  if (!OPERATOR_SET.has(operator)) return null;
  let prefix = body.slice(colon + 1);
  if (prefix.startsWith('"')) prefix = prefix.slice(1);
  if (/["(){}]/.test(prefix)) return null;
  const value = token.text.slice(token.text.indexOf(":") + 1);
  return {
    kind: "value",
    operator: operator as Operator,
    prefix,
    value: value.startsWith('"') ? value.slice(1) : value,
    negated,
    start: token.start,
    end: token.end,
  };
}

/**
 * Whether the token under the cursor is an operator with nothing after its colon (`in:`, `-from:`,
 * `subject:"`), so the query is unfinished: Enter completes a suggestion there instead of searching.
 */
export function awaitsValue(
  context: CompletionContext | null,
): context is Extract<CompletionContext, { kind: "value" }> {
  return context?.kind === "value" && context.value === "";
}

/** The free text with the operator `context` stands on taken out, for searching an unfinished query. */
export function withoutOperator(
  free: string,
  context: CompletionContext,
): string {
  const before = free.slice(0, context.start).trimEnd();
  const after = free.slice(context.end).trimStart();
  return before === "" || after === "" ? before + after : `${before} ${after}`;
}

function startsWithFolded(text: string, prefix: string): boolean {
  return text.toLowerCase().startsWith(prefix.toLowerCase());
}

function fixed(values: readonly string[], prefix: string): Candidate[] {
  return values
    .filter((value) => startsWithFolded(value, prefix))
    .map((value) => ({ label: value, value, detail: null }));
}

/** A label name as Gmail's `label:` takes it: lower-case, spaces and `/` as `-`. */
export function labelQueryValue(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[\s/]+/g, "-");
}

function labelCandidates(
  labels: readonly string[],
  prefix: string,
): Candidate[] {
  const folded = prefix.toLowerCase();
  return labels
    .filter(
      (name) =>
        startsWithFolded(labelQueryValue(name), folded) ||
        name
          .toLowerCase()
          .split(/[\s/]+/)
          .some((part) => part.startsWith(folded)),
    )
    .slice(0, MAX_HOOK_CANDIDATES)
    .map((name) => ({
      label: name,
      value: labelQueryValue(name),
      detail: null,
    }));
}

function contactCandidates(
  contacts: readonly Mailbox[],
  prefix: string,
): Candidate[] {
  const folded = prefix.toLowerCase().trim();
  return contacts
    .filter(
      (contact) =>
        startsWithFolded(contact.email, folded) ||
        startsWithFolded(contact.name, folded) ||
        contact.name
          .toLowerCase()
          .split(/\s+/)
          .some((part) => part.startsWith(folded)),
    )
    .slice(0, MAX_HOOK_CANDIDATES)
    .map((contact) => ({
      label: contact.name || contact.email,
      value: contact.email,
      detail: contact.name ? contact.email : null,
    }));
}

/** Calendar date `days` before today in `timeZone`, as Gmail's `yyyy/mm/dd`. */
export function dateDaysAgo(
  now: number,
  timeZone: string,
  days: number,
): string {
  const parts = dateTimeFormat({
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(now));
  const part = (type: string) =>
    Number(parts.find((candidate) => candidate.type === type)!.value);
  const date = new Date(
    Date.UTC(part("year"), part("month") - 1, part("day") - days),
  );
  return `${date.getUTCFullYear()}/${zeroPad(date.getUTCMonth() + 1)}/${zeroPad(date.getUTCDate())}`;
}

/** Date shortcuts offered for `after:` and `before:`: label and days back. */
export const DATE_SHORTCUTS: readonly [string, number][] = [
  ["today", 0],
  ["yesterday", 1],
  ["last week", 7],
];

/**
 * Completions for a context: operators by name (only `COMMON_OPERATORS` before a letter is typed), fixed values for `in:`, `is:`, `has:`,
 * `category:`, sizes and ages, date shortcuts (today, yesterday, last week as concrete
 * `yyyy/mm/dd` dates in `timeZone`) for `after:`/`before:`, and the caller's labels and contacts
 * (at most `MAX_HOOK_CANDIDATES`, in the caller's order). Matching is by case-insensitive prefix.
 */
export function completions(
  context: CompletionContext,
  options: CompletionOptions,
): Candidate[] {
  const { prefix } = context;
  if (context.kind === "operator") {
    const offered: readonly Operator[] =
      prefix === "" ? COMMON_OPERATORS : OPERATORS;
    return offered
      .filter((operator) => startsWithFolded(operator, prefix))
      .map((operator) => ({
        label: `${operator}:`,
        value: `${operator}:`,
        detail: OPERATOR_DETAILS[operator],
      }));
  }
  switch (context.operator) {
    case "in":
      return fixed(IN_VALUES, prefix);
    case "is":
      return fixed(IS_VALUES, prefix);
    case "has":
      return fixed(HAS_VALUES, prefix);
    case "category":
      return fixed(CATEGORY_VALUES, prefix);
    case "larger":
    case "smaller":
    case "size":
      return fixed(SIZE_VALUES, prefix);
    case "older_than":
    case "newer_than":
      return AGE_VALUES.filter(
        ([value, label]) =>
          startsWithFolded(value, prefix) || startsWithFolded(label, prefix),
      ).map(([value, label]) => ({ label, value, detail: null }));
    case "after":
    case "before":
      return DATE_SHORTCUTS.map(([label, days]) => {
        const value = dateDaysAgo(options.now, options.timeZone, days);
        return { label, value, detail: value };
      }).filter(
        (candidate) =>
          startsWithFolded(candidate.label, prefix) ||
          startsWithFolded(candidate.value, prefix),
      );
    case "label":
      return labelCandidates(options.labels ?? [], prefix);
    default:
      return CONTACT_OPERATORS.has(context.operator)
        ? contactCandidates(options.contacts ?? [], prefix)
        : [];
  }
}

/** Free text and cursor after a change. */
export type Edit = { text: string; cursor: number };

/**
 * Put a candidate into the free text: an operator replaces the name being typed (cursor after
 * the colon); a value replaces the token with the finished chip text and a space, cursor after
 * the space, so the next `parseQuery` turns it into a chip.
 */
export function applyCompletion(
  text: string,
  context: CompletionContext,
  candidate: Candidate,
): Edit {
  const before = text.slice(0, context.start);
  const after = text.slice(context.end);
  const minus = context.negated ? "-" : "";
  if (context.kind === "operator") {
    const insert = minus + candidate.value;
    return {
      text: before + insert + after,
      cursor: before.length + insert.length,
    };
  }
  const chip = formatChip({
    operator: context.operator,
    value: candidate.value,
    negated: context.negated,
  });
  const space = /^\s/.test(after) ? "" : " ";
  return {
    text: before + chip + space + after,
    cursor: before.length + chip.length + 1,
  };
}

/**
 * Backspace on a chip: take it out of the chips and put its text at the start of the free text,
 * cursor at its end (so it stays text until the cursor leaves it).
 */
export function unchip(
  chips: readonly Chip[],
  index: number,
  free: string,
): { chips: Chip[]; free: string; cursor: number } {
  const chip = chips[index];
  if (chip === undefined) {
    return { chips: [...chips], free, cursor: 0 };
  }
  const text = formatChip(chip);
  const rest = free.trimStart();
  return {
    chips: chips.filter((_, other) => other !== index),
    free: rest === "" ? text : `${text} ${rest}`,
    cursor: text.length,
  };
}
