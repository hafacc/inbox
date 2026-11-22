// Gmail filters ↔ the filter editor's rows and actions. Pure; web uses it directly, Android ports
// it to :core (filter-fixtures.json).

import { tokenize } from "./search";

/** `criteria` of a Gmail API `Filter` resource. */
export type GmailFilterCriteria = {
  /** sender text */
  from?: string;
  /** recipient text */
  to?: string;
  /** subject text */
  subject?: string;
  /** "has the words": Gmail search text */
  query?: string;
  /** "doesn't have": Gmail search text */
  negatedQuery?: string;
  /** only messages with an attachment */
  hasAttachment?: boolean;
  /** leave out chats */
  excludeChats?: boolean;
  /** bytes */
  size?: number;
  /** how `size` compares */
  sizeComparison?: "larger" | "smaller" | "unspecified";
};

/** `action` of a Gmail API `Filter` resource. */
export type GmailFilterAction = {
  /** label ids added */
  addLabelIds?: string[];
  /** label ids removed */
  removeLabelIds?: string[];
  /** a verified forwarding address */
  forward?: string;
};

/** A Gmail API `Filter` resource. */
export type GmailFilter = {
  /** Gmail's id, absent before it is created */
  id?: string;
  /** what it matches */
  criteria?: GmailFilterCriteria;
  /** what it does */
  action?: GmailFilterAction;
};

/** Fields a row can match, in the order rows are listed. */
export const FILTER_FIELDS = [
  "from",
  "to",
  "subject",
  "hasWords",
  "doesNotHave",
  "size",
  "hasAttachment",
] as const;

/** What a row matches. */
export type FilterField = (typeof FILTER_FIELDS)[number];

/** How a row matches: `contains` for text fields, `larger`/`smaller` for size, `is` for has attachment. */
export type RowOperator = "contains" | "larger" | "smaller" | "is";

/** One condition in the editor. */
export type FilterRow = {
  /** what it matches */
  field: FilterField;
  /** how it matches */
  operator: RowOperator;
  /** text; for size a number with an optional unit (`5MB`, `500KB`, `1200` bytes); empty for has attachment */
  value: string;
};

/** Rows that must all match, any match, or none match. */
export type ConditionGroup = {
  /** how the rows combine */
  match: "all" | "any" | "not";
  /** its conditions, in order */
  rows: FilterRow[];
};

/** The editor's conditions: groups (all of which must hold), or raw search text when they don't parse. */
export type FilterConditions =
  | { kind: "rows"; groups: ConditionGroup[] }
  | { kind: "raw"; query: string };

/** What a filter does. One label at most; ids Gmail uses but the editor doesn't show are kept. */
export type FilterActions = {
  /** user label id (`Label_…`) to add, or null */
  labelId: string | null;
  /** skip the inbox (remove `INBOX`) */
  archive: boolean;
  /** remove `UNREAD` */
  markRead: boolean;
  /** add `STARRED` */
  star: boolean;
  /** never send to spam (remove `SPAM`) */
  neverSpam: boolean;
  /** always (add `IMPORTANT`), never (remove it), or leave to Gmail */
  important: "always" | "never" | null;
  /** delete (add `TRASH`) */
  trash: boolean;
  /** forwarding address, or null */
  forward: string | null;
  /** other label ids added (e.g. `CATEGORY_SOCIAL`, extra user labels) */
  otherAddLabelIds: string[];
  /** other label ids removed */
  otherRemoveLabelIds: string[];
};

/** The whole editor. */
export type FilterEditor = {
  /** what it matches */
  conditions: FilterConditions;
  /** what it does */
  actions: FilterActions;
};

/** Actions with nothing chosen. */
export const NO_ACTIONS: FilterActions = {
  labelId: null,
  archive: false,
  markRead: false,
  star: false,
  neverSpam: false,
  important: null,
  trash: false,
  forward: null,
  otherAddLabelIds: [],
  otherRemoveLabelIds: [],
};

const KB = 1024;
const MB = 1024 * 1024;

