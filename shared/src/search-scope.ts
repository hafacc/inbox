// What a search lists: the mail Gmail is asked for and the todos the device adds. Web and Android
// (:core SearchScope.kt) both run shared/testdata/search-scope-fixtures.json.

import {
  type Chip,
  formatChip,
  gmailQueryText,
  isLocalChip,
  labelQueryValue,
  parseQuery,
  TAG_CHIP,
  tokenChip,
  tokenize,
} from "./search";
import { type LabelTags, namesLabelQuery, tagLabel } from "./todos/label-tags";
import type { EmailTodo } from "./todos/linked";
import { rankBySearch } from "./todos/list";
import { parseTags, tagKey } from "./todos/tags";
import { isSnoozed, sortForFilter, type Todo } from "./todos/todo";

/** Which todos a search lists: none, every todo, or only those still snoozed. */
export type SearchTodos = "none" | "all" | "snoozed";

/** What a search lists. */
export type SearchScope = {
  /** Gmail's `q` for the mail listed, our own chips left out; null when Gmail lists no mail for it */
  gmailQuery: string | null;
  /** whether Gmail is asked for spam and trash too */
  includeSpamTrash: boolean;
  /** which todos are listed */
  todos: SearchTodos;
  /** `is:snoozed`: only threads still snoozed, from the device's snoozes when there is no Gmail query */
  snoozedOnly: boolean;
};

const SPAM_OR_TRASH = ["spam", "trash", "anywhere"];

// The chips a todo can be held to (each also negated), by what it must be for each.
const TODO_CHIPS: Readonly<
  Record<string, (todo: Todo, now: number) => boolean>
> = {
  "is:todo": () => true,
  "is:snoozed": (todo, now) => isSnoozed(todo, now),
  "is:pinned": (todo) => todo.pinned,
  "is:starred": (todo) => todo.pinned,
  "is:done": (todo) => todo.state === "DONE",
  "in:inbox": (todo, now) => todo.state !== "DONE" && !isSnoozed(todo, now),
};

function todoChipTest(
  chip: Chip,
): ((todo: Todo, now: number) => boolean) | undefined {
  return TODO_CHIPS[`${chip.operator}:${chip.value.toLowerCase()}`];
}

/**
 * `query` as a search runs it: each `#name` word (a todo's tag, maybe after `-`) that names one of
 * `labels` becomes that label's `label:` term, matched by {@link tagKey} so case doesn't matter.
 * Any other `#name` stays, a tag that only todos carry; the rest of the query is as it was. The
 * search field, the address and recent searches keep the query as typed; this runs only where the
 * search does (Gmail's query, {@link searchScope}, {@link searchTodos}, the list's filter).
 *
 * ```ts
 * searchTagTerms("#Read-Later lunch", labels) // "label:read-later lunch"
 * ```
 */
export function searchTagTerms(query: string, labels: LabelTags): string {
  const tokens = tokenize(query);
  let out = "";
  let at = 0;
  tokens.forEach((token, index) => {
    const besideOr =
      tokens[index - 1]?.text === "OR" || tokens[index + 1]?.text === "OR";
    const chip = besideOr ? null : tokenChip(token, true);
    if (chip?.operator !== TAG_CHIP) return;
    const label = tagLabel(labels, chip.value);
    if (label === null) return;
    out +=
      query.slice(at, token.start) +
      formatChip({
        operator: "label",
        value: labelQueryValue(label.name),
        negated: chip.negated,
      });
    at = token.end;
  });
  return out + query.slice(at);
}

/**
 * What a whole search query lists, given the query as it runs ({@link searchTagTerms}). `is:todo`
 * lists todos and no mail; `is:snoozed` or `in:outbox` with nothing else lists no mail from Gmail
 * (the device has those); `-is:todo` lists no todos, so on its own it lists all mail. Todos show only while every chip is one a todo can be held to:
 * `is:todo`, `is:snoozed`, `is:pinned` (or `is:starred`), `is:done`, `in:inbox` (any of them
 * negated) or `label:` (which {@link searchTodos} reads against the todos' tags). `is:pinned` and
 * `is:done` go to Gmail as `is:starred` and `-in:inbox` ({@link gmailQueryText}). A `#tag` word left
 * by {@link searchTagTerms} names no label, so only todos carry it and Gmail lists no mail for it;
 * nor for free text that is only `#tag` words joined by `OR`, which todos match by either tag.
 */
export function searchScope(query: string): SearchScope {
  const { chips, free } = parseQuery(query, null);
  const has = (operator: string, value: string, negated: boolean) =>
    chips.some(
      (chip) =>
        isLocalChip(chip) &&
        chip.operator === operator &&
        chip.value.toLowerCase() === value &&
        chip.negated === negated,
    );
  const todosOnly = has("is", "todo", false);
  const snoozedOnly = has("is", "snoozed", false);
  const outbox = has("in", "outbox", false);
  const mailQuery = gmailQueryText(chips, free);
  const deviceOnly = mailQuery === "" && (snoozedOnly || outbox);
  const tagOnly = keptTags(free).length > 0 || tagsJoinedByOr(free);
  const mailChips = chips.some(
    (chip) => chip.operator !== "label" && todoChipTest(chip) === undefined,
  );
  let todos: SearchTodos;
  if (has("is", "todo", true) || (mailChips && !todosOnly)) todos = "none";
  else if (snoozedOnly) todos = "snoozed";
  else todos = "all";
  return {
    gmailQuery: todosOnly || deviceOnly || tagOnly ? null : mailQuery,
    includeSpamTrash: chips.some(
      (chip) =>
        chip.operator === "in" &&
        !chip.negated &&
        SPAM_OR_TRASH.includes(chip.value.toLowerCase()),
    ),
    todos,
    snoozedOnly,
  };
}

