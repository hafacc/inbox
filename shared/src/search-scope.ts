// What a search lists: the mail Gmail is asked for and the todos the device adds. Web and Android
// (:core SearchScope.kt) both run shared/testdata/search-scope-fixtures.json.

import {
  type Chip,
  gmailQueryText,
  isLocalChip,
  parseQuery,
  queryText,
  TAG_CHIP,
} from "./search";
import { type LabelTags, namesLabelQuery } from "./todos/label-tags";
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
  "in:inbox": (todo) => todo.state !== "DONE",
};

function todoChipTest(
  chip: Chip,
): ((todo: Todo, now: number) => boolean) | undefined {
  return TODO_CHIPS[`${chip.operator}:${chip.value.toLowerCase()}`];
}

/**
 * What a whole search query lists. `is:todo` lists todos and no mail; `is:snoozed` or `in:outbox`
 * with nothing else lists no mail from Gmail (the device has those); `-is:todo` lists no todos, so
 * on its own it lists all mail. Todos show only while every chip is one a todo can be held to:
 * `is:todo`, `is:snoozed`, `is:pinned` (or `is:starred`), `is:done`, `in:inbox` (any of them
 * negated) or `label:` (which {@link searchTodos} reads against the todos' tags). `is:pinned` and
 * `is:done` go to Gmail as `is:starred` and `-in:inbox` ({@link gmailQueryText}).
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
  const mailChips = chips.some(
    (chip) => chip.operator !== "label" && todoChipTest(chip) === undefined,
  );
  let todos: SearchTodos;
  if (has("is", "todo", true) || (mailChips && !todosOnly)) todos = "none";
  else if (snoozedOnly) todos = "snoozed";
  else todos = "all";
  return {
    gmailQuery: todosOnly || deviceOnly ? null : mailQuery,
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
 * `in:inbox` those not done (snoozed included, as a snoozed email stays in Gmail's inbox), and
 * `-is:snoozed` those not snoozed; each negated keeps the others. A `-#tag` word keeps the todos
 * without that tag; a `#tag` word ranks like any other word.
 */
export function searchTodos(
  todos: readonly Todo[],
  query: string,
  now: number,
  labels: LabelTags = new Map(),
): Todo[] {
  const scope = searchScope(query);
  if (scope.todos === "none") return [];
  const kept = matchingText(
    todos,
    query,
    labels,
    (chip, todo) => todoChipTest(chip)?.(todo, now) ?? true,
  );
  const shown = sortForFilter(
    kept,
    scope.todos === "snoozed" ? "SNOOZED" : "ALL",
  );
  return rankBySearch(shown, freeWords(query));
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
  if (searchScope(query).todos === "none") return [];
  const kept = matchingText(todos, query, labels, () => null);
  return rankBySearch(kept, freeWords(query)).map((todo) => todo.threadId);
}

// The query's free text with its `-#tag` words left out, as todos rank by it.
function freeWords(query: string): string {
  const tagged = parseQuery(parseQuery(query, null).free, null, true);
  return queryText(
    tagged.chips.filter((chip) => chip.operator === TAG_CHIP && !chip.negated),
    tagged.free,
  );
}

// The items whose text lacks every `-#tag` and holds to every chip: `label:` read against the
// tags, any other by `holds`, which answers null for a chip it leaves to something else.
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
  const lacksLeftTags = (item: Item): boolean =>
    parseTags(item.text).every(
      ({ start, end }) => !left.has(tagKey(item.text.slice(start + 1, end))),
    );
  const holdsChip = (chip: Chip, item: Item): boolean => {
    const held =
      chip.operator === "label"
        ? namesLabelQuery(item.text, labels, chip.value)
        : holds(chip, item);
    return held === null || held !== chip.negated;
  };
  return items.filter(
    (item) =>
      lacksLeftTags(item) && chips.every((chip) => holdsChip(chip, item)),
  );
}