/** A size row's value in bytes (`5MB`, `5 mb`, `500KB`, `1200`), or null when it isn't one. */
export function parseSize(value: string): number | null {
  const match = /^\s*(\d+(?:\.\d+)?)\s*(b|bytes?|k|kb|m|mb)?\s*$/i.exec(value);
  if (match === null) return null;
  const unit = (match[2] ?? "").toLowerCase();
  const scale = unit.startsWith("m") ? MB : unit.startsWith("k") ? KB : 1;
  return Math.round(Number(match[1]) * scale);
}

/** Bytes as a size row's value: `5MB`, `500KB`, else bytes. */
export function formatSize(bytes: number): string {
  if (bytes > 0 && bytes % MB === 0) return `${bytes / MB}MB`;
  if (bytes > 0 && bytes % KB === 0) return `${bytes / KB}KB`;
  return String(bytes);
}

/** Bytes as Gmail search writes them: `5M`, else bytes. */
function searchSize(bytes: number): string {
  return bytes > 0 && bytes % MB === 0 ? `${bytes / MB}M` : String(bytes);
}

/** What's wrong with a row, or null when it can be saved. */
export function rowError(row: FilterRow): string | null {
  if (row.field === "hasAttachment") return null;
  if (row.value.trim() === "") return "Enter a value";
  if (row.field === "size" && parseSize(row.value) === null) {
    return "Enter a size like 5MB";
  }
  return null;
}

function hasSpace(text: string): boolean {
  return /\s/.test(text.trim());
}

/** A value as one search token: in parentheses when it has spaces (unless already quoted). */
function grouped(value: string): string {
  const trimmed = value.trim();
  return hasSpace(trimmed) && !/^"[^"]*"$/.test(trimmed)
    ? `(${trimmed})`
    : trimmed;
}

/** "Doesn't have" text as search: `-word`, or `-{a b}` (none of the words) as Gmail writes it. */
function without(value: string): string {
  const trimmed = value.trim();
  return tokenize(trimmed).length > 1 ? `-{${trimmed}}` : `-${trimmed}`;
}

function rowTerm(row: FilterRow): string {
  switch (row.field) {
    case "from":
    case "to":
    case "subject":
      return `${row.field}:${grouped(row.value)}`;
    case "hasWords":
      return grouped(row.value);
    case "doesNotHave":
      return without(row.value);
    case "size":
      return `${row.operator === "smaller" ? "smaller" : "larger"}:${searchSize(parseSize(row.value) ?? 0)}`;
    case "hasAttachment":
      return "has:attachment";
  }
}

function negatedTerm(term: string): string {
  return term.startsWith("-") ? term.slice(1) : `-${term}`;
}

/**
 * Criteria for the editor's conditions, as Gmail's own editor writes them where it can.
 *
 * Raw text goes to `query`. Otherwise the first row of each field in the "all" groups fills the
 * structured fields (`from`, `to`, `subject`, `query` for has words, `negatedQuery` for doesn't
 * have, `size` with `sizeComparison`, `hasAttachment`); further rows of a field, "any" groups
 * (`{a b}`) and "not" groups (`-a -b`) are added to `query` as search text. Rows with a
 * `rowError` are left out.
 */
export function conditionsToCriteria(
  conditions: FilterConditions,
): GmailFilterCriteria {
  if (conditions.kind === "raw") {
    const query = conditions.query.trim();
    return query === "" ? {} : { query };
  }
  const criteria: GmailFilterCriteria = {};
  const extra: string[] = [];
  const seen = new Set<FilterField>();
  let hasWords: string | null = null;
  for (const group of conditions.groups) {
    const rows = group.rows.filter((row) => rowError(row) === null);
    if (group.match !== "all") {
      const terms = rows.map(rowTerm);
      if (group.match === "not") extra.push(...terms.map(negatedTerm));
      else if (terms.length === 1) extra.push(terms[0]!);
      else if (terms.length > 1) extra.push(`{${terms.join(" ")}}`);
      continue;
    }
    for (const row of rows) {
      if (seen.has(row.field)) {
        extra.push(rowTerm(row));
        continue;
      }
      seen.add(row.field);
      const value = row.value.trim();
      switch (row.field) {
        case "from":
        case "to":
        case "subject":
          criteria[row.field] = value;
          break;
        case "hasWords":
          hasWords = value;
          break;
        case "doesNotHave":
          criteria.negatedQuery = value;
          break;
        case "size":
          criteria.size = parseSize(value) ?? 0;
          criteria.sizeComparison =
            row.operator === "smaller" ? "smaller" : "larger";
          break;
        case "hasAttachment":
          criteria.hasAttachment = true;
          break;
      }
    }
  }
  const query = [hasWords, ...extra].filter((part) => part !== null).join(" ");
  if (query !== "") criteria.query = query;
  return sortedCriteria(criteria);
}