/**
 * The todos a search lists at `now`, best match first: for `is:snoozed` the snoozed ones, soonest
 * to wake first; otherwise every todo in the All list's order. Ties keep that order. A `label:`
 * keeps the todos with a tag naming that label among `labels` (see `labelTags`), `-label:` those
 * without. `is:pinned` (or `is:starred`) keeps the pinned ones, `is:done` the done ones,
 * `in:inbox` those neither done nor snoozed, and `-is:snoozed` those not snoozed; each negated
 * keeps the others. A `#name` naming one of `labels`
 * reads as its `label:` ({@link searchTagTerms}); any other `#tag` word keeps the todos with that
 * tag, a `-#tag` word those without it.
 */
export function searchTodos(
  todos: readonly Todo[],
  query: string,
  now: number,
  labels: LabelTags = new Map(),
): Todo[] {
  const run = searchTagTerms(query, labels);
  const scope = searchScope(run);
  if (scope.todos === "none") return [];
  const kept = matchingText(
    todos,
    run,
    labels,
    (chip, todo) => todoChipTest(chip)?.(todo, now) ?? true,
  );
  const shown = sortForFilter(
    kept,
    scope.todos === "snoozed" ? "SNOOZED" : "ALL",
  );
  return rankBySearch(shown, freeWords(run));
}

/**
 * The emails a search lists for their todos, by thread id, in the order given: those whose todo's
 * text matches as {@link searchTodos} reads text (words, `#tag` and `-#tag` words, `label:` and
 * `-label:`). Whether the email is in the inbox, snoozed, pinned or done is the email's own, so
 * the list's filter holds those terms to it as to any thread. None when the search lists no todos.
 */
export function searchEmailTodos(
  todos: readonly Pick<EmailTodo, "threadId" | "text">[],
  query: string,
  labels: LabelTags = new Map(),
): string[] {
  const run = searchTagTerms(query, labels);
  if (searchScope(run).todos === "none") return [];
  const kept = matchingText(todos, run, labels, () => null);
  return rankBySearch(kept, freeWords(run)).map((todo) => todo.threadId);
}

// The query's free text with its `#tag` and `-#tag` words left out, as todos rank by it.
function freeWords(query: string): string {
  return parseQuery(parseQuery(query, null).free, null, true).free;
}

// The keys of the `#tag` words (not negated) in a query's free text.
function keptTags(free: string): string[] {
  return parseQuery(free, null, true)
    .chips.filter((chip) => chip.operator === TAG_CHIP && !chip.negated)
    .map((chip) => tagKey(chip.value));
}

// Whether free text is only `#tag` words (not negated) joined by `OR`, as `#home OR #work`.
function tagsJoinedByOr(free: string): boolean {
  const tokens = tokenize(free);
  return (
    tokens.length >= 3 &&
    tokens.length % 2 === 1 &&
    tokens.every((token, index) => {
      if (index % 2 === 1) return token.text === "OR";
      const chip = tokenChip(token, true);
      return chip?.operator === TAG_CHIP && !chip.negated;
    })
  );
}

// The items whose text has every `#tag`, lacks every `-#tag` and holds to every chip: `label:`
// read against the tags, any other by `holds`, which answers null for a chip it leaves to
// something else.
function matchingText<Item extends { text: string }>(
  items: readonly Item[],
  query: string,
  labels: LabelTags,
  holds: (chip: Chip, item: Item) => boolean | null,
): Item[] {
  const { chips, free } = parseQuery(query, null);
  const left = new Set(
    parseQuery(free, null, true)
      .chips.filter((chip) => chip.operator === TAG_CHIP && chip.negated)
      .map((chip) => tagKey(chip.value)),
  );
  const kept = keptTags(free);
  const tagsOf = (item: Item): string[] =>
    parseTags(item.text).map(({ start, end }) =>
      tagKey(item.text.slice(start + 1, end)),
    );
  const holdsTags = (item: Item): boolean => {
    const tags = tagsOf(item);
    return (
      kept.every((tag) => tags.includes(tag)) &&
      tags.every((tag) => !left.has(tag))
    );
  };
  const holdsChip = (chip: Chip, item: Item): boolean => {
    const held =
      chip.operator === "label"
        ? namesLabelQuery(item.text, labels, chip.value)
        : holds(chip, item);
    return held === null || held !== chip.negated;
  };
  return items.filter(
    (item) => holdsTags(item) && chips.every((chip) => holdsChip(chip, item)),
  );
}
