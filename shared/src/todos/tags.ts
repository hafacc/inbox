// latr's `#tags`, ported from latr's web/src/lib/utils/tags.ts with the changes
// `noUncheckedIndexedAccess` needs; unlike latr, the tag being typed ends at the caret whatever
// follows it. shared/testdata/tag-fixtures.json is latr's byte for byte; tag-complete-fixtures.json
// is latr's with that change.

/** A `#tag`, as UTF-16 offsets covering the `#` and the name. */
export type TagSpan = { start: number; end: number };

const NAME_CHAR = /[\p{L}\p{Nd}\p{M}_\-/]/u;
const LETTER = /\p{L}/u;
const WHITESPACE = /\s/u;
const OPENERS = new Set(["(", "[", "{", '"', "'", "*", "_", "“", "‘", "«"]);
const TRAILING = new Set(["_", "-", "/"]);

function startsTag(prev: string | undefined): boolean {
  return prev === undefined || WHITESPACE.test(prev) || OPENERS.has(prev);
}

/** Every `#tag` in `text`: a `#` at a word start followed by a name with at least one letter. */
export function parseTags(text: string): TagSpan[] {
  if (!text.includes("#")) return [];
  const tags: TagSpan[] = [];
  let index = 0;
  while (index < text.length) {
    if (text[index] !== "#" || !startsTag(text[index - 1])) {
      index++;
      continue;
    }
    let end = index + 1;
    let lastKept = index + 1;
    let hasLetter = false;
    while (end < text.length) {
      const char = String.fromCodePoint(text.codePointAt(end) as number);
      if (!NAME_CHAR.test(char)) break;
      end += char.length;
      if (!TRAILING.has(char)) {
        lastKept = end;
        if (LETTER.test(char)) hasLetter = true;
      }
    }
    if (hasLetter) tags.push({ start: index, end: lastKept });
    index = Math.max(end, index + 1);
  }
  return tags;
}

/** A `#tag` being typed: `start` is its `#`, the caret ends the name typed so far. */
export type TypedTag = { start: number; prefix: string };

/** The tag being typed at the caret: from its `#` to the caret, whatever follows; the name may still be empty. */
export function typingTag(text: string, caret: number): TypedTag | null {
  if (caret <= 0) return null;
  const start = text.lastIndexOf("#", caret - 1);
  if (start < 0 || !startsTag(text[start - 1])) return null;
  const prefix = text.slice(start + 1, caret);
  for (const char of prefix) {
    if (!NAME_CHAR.test(char)) return null;
  }
  return { start, prefix };
}

/** Tag names (without `#`) by lowercased name: the first spelling seen and how many texts use it. */
export type TagCounts = Map<string, { name: string; count: number }>;

/** How many of `texts` use each tag; a text counts once per tag. */
export function countTags(texts: Iterable<string>): TagCounts {
  const counts: TagCounts = new Map();
  for (const text of texts) {
    for (const name of tagNames(text)) {
      const key = name.toLowerCase();
      const entry = counts.get(key);
      if (entry) entry.count++;
      else counts.set(key, { name, count: 1 });
    }
  }
  return counts;
}

function tagNames(text: string): Set<string> {
  const seen = new Map<string, string>();
  for (const tag of parseTags(text)) {
    const name = text.slice(tag.start + 1, tag.end);
    const key = name.toLowerCase();
    if (!seen.has(key)) seen.set(key, name);
  }
  return new Set(seen.values());
}

const NOT_KEY_CHARS = /[^\p{L}\p{Nd}\p{M}_]+/gu;
const KEY_EDGES = /^[-_]+|[-_]+$/g;

/**
 * The key a tag name (without `#`) or a label name matches by: lower case, each run of anything
 * but letters, digits, marks and `_` as one `-`, `-` and `_` trimmed from each `/`-separated part,
 * empty parts dropped. `Read Later` and `read--later` both give `read-later`.
 */
export function tagKey(name: string): string {
  return name
    .split("/")
    .map((part) =>
      part.toLowerCase().replace(NOT_KEY_CHARS, "-").replace(KEY_EDGES, ""),
    )
    .filter((part) => part !== "")
    .join("/");
}

/**
 * Known tags starting with `prefix` (ignoring case), most used first, then by name.
 *
 * `ownText` is the saved text of the todo being edited: its tags are left out, being there
 * already (and so is the half-typed one it holds). A tag spelled exactly as typed is left out too.
 * `offered` adds tags no text uses yet (the user's labels as tags), as used by none; one that
 * matches a tag in use or in `ownText` (by {@link tagKey}) is left out.
 */
export function suggestTags(
  counts: TagCounts,
  ownText: string,
  prefix: string,
  limit = 5,
  offered: readonly string[] = [],
): string[] {
  const ownNames = [...tagNames(ownText)];
  const own = new Set(ownNames.map((name) => name.toLowerCase()));
  const wanted = prefix.toLowerCase();
  const matches: { name: string; key: string; count: number }[] = [];
  for (const [key, { name, count }] of counts) {
    if (!own.has(key) && key.startsWith(wanted) && key !== wanted) {
      matches.push({ name, key, count });
    }
  }
  const taken = new Set([
    ...ownNames.map(tagKey),
    ...[...counts.values()].map((entry) => tagKey(entry.name)),
  ]);
  for (const name of offered) {
    const key = name.toLowerCase();
    const matchKey = tagKey(name);
    if (taken.has(matchKey)) continue;
    taken.add(matchKey);
    if (key.startsWith(wanted) && key !== wanted) {
      matches.push({ name, key, count: 0 });
    }
  }
  matches.sort(
    (left, right) =>
      right.count - left.count ||
      (left.key < right.key ? -1 : left.key > right.key ? 1 : 0),
  );
  return matches.slice(0, limit).map((match) => match.name);
}

/** `text` with the typed tag replaced by `#name` and a space, caret after the space. */
export function completeTag(
  text: string,
  typed: TypedTag,
  caret: number,
  name: string,
): { text: string; caret: number } {
  const before = text.slice(0, typed.start);
  const after = text.slice(caret);
  const tag = `#${name}`;
  if (after.startsWith(" ")) {
    return {
      text: before + tag + after,
      caret: before.length + tag.length + 1,
    };
  } else {
    return {
      text: `${before}${tag} ${after}`,
      caret: before.length + tag.length + 1,
    };
  }
}