const CRITERIA_ORDER: (keyof GmailFilterCriteria)[] = [
  "from",
  "to",
  "subject",
  "query",
  "negatedQuery",
  "hasAttachment",
  "excludeChats",
  "size",
  "sizeComparison",
];

function sortedCriteria(criteria: GmailFilterCriteria): GmailFilterCriteria {
  const sorted: Record<string, unknown> = {};
  for (const key of CRITERIA_ORDER) {
    if (criteria[key] !== undefined) sorted[key] = criteria[key];
  }
  return sorted as GmailFilterCriteria;
}

/** Search text that finds what the criteria match, for the live preview and the raw editor. */
export function criteriaToSearch(criteria: GmailFilterCriteria): string {
  const parts: string[] = [];
  for (const field of ["from", "to", "subject"] as const) {
    const value = criteria[field]?.trim();
    if (value) parts.push(`${field}:${grouped(value)}`);
  }
  const query = criteria.query?.trim();
  if (query) {
    const tokens = tokenize(query);
    parts.push(
      tokens.length > 1 && tokens.some((token) => token.text === "OR")
        ? `(${query})`
        : query,
    );
  }
  const negated = criteria.negatedQuery?.trim();
  if (negated) parts.push(without(negated));
  if (criteria.hasAttachment) parts.push("has:attachment");
  if (criteria.size !== undefined && criteria.size > 0) {
    const comparison =
      criteria.sizeComparison === "smaller" ? "smaller" : "larger";
    parts.push(`${comparison}:${searchSize(criteria.size)}`);
  }
  return parts.join(" ");
}

/** Search text for the editor's conditions (the live preview). */
export function conditionsToSearch(conditions: FilterConditions): string {
  return criteriaToSearch(conditionsToCriteria(conditions));
}

function unwrapped(text: string, open: string, close: string): string | null {
  if (!text.startsWith(open) || !text.endsWith(close) || text.length < 2) {
    return null;
  }
  const inner = text.slice(1, -1);
  const tokens = tokenize(inner);
  return tokens.every((token) => token.complete) ? inner.trim() : null;
}

/** A search size (`5M`, `500K`, `1200`) in bytes. */
function searchBytes(value: string): number | null {
  const match = /^(\d+)([kKmM]?)$/.exec(value);
  if (match === null) return null;
  const unit = match[2]!.toLowerCase();
  const scale = unit === "m" ? MB : unit === "k" ? KB : 1;
  return Number(match[1]) * scale;
}

/** The row an operator term makes (`from:x`, `subject:(a b)`, `larger:5M`, `has:attachment`), or null. */
function operatorRow(term: string): FilterRow | null {
  const match = /^([A-Za-z_]+):(.+)$/s.exec(term);
  if (match === null) return null;
  const operator = match[1]!.toLowerCase();
  const value = match[2]!;
  switch (operator) {
    case "from":
    case "to":
    case "subject":
      return {
        field: operator,
        operator: "contains",
        value: unwrapped(value, "(", ")") ?? value,
      };
    case "larger":
    case "smaller":
    case "size": {
      const bytes = searchBytes(value);
      return bytes === null
        ? null
        : {
            field: "size",
            operator: operator === "smaller" ? "smaller" : "larger",
            value: formatSize(bytes),
          };
    }
    case "has":
      return value.toLowerCase() === "attachment"
        ? { field: "hasAttachment", operator: "is", value: "" }
        : null;
    default:
      return null;
  }
}

/** Rows for the terms inside `{…}` or a negated run, or null when one can't be a row. */
function termRows(texts: string[]): FilterRow[] | null {
  const rows: FilterRow[] = [];
  for (const text of texts) {
    if (text === "OR" || /^[-{]/.test(text)) return null;
    rows.push(
      operatorRow(text) ?? {
        field: "hasWords",
        operator: "contains",
        value: unwrapped(text, "(", ")") ?? text,
      },
    );
  }
  return rows;
}

type QueryRows = { all: FilterRow[]; others: ConditionGroup[] };

/** Rows for a `query`, or null when it doesn't parse into rows. */
function queryRows(query: string): QueryRows | null {
  const tokens = tokenize(query);
  if (tokens.some((token) => !token.complete)) return null;
  const all: FilterRow[] = [];
  if (tokens.some((token) => token.text === "OR")) {
    return {
      all: [{ field: "hasWords", operator: "contains", value: query.trim() }],
      others: [],
    };
  }
  const others: ConditionGroup[] = [];
  const words: string[] = [];
  for (const { text } of tokens) {
    if (text.startsWith("-") && text.length > 1) {
      const rest = text.slice(1);
      const inner = unwrapped(rest, "{", "}");
      const rows = termRows(
        inner === null ? [rest] : tokenize(inner).map((token) => token.text),
      );
      if (rows === null) return null;
      const last = others.at(-1);
      if (last?.match === "not" && inner === null) last.rows.push(...rows);
      else others.push({ match: "not", rows });
      continue;
    }
    const inner = unwrapped(text, "{", "}");
    if (inner !== null) {
      const rows = termRows(tokenize(inner).map((token) => token.text));
      if (rows === null) return null;
      others.push({ match: "any", rows });
      continue;
    }
    const row = operatorRow(text);
    if (row !== null) all.push(row);
    else words.push(text);
  }
  if (words.length > 0) {
    all.push({
      field: "hasWords",
      operator: "contains",
      value: words.join(" "),
    });
  }
  return { all, others };
}

/**
 * The editor's conditions for a filter's criteria: structured fields and `query` terms as rows
 * (an "all" group in `FILTER_FIELDS` order, then "any" groups for `{…}` and "not" groups for
 * runs of `-term`), or raw search text (`criteriaToSearch`) when `query` has an open quote or
 * group, a `{…}` holding something other than plain terms, or the size comparison is unspecified.
 * A query with a top-level `OR` stays one has-words row.
 */
export function criteriaToConditions(
  criteria: GmailFilterCriteria,
): FilterConditions {
  const raw = (): FilterConditions => ({
    kind: "raw",
    query: criteriaToSearch(criteria),
  });
  if (
    criteria.size !== undefined &&
    criteria.sizeComparison === "unspecified"
  ) {
    return raw();
  }
  const all: FilterRow[] = [];
  for (const field of ["from", "to", "subject"] as const) {
    const value = criteria[field]?.trim();
    if (value) all.push({ field, operator: "contains", value });
  }
  const negated = criteria.negatedQuery?.trim();
  if (negated) {
    if (tokenize(negated).some((token) => !token.complete)) return raw();
    all.push({ field: "doesNotHave", operator: "contains", value: negated });
  }
  if (criteria.size !== undefined && criteria.size > 0) {
    all.push({
      field: "size",
      operator: criteria.sizeComparison === "smaller" ? "smaller" : "larger",
      value: formatSize(criteria.size),
    });
  }
  if (criteria.hasAttachment) {
    all.push({ field: "hasAttachment", operator: "is", value: "" });
  }
  let others: ConditionGroup[] = [];
  const query = criteria.query?.trim();
  if (query) {
    const parsed = queryRows(query);
    if (parsed === null) return raw();
    all.push(...parsed.all);
    others = parsed.others;
  }
  const order = (row: FilterRow) => FILTER_FIELDS.indexOf(row.field);
  const sorted = all.sort((left, right) => order(left) - order(right));
  const groups: ConditionGroup[] =
    sorted.length > 0 ? [{ match: "all", rows: sorted }, ...others] : others;
  return { kind: "rows", groups };
}

const USER_LABEL = /^Label_/;

/** The editor's actions for a filter's action. */
export function actionFromGmail(action: GmailFilterAction): FilterActions {
  const actions: FilterActions = {
    ...NO_ACTIONS,
    otherAddLabelIds: [],
    otherRemoveLabelIds: [],
  };
  for (const id of action.addLabelIds ?? []) {
    if (id === "STARRED") actions.star = true;
    else if (id === "IMPORTANT") actions.important = "always";
    else if (id === "TRASH") actions.trash = true;
    else if (USER_LABEL.test(id) && actions.labelId === null) {
      actions.labelId = id;
    } else actions.otherAddLabelIds.push(id);
  }
  for (const id of action.removeLabelIds ?? []) {
    if (id === "INBOX") actions.archive = true;
    else if (id === "UNREAD") actions.markRead = true;
    else if (id === "SPAM") actions.neverSpam = true;
    else if (id === "IMPORTANT") actions.important = "never";
    else actions.otherRemoveLabelIds.push(id);
  }
  if (action.forward) actions.forward = action.forward;
  return actions;
}

/**
 * A filter's action for the editor's actions. `addLabelIds`: the label, `STARRED`, `IMPORTANT`,
 * `TRASH`, then the others; `removeLabelIds`: `INBOX`, `UNREAD`, `SPAM`, `IMPORTANT`, then the
 * others. Empty lists and a null forward are left out.
 */
export function actionToGmail(actions: FilterActions): GmailFilterAction {
  const add: string[] = [];
  if (actions.labelId !== null) add.push(actions.labelId);
  if (actions.star) add.push("STARRED");
  if (actions.important === "always") add.push("IMPORTANT");
  if (actions.trash) add.push("TRASH");
  add.push(...actions.otherAddLabelIds);
  const remove: string[] = [];
  if (actions.archive) remove.push("INBOX");
  if (actions.markRead) remove.push("UNREAD");
  if (actions.neverSpam) remove.push("SPAM");
  if (actions.important === "never") remove.push("IMPORTANT");
  remove.push(...actions.otherRemoveLabelIds);
  const action: GmailFilterAction = {};
  if (add.length > 0) action.addLabelIds = add;
  if (remove.length > 0) action.removeLabelIds = remove;
  if (actions.forward) action.forward = actions.forward;
  return action;
}

/** Label ids a filter adds and removes, for applying it to mail already there. */
export function labelChanges(actions: FilterActions): {
  add: string[];
  remove: string[];
} {
  const add: string[] = [];
  const remove: string[] = [];
  if (actions.labelId) add.push(actions.labelId);
  if (actions.star) add.push("STARRED");
  if (actions.important === "always") add.push("IMPORTANT");
  if (actions.trash) add.push("TRASH");
  if (actions.archive) remove.push("INBOX");
  if (actions.markRead) remove.push("UNREAD");
  if (actions.important === "never") remove.push("IMPORTANT");
  return { add, remove };
}

/** Load a Gmail filter into the editor. */
export function filterToEditor(filter: GmailFilter): FilterEditor {
  return {
    conditions: criteriaToConditions(filter.criteria ?? {}),
    actions: actionFromGmail(filter.action ?? {}),
  };
}

/** The Gmail filter to create for the editor's state (an edit is delete + create). */
export function editorToFilter(editor: FilterEditor): {
  criteria: GmailFilterCriteria;
  action: GmailFilterAction;
} {
  return {
    criteria: conditionsToCriteria(editor.conditions),
    action: actionToGmail(editor.actions),
  };
}

/**
 * Whether the editor's filter can be saved: it matches something and does something. Leaving the
 * editor keeps a change only then, and drops it otherwise.
 */
export function filterIsWhole(editor: FilterEditor): boolean {
  const { criteria, action } = editorToFilter(editor);
  return (
    Object.keys(criteria).length > 0 &&
    ((action.addLabelIds?.length ?? 0) > 0 ||
      (action.removeLabelIds?.length ?? 0) > 0 ||
      action.forward !== undefined)
  );
}
